package com.ykkap.lockbridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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

private sealed class DoorState {
  object Locked : DoorState()
  object Unlocked : DoorState()
  object Sleep : DoorState()
  object Disconnected : DoorState()
  object Unknown : DoorState()
}

enum class YkkAction {
  LOCK, UNLOCK, CHECK_STATUS, DUMP_VIEW_HIERARCHY
}

class YkkAccessibilityService : AccessibilityService() {

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
  private lateinit var powerManager: PowerManager

  companion object {
    private const val TAG = "YkkAccessibilityService"

    private var instance: YkkAccessibilityService? = null
    private val actionMutex = Mutex()

    fun executeAction(action: YkkAction) {
      val service = instance
      if (service == null) {
        Log.e(TAG, "executeAction called but service instance is null.")
        return
      }
      service.serviceScope.launch {
        service.performActionInternal(action)
      }
    }

    fun requestStatusCheck() {
      executeAction(YkkAction.CHECK_STATUS)
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

  private suspend fun performActionInternal(action: YkkAction) {
    actionMutex.withLock {
      val wakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "YkkAccessibilityService::ActionWakeLock"
      )
      val wasInteractive = powerManager.isInteractive

      try {
        wakeLock.acquire(30000L)
        Log.i(TAG, "Starting operation: $action")

        // 1. Bring YKKAP to foreground
        launchApp()

        // 2. Make sure it is in foreground
        val inForeground = waitForForeground(10000L)
        if (!inForeground) {
          Log.e(TAG, "Failed to bring YKKAP to foreground.")
          LockBridgeService.updateLockStatus("UNKNOWN")
          return@withLock
        }

        // Give UI a moment to load and stabilize
        delay(2000L)

        if (action == YkkAction.DUMP_VIEW_HIERARCHY) {
          dumpNodeHierarchy(rootInActiveWindow)
          return@withLock
        }

        // 3. Detect its current state
        var state = detectCurrentState(rootInActiveWindow)
        Log.i(TAG, "Initial state detected: $state")

        if (state is DoorState.Sleep) {
          Log.i(TAG, "App is sleeping. Waking it up.")
          val wakeUpButton = findClickableNodeByDescription(rootInActiveWindow, YKK_WAKE_UP_BUTTON_DESC)
          if (wakeUpButton != null) {
            wakeUpButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(6000L) // Wait for connection after waking up
            state = detectCurrentState(rootInActiveWindow)
            Log.i(TAG, "State after wake up: $state")
          } else {
            Log.w(TAG, "Could not find Wake Up button.")
          }
        }

        // 4. If state is one of the expected states at that moment, perform operation
        when (action) {
          YkkAction.LOCK -> {
            if (state is DoorState.Unlocked) {
              val buttons = findActionableLockUnlockButtons(rootInActiveWindow)
              val targetButton = buttons.getOrNull(0)
              if (targetButton != null) {
                targetButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(5000L) // Wait for lock to engage
                reportStatus(detectCurrentState(rootInActiveWindow))
              } else {
                Log.e(TAG, "Lock button not found.")
                LockBridgeService.updateLockStatus("UNKNOWN")
              }
            } else if (state is DoorState.Locked) {
              Log.i(TAG, "Already locked.")
              reportStatus(state)
            } else {
              // 5. Otherwise report that the door is in unknown state
              Log.w(TAG, "Unexpected state for LOCK: $state. Reporting UNKNOWN.")
              LockBridgeService.updateLockStatus("UNKNOWN")
            }
          }
          YkkAction.UNLOCK -> {
            if (state is DoorState.Locked) {
              val buttons = findActionableLockUnlockButtons(rootInActiveWindow)
              val targetButton = buttons.getOrNull(1)
              if (targetButton != null) {
                targetButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(5000L) // Wait for unlock to engage
                reportStatus(detectCurrentState(rootInActiveWindow))
              } else {
                Log.e(TAG, "Unlock button not found.")
                LockBridgeService.updateLockStatus("UNKNOWN")
              }
            } else if (state is DoorState.Unlocked) {
              Log.i(TAG, "Already unlocked.")
              reportStatus(state)
            } else {
              Log.w(TAG, "Unexpected state for UNLOCK: $state. Reporting UNKNOWN.")
              LockBridgeService.updateLockStatus("UNKNOWN")
            }
          }
          YkkAction.CHECK_STATUS -> {
            if (state is DoorState.Locked || state is DoorState.Unlocked || state is DoorState.Disconnected) {
              reportStatus(state)
            } else {
              Log.w(TAG, "Unexpected state for CHECK_STATUS: $state. Reporting UNKNOWN.")
              LockBridgeService.updateLockStatus("UNKNOWN")
            }
          }
          else -> {}
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error during operation", e)
        LockBridgeService.updateLockStatus("UNKNOWN")
      } finally {
        if (!wasInteractive) {
          performGlobalAction(GLOBAL_ACTION_HOME)
        }
        if (wakeLock.isHeld) {
          wakeLock.release()
        }
      }
    }
  }

  private fun reportStatus(state: DoorState) {
    when (state) {
      is DoorState.Locked -> LockBridgeService.updateLockStatus("LOCKED")
      is DoorState.Unlocked -> LockBridgeService.updateLockStatus("UNLOCKED")
      is DoorState.Disconnected -> LockBridgeService.updateLockStatus("UNAVAILABLE")
      else -> LockBridgeService.updateLockStatus("UNKNOWN")
    }
  }

  private suspend fun launchApp() {
    val launchIntent = Intent(this, UnlockAndLaunchActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(launchIntent)
  }

  private suspend fun waitForForeground(timeout: Long): Boolean {
    val endTime = System.currentTimeMillis() + timeout
    while (System.currentTimeMillis() < endTime) {
      if (rootInActiveWindow?.packageName == YKK_PACKAGE_NAME) {
        return true
      }
      delay(200)
    }
    return false
  }

  private fun detectCurrentState(rootNode: AccessibilityNodeInfo?): DoorState {
    if (rootNode == null) return DoorState.Unknown
    if (findNodeByText(rootNode, YKK_CONNECTION_ERROR_TEXT) != null) return DoorState.Disconnected
    if (findNodeByDescription(rootNode, YKK_SLEEP_MODE_HEADER_SUFFIX, MatchType.ENDS_WITH) != null) return DoorState.Sleep
    if (findNodeByText(rootNode, YKK_LOCKED_STATUS_TEXT) != null) return DoorState.Locked
    if (findNodeByText(rootNode, YKK_UNLOCKED_STATUS_TEXT) != null) return DoorState.Unlocked
    return DoorState.Unknown
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

  private enum class MatchType { EXACT, CONTAINS, ENDS_WITH }

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

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
  }

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
