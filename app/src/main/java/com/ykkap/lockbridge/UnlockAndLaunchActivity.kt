package com.ykkap.lockbridge

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity

private const val YKK_PACKAGE_NAME = "com.alpha.lockapp"
private const val TAG = "UnlockAndLaunchActivity"

/**
 * An Activity whose sole purpose is to wake the device, dismiss the keyguard if necessary,
 * and then launch the target YKK AP application.
 *
 * This implementation robustly handles all types of lock screens by always calling
 * the `requestDismissKeyguard` API. This correctly dismisses non-secure (swipe)
 * lock screens which may otherwise report `isKeyguardLocked` as false but still
 * obstruct UI interaction.
 */
class UnlockAndLaunchActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    // This block must be called before super.onCreate() to apply window flags.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      // Modern APIs for Android 8.1 (API 27) and above to show over lock screen.
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      // Legacy flags required for Android 8.0 (API 26) and below.
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
      )
    }
    super.onCreate(savedInstanceState)
    Log.d(TAG, "onCreate: Waking device and preparing to unlock.")

    val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

    // Always request dismissal. The callback API is designed to handle all cases:
    // - No keyguard: onDismissError() may be called, which we handle gracefully.
    // - Non-secure keyguard (swipe): onDismissSucceeded() is called, dismissing it.
    // - Secure keyguard (PIN/Pattern): The user is prompted, and a callback is fired on the result.
    Log.d(TAG, "Requesting keyguard dismissal.")
    keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
      override fun onDismissSucceeded() {
        super.onDismissSucceeded()
        Log.i(TAG, "Keyguard dismissed successfully.")
        launchTargetApp()
        // Ensure this transient activity is destroyed after its job is done.
        finish()
      }

      override fun onDismissCancelled() {
        super.onDismissCancelled()
        Log.w(TAG, "Keyguard dismiss was cancelled by the user.")
        finish()
      }

      override fun onDismissError() {
        super.onDismissError()
        // This can happen if the activity is not in the foreground, or if there's no
        // keyguard to dismiss at all. In the latter case, it is safe to proceed.
        // We optimistically try to launch the app anyway.
        Log.w(TAG, "Keyguard dismiss error occurred. Proceeding to launch target app.")
        launchTargetApp()
        finish()
      }
    })
  }

  private fun launchTargetApp() {
    val intent = packageManager.getLaunchIntentForPackage(YKK_PACKAGE_NAME)
    if (intent != null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      startActivity(intent)
      Log.i(TAG, "Launch intent for '$YKK_PACKAGE_NAME' sent.")
    } else {
      Log.e(TAG, "Could not get launch intent for package '$YKK_PACKAGE_NAME'. Is the app installed?")
    }
  }
}
