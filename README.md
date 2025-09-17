# YKK AP Smart Lock Bridge for Home Assistant

[![License: GPT3](https://img.shields.io/badge/License-GPT3-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-blueviolet.svg)]()

An Android application that acts as a bridge, integrating your YKK AP smart lock with Home Assistant via MQTT and a local web server.

This project provides a robust solution for controlling and monitoring your YKK AP "スマートコントロールキー" (Smart Control Key) electric lock, which lacks a public API for smart home integration. By leveraging Android's Accessibility Service, this app can programmatically interact with the official YKK AP application to lock, unlock, and read the status of your door.

![screenshot of the main screen](img/readme-scr-main.png "Main Screen")
![screenshot of the settings screen](img/readme-scr-settings.png "Settings Screen")

## Features

-   **Home Assistant Integration**: Full control and status monitoring through a highly reliable MQTT connection.
-   **Web UI Control**: A simple, local web interface for locking and unlocking, accessible from any device on your network.
-   **Real-time Status**: Publishes lock status (`LOCKED`, `UNLOCKED`, `UNAVAILABLE`) instantly to Home Assistant.
-   **Robust and Reliable**:
    -   Runs as a persistent foreground service to prevent the OS from terminating it.
    -   Includes intelligent retry logic for operations to handle unresponsiveness from the official YKK app.
    -   Uses MQTT's Last Will and Testament (LWT) to correctly report the lock as `offline` in Home Assistant if the connection is lost.
    -   Automatically wakes the device screen to ensure UI interactions are successful.
-   **No Root Required**: Operates on a standard, non-rooted Android device.
-   **Modern UI**: A clean, Jetpack Compose-based user interface for easy configuration of MQTT and web server settings.

## System Architecture

The application is installed on a dedicated Android device placed near the lock. It runs a background service that listens for commands and interacts with the official YKK AP application.

```mermaid
graph TD
    subgraph Smart Home
        HA[Home Assistant]
        MQTT[MQTT Broker]
    end

    subgraph User Interfaces
        U_HA((User via HA)) --> HA
        U_WEB((User via Web Browser)) --> KTOR[Web Server]
    end

    subgraph Android Bridge Device
        LBS[Lock Bridge Service]
        MQTT_MGR[MQTT Manager]
        KTOR
        AS[YkkAccessibilityService]
        YKK[Official YKK App]

        KTOR -- Command --> LBS
        MQTT_MGR -- Command --> LBS
        LBS -- Perform Action --> AS
        AS -- Wakes Screen & Launches --> YKK
        AS -- Clicks Buttons / Reads UI --> YKK
        YKK -- UI Update --> AS
        AS -- Reports Status --> LBS
        LBS -- Publish Status --> MQTT_MGR
    end

    subgraph Physical
        LOCK((YKK AP Electric Lock))
    end

    HA <--> MQTT
    MQTT <--> MQTT_MGR

    YKK <-->|Bluetooth| LOCK
```

## Prerequisites

1.  **Dedicated Android Device**: An Android phone or tablet (Android 8.0 Oreo / API 26 or newer) that can be left powered on and close to the door lock at all times.
2.  **YKK AP App**: The official [スマートコントロールキー app](https://play.google.com/store/apps/details?id=com.alpha.lockapp) installed and paired with your door lock on the dedicated Android device.
3.  **Home Assistant**: A running instance of Home Assistant.
4.  **MQTT Broker**: A running MQTT broker accessible from the Android device.

## Installation and Configuration

### Step 1: Android Device Setup

1.  **Install the YKK AP App**: Install the "スマートコントロールキー" app from the Google Play Store on your dedicated Android device and complete the pairing process with your door lock.
2.  **Install This App**: Download the latest `.apk` from the [Releases page](https://github.com/your-username/your-repo/releases) of this repository and install it on the same Android device.
3.  **Disable Lock Screen**: For maximum reliability, it is recommended to disable the screen lock (set to "None" or "Swipe") on the Android device. The app is designed to dismiss non-secure lock screens, but disabling it entirely removes a potential point of failure.

### Step 2: App Configuration

1.  **Launch the App**: Open the "YKK AP Smart Lock Assistant" app.
2.  **Grant Permissions**: The main screen will guide you through granting three critical permissions required for the service to run:
    *   **Accessibility Service**: This is the core of the integration. It allows the app to read the lock status from the YKK app's screen and click the lock/unlock buttons.
    *   **Notifications**: Required for the app to run as a persistent foreground service, which is essential for background operation.
    *   **Battery Optimization Exemption**: Prevents the Android OS from putting the app to sleep to save power.
3.  **Configure Integrations**:
    *   Navigate to **Settings** (top-right icon).
    *   **Home Assistant via MQTT**: Enable this integration and enter your MQTT broker's URL, port, and credentials (if applicable).
    *   **Web Server**: Optionally, enable the web server for an alternative control method.
    *   Click **Save**.
4.  **Start the Service**: Return to the main screen and tap **"Start Service"**. The status indicators for MQTT, Web Server, and the Door Lock should update.

### Step 3: Home Assistant Configuration

1.  **Add MQTT Configuration**: Add the following configuration to your `configuration.yaml` file in Home Assistant. This will create the lock entity, as well as several helpful sensors and a button for a complete integration.

    ```yaml
    # MQTT Integration for the Smart Door Lock
    mqtt:
      lock:
        - name: "Smart Door Lock"
          unique_id: smart_door_lock_mqtt
          state_topic: "home/doorlock/state"
          command_topic: "home/doorlock/set"
          payload_lock: "LOCK"
          payload_unlock: "UNLOCK"
          state_locked: "LOCKED"
          state_unlocked: "UNLOCKED"
          optimistic: false
          qos: 1
          retain: false
          availability:
            - topic: "home/doorlock/availability"
          payload_available: "online"
          payload_not_available: "offline"
          device:
            identifiers:
              - smart_door_lock_mqtt
            name: "Smart Door Lock"
            manufacturer: "YKK AP"
            model: "Smart Control Key Bridge"

      button:
        - name: "Update Door Lock Status"
          unique_id: smart_door_lock_update_status
          command_topic: "home/doorlock/check_status"
          payload_press: "CHECK"
          retain: false
          device:
            identifiers:
              - smart_door_lock_mqtt

      sensor:
        - name: "Door Lock Last Update"
          unique_id: smart_door_lock_last_update
          state_topic: "home/doorlock/last_updated"
          device_class: timestamp
          device:
            identifiers:
              - smart_door_lock_mqtt

        - name: "Door Lock Status"
          unique_id: smart_door_lock_status
          state_topic: "home/doorlock/state"
          device:
            identifiers:
              - smart_door_lock_mqtt
          availability:
            - topic: "home/doorlock/availability"
          payload_available: "online"
          payload_not_available: "offline"
          icon: >-
            {% if value == 'LOCKED' %}
              mdi:lock
            {% elif value == 'UNLOCKED' %}
              mdi:lock-open
            {% else %}
              mdi:lock-question
            {% endif %}
    ```

2.  **Reload Home Assistant Configuration**: Reload your Home Assistant configuration (or simply restart Home Assistant instance) to apply the changes. A new "Smart Door Lock" device with its associated entities should now be available.

## Troubleshooting

-   **Service Stops Randomly**: This is almost always due to aggressive battery optimization by the phone's manufacturer (e.g., Samsung, OnePlus, Xiaomi). Ensure you have granted the "Battery Optimization Exemption" and check for any other non-standard power-saving apps or settings on the device.
-   **"Restricted setting" Popup**: On newer Android versions, enabling the Accessibility Service for a sideloaded app is protected. The app provides a guidance dialog to help you navigate this: you typically need to go into the app's info page from the system settings and allow restricted settings before you can enable the service.
-   **Lock Status is "UNAVAILABLE"**: This means the Accessibility Service cannot read the state of the YKK app. This can happen if:
    -   The YKK app is showing a connection error.
    -   The phone screen is on, but the YKK app is not in the foreground.
    -   The YKK app has been updated with UI changes that are not yet supported by this bridge.
    -   Use the "Update Door Lock Status" button in Home Assistant to trigger a fresh status check.

## Contributing

Contributions are welcome! If you have an idea for an improvement or find a bug, please feel free to open an issue or submit a pull request.

## License

This project is licensed under the GPT3 License. See the [LICENSE](LICENSE) file for details.
