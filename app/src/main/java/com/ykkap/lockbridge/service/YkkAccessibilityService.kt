package com.ykkap.lockbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ykkap.lockbridge.UnlockAndLaunchActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// --- UI Text Constants ---
private const val YKK_PACKAGE_NAME = "com.alpha.lockapp"
@Suppress("unused")
private const val YKK_LOCK_TEXT = "施錠"
@Suppress("unused")
private const val YKK_UNLOCK_TEXT = "解錠"
private const val YKK_WAKE_UP_BUTTON_DESC = "スリープモード解除"
private const val YKK_LOCKED_STATUS_TEXT = "施錠されています"
private const val YKK_UNLOCKED_STATUS_TEXT = "解錠されています"
private const val YKK_CONNECTION_ERROR_TEXT = "ドアと接続できません"
private const val YKK_SLEEP_MODE_HEADER_SUFFIX = "スリープモード"

/**
 * Defines the high-level states of the YKK AP application's user interface.
 * This allows for a clean, state-machine-driven approach to UI interaction.
 */
private sealed class DoorState {
  /** The door is responsive and ready to accept lock/unlock commands. */
  object Available : DoorState()

  /** The app is in power-saving mode and must be woken up. */
  object Sleep : DoorState()

  /** The app cannot communicate with the door's Bluetooth module. */
  object Disconnected : DoorState()

  /** The UI is in a transitional or unrecognized state. */
  object Unknown : DoorState()
}

enum class YkkAction {
  LOCK, UNLOCK, CHECK_STATUS, DUMP_VIEW_HIERARCHY
}

@SuppressLint("AccessibilityPolicy")
class YkkAccessibilityService : AccessibilityService() {

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
  private lateinit var powerManager: PowerManager

  companion object {
    private const val TAG = "YkkAccessibilityService"
    private const val MAX_WAIT_TIME_MS = 20000L // 20 seconds
    private const val RETRY_INTERVAL_MS = 500L
    private const val WAKELOCK_TIMEOUT_MS = 30000L // 30 seconds
    private const val POST_CLICK_CONFIRMATION_TIMEOUT_MS = 8000L // 8 seconds to wait for UI to update after a click

    private var instance: YkkAccessibilityService? = null
    private val actionMutex = Mutex()

    suspend fun executeAction(action: YkkAction): Boolean {
      val service = instance
      if (service == null) {
        Log.e(TAG, "executeAction called but service instance is null.")
        return false
      }
      return service.performActionInternal(action)
    }

    fun requestStatusCheck() {
      instance?.serviceScope?.launch {
        instance?.performActionInternal(YkkAction.CHECK_STATUS)
      }
    }
  }

  override fun onCreate() {
    super.onCreate()
    powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    serviceInfo = serviceInfo.apply {
      eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      packageNames = arrayOf(YKK_PACKAGE_NAME)
      feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
    }
    Log.i(TAG, "Accessibility Service connected.")
  }

  private suspend fun performActionInternal(action: YkkAction): Boolean {
    return actionMutex.withLock {
      if (powerManager.isInteractive) {
        val currentNode = rootInActiveWindow
        if (currentNode?.packageName == YKK_PACKAGE_NAME && determineDoorState(currentNode) is DoorState.Available) {
          Log.i(TAG, "Fast path: Device is interactive and app is available. Executing '$action' immediately.")
          updateStatusFromNode(currentNode)

          if (action == YkkAction.CHECK_STATUS) return@withLock true

          if (action == YkkAction.LOCK || action == YkkAction.UNLOCK) {
            val buttons = findActionableLockUnlockButtons(currentNode)
            val targetButton = if (action == YkkAction.LOCK) buttons.getOrNull(0) else buttons.getOrNull(1)
            if (targetButton != null) {
              targetButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
              return@withLock waitForStateChange(action)
            } else {
              Log.e(TAG, "Fast path failed: Could not find button for action '$action'.")
              // Fall through to the full launch sequence.
            }
          }
        }
      } else {
        Log.i(TAG, "Fast path skipped: Device is not interactive.")
      }

      val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "YkkAccessibilityService::ActionWakeLock"
      )
      try {
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
        Log.d(TAG, "Full path: Bright screen wakelock acquired for action: $action")

        withContext(Dispatchers.IO) {
          launchApp()

          if (action == YkkAction.DUMP_VIEW_HIERARCHY) {
            delay(1000)
            Log.i(TAG, "Executing view hierarchy dump as requested.")
            dumpNodeHierarchy(rootInActiveWindow)
            return@withContext true
          }

          val success = withTimeoutOrNull(MAX_WAIT_TIME_MS) {
            while (true) {
              val updatedNode = rootInActiveWindow
              when (determineDoorState(updatedNode)) {
                is DoorState.Disconnected -> {
                  Log.e(TAG, "Door is disconnected. Aborting operation.")
                  LockBridgeService.updateLockStatus("UNAVAILABLE")
                  return@withTimeoutOrNull false
                }
                is DoorState.Sleep -> {
                  Log.i(TAG, "Door is in sleep mode. Attempting to wake up.")
                  val wakeUpButton = findClickableNodeByDescription(updatedNode, YKK_WAKE_UP_BUTTON_DESC)
                  if (wakeUpButton != null) {
                    wakeUpButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                  } else {
                    Log.e(TAG, "Could not find the 'Wake Up' button. Aborting.")
                    return@withTimeoutOrNull false
                  }
                }
                is DoorState.Available -> {
                  Log.i(TAG, "Door is available and ready for action.")
                  updateStatusFromNode(updatedNode)

                  if (action == YkkAction.CHECK_STATUS) return@withTimeoutOrNull true

                  val buttons = findActionableLockUnlockButtons(updatedNode)
                  val targetButton = if (action == YkkAction.LOCK) buttons.getOrNull(0) else buttons.getOrNull(1)

                  if (targetButton != null) {
                    targetButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return@withTimeoutOrNull waitForStateChange(action)
                  } else {
                    Log.e(TAG, "Could not find button for action '$action'.")
                    return@withTimeoutOrNull false
                  }
                }
                is DoorState.Unknown -> {
                  Log.v(TAG, "Door state is UNKNOWN. Waiting for a stable state...")
                }
              }
              delay(RETRY_INTERVAL_MS)
            }
            false
          }

          if (success == null) {
            Log.e(TAG, "Operation timed out after $MAX_WAIT_TIME_MS ms.")
            LockBridgeService.updateLockStatus("UNAVAILABLE")
          }
          return@withContext success ?: false
        }
      } catch (e: Exception) {
        Log.e(TAG, "An unexpected exception occurred during performActionInternal.", e)
        return@withLock false
      } finally {
        if (wakeLock.isHeld) {
          wakeLock.release()
          Log.d(TAG, "Bright screen wakelock released.")
        }
      }
    }
  }

  private suspend fun waitForStateChange(action: YkkAction): Boolean {
    val expectedText = when (action) {
      YkkAction.LOCK -> YKK_LOCKED_STATUS_TEXT
      YkkAction.UNLOCK -> YKK_UNLOCKED_STATUS_TEXT
      else -> return true
    }
    Log.d(TAG, "Waiting for UI to update with text: '$expectedText'")
    val success = withTimeoutOrNull(POST_CLICK_CONFIRMATION_TIMEOUT_MS) {
      while (true) {
        val node = rootInActiveWindow
        if (node != null && findNodeByText(node, expectedText) != null) {
          Log.i(TAG, "UI update confirmed for action $action.")
          updateStatusFromNode(node)
          return@withTimeoutOrNull true
        }
        delay(RETRY_INTERVAL_MS)
      }
      false
    }
    return success ?: false
  }

  private suspend fun launchApp() {
    withContext(Dispatchers.Main) {
      val launchIntent = Intent(this@YkkAccessibilityService, UnlockAndLaunchActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      startActivity(launchIntent)
    }
    withTimeoutOrNull(5000L) {
      while (rootInActiveWindow?.packageName != YKK_PACKAGE_NAME) {
        delay(200)
      }
    }
  }

  private fun determineDoorState(rootNode: AccessibilityNodeInfo?): DoorState {
    if (rootNode == null) return DoorState.Unknown
    if (findNodeByText(rootNode, YKK_CONNECTION_ERROR_TEXT) != null) return DoorState.Disconnected
    if (findNodeByDescription(rootNode, YKK_SLEEP_MODE_HEADER_SUFFIX, MatchType.ENDS_WITH) != null) return DoorState.Sleep
    if (findNodeByText(rootNode, YKK_LOCKED_STATUS_TEXT) != null || findNodeByText(rootNode, YKK_UNLOCKED_STATUS_TEXT) != null) return DoorState.Available
    return DoorState.Unknown
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event?.packageName != YKK_PACKAGE_NAME) return
    serviceScope.launch(Dispatchers.Default) {
      rootInActiveWindow?.let { updateStatusFromNode(it) }
    }
  }

  private fun updateStatusFromNode(rootNode: AccessibilityNodeInfo) {
    try {
      when (determineDoorState(rootNode)) {
        is DoorState.Available -> {
          val isLocked = findNodeByText(rootNode, YKK_LOCKED_STATUS_TEXT) != null
          val status = if (isLocked) "LOCKED" else "UNLOCKED"
          LockBridgeService.updateLockStatus(status)
        }
        is DoorState.Disconnected -> LockBridgeService.updateLockStatus("UNAVAILABLE")
        is DoorState.Sleep, is DoorState.Unknown -> LockBridgeService.updateLockStatus("UNKNOWN")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error during status update from node.", e)
      LockBridgeService.updateLockStatus("UNKNOWN")
    }
  }

  // region Node Search Helpers

  private enum class MatchType {
    EXACT, CONTAINS, ENDS_WITH
  }

  private fun findActionableLockUnlockButtons(rootNode: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
    val buttons = mutableListOf<AccessibilityNodeInfo>()
    if (rootNode == null) return buttons

    fun findNodes(node: AccessibilityNodeInfo) {
      if (node.className == "android.view.ViewGroup" && node.isClickable && node.contentDescription.isNullOrEmpty()) {
        buttons.add(node)
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let { findNodes(it) }
      }
    }
    val scrollView = findNodeByClassName(rootNode, "android.widget.ScrollView")
    scrollView?.let { findNodes(it) }
    return buttons
  }

  private fun findNodeByClassName(rootNode: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
    if (rootNode.className == className) return rootNode
    for (i in 0 until rootNode.childCount) {
      val child = rootNode.getChild(i)
      val result = child?.let { findNodeByClassName(it, className) }
      if (result != null) return result
    }
    return null
  }

  private fun findNodeByText(rootNode: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
    rootNode ?: return null
    val nodes = rootNode.findAccessibilityNodeInfosByText(text)
    return nodes.firstOrNull { it.text?.toString() == text }
  }

  private fun findNodeByDescription(rootNode: AccessibilityNodeInfo?, text: String, matchType: MatchType = MatchType.EXACT): AccessibilityNodeInfo? {
    rootNode ?: return null

    fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
      val desc = node.contentDescription
      if (desc != null) {
        val isMatch = when (matchType) {
          MatchType.EXACT -> desc == text
          MatchType.CONTAINS -> desc.contains(text)
          MatchType.ENDS_WITH -> desc.endsWith(text)
        }
        if (isMatch) return node
      }
      for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        val result = child?.let { search(it) }
        if (result != null) return result
      }
      return null
    }
    return search(rootNode)
  }

  private fun findClickableNodeByDescription(rootNode: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
    val descNode = findNodeByDescription(rootNode, text) ?: return null
    return generateSequence(descNode) { it.parent }.firstOrNull { it.isClickable }
  }

  private fun dumpNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int = 0) {
    node ?: return
    val indent = "  ".repeat(depth)
    val nodeText = node.text?.let { "text: '$it'" } ?: ""
    val contentDesc = node.contentDescription?.let { "desc: '$it'" } ?: ""
    val properties = "clickable: ${node.isClickable}, $nodeText $contentDesc".trim()
    Log.d("ViewHierarchy", "$indent[${node.className}] $properties")
    for (i in 0 until node.childCount) {
      dumpNodeHierarchy(node.getChild(i), depth + 1)
    }
  }

  // endregion

  override fun onInterrupt() {
    Log.w(TAG, "Accessibility Service interrupted.")
  }

  override fun onDestroy() {
    super.onDestroy()
    instance = null
    serviceJob.cancel()
    Log.i(TAG, "Accessibility Service destroyed.")
  }
}
