package com.example.eip.ble

import java.util.UUID

object BleProtocol {

    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private fun String.toFullUuid(): UUID = UUID.fromString("0000$this$BASE_UUID_SUFFIX")

    // Common Packet Headers
    const val APP_HEADER_0: Byte = 0x55.toByte()
    const val APP_HEADER_1: Byte = 0xAA.toByte()
    const val DEVICE_HEADER_0: Byte = 0xAA.toByte()
    const val DEVICE_HEADER_1: Byte = 0x55.toByte()

    // --- Pencil Protocol Constants (eiP Pencil X / Ultra) ---
    object Pencil {
        val SERVICE_UUID: UUID = "ffe0".toFullUuid()
        val WRITE_CHAR_UUID: UUID = "ffe2".toFullUuid()
        val NOTIFY_CHAR_UUID: UUID = "ffe3".toFullUuid()

        // Commands
        const val CMD_QUERY: Byte = 0x00
        const val CMD_SET: Byte = 0x01
        const val CMD_FACTORY_RESET: Byte = 0x03
        const val CMD_SIGNAL_ADJUST: Byte = 0x04
        const val CMD_DISABLE_ALL: Byte = 0x05
        const val CMD_SMART_SHUTDOWN: Byte = 0x06
        
        const val CMD_BOTTOM_USI_QUERY: Byte = 0x07
        const val CMD_BOTTOM_USI_SET: Byte = 0x08
        const val CMD_TAIL_USI_QUERY: Byte = 0x09
        const val CMD_TAIL_USI_SET: Byte = 0x0A
        
        const val CMD_STATUS_QUERY: Byte = 0xF0.toByte()
        const val CMD_FIRMWARE_QUERY: Byte = 0xF1.toByte()

        // Key Codes
        object KeyCode {
            const val TOP_SINGLE: Byte = 0x01
            const val TOP_DOUBLE: Byte = 0x02
            const val TOP_TRIPLE: Byte = 0x03
            const val BOTTOM_SINGLE: Byte = 0x04
            const val BOTTOM_DOUBLE: Byte = 0x05
            const val BOTTOM_TRIPLE: Byte = 0x06
            const val BOTTOM_LONG: Byte = 0x07
            const val TAIL_SINGLE: Byte = 0x08
            const val TAIL_DOUBLE: Byte = 0x09
            const val TAIL_TRIPLE: Byte = 0x0A
        }

        // Function Codes (Updated to English)
        object Function {
            const val NONE: Byte = 0x00
            const val HOME: Byte = 0x01
            const val BRIGHTNESS_UP: Byte = 0x02
            const val BRIGHTNESS_DOWN: Byte = 0x03
            const val MULTI_TASK: Byte = 0x04
            const val VOLUME_UP: Byte = 0x05
            const val VOLUME_DOWN: Byte = 0x06
            const val EMAIL: Byte = 0x07
            const val LOCK_SCREEN: Byte = 0x08
            const val SCREENSHOT: Byte = 0x09
            const val BROWSER: Byte = 0x0A
            const val NOTIFICATIONS: Byte = 0x0B
            const val GEMINI: Byte = 0x0C
            const val META: Byte = 0x0D
            const val COPY: Byte = 0x0E
            const val PASTE: Byte = 0x0F
            const val UNDO: Byte = 0x10
            const val REDO: Byte = 0x11
            const val CUT: Byte = 0x12
            const val SELECT_ALL: Byte = 0x13
            const val QUERY: Byte = 0x14
            const val SEARCH: Byte = 0x15
            const val PREV_STEP: Byte = 0x16
            const val NEXT_STEP: Byte = 0x17
            const val FULL_SCREEN: Byte = 0x18
            const val SHOW_ALL_WINDOWS: Byte = 0x19

            val mapping: Map<Byte, String> = mapOf(
                NONE to "No Function",
                HOME to "HOME",
                BRIGHTNESS_UP to "Brightness +",
                BRIGHTNESS_DOWN to "Brightness -",
                MULTI_TASK to "Multi-task",
                VOLUME_UP to "Volume +",
                VOLUME_DOWN to "Volume -",
                EMAIL to "Email",
                LOCK_SCREEN to "Lock Screen",
                SCREENSHOT to "Screenshot",
                BROWSER to "Browser",
                NOTIFICATIONS to "Notifications",
                GEMINI to "Gemini",
                META to "META Key",
                COPY to "Copy",
                PASTE to "Paste",
                UNDO to "Undo",
                REDO to "Redo",
                CUT to "Cut",
                SELECT_ALL to "Select All",
                QUERY to "Query",
                SEARCH to "Search",
                PREV_STEP to "Prev Step",
                NEXT_STEP to "Next Step",
                FULL_SCREEN to "Full Screen",
                SHOW_ALL_WINDOWS to "Show All Windows"
            )
        }
    }
}
