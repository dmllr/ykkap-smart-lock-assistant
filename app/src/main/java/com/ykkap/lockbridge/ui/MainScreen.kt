package com.ykkap.lockbridge.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ykkap.lockbridge.service.LockBridgeService
import com.ykkap.lockbridge.service.YkkAccessibilityService
import com.ykkap.lockbridge.viewmodel.MainViewModel
import com.ykkap.lockbridge.viewmodel.ServiceState
import androidx.core.net.toUri

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
  viewModel: MainViewModel,
  onNavigateToSettings: () -> Unit
) {
  val serviceState by viewModel.serviceState.collectAsState()
  val mqttStatus by viewModel.mqttStatus.collectAsState()
  val webServerStatus by viewModel.webServerStatus.collectAsState()
  val lockStatus by viewModel.lockStatus.collectAsState()

  val context = LocalContext.current
  var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
  var hasNotificationPermission by remember { mutableStateOf(hasNotificationPermission(context)) }
  var hasBatteryExemption by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }


  // This state prevents the permissions card from showing immediately on app launch.
  // It is only shown after the user attempts to start the service without permissions.
  var showPermissionsRationale by remember { mutableStateOf(false) }

  val accessibilitySettingsLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    // After returning from settings, re-check the service status.
    isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { isGranted ->
    hasNotificationPermission = isGranted
  }

  val batteryOptimizationsLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    // After returning from settings, re-check the optimization status.
    hasBatteryExemption = isIgnoringBatteryOptimizations(context)
  }

  val allPermissionsGranted = isAccessibilityEnabled && hasNotificationPermission && hasBatteryExemption

  // Automatically hide the permissions rationale card if the user grants all permissions.
  LaunchedEffect(allPermissionsGranted) {
    if (allPermissionsGranted) {
      showPermissionsRationale = false
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("YKK AP Lock Bridge") },
        actions = {
          IconButton(onClick = onNavigateToSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
          }
        }
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      ServiceControlSection(
        serviceState = serviceState,
        onToggleService = {
          if (allPermissionsGranted || serviceState == ServiceState.RUNNING) {
            val intent = Intent(context, LockBridgeService::class.java)
            if (serviceState == ServiceState.RUNNING) {
              context.stopService(intent)
            } else {
              context.startForegroundService(intent)
            }
          } else {
            // If permissions are not granted, show the rationale card to the user.
            showPermissionsRationale = true
          }
        }
      )

      AnimatedVisibility(visible = showPermissionsRationale && !allPermissionsGranted) {
        PermissionsAlert(
          isAccessibilityEnabled = isAccessibilityEnabled,
          hasNotificationPermission = hasNotificationPermission,
          hasBatteryExemption = hasBatteryExemption,
          onGrantAccessibility = {
            accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
          },
          onGrantNotification = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
              notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
          },
          onGrantBatteryExemption = {
            val intent = Intent().apply {
              action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
              data = "package:${context.packageName}".toUri()
            }
            batteryOptimizationsLauncher.launch(intent)
          }
        )
      }

      StatusCard(mqttStatus, webServerStatus, lockStatus)
    }
  }
}

@Composable
private fun ServiceControlSection(
  serviceState: ServiceState,
  onToggleService: () -> Unit
) {
  val isRunning = serviceState == ServiceState.RUNNING

  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Button(
      onClick = onToggleService,
      modifier = Modifier
        .fillMaxWidth()
        .height(50.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
      )
    ) {
      Text(if (isRunning) "Stop Service" else "Start Service")
    }
  }
}

@Composable
private fun StatusCard(mqttStatus: String, webServerStatus: String, lockStatus: String) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Live Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
      StatusRow("MQTT Broker:", mqttStatus)
      StatusRow("Web Server:", webServerStatus)
      StatusRow("Door Lock:", lockStatus)
    }
  }
}

@Composable
private fun StatusRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
  }
}

@Composable
private fun PermissionsAlert(
  isAccessibilityEnabled: Boolean,
  hasNotificationPermission: Boolean,
  hasBatteryExemption: Boolean,
  onGrantAccessibility: () -> Unit,
  onGrantNotification: () -> Unit,
  onGrantBatteryExemption: () -> Unit
) {
  var showAccessibilityDialog by remember { mutableStateOf(false) }

  OutlinedCard(
    modifier = Modifier.fillMaxWidth(),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Text(
        "Action Required",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error
      )
      Text(
        "The following permissions are required to start the service:",
        style = MaterialTheme.typography.bodyMedium
      )
      PermissionItem(
        text = "Accessibility Service",
        isGranted = isAccessibilityEnabled,
        onClick = { showAccessibilityDialog = true }
      )
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PermissionItem(
          text = "Notifications",
          isGranted = hasNotificationPermission,
          onClick = onGrantNotification
        )
      }
      PermissionItem(
        text = "Battery Optimization",
        isGranted = hasBatteryExemption,
        onClick = onGrantBatteryExemption
      )
    }
  }

  if (showAccessibilityDialog) {
    AccessibilityGuidanceDialog(
      onDismiss = { showAccessibilityDialog = false },
      onConfirm = {
        showAccessibilityDialog = false
        onGrantAccessibility()
      }
    )
  }
}

@Composable
fun PermissionItem(text: String, isGranted: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
        contentDescription = if (isGranted) "Granted" else "Required",
        tint = if (isGranted) Color(0xFF008000) else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(24.dp)
      )
      Spacer(Modifier.width(8.dp))
      Text(text)
    }
    if (!isGranted) {
      Button(onClick = onClick) {
        Text("Grant")
      }
    }
  }
}

@Composable
fun AccessibilityGuidanceDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Guidance for Accessibility") },
    text = {
      Text(
        "For security, Android requires a few steps to enable this. This may be required again after each app update.\n\n" +
            "1. On the next screen, find and tap 'YKK AP Smart Lock Assistant'.\n\n" +
            "2. The system may show a 'Restricted setting' popup. If so, tap 'OK' and press the BACK button when you reach the App Info page.\n\n" +
            "3. You will now be back on the Accessibility page, and the switch for the service can be enabled."
      )
    },
    confirmButton = {
      Button(onClick = onConfirm) {
        Text("Proceed to Settings")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

// region Helper Functions
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
  val serviceId = "${context.packageName}/${YkkAccessibilityService::class.java.name}"
  val settingValue = Settings.Secure.getString(
    context.contentResolver,
    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
  )
  return settingValue?.contains(serviceId) == true
}

private fun hasNotificationPermission(context: Context): Boolean {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
    return true
  }
  return ContextCompat.checkSelfPermission(
    context,
    Manifest.permission.POST_NOTIFICATIONS
  ) == PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
  val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
  return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
// endregion
