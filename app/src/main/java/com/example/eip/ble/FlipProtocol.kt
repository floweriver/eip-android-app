// v1.0.0 | 2026-10-01 | 初版：eiP Flip Keyboard（KL122）通訊協議與功能碼表
//
// changelog:
//   v1.0.0 | 2026-10-01 | 自 iOS 版 FlipKeyboardCatalog 移植。純 Kotlin、不依賴 Android
//                         框架，可直接以 JVM 單元測試驗證（見 FlipProtocolTest）。
package com.example.eip.ble

import java.util.UUID

// eiP Flip Keyboard（KL122）與觸控筆是兩套互不相干的協議：帧頭與 CRC 相同，但
// 鍵盤固定 9 bytes（0x55 0xAA + Cmd + Data1~5 + CRC），Cmd 與功能碼的意義完全不同
// （例如 Cmd 0x01 在筆是「設定按鍵」，在鍵盤是「裝置1 的設定」）。兩者不可混用，
// 因此獨立成檔，BleProtocol.Pencil 完全不受影響。

/** 鍵盤配置系統碼，對應 Cmd 0x01~0x03 的 Data1。 */
enum class FlipSystem(val code: Int, val displayName: String) {
    IOS(0x01, "iOS"),
    WINDOWS(0x02, "Windows"),
    ANDROID(0x03, "Android"),
    SAMSUNG(0x04, "Samsung");

    companion object {
        fun fromCode(code: Int): FlipSystem? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 三組設定，一組對應一台已配對的裝置，即鍵盤上 F10~F12 的「裝置1~3」。
 * Cmd 0x01 = 裝置1、0x02 = 裝置2、0x03 = 裝置3。
 */
enum class FlipMode(val cmd: Int) {
    DEVICE_1(0x01),
    DEVICE_2(0x02),
    DEVICE_3(0x03);

    /** 在設定清單中的索引。 */
    val index: Int get() = cmd - 1

    companion object {
        fun fromCmd(cmd: Int): FlipMode? = entries.firstOrNull { it.cmd == cmd }
    }
}

/** 一台裝置的設定內容。 */
data class FlipModeConfig(
    val system: FlipSystem = FlipSystem.ANDROID,
    /** 三顆自訂鍵的功能碼；0x00 表示該鍵停用。 */
    val keyCodes: List<Int> = listOf(0x00, 0x00, 0x00)
) {
    /** 三顆鍵全為 0x00 代表停用該裝置。 */
    val isDisabled: Boolean get() = keyCodes.all { it == 0x00 }
}

/** 一顆自訂鍵可指派的功能。功能碼本身即代表所屬系統，韌體依單一總表查詢。 */
data class FlipFunction(
    val code: Int,
    val nameZh: String,
    val nameEn: String,
    /** 所屬系統；null 表示不屬於任何系統（停用）。 */
    val system: FlipSystem?,
    /** 對應的 HID 組合鍵。保留作為對照韌體行為的依據，畫面上不顯示。 */
    val hid: String,
    /** 功能分組，存英文鍵值以免隨語言變動而影響篩選。 */
    val group: String? = null
) {
    fun name(chinese: Boolean): String = if (chinese) nameZh else nameEn
}

/**
 * eiP Flip Keyboard 的功能碼總表，共 125 個功能碼：
 * iOS 74、Windows 16、Android 19、Samsung 16。各段之間的號碼為預留空號。
 *
 * 內容與 iOS 版 FlipFunctionCatalog 一致，皆經實機驗證。《KL112 鍵盤編碼》原表中
 * 實測不通過的項目已剔除，韌體修正前請勿加回：
 * iOS 0x0C、0x0F、0x13、0x46，Windows 0x65、0x75、0x76，
 * Android 0x8F、0x97、0x98，Samsung 0xAC、0xAE。
 */
object FlipFunctionCatalog {

    /** 停用該鍵（三顆全為 0x00 表示停用該裝置）。 */
    val DISABLED = FlipFunction(0x00, "停用", "Disabled", null, hid = "—")

    /** 鍵盤回報欄位異常時使用的保留碼。 */
    const val ERROR_CODE = 0xFF

    val all: List<FlipFunction> = listOf(
        DISABLED,
        // iOS — General
        FlipFunction(0x01, "HOME 鍵", "Home Key", FlipSystem.IOS, hid = "Consumer 0x223", group = "General"),
        FlipFunction(0x02, "電源鍵", "Lock Screen", FlipSystem.IOS, hid = "Consumer 0x30", group = "General"),
        FlipFunction(0x03, "亮度 +", "Brightness +", FlipSystem.IOS, hid = "Consumer 0x6F", group = "General"),
        FlipFunction(0x04, "亮度 -", "Brightness -", FlipSystem.IOS, hid = "Consumer 0x70", group = "General"),
        FlipFunction(0x05, "APP 切換器", "App Switcher", FlipSystem.IOS, hid = "Globe+↑（Globe : Consumer 0x29D）", group = "General"),
        FlipFunction(0x06, "音量 +", "Volume +", FlipSystem.IOS, hid = "Consumer 0xE9", group = "General"),
        FlipFunction(0x07, "音量 -", "Volume -", FlipSystem.IOS, hid = "Consumer 0xEA", group = "General"),
        FlipFunction(0x08, "靜音", "Mute", FlipSystem.IOS, hid = "Consumer 0xE2", group = "General"),
        FlipFunction(0x09, "上一頁", "Page Up", FlipSystem.IOS, hid = "cmd+↑", group = "General"),
        FlipFunction(0x0A, "下一頁", "Page Down", FlipSystem.IOS, hid = "cmd+↓", group = "General"),
        FlipFunction(0x0B, "搜尋", "Search", FlipSystem.IOS, hid = "Consumer 0x221", group = "General"),
        FlipFunction(0x0D, "快速截圖", "Quick Screenshot", FlipSystem.IOS, hid = "cmd+shift+3", group = "General"),
        FlipFunction(0x0E, "編輯截圖", "Edit Screenshot", FlipSystem.IOS, hid = "cmd+shift+4", group = "General"),
        FlipFunction(0x10, "播放 / 暫停", "Play / Pause", FlipSystem.IOS, hid = "Consumer 0xCD", group = "General"),
        FlipFunction(0x11, "上一首", "Previous Track", FlipSystem.IOS, hid = "Consumer 0xB6", group = "General"),
        FlipFunction(0x12, "下一首", "Next Track", FlipSystem.IOS, hid = "Consumer 0xB5", group = "General"),
        FlipFunction(0x14, "Siri", "Siri", FlipSystem.IOS, hid = "Globe+S（长按1.5S）", group = "General"),
        FlipFunction(0x15, "上一步", "Undo", FlipSystem.IOS, hid = "cmd+z", group = "General"),
        FlipFunction(0x16, "下一步", "Redo", FlipSystem.IOS, hid = "cmd+shift+z", group = "General"),
        FlipFunction(0x17, "複製", "Copy", FlipSystem.IOS, hid = "cmd+C", group = "General"),
        FlipFunction(0x18, "貼上", "Paste", FlipSystem.IOS, hid = "cmd+V", group = "General"),
        FlipFunction(0x19, "剪下", "Cut", FlipSystem.IOS, hid = "cmd+x", group = "General"),
        FlipFunction(0x1A, "全選", "Select All", FlipSystem.IOS, hid = "cmd+A", group = "General"),

        // iOS — Goodnotes
        FlipFunction(0x1B, "筆刷", "Pen", FlipSystem.IOS, hid = "P", group = "Goodnotes"),
        FlipFunction(0x1C, "橡皮擦", "Eraser", FlipSystem.IOS, hid = "E", group = "Goodnotes"),
        FlipFunction(0x1D, "螢光筆", "Highlighter", FlipSystem.IOS, hid = "H", group = "Goodnotes"),
        FlipFunction(0x1E, "形狀工具", "Shape", FlipSystem.IOS, hid = "S", group = "Goodnotes"),
        FlipFunction(0x1F, "套索工具", "Lasso", FlipSystem.IOS, hid = "V", group = "Goodnotes"),
        FlipFunction(0x20, "素材", "Materials", FlipSystem.IOS, hid = "M", group = "Goodnotes"),
        FlipFunction(0x21, "文字工具", "Text", FlipSystem.IOS, hid = "T", group = "Goodnotes"),
        FlipFunction(0x22, "插入圖片", "Insert Image", FlipSystem.IOS, hid = "I", group = "Goodnotes"),
        FlipFunction(0x23, "表情符號", "Emoji", FlipSystem.IOS, hid = "Globe+E", group = "Goodnotes"),

        // iOS — Notability
        FlipFunction(0x24, "粗體", "Bold", FlipSystem.IOS, hid = "cmd-B", group = "Notability"),
        FlipFunction(0x25, "文字工具", "Text", FlipSystem.IOS, hid = "cmd+shift+T", group = "Notability"),
        FlipFunction(0x26, "插入圖片", "Insert Image", FlipSystem.IOS, hid = "cmd+shift+I", group = "Notability"),
        FlipFunction(0x27, "開始錄音", "Start Recording", FlipSystem.IOS, hid = "cmd+R", group = "Notability"),
        FlipFunction(0x28, "選擇工具 1", "Select Tool 1", FlipSystem.IOS, hid = "cmd+1", group = "Notability"),
        FlipFunction(0x29, "選擇工具 2", "Select Tool 2", FlipSystem.IOS, hid = "cmd+2", group = "Notability"),
        FlipFunction(0x2A, "選擇工具 3", "Select Tool 3", FlipSystem.IOS, hid = "cmd+3", group = "Notability"),
        FlipFunction(0x2B, "選擇工具 4", "Select Tool 4", FlipSystem.IOS, hid = "cmd+4", group = "Notability"),
        FlipFunction(0x2C, "選擇工具 5", "Select Tool 5", FlipSystem.IOS, hid = "cmd+5", group = "Notability"),
        FlipFunction(0x2D, "選擇顏色 1", "Select Color 1", FlipSystem.IOS, hid = "option+cmd+1", group = "Notability"),
        FlipFunction(0x2E, "選擇顏色 2", "Select Color 2", FlipSystem.IOS, hid = "option+cmd+2", group = "Notability"),
        FlipFunction(0x2F, "選擇顏色 3", "Select Color 3", FlipSystem.IOS, hid = "option+cmd+3", group = "Notability"),
        FlipFunction(0x30, "選擇顏色 4", "Select Color 4", FlipSystem.IOS, hid = "option+cmd+4", group = "Notability"),
        FlipFunction(0x31, "選擇顏色 5", "Select Color 5", FlipSystem.IOS, hid = "option+cmd+5", group = "Notability"),
        FlipFunction(0x32, "選擇上一個顏色", "Select Previous Color", FlipSystem.IOS, hid = "option+cmd+9", group = "Notability"),
        FlipFunction(0x33, "選擇下一個顏色", "Select Next Color", FlipSystem.IOS, hid = "option+cmd+0", group = "Notability"),
        FlipFunction(0x34, "選擇筆刷大小 1", "Select Size 1", FlipSystem.IOS, hid = "option+ctrl+1", group = "Notability"),
        FlipFunction(0x35, "選擇筆刷大小 2", "Select Size 2", FlipSystem.IOS, hid = "option+ctrl+2", group = "Notability"),
        FlipFunction(0x36, "選擇筆刷大小 3", "Select Size 3", FlipSystem.IOS, hid = "option+ctrl+3", group = "Notability"),

        // iOS — Procreate
        FlipFunction(0x37, "開啟快速選單", "Open QuickMenu", FlipSystem.IOS, hid = "space", group = "Procreate"),
        FlipFunction(0x38, "畫筆縮小 -1%", "Brush Size -1%", FlipSystem.IOS, hid = "cmd+[", group = "Procreate"),
        FlipFunction(0x39, "畫筆放大 +1%", "Brush Size +1%", FlipSystem.IOS, hid = "cmd+]", group = "Procreate"),
        FlipFunction(0x3A, "畫筆縮小 -5%", "Brush Size -5%", FlipSystem.IOS, hid = "[", group = "Procreate"),
        FlipFunction(0x3B, "畫筆放大 +5%", "Brush Size +5%", FlipSystem.IOS, hid = "]", group = "Procreate"),
        FlipFunction(0x3C, "畫筆縮小 -10%", "Brush Size -10%", FlipSystem.IOS, hid = "shift+[", group = "Procreate"),
        FlipFunction(0x3D, "畫筆放大 +10%", "Brush Size +10%", FlipSystem.IOS, hid = "shift+]", group = "Procreate"),
        FlipFunction(0x3E, "切換畫筆工具", "Switch to Brush Tool", FlipSystem.IOS, hid = "B", group = "Procreate"),
        FlipFunction(0x3F, "開啟色板", "Color Panel", FlipSystem.IOS, hid = "C", group = "Procreate"),
        FlipFunction(0x40, "橡皮擦", "Eraser", FlipSystem.IOS, hid = "E", group = "Procreate"),
        FlipFunction(0x41, "開啟圖層面板", "Layer Panel", FlipSystem.IOS, hid = "L", group = "Procreate"),
        FlipFunction(0x42, "滴管工具", "Eyedropper Tool", FlipSystem.IOS, hid = "M", group = "Procreate"),
        FlipFunction(0x43, "選取模式", "Select", FlipSystem.IOS, hid = "S", group = "Procreate"),
        FlipFunction(0x44, "變形模式", "Transform", FlipSystem.IOS, hid = "V", group = "Procreate"),
        FlipFunction(0x45, "切換前景/背景顏色", "Switch Foreground/Background Color", FlipSystem.IOS, hid = "X", group = "Procreate"),
        FlipFunction(0x47, "複製全部圖層", "Copy All Layers", FlipSystem.IOS, hid = "cmd+A", group = "Procreate"),
        FlipFunction(0x48, "色彩平衡調整", "Color Balance Adjustment", FlipSystem.IOS, hid = "cmd+B", group = "Procreate"),
        FlipFunction(0x49, "清除選取區域", "Clear Selection", FlipSystem.IOS, hid = "cmd+D", group = "Procreate"),
        FlipFunction(0x4A, "複製選取區域", "Copy Selection", FlipSystem.IOS, hid = "cmd+J", group = "Procreate"),
        FlipFunction(0x4B, "動作選單", "Actions Menu", FlipSystem.IOS, hid = "cmd+K", group = "Procreate"),
        FlipFunction(0x4C, "HSB 色彩調整", "HSB Color Adjustment", FlipSystem.IOS, hid = "cmd+U", group = "Procreate"),
        FlipFunction(0x4D, "全螢幕開/關", "Full Screen On/Off", FlipSystem.IOS, hid = "cmd+0", group = "Procreate"),
        FlipFunction(0x4E, "清除所選圖層", "Clear Selected Layer", FlipSystem.IOS, hid = "cmd+delete", group = "Procreate"),

        // Windows
        FlipFunction(0x66, "亮度 +", "Brightness +", FlipSystem.WINDOWS, hid = "Consumer 0x6F"),
        FlipFunction(0x67, "亮度 -", "Brightness -", FlipSystem.WINDOWS, hid = "Consumer 0x70"),
        FlipFunction(0x68, "音量 +", "Volume +", FlipSystem.WINDOWS, hid = "Consumer 0xE9"),
        FlipFunction(0x69, "音量 -", "Volume -", FlipSystem.WINDOWS, hid = "Consumer 0xEA"),
        FlipFunction(0x6A, "複製", "Copy", FlipSystem.WINDOWS, hid = "CTRL+C"),
        FlipFunction(0x6B, "貼上", "Paste", FlipSystem.WINDOWS, hid = "CTRL+V"),
        FlipFunction(0x6C, "全選", "Select All", FlipSystem.WINDOWS, hid = "CTRL+A"),
        FlipFunction(0x6D, "上一步", "Prev Step", FlipSystem.WINDOWS, hid = "CTRL+Z"),
        FlipFunction(0x6E, "下一步", "Next Step", FlipSystem.WINDOWS, hid = "CTRL+SHIFT+Z"),
        FlipFunction(0x6F, "剪下", "Cut", FlipSystem.WINDOWS, hid = "CTRL+X"),
        FlipFunction(0x70, "搜尋", "Search", FlipSystem.WINDOWS, hid = "CTRL+F"),
        FlipFunction(0x71, "鎖定螢幕", "Lock Screen", FlipSystem.WINDOWS, hid = "win+L"),
        FlipFunction(0x72, "開啟任務欄", "Multi task", FlipSystem.WINDOWS, hid = "win+Tab"),
        FlipFunction(0x73, "開啟信箱", "Email", FlipSystem.WINDOWS, hid = "Consumer 0x18A"),
        FlipFunction(0x74, "截圖", "Screenshot", FlipSystem.WINDOWS, hid = "Print Screen"),
        FlipFunction(0x77, "Copilot", "Copilot", FlipSystem.WINDOWS, hid = "LGUI+LShift+F23"),

        // Android
        FlipFunction(0x84, "Home 鍵", "HOME", FlipSystem.ANDROID, hid = "Consumer 0x223"),
        FlipFunction(0x85, "亮度 +", "Brightness +", FlipSystem.ANDROID, hid = "Consumer 0x6F"),
        FlipFunction(0x86, "亮度 -", "Brightness -", FlipSystem.ANDROID, hid = "Consumer 0x70"),
        FlipFunction(0x87, "音量 +", "Volume +", FlipSystem.ANDROID, hid = "Consumer 0xE9"),
        FlipFunction(0x88, "音量 -", "Volume -", FlipSystem.ANDROID, hid = "Consumer 0xEA"),
        FlipFunction(0x89, "複製", "Copy", FlipSystem.ANDROID, hid = "CTRL+C"),
        FlipFunction(0x8A, "貼上", "Paste", FlipSystem.ANDROID, hid = "CTRL+V"),
        FlipFunction(0x8B, "全選", "Select All", FlipSystem.ANDROID, hid = "CTRL+A"),
        FlipFunction(0x8C, "上一步", "Prev Step", FlipSystem.ANDROID, hid = "CTRL+Z"),
        FlipFunction(0x8D, "下一步", "Next Step", FlipSystem.ANDROID, hid = "CTRL+SHIFT+Z"),
        FlipFunction(0x8E, "剪下", "Cut", FlipSystem.ANDROID, hid = "CTRL+X"),
        FlipFunction(0x90, "搜尋", "Lock Screen", FlipSystem.ANDROID, hid = "Consumer 0x30"),
        FlipFunction(0x91, "鎖定螢幕", "Multi task", FlipSystem.ANDROID, hid = "WIN+Tab"),
        FlipFunction(0x92, "開啟信箱", "Email", FlipSystem.ANDROID, hid = "Consumer 0x18A"),
        FlipFunction(0x93, "截圖", "Screenshot", FlipSystem.ANDROID, hid = "Print Screen"),
        FlipFunction(0x94, "開啟瀏覽器", "Browser", FlipSystem.ANDROID, hid = "Consumer 0x196"),
        FlipFunction(0x95, "通知列表", "Notifications", FlipSystem.ANDROID, hid = "win+n"),
        FlipFunction(0x96, "搜尋", "Search", FlipSystem.ANDROID, hid = "win"),
        FlipFunction(0x99, "Gemini", "Gemini", FlipSystem.ANDROID, hid = "Consumer 0x30(长按1.5S)"),

        // Samsung
        FlipFunction(0xA1, "Home 鍵", "HOME", FlipSystem.SAMSUNG, hid = "Consumer 0x223"),
        FlipFunction(0xA2, "亮度 +", "Brightness +", FlipSystem.SAMSUNG, hid = "Consumer 0x6F"),
        FlipFunction(0xA3, "亮度 -", "Brightness -", FlipSystem.SAMSUNG, hid = "Consumer 0x70"),
        FlipFunction(0xA4, "音量 +", "Volume +", FlipSystem.SAMSUNG, hid = "Consumer 0xE9"),
        FlipFunction(0xA5, "音量 -", "Volume -", FlipSystem.SAMSUNG, hid = "Consumer 0xEA"),
        FlipFunction(0xA6, "複製", "Copy", FlipSystem.SAMSUNG, hid = "CTRL+C"),
        FlipFunction(0xA7, "貼上", "Paste", FlipSystem.SAMSUNG, hid = "CTRL+V"),
        FlipFunction(0xA8, "全選", "Select All", FlipSystem.SAMSUNG, hid = "CTRL+A"),
        FlipFunction(0xA9, "上一步", "Prev Step", FlipSystem.SAMSUNG, hid = "CTRL+Z"),
        FlipFunction(0xAA, "下一步", "Next Step", FlipSystem.SAMSUNG, hid = "CTRL+SHIFT+Z"),
        FlipFunction(0xAB, "剪下", "Cut", FlipSystem.SAMSUNG, hid = "CTRL+X"),
        FlipFunction(0xAD, "鎖定螢幕", "Lock Screen", FlipSystem.SAMSUNG, hid = "Consumer 0x30"),
        FlipFunction(0xAF, "截圖", "Screenshot", FlipSystem.SAMSUNG, hid = "Print Screen"),
        FlipFunction(0xB0, "Google 助理", "META Key", FlipSystem.SAMSUNG, hid = "WIN"),
        FlipFunction(0xB3, "搜尋", "Search", FlipSystem.SAMSUNG, hid = "Consumer 0x221"),
        FlipFunction(0xB4, "Galaxy AI", "Galaxy AI", FlipSystem.SAMSUNG, hid = "Consumer 0x30(长按1.5S)")
    )

    private val byCode: Map<Int, FlipFunction> = all.associateBy { it.code }

    /** 依功能碼查表；查不到時回傳 null（例如尚未定義的預留碼）。 */
    fun function(code: Int): FlipFunction? = byCode[code]

    /** 某個系統底下的所有功能（不含「停用」）。 */
    fun functions(system: FlipSystem): List<FlipFunction> = all.filter { it.system == system }

    /** 某個系統的分組名稱，依表中出現順序。該系統不分組時回傳空清單。 */
    fun groups(system: FlipSystem): List<String> = functions(system).mapNotNull { it.group }.distinct()

    /** 某個系統、某個分組底下的功能。 */
    fun functions(system: FlipSystem, group: String): List<FlipFunction> =
        functions(system).filter { it.group == group }

    /**
     * 接線模式下三顆鍵固定為複製 / 貼上 / 全選，此處回傳各系統對應的功能碼。
     * 接線時韌體以固定行為運作，App 無法更改，僅用於顯示。
     */
    fun wiredKeyCodes(system: FlipSystem): List<Int> = when (system) {
        FlipSystem.IOS -> listOf(0x17, 0x18, 0x1A)
        FlipSystem.WINDOWS -> listOf(0x6A, 0x6B, 0x6C)
        FlipSystem.ANDROID -> listOf(0x89, 0x8A, 0x8B)
        FlipSystem.SAMSUNG -> listOf(0xA6, 0xA7, 0xA8)
    }
}

/** 指令組裝與回覆解析。 */
object FlipProtocol {

    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"
    private fun String.toFullUuid(): UUID = UUID.fromString("0000$this$BASE_UUID_SUFFIX")

    // 協議文件寫的是 FFE0 / FFE2 / FFE3，但實機實測為 FF00 / FF02 / FF01。
    // 兩組都納入，連線後取實際存在的那組；實測的那組排前面。
    val SERVICE_UUIDS: List<UUID> = listOf("ff00".toFullUuid(), "ffe0".toFullUuid())
    val WRITE_CHAR_UUIDS: List<UUID> = listOf("ff02".toFullUuid(), "ffe2".toFullUuid())
    val NOTIFY_CHAR_UUIDS: List<UUID> = listOf("ff01".toFullUuid(), "ffe3".toFullUuid())

    /**
     * 是否為 eiP Flip Keyboard。其他 eiP 鍵盤（HyperKeys、SK-M1300 等）的功能碼
     * 編號不同，本 App 不支援，不可一併放行。
     */
    fun isFlipKeyboardName(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return n.contains("eip") && n.contains("flip")
    }

    /** 帧長度固定 9 bytes：帧頭 2 + Cmd 1 + Data 5 + CRC 1。 */
    const val FRAME_LENGTH = 9

    /** 休眠時間指令碼。 */
    const val CMD_SLEEP = 0x05
    /** 查詢指令碼。鍵盤會依序回覆 4 則。 */
    const val CMD_QUERY = 0xFF

    /** 休眠時間允許範圍：1 ~ 15 分鐘。 */
    val SLEEP_RANGE = 1..15

    /**
     * CRC-8/MAXIM 校驗（多項式 0x31，反射實作 0x8C），計算範圍含帧頭。
     * 演算法與 BluetoothViewModel.calculateCRC8Maxim 相同；此處另寫一份純函式，
     * 解析回覆與單元測試才不必依賴 ViewModel。
     */
    fun crc8Maxim(data: ByteArray, length: Int = data.size): Int {
        var crc = 0x00
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) == 1) (crc ushr 1) xor 0x8C else crc ushr 1 }
        }
        return crc
    }

    /** 把 Cmd + 五個 Data 組成完整 9 bytes 封包（App 方向，自動補 CRC）。 */
    private fun frame(cmd: Int, data: List<Int>): ByteArray {
        require(data.size == 5) { "Data 欄位固定 5 bytes" }
        val pkt = ByteArray(FRAME_LENGTH)
        pkt[0] = BleProtocol.APP_HEADER_0
        pkt[1] = BleProtocol.APP_HEADER_1
        pkt[2] = cmd.toByte()
        data.forEachIndexed { i, v -> pkt[3 + i] = v.toByte() }
        pkt[FRAME_LENGTH - 1] = crc8Maxim(pkt, FRAME_LENGTH - 1).toByte()
        return pkt
    }

    /**
     * Cmd 0x01 ~ 0x03：裝置設定。
     * Data5 在「設定」時為預留 0x00；只有查詢回覆才會帶休眠時間。
     */
    fun modeCommand(mode: FlipMode, system: FlipSystem, keyCodes: List<Int>): ByteArray {
        require(keyCodes.size == 3) { "本機型為三顆自訂鍵" }
        return frame(mode.cmd, listOf(system.code, keyCodes[0], keyCodes[1], keyCodes[2], 0x00))
    }

    /** Cmd 0x05：設定休眠時間。休眠值放 Data5（K11 放 Data1，此處不同）。 */
    fun sleepCommand(minutes: Int): ByteArray =
        frame(CMD_SLEEP, listOf(0x00, 0x00, 0x00, 0x00, minutes.coerceIn(SLEEP_RANGE)))

    /** Cmd 0xFF：查詢。鍵盤會依序回覆 4 則：裝置1~3 的設定，末則為當前作用中的裝置。 */
    fun queryCommand(): ByteArray = frame(CMD_QUERY, listOf(0x00, 0x00, 0x00, 0x00, 0x00))

    /** 鍵盤回覆封包（帧頭 0xAA 0x55）。 */
    data class Reply(
        val cmd: Int,
        /** Data1 ~ Data5。 */
        val data: List<Int>
    ) {
        val system: FlipSystem? get() = FlipSystem.fromCode(data[0])
        val keyCodes: List<Int> get() = data.subList(1, 4)
        /** 查詢回覆時 Data5 帶當前休眠時間；設定應答時為 0x00。 */
        val sleepMinutes: Int get() = data[4]
        val mode: FlipMode? get() = FlipMode.fromCmd(cmd)
        /** 任一欄位為 0xFF 表示該欄位異常。 */
        val hasError: Boolean get() = data.contains(FlipFunctionCatalog.ERROR_CODE)
    }

    /**
     * 解析鍵盤回覆；帧頭、長度或 CRC 不符時回傳 null。
     * 必須驗 CRC——受干擾的封包若只靠帧頭判斷就會被當成有效設定寫回畫面。
     */
    fun parseReply(bytes: ByteArray): Reply? {
        if (bytes.size != FRAME_LENGTH) return null
        if (bytes[0] != BleProtocol.DEVICE_HEADER_0 || bytes[1] != BleProtocol.DEVICE_HEADER_1) return null
        if (crc8Maxim(bytes, FRAME_LENGTH - 1) != (bytes[FRAME_LENGTH - 1].toInt() and 0xFF)) return null
        return Reply(
            cmd = bytes[2].toInt() and 0xFF,
            data = (3..7).map { bytes[it].toInt() and 0xFF }
        )
    }
}
