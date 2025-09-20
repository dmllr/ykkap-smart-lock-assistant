package com.ykkap.lockbridge.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ykkap.lockbridge.R
import com.ykkap.lockbridge.data.AppSettings
import com.ykkap.lockbridge.data.MqttSettings
import com.ykkap.lockbridge.data.SettingsRepository
import com.ykkap.lockbridge.data.WebServerSettings
import com.ykkap.lockbridge.http.WebServerManager
import com.ykkap.lockbridge.mqtt.MqttManager
import com.ykkap.lockbridge.viewmodel.ServiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class LockBridgeService : LifecycleService() {

  private lateinit var mqttManager: MqttManager
  private var webServerManager: WebServerManager? = null

  private lateinit var settingsRepository: SettingsRepository
  private var wakeLock: PowerManager.WakeLock? = null
  private var supervisorJob: Job? = null
  private var statusCheckerJob: Job? = null

  companion object {
    private const val TAG = "LockBridgeService"
    private const val NOTIFICATION_ID = 1
    private const val CHANNEL_ID = "LockBridgeServiceChannel"
    private const val OPERATION_RETRY_LIMIT = 3
    private const val OPERATION_TIMEOUT_MS = 10000L // 10 seconds to wait for state change
    private const val STATUS_CHECK_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

    private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _mqttStatus = MutableStateFlow("Stopped")
    val mqttStatus: StateFlow<String> = _mqttStatus.asStateFlow()

    private val _webServerStatus = MutableStateFlow("Stopped")
    val webServerStatus: StateFlow<String> = _webServerStatus.asStateFlow()

    // Using a SharedFlow ensures that every update emission is processed by collectors,
    // even if the new state is the same as the old one. This is crucial for refreshing
    // the 'last_updated' timestamp in Home Assistant on every manual check.
    // 'replay = 1' ensures new subscribers immediately get the last known status.
    private val _lockStatus = MutableSharedFlow<String>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lockStatus: SharedFlow<String> = _lockStatus.asSharedFlow()

    private val _lastStatusUpdateTime = MutableStateFlow<Long?>(null)
    val lastStatusUpdateTime: StateFlow<Long?> = _lastStatusUpdateTime.asStateFlow()

    private val _desiredLockState = MutableStateFlow<String?>(null)
    private val manualUpdateRequest = MutableSharedFlow<Unit>(
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
      // Provide the initial state for the replay cache.
      _lockStatus.tryEmit("Unknown")
    }

    fun updateLockStatus(status: String) {
      _lockStatus.tryEmit(status)
      if (status == "LOCKED" || status == "UNLOCKED") {
        _lastStatusUpdateTime.value = System.currentTimeMillis()
      }
    }

    // This function provides a clean, thread-safe way for external components like the
    // ViewModel to request a status update without needing a direct service instance.
    fun requestManualUpdate() {
      // tryEmit is used here to avoid suspending if there's no collector,
      // making it safe to call from any context. The buffer ensures the event is not dropped.
      manualUpdateRequest.tryEmit(Unit)
    }
  }

  override fun onCreate() {
    super.onCreate()
    settingsRepository = SettingsRepository(this)
    _serviceState.value = ServiceState.RUNNING

    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YkkApp::BridgeWakelockTag")
    @SuppressLint("WakelockTimeout")
    wakeLock?.acquire()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    startForeground(NOTIFICATION_ID, createNotification())

    _desiredLockState.value = null

    lifecycleScope.launch {
      settingsRepository.settingsFlow.distinctUntilChanged().collect { settings ->
        handleSettingsUpdate(settings)
      }
    }

    lifecycleScope.launch {
      lockStatus.collect { status ->
        if (::mqttManager.isInitialized && mqttManager.isConnected) {
          when (status) {
            "LOCKED", "UNLOCKED" -> {
              // When the lock is accessible, report it as 'online' and publish its state.
              mqttManager.publish("home/doorlock/availability", "online")
              mqttManager.publish("home/doorlock/state", status.uppercase())

              // Publish the current timestamp to the dedicated topic for HA.
              // ISO 8601 format is required for the 'timestamp' device_class.
              val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(ZoneOffset.UTC)
                .format(Instant.now())
              mqttManager.publish("home/doorlock/last_updated", timestamp)
            }

            "UNAVAILABLE" -> {
              // When not accessible, report it as 'offline'.
              mqttManager.publish("home/doorlock/availability", "offline")
            }
          }
        }
      }
    }

    // This collector listens for update requests from the UI (via the ViewModel).
    lifecycleScope.launch {
      manualUpdateRequest.collect {
        Log.i(TAG, "Manual status update requested from UI.")
        YkkAccessibilityService.requestStatusCheck()
      }
    }

    // Cancel and restart jobs to ensure they are always running when the service is.
    supervisorJob?.cancel()
    supervisorJob = lifecycleScope.launch {
      startOperationSupervisor()
    }

    statusCheckerJob?.cancel()
    statusCheckerJob = lifecycleScope.launch {
      startPeriodicStatusChecker()
    }


    return START_STICKY
  }

  private fun handleSettingsUpdate(settings: AppSettings) {
    if (::mqttManager.isInitialized) {
      mqttManager.disconnect()
    }

    if (settings.mqttSettings.isEnabled) {
      setupMqtt(settings.mqttSettings)
    } else {
      _mqttStatus.value = "Disabled"
    }

    setupWebServer(settings.webServerSettings)
  }

  private fun setupWebServer(settings: WebServerSettings) {
    if (settings.isEnabled) {
      _webServerStatus.value = "Starting..."
      webServerManager?.stop()
      webServerManager = WebServerManager(
        lockStatus = lockStatus,
        lastStatusUpdateTime = lastStatusUpdateTime
      ) { command ->
        triggerLockOperation(command)
      }.also {
        try {
          it.start(settings.port)
          _webServerStatus.value = "Running on port ${settings.port}"
        } catch (e: Exception) {
          Log.e(TAG, "Failed to start web server", e)
          _webServerStatus.value = "Error: ${e.message}"
        }
      }
    } else {
      webServerManager?.stop()
      webServerManager = null
      _webServerStatus.value = "Disabled"
    }
  }

  private suspend fun startOperationSupervisor() {
    _desiredLockState.collect { desiredState ->
      if (desiredState == null) return@collect

      for (attempt in 1..OPERATION_RETRY_LIMIT) {
        if (lockStatus.first().equals(desiredState, ignoreCase = true)) {
          Log.i(TAG, "Operation successful. Desired state '$desiredState' matches actual state.")
          _desiredLockState.value = null
          return@collect
        }

        Log.i(
          TAG,
          "Attempt $attempt/$OPERATION_RETRY_LIMIT: State mismatch. Desired: '$desiredState', Actual: '${lockStatus.first()}'."
        )

        val action = if (desiredState == "LOCKED") YkkAction.LOCK else YkkAction.UNLOCK

        // 1. Await the result of the click action itself.
        val clickSuccess = YkkAccessibilityService.executeAction(action)

        // 2. Only if the click was successful, wait for the state to change.
        if (clickSuccess) {
          Log.i(TAG, "Click action for '$desiredState' performed successfully. Waiting for state confirmation.")
          val success = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
            lockStatus.first { it.equals(desiredState, ignoreCase = true) }
            true
          }

          if (success == true) {
            Log.i(TAG, "State successfully changed to '$desiredState' on attempt $attempt.")
            _desiredLockState.value = null
            return@collect
          } else {
            Log.w(TAG, "Attempt $attempt: State change confirmation timed out.")
          }
        } else {
          Log.w(TAG, "Attempt $attempt: The click action itself failed or timed out.")
        }

        delay(2000) // Wait before the next full attempt.
      }

      Log.e(TAG, "Operation failed after $OPERATION_RETRY_LIMIT attempts. Could not set state to '$desiredState'.")
      // Report that the lock is unavailable since the operation failed.
      updateLockStatus("UNAVAILABLE")
      _desiredLockState.value = null
    }
  }

  private suspend fun startPeriodicStatusChecker() {
    // Perform an initial check shortly after the service starts.
    delay(10000) // Wait 10 seconds before the first check.
    while (currentCoroutineContext().isActive) {
      Log.i(TAG, "Performing periodic status check...")
      // We only want to check the status, not perform a lock/unlock action.
      // This wakes the app from sleep and triggers the AccessibilityEvent listener.
      YkkAccessibilityService.requestStatusCheck()
      delay(STATUS_CHECK_INTERVAL_MS)
    }
  }

  private fun setupMqtt(settings: MqttSettings) {
    if (settings.broker.isBlank()) {
      _mqttStatus.value = "Error: Broker URL is not set."
      return
    }

    _mqttStatus.value = "Connecting..."

    mqttManager = MqttManager(
      serverHost = settings.broker,
      serverPort = settings.port,
      username = settings.username,
      password = settings.password,
      onConnectionStatusChange = { isConnected, reason ->
        _mqttStatus.value = when {
          isConnected -> "Connected"
          reason != null -> "Error: $reason"
          else -> "Disconnected"
        }
        // When the connection is successfully established, publish the 'online' availability message.
        // This signals to Home Assistant that the lock entity is active and controllable.
        if (isConnected) {
          mqttManager.publish("home/doorlock/availability", "online")
        }
      },
      onMessageArrived = { topic, message ->
        handleMqttMessage(topic, message)
      }
    )
    mqttManager.connect()
  }

  private fun handleMqttMessage(topic: String, message: String) {
    when (topic) {
      "home/doorlock/set" -> {
        when (message) {
          "LOCK", "UNLOCK" -> triggerLockOperation(message)
        }
      }

      "home/doorlock/check_status" -> YkkAccessibilityService.requestStatusCheck()
      "home/doorlock/debug" -> {
        lifecycleScope.launch { YkkAccessibilityService.executeAction(YkkAction.DUMP_VIEW_HIERARCHY) }
      }
    }
  }

  private fun triggerLockOperation(command: String) {
    when (command) {
      "LOCK" -> _desiredLockState.value = "LOCKED"
      "UNLOCK" -> _desiredLockState.value = "UNLOCKED"
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // Cancel all running coroutines when the service is destroyed.
    supervisorJob?.cancel()
    statusCheckerJob?.cancel()

    if (::mqttManager.isInitialized) {
      // Before performing a graceful disconnect, explicitly publish the 'offline' availability message.
      // This ensures Home Assistant is immediately notified that the lock is unavailable.
      // The LWT message is only sent by the broker in cases of ungraceful disconnection.
      if (mqttManager.isConnected) {
        mqttManager.publish("home/doorlock/availability", "offline")
      }
      mqttManager.disconnect()
    }
    webServerManager?.stop()
    wakeLock?.release()
    _serviceState.value = ServiceState.STOPPED
    _mqttStatus.value = "Stopped"
    _webServerStatus.value = "Stopped"
  }

  private fun createNotification(): Notification {
    val serviceChannel = NotificationChannel(
      CHANNEL_ID,
      "Lock Bridge Service Channel",
      NotificationManager.IMPORTANCE_DEFAULT
    )
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(serviceChannel)

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("YKK Lock Bridge Active")
      .setContentText("Monitoring lock status via MQTT and Web")
      .setSmallIcon(R.mipmap.ic_launcher_foreground)
      .build()
  }
}
