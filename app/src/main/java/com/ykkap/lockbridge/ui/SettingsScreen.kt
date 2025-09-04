package com.ykkap.lockbridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ykkap.lockbridge.data.AppSettings
import com.ykkap.lockbridge.data.MqttSettings
import com.ykkap.lockbridge.data.WebServerSettings
import com.ykkap.lockbridge.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  viewModel: MainViewModel,
  onNavigateUp: () -> Unit
) {
  val settings by viewModel.appSettings.collectAsState()
  val coroutineScope = rememberCoroutineScope()

  var mqttEnabled by remember(settings.mqttSettings.isEnabled) { mutableStateOf(settings.mqttSettings.isEnabled) }
  var broker by remember(settings.mqttSettings.broker) { mutableStateOf(settings.mqttSettings.broker) }
  var port by remember(settings.mqttSettings.port) { mutableStateOf(settings.mqttSettings.port.toString()) }
  var username by remember(settings.mqttSettings.username) { mutableStateOf(settings.mqttSettings.username) }
  var password by remember(settings.mqttSettings.password) { mutableStateOf(settings.mqttSettings.password) }

  var webServerEnabled by remember(settings.webServerSettings.isEnabled) { mutableStateOf(settings.webServerSettings.isEnabled) }
  var webServerPort by remember(settings.webServerSettings.port) { mutableStateOf(settings.webServerSettings.port.toString()) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onNavigateUp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        }
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      Column(
        modifier = Modifier
          .weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        WebServerSection(
          isEnabled = webServerEnabled,
          port = webServerPort,
          onEnabledChange = { webServerEnabled = it },
          onPortChange = { webServerPort = it }
        )
        MqttSettingsSection(
          isEnabled = mqttEnabled,
          broker = broker,
          port = port,
          username = username,
          password = password,
          onEnabledChange = { mqttEnabled = it },
          onBrokerChange = { broker = it },
          onPortChange = { port = it },
          onUsernameChange = { username = it },
          onPasswordChange = { password = it }
        )
      }

      Button(
        onClick = {
          coroutineScope.launch {
            val appSettings = AppSettings(
              mqttSettings = MqttSettings(
                isEnabled = mqttEnabled,
                broker = broker,
                port = port.filter { it.isDigit() }.toIntOrNull() ?: 1883,
                username = username,
                password = password
              ),
              webServerSettings = WebServerSettings(
                isEnabled = webServerEnabled,
                port = webServerPort.filter { it.isDigit() }.toIntOrNull() ?: 8080
              )
            )
            viewModel.saveAppSettings(appSettings)
            onNavigateUp()
          }
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)
          .height(50.dp)
      ) {
        Text("Save")
      }
    }
  }
}

@Suppress("SameParameterValue")
@Composable
private fun CollapsibleSection(
  title: String,
  initiallyExpanded: Boolean = true,
  content: @Composable () -> Unit
) {
  var isExpanded by remember { mutableStateOf(initiallyExpanded) }
  val rotationAngle by animateFloatAsState(targetValue = if (isExpanded) 180f else 0f, label = "rotation")

  Card {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { isExpanded = !isExpanded }
          .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Icon(
          imageVector = Icons.Default.ArrowDropDown,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          modifier = Modifier.rotate(rotationAngle)
        )
      }
      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          content()
        }
      }
    }
  }
}


@Composable
private fun WebServerSection(
  isEnabled: Boolean,
  port: String,
  onEnabledChange: (Boolean) -> Unit,
  onPortChange: (String) -> Unit
) {
  CollapsibleSection(title = "Web Server", initiallyExpanded = true) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text("Enable Web Server", style = MaterialTheme.typography.bodyLarge)
      Switch(checked = isEnabled, onCheckedChange = onEnabledChange)
    }
    OutlinedTextField(
      value = port,
      onValueChange = onPortChange,
      label = { Text("Web Server Port") },
      enabled = isEnabled,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth()
    )
  }
}


@Composable
private fun MqttSettingsSection(
  isEnabled: Boolean,
  broker: String,
  port: String,
  username: String,
  password: String,
  onEnabledChange: (Boolean) -> Unit,
  onBrokerChange: (String) -> Unit,
  onPortChange: (String) -> Unit,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit
) {
  CollapsibleSection(title = "Home Assistant via MQTT", initiallyExpanded = true) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text("Enable Home Assistant Integration", style = MaterialTheme.typography.bodyLarge)
      Switch(checked = isEnabled, onCheckedChange = onEnabledChange)
    }
    OutlinedTextField(
      value = broker,
      onValueChange = onBrokerChange,
      label = { Text("MQTT Broker URL") },
      enabled = isEnabled,
      modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
      value = port,
      onValueChange = onPortChange,
      label = { Text("MQTT Port") },
      enabled = isEnabled,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
      value = username,
      onValueChange = onUsernameChange,
      label = { Text("Username (Optional)") },
      enabled = isEnabled,
      modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
      value = password,
      onValueChange = onPasswordChange,
      label = { Text("Password (Optional)") },
      enabled = isEnabled,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
      modifier = Modifier.fillMaxWidth()
    )
  }
}
