plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "com.ykkap.lockbridge"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.ykkap.lockbridge"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables {
      useSupportLibrary = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
  }
  composeOptions {
    kotlinCompilerExtensionVersion = "1.5.1"
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
      excludes += "META-INF/INDEX.LIST"
      excludes += "META-INF/io.netty.versions.properties"
    }
  }
}

dependencies {
  // Core Android & Jetpack
  implementation(libs.core.ktx.v1120)
  implementation(libs.androidx.lifecycle.runtime.ktx.v262)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose.v182)

  // Jetpack Compose
  implementation(platform(libs.androidx.compose.bom.v20230800))
  implementation(libs.ui)
  implementation(libs.ui.graphics)
  implementation(libs.ui.tooling.preview)
  implementation(libs.material3)
  implementation(libs.androidx.material.icons.core)
  implementation(libs.androidx.material.icons.extended)

  // Navigation
  implementation(libs.androidx.navigation.compose)

  // DataStore for Settings
  implementation(libs.androidx.datastore.preferences)

  // Modern MQTT Client (HiveMQ)
  implementation(libs.hivemq.mqtt.client)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)

  // Ktor Web Server
  implementation(libs.ktor.server.cio)
  implementation(libs.ktor.server.html.builder)
  implementation(libs.ktor.server.status.pages)
}
