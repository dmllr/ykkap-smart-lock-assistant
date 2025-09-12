package com.ykkap.lockbridge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ykkap.lockbridge.data.AppSettings
import com.ykkap.lockbridge.data.SettingsRepository
import com.ykkap.lockbridge.service.LockBridgeService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ServiceState {
  RUNNING, STOPPED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val settingsRepository = SettingsRepository(application)

  val appSettings: StateFlow<AppSettings> = settingsRepository.settingsFlow
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = AppSettings()
    )

  val serviceState: StateFlow<ServiceState> = LockBridgeService.serviceState
  val mqttStatus: StateFlow<String> = LockBridgeService.mqttStatus
  val webServerStatus: StateFlow<String> = LockBridgeService.webServerStatus
  val lockStatus: StateFlow<String> = LockBridgeService.lockStatus
  val lastStatusUpdateTime: StateFlow<Long?> = LockBridgeService.lastStatusUpdateTime

  fun saveAppSettings(settings: AppSettings) {
    viewModelScope.launch {
      settingsRepository.updateAppSettings(settings)
    }
  }
}
