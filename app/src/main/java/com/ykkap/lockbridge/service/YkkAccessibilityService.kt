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
import kotlinx.coroutines.withTimeoutOrNull

// These are the Japanese strings on the buttons in the YKK app.
@Suppress("unused")
private const val YKK_UNLOCK_TEXT = "解錠"
@Suppress("unused")
private const val YKK_LOCK_TEXT = "施錠"
private const val YKK_WAKE_UP_TEXT = "スリープモード解除"

// This is the text present in the green "locked" status view.
private const val YKK_LOCKED_STATUS_TEXT = "施錠されています"

// This is the text present in the red "unlocked" status view.
private const val YKK_UNLOCKED_STATUS_TEXT = "解錠されています"
private const val YKK_CONNECTION_ERROR_TEXT = "ドアと接続できません"
private const val YKK_SLEEP_MODE_STATUS_TEXT = "スリープモード"
private const val YKK_PACKAGE_NAME = "com.alpha.lockapp"


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
    private const val MAX_WAIT_TIME_MS = 15000L
    private const val RETRY_INTERVAL_MS = 500L
    private const val WAKELOCK_TIMEOUT_MS = 20000L // 20 seconds

    private var instance: YkkAccessibilityService? = null

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
  }

  private suspend fun performActionInternal(action: YkkAction): Boolean {
    val wakeLock = powerManager.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
      "YkkAccessibilityService::ActionWakeLock"
    )

    try {
      wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
      Log.d(TAG, "Bright screen wakelock acquired.")

      // Always launch the UnlockAndLaunchActivity. It is the designated component for
      // waking the screen and ensuring the target app is in the foreground. This avoids
      // edge cases where the screen is off but the system still considers the app "active".
      val launchIntent = Intent(this, UnlockAndLaunchActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      startActivity(launchIntent)

      val rootNode = withTimeoutOrNull(MAX_WAIT_TIME_MS) {
        var node: AccessibilityNodeInfo?
        while (true) {
          node = rootInActiveWindow
          if (node != null && node.packageName == YKK_PACKAGE_NAME) break
          delay(RETRY_INTERVAL_MS)
        }
        node
      }

      if (rootNode == null) {
        Log.e(TAG, "Timed out waiting for '$YKK_PACKAGE_NAME' window.")
        LockBridgeService.updateLockStatus("UNAVAILABLE")
        return false
      }

      if (findNodeByText(rootNode, YKK_CONNECTION_ERROR_TEXT) != null) {
        Log.w(TAG, "Connection error detected on initial screen. Aborting action.")
        LockBridgeService.updateLockStatus("UNAVAILABLE")
        return false
      }

      if (action == YkkAction.DUMP_VIEW_HIERARCHY) {
        dumpNodeHierarchy(rootNode)
        return true
      }

      // --- Unified Wake-Up and Readiness Logic ---

      val isSleepMode = findNodeByText(rootNode, YKK_SLEEP_MODE_STATUS_TEXT) != null
      if (isSleepMode) {
        Log.i(TAG, "Sleep mode status text detected. Attempting to wake up.")
        val wakeUpButton = findClickableNodeByText(rootNode, YKK_WAKE_UP_TEXT)
        if (wakeUpButton != null) {
          wakeUpButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          delay(500)
        } else {
          Log.e(TAG, "Sleep mode detected, but the 'Wake Up' button could not be found. Aborting.")
          return false
        }
      }

      val readyRootNode = withTimeoutOrNull(MAX_WAIT_TIME_MS) {
        var currentNode: AccessibilityNodeInfo?
        var isReady = false
        while (true) {
          currentNode = rootInActiveWindow
          if (findNodeByText(currentNode, YKK_CONNECTION_ERROR_TEXT) != null) {
            Log.w(TAG, "Connection error detected while waiting for buttons. Aborting.")
            LockBridgeService.updateLockStatus("UNAVAILABLE")
            break
          }

          val sleepModeNode = findNodeByText(currentNode, YKK_SLEEP_MODE_STATUS_TEXT)
          val buttons = findActionableLockUnlockButtons(currentNode)

          if (sleepModeNode == null && buttons.size >= 2) {
            Log.i(TAG, "Sleep mode text is gone and Lock/Unlock buttons are now active. App is ready.")
            isReady = true
            break
          }

          delay(RETRY_INTERVAL_MS)
        }
        if (isReady) rootInActiveWindow else null
      }


      if (readyRootNode == null) {
        Log.e(TAG, "Timed out or connection error occurred while waiting for lock/unlock buttons to become active.")
        return false
      }

      // --- Action-Specific Logic ---

      if (action == YkkAction.CHECK_STATUS) {
        Log.i(TAG, "App is awake. Proactively checking status from the ready UI.")
        checkStatus(readyRootNode)
        return true
      }

      val finalButtons = findActionableLockUnlockButtons(readyRootNode)
      val targetNode = when (action) {
        YkkAction.LOCK -> finalButtons.getOrNull(0)
        YkkAction.UNLOCK -> finalButtons.getOrNull(1)
        else -> null
      }

      return if (targetNode != null) {
        Log.d(TAG, "Found target button for action $action. Performing click.")
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      } else {
        Log.w(TAG, "Could not find a target button for action: $action.")
        false
      }
    } finally {
      if (wakeLock.isHeld) {
        wakeLock.release()
        Log.d(TAG, "Bright screen wakelock released.")
      }
    }
  }

  private fun findActionableLockUnlockButtons(rootNode: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
    val buttons = mutableListOf<AccessibilityNodeInfo>()
    fun findNodes(node: AccessibilityNodeInfo?) {
      node ?: return
      if (node.className == "android.view.ViewGroup" && node.isClickable && node.contentDescription.isNullOrEmpty()) {
        buttons.add(node)
      }
      for (i in 0 until node.childCount) {
        findNodes(node.getChild(i))
      }
    }
    rootNode?.let { findNodes(it) }
    return buttons
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
    if (event?.packageName != YKK_PACKAGE_NAME) return
    rootInActiveWindow?.let { checkStatus(it) }
  }

  private fun checkStatus(rootNode: AccessibilityNodeInfo) {
    val isUnavailable = findNodeByText(rootNode, YKK_CONNECTION_ERROR_TEXT) != null
    if (isUnavailable) {
      LockBridgeService.updateLockStatus("UNAVAILABLE")
      return
    }

    val isLocked = findNodeByText(rootNode, YKK_LOCKED_STATUS_TEXT) != null
    val isUnlocked = findNodeByText(rootNode, YKK_UNLOCKED_STATUS_TEXT) != null

    val status = when {
      isLocked -> "LOCKED"
      isUnlocked -> "UNLOCKED"
      else -> "UNKNOWN"
    }
    if (status != "UNKNOWN") {
      LockBridgeService.updateLockStatus(status)
    }
  }

  @Suppress("SameParameterValue")
  private fun findClickableNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
    val textNode = findNodeByText(rootNode, text) ?: return null
    var currentNode: AccessibilityNodeInfo? = textNode
    while (currentNode != null) {
      if (currentNode.isClickable) {
        return currentNode
      }
      currentNode = currentNode.parent
    }
    return if (textNode.isClickable) textNode else null
  }

  private fun findNodeByText(rootNode: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
    rootNode ?: return null
    val nodes = rootNode.findAccessibilityNodeInfosByText(text)
    return nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
  }

  override fun onInterrupt() {
    // Not implemented
  }

  override fun onDestroy() {
    super.onDestroy()
    instance = null
    serviceJob.cancel()
  }
}
