package com.ykkap.lockbridge.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ykkap.lockbridge.viewmodel.MainViewModel

@Composable
fun MainNavigation() {
  val navController = rememberNavController()
  val mainViewModel: MainViewModel = viewModel()

  NavHost(navController = navController, startDestination = "main") {
    composable("main") {
      MainScreen(
        viewModel = mainViewModel,
        onNavigateToSettings = { navController.navigate("settings") }
      )
    }
    composable("settings") {
      SettingsScreen(
        viewModel = mainViewModel,
        onNavigateUp = { navController.navigateUp() }
      )
    }
  }
}
