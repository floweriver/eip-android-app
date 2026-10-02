package com.example.eip.ble

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import androidx.compose.ui.graphics.Color

enum class DeviceConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    DISCOVERING_CHARACTERISTICS,
    READY;

    val displayText: String
        get() = when (this) {
            DISCONNECTED -> "Not Connected"
            CONNECTING -> "Connecting"
            CONNECTED -> "Connected"
            DISCOVERING_SERVICES -> "Discovering Services"
            DISCOVERING_CHARACTERISTICS -> "Discovering Characteristics"
            READY -> "Ready"
        }

    val displayColor: Color
        get() = when (this) {
            DISCONNECTED -> Color.Gray
            CONNECTING -> Color(0xFFFFA500)
            CONNECTED, DISCOVERING_SERVICES, DISCOVERING_CHARACTERISTICS -> Color.Blue
            READY -> Color.Green
        }
}

enum class DeviceType {
    PENCIL,
    KEYBOARD
}

data class BleDevice(
    val peripheral: BluetoothDevice?,
    val name: String,
    val rssi: Int = -1,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val deviceType: DeviceType = DeviceType.PENCIL,
    val batteryLevel: Int? = null,
    val discoveredServices: List<ParcelUuid> = emptyList(),
    val discoveredCharacteristics: Map<ParcelUuid, List<String>> = emptyMap(),
    val isNearby: Boolean = false // 偵測附近裝置的信號狀態
) {
    val id: String
        get() = peripheral?.address ?: "SIMULATOR_001"

    val isKeyboard: Boolean
        get() = name.lowercase().let { n ->
            n.contains("keyboard") || n.contains("magic") || n.contains("hyperkeys") || n.contains("hyper") || name == "SK-M1300"
        }

    // eiP Flip Keyboard（KL122）。協議與畫面都和觸控筆分開，連線後依此分流。
    val isFlipKeyboard: Boolean
        get() = FlipProtocol.isFlipKeyboardName(name)
}
