// v1.2.0 | 2026-11-27 | 系統／鍵碼不符的橘色提醒、⚠︎ 標記、頁首「?」重看教學
//
// changelog:
//   v1.2.1 | 2026-11-27 | 頁首加語言切換（跟隨系統／繁中／英／日），用 AppCompat 的 per-app locale。
//   v1.2.0 | 2026-11-27 | 槽位系統不是 Android（Samsung 裝置亦接受 Samsung）時卡片頂端顯示橘色提醒
//                         並可一鍵改為目標系統；系統正確但某顆鍵屬於別的系統時功能名加 ⚠︎、
//                         提示幾顆鍵屬於哪個系統並可「改為預設」。頁首加「?」重看首次使用教學。
//   v1.1.0 | 2026-11-11 | 版面比照 iOS 版新設計（配色維持本 App 原有的橘色系與深色鍵帽底板）：
//                         「自定義按鍵」卡＝預覽區（裝置膠囊＋三顆鍵帽）＋四張可展開的裝置卡；
//                         展開後為系統模式（必選）與三顆快捷鍵，每顆鍵分「使用平臺／功能」兩個選單。
//                         沒有送出按鈕，改了就自動寫入鍵盤。接線模式顯示為鎖定。
//   v1.0.0 | 2026-10-01 | 自 iOS 版 FlipKeyboardView / FlipFunctionSelector 移植。
//                         三台裝置 × 三顆自訂鍵，外加整機共用的休眠時間。
//                         首次使用教學尚未移植。
package com.example.eip.ui

import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eip.device.R
import com.example.eip.ble.BleDevice
import com.example.eip.ble.BluetoothViewModel
import com.example.eip.ble.DeviceConnectionState
import com.example.eip.ble.FlipFunction
import com.example.eip.ble.FlipFunctionCatalog
import com.example.eip.ble.FlipKeyboardController
import com.example.eip.ble.FlipMode
import com.example.eip.ble.FlipModeConfig
import com.example.eip.ble.FlipProtocol
import com.example.eip.ble.FlipStatus
import com.example.eip.ble.FlipSystem
import com.example.eip.ble.FlipUiState
import com.example.eip.ble.LogType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Flip_Green = Color(0xFF34C759)
private val Flip_KeyPanel = Color(0xFF292929)
private val Flip_Pill = Color(0xFF48484A)        // 預覽區未選取的裝置膠囊
private val Flip_Border = Color(0xFFDCDCDC)
private val Flip_FieldGray = Color(0xFFEFEFF4)
private val Flip_Secondary = Color(0xFF8E8E93)
private val Flip_NoticeBg = Color(0xFFFFF3E0)
private val Flip_NoticeText = Color(0xFFB36B00)

// 裝置分頁：0~2 為裝置1~3，3 為接線模式。
// 接線模式不是協議中的 Cmd，鍵盤以固定行為運作、App 無法變更，純粹是畫面上的一個分頁。
private const val TAB_WIRED = 3
private const val TAB_COUNT = 4

/** 沒有平臺分組的系統（Windows / Android / Samsung）一律歸在這個平臺底下。 */
private const val PLATFORM_GENERAL = "General"

/** 接線時固定為複製／貼上／全選。各系統的名稱相同，取 iOS 那組來顯示即可。 */
private val WIRED_KEY_CODES = FlipFunctionCatalog.wiredKeyCodes(FlipSystem.IOS)

// --- 槽位系統是否適合這台裝置 ---
// 三個槽位各自存在鍵盤 flash 裡，不會跟著連線裝置自動換；之前用 iPad 設好的槽位，
// 換到 Android 平板連上仍會原樣讀回，使用者容易誤以為 App 顯示錯誤，所以明白提示並提供一鍵切換。

private val isSamsungDevice: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/** 這台裝置建議的系統：Samsung 裝置為 Samsung（有 Galaxy AI 等專屬功能），其餘 Android 裝置為 Android。 */
private val expectedSystem: FlipSystem = if (isSamsungDevice) FlipSystem.SAMSUNG else FlipSystem.ANDROID

/** 在這台裝置上可以正常使用的系統。Samsung 裝置用一般 Android 的鍵碼也能動，不算不符。 */
private fun isAcceptedSystem(system: FlipSystem): Boolean =
    system == FlipSystem.ANDROID || (isSamsungDevice && system == FlipSystem.SAMSUNG)

@StringRes
private fun tabNameRes(tab: Int): Int = when (tab) {
    0 -> R.string.flip_device_1
    1 -> R.string.flip_device_2
    2 -> R.string.flip_device_3
    else -> R.string.flip_wired_mode
}

@DrawableRes
private fun deviceKeyCapRes(tab: Int): Int = when (tab) {
    0 -> R.drawable.keycap_device_1
    1 -> R.drawable.keycap_device_2
    else -> R.drawable.keycap_device_3
}

@DrawableRes
private fun shortcutKeyCapRes(keyIndex: Int): Int = when (keyIndex) {
    0 -> R.drawable.keycap_shortcut_1
    1 -> R.drawable.keycap_shortcut_2
    else -> R.drawable.keycap_shortcut_3
}

// --- 平臺與功能 ---
// 功能碼本身就帶平臺（iOS 的分組），所以「使用平臺」只是畫面上用來縮小功能清單的篩選。

/** 某系統可選的平臺。只有 iOS 有分組；其餘系統只有「通用」一項。 */
private fun platformsOf(system: FlipSystem): List<String> =
    FlipFunctionCatalog.groups(system).ifEmpty { listOf(PLATFORM_GENERAL) }

private fun platformOf(function: FlipFunction): String = function.group ?: PLATFORM_GENERAL

private fun functionsOf(system: FlipSystem, platform: String?): List<FlipFunction> {
    if (platform == null) return emptyList()
    val all = FlipFunctionCatalog.functions(system)
    // 沒有分組的系統，所有功能都歸在「通用」。
    return if (FlipFunctionCatalog.groups(system).isEmpty()) all else all.filter { it.group == platform }
}

/**
 * 某顆鍵目前選定的平臺：使用者剛選的優先，其次由功能碼反推；
 * 只有一個平臺的系統直接帶入，省得使用者多點一次。
 */
private fun selectedPlatform(config: FlipModeConfig, keyIndex: Int, pending: String?): String? {
    if (pending != null) return pending
    val function = FlipFunctionCatalog.function(config.keyCodes[keyIndex])
    if (function != null && function.system == config.system) return platformOf(function)
    return platformsOf(config.system).singleOrNull()
}

/**
 * 某顆鍵要顯示的功能碼。剛換了平臺但還沒選功能時顯示為未選擇，
 * 否則功能欄會留著上一個平臺的功能，看起來像是選好了。
 */
private fun displayedCode(config: FlipModeConfig, keyIndex: Int, pending: String?): Int {
    val code = config.keyCodes[keyIndex]
    if (pending == null || code == 0x00) return code
    val function = FlipFunctionCatalog.function(code) ?: return code
    return if (platformOf(function) == pending) code else 0x00
}

@Composable
private fun platformName(platform: String): String =
    // 分組存英文鍵值，只有「General」需要依語言轉換，其餘是 App 名稱。
    if (platform == PLATFORM_GENERAL) stringResource(R.string.flip_group_general) else platform

/** 功能碼的顯示名稱。未定義的碼才顯示十六進位值，方便對照韌體。 */
@Composable
private fun flipFunctionName(code: Int, chinese: Boolean): String = when {
    code == 0x00 -> stringResource(R.string.flip_not_selected)
    code == FlipFunctionCatalog.ERROR_CODE -> stringResource(R.string.flip_function_error)
    else -> FlipFunctionCatalog.function(code)?.name(chinese)
        ?: stringResource(R.string.flip_function_undefined, code)
}

/** 功能名稱；屬於別的系統的鍵碼後面加警示符號，和清單裡其他正常的鍵區分開。 */
@Composable
private fun flipFunctionLabel(code: Int, system: FlipSystem, chinese: Boolean): String {
    val name = flipFunctionName(code, chinese)
    return if (FlipFunctionCatalog.isForeign(code, system)) "$name ⚠︎" else name
}

@Composable
private fun flipStatusText(status: FlipStatus): String {
    val modeName = status.mode?.let { stringResource(tabNameRes(it.index)) } ?: ""
    val minutes = status.minutes ?: 0
    return when (status.message) {
        FlipStatus.Message.NOT_LOADED -> stringResource(R.string.flip_status_not_loaded)
        FlipStatus.Message.DISCONNECTED -> stringResource(R.string.flip_status_disconnected)
        FlipStatus.Message.LOADING -> stringResource(R.string.flip_status_loading)
        FlipStatus.Message.QUERY_FAILED -> stringResource(R.string.flip_status_query_failed)
        FlipStatus.Message.ALL_LOADED -> stringResource(R.string.flip_status_all_loaded)
        FlipStatus.Message.LOADED_CURRENT -> stringResource(R.string.flip_status_loaded_current, modeName)
        FlipStatus.Message.LOADED_NO_CURRENT -> stringResource(R.string.flip_status_loaded_no_current)
        FlipStatus.Message.SENT -> stringResource(R.string.flip_status_sent, modeName)
        FlipStatus.Message.APPLIED -> stringResource(R.string.flip_status_applied, modeName)
        FlipStatus.Message.FIELD_ERROR -> stringResource(R.string.flip_status_field_error, modeName)
        FlipStatus.Message.SEND_FAILED -> stringResource(R.string.flip_status_send_failed)
        FlipStatus.Message.SLEEP_SENT -> stringResource(R.string.flip_status_sleep_sent, minutes)
        FlipStatus.Message.SLEEP_SET -> stringResource(R.string.flip_status_sleep_set, minutes)
        FlipStatus.Message.SLEEP_ERROR -> stringResource(R.string.flip_status_sleep_error)
    }
}

/** 「使用者已選平臺但還沒選功能」的暫存，key 為 裝置索引 × 3 + 鍵索引。 */
private typealias PendingPlatforms = SnapshotStateMap<Int, String>

private fun pendingKey(mode: FlipMode, keyIndex: Int): Int = mode.index * 3 + keyIndex

@Composable
fun FlipKeyboardScreen(device: BleDevice, viewModel: BluetoothViewModel, onShowTutorial: () -> Unit = {}) {
    val flip = viewModel.flip
    val state by flip.state.collectAsState()
    val chinese = booleanResource(R.bool.flip_use_chinese_names)
    val context = LocalContext.current
    val isReady = device.connectionState == DeviceConnectionState.READY

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 單選平臺沒有東西可以寫進鍵盤，所以只存在畫面上，等選了功能才一併送出。
    val pendingPlatforms: PendingPlatforms = remember { mutableStateMapOf() }
    // 等待確認重設的裝置；非 null 時顯示確認對話框。
    var resetTarget by remember { mutableStateOf<FlipMode?>(null) }
    // 比照筆的設定頁：連點標題 6 下才顯示 Debug Logs
    var debugClickCount by rememberSaveable { mutableIntStateOf(0) }

    // 查詢讀回當前裝置後，畫面直接展開那一台。
    LaunchedEffect(state.activeReportCount) {
        val active = state.activeMode
        if (state.activeReportCount > 0 && active != null) selectedTab = active.index
    }

    // 斷線後清掉畫面上的暫存，下次連上的可能是另一把鍵盤。
    LaunchedEffect(isReady) {
        if (!isReady) pendingPlatforms.clear()
    }

    // 新版畫面沒有狀態列，送出失敗等警告改以 Toast 提示。斷線已由上方的裝置卡呈現，不重複提示。
    val warningText = flipStatusText(state.status)
    LaunchedEffect(state.warningCount) {
        if (state.warningCount > 0 && state.status.kind == FlipStatus.Kind.WARNING &&
            state.status.message != FlipStatus.Message.DISCONNECTED
        ) {
            Toast.makeText(context, warningText, Toast.LENGTH_SHORT).show()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Bg_Gray)) {
        val twoColumns = maxWidth >= 700.dp
        // 橫向時展開的裝置卡內部也分左右：系統模式在左、快捷鍵在右。
        val isLandscape = maxWidth > maxHeight
        // 右欄在空間不足時等比縮小，避免窄畫面下左欄被擠爆。
        val sideWidth = minOf(340.dp, maxWidth * 0.30f)

        val customizeCard: @Composable () -> Unit = {
            FlipCustomizeCard(
                state = state,
                selectedTab = selectedTab,
                isReady = isReady,
                innerWide = twoColumns && isLandscape,
                chinese = chinese,
                pendingPlatforms = pendingPlatforms,
                flip = flip,
                onSelectTab = { selectedTab = it },
                onRequestReset = { resetTarget = it },
                onTitleClick = { debugClickCount++ }
            )
        }
        val sideCards: @Composable () -> Unit = {
            FlipSleepCard(state, flip, isReady)
            if (debugClickCount >= 6) FlipDebugLogCard(viewModel)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 裝置名稱、切換選單、連線狀態與電量：沿用與筆相同的 TopHeader。
            FlipCard(padding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) { TopHeader(device, viewModel, title = device.name) }
                    FlipLanguageButton()
                    Spacer(Modifier.width(10.dp))
                    // 「?」隨時可重看首次使用教學。
                    FlipHelpButton(onClick = onShowTutorial)
                }
            }

            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    Box(modifier = Modifier.weight(1f)) { customizeCard() }
                    Column(modifier = Modifier.width(sideWidth), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        sideCards()
                    }
                }
            } else {
                customizeCard()
                sideCards()
            }

            Text(
                stringResource(R.string.flip_copyright, Calendar.getInstance().get(Calendar.YEAR)),
                fontSize = 13.sp,
                color = Flip_Secondary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
    }

    resetTarget?.let { mode ->
        val modeName = stringResource(tabNameRes(mode.index))
        AlertDialog(
            onDismissRequest = { resetTarget = null },
            title = { Text(stringResource(R.string.flip_reset_confirm_title, modeName)) },
            text = { Text(stringResource(R.string.flip_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    for (keyIndex in 0 until 3) pendingPlatforms.remove(pendingKey(mode, keyIndex))
                    flip.resetKeys(mode)
                    resetTarget = null
                }) { Text(stringResource(R.string.flip_reset), color = EiP_Orange, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { resetTarget = null }) {
                    Text(stringResource(R.string.flip_cancel), color = Flip_Secondary)
                }
            }
        )
    }
}

// --- 語言切換 ---
// 與 iOS 版一樣可在 App 內切換。Android 13 以上會寫進系統的「應用程式語言」設定（兩邊同步），
// 12 以下由 AppCompat 自行保存。選了之後 Activity 會重建、整個畫面換語言。

/** 可選語言：語言標籤 → 以該語言自身書寫的名稱（不隨目前語言翻譯，才找得到自己的語言）。 */
private val FLIP_LANGUAGES = listOf("zh-TW" to "繁體中文", "en" to "English", "ja" to "日本語")

/** 目前 App 的語言標籤；跟隨系統時為 null。 */
private fun currentAppLanguage(): String? {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (tag.isEmpty()) return null
    return FLIP_LANGUAGES.firstOrNull { tag.startsWith(it.first.substringBefore('-')) }?.first ?: tag
}

/** 頁首的語言膠囊：顯示目前語言，點開選單切換。 */
@Composable
private fun FlipLanguageButton() {
    var menuOpen by remember { mutableStateOf(false) }
    val current = remember { currentAppLanguage() }
    val label = FLIP_LANGUAGES.firstOrNull { it.first == current }?.second ?: stringResource(R.string.flip_language_system)
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .border(1.dp, Flip_Border, CircleShape)
                .clickable { menuOpen = true }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Text_Black, maxLines = 1)
            Icon(Icons.Default.KeyboardArrowDown, null, tint = Flip_Secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            FlipMenuItem(stringResource(R.string.flip_language_system), checked = current == null) {
                menuOpen = false
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
            FLIP_LANGUAGES.forEach { (tag, name) ->
                FlipMenuItem(name, checked = current == tag) {
                    menuOpen = false
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                }
            }
        }
    }
}

/** 頁首右側的「?」圓形按鈕，點了重看教學。 */
@Composable
private fun FlipHelpButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .border(1.5.dp, EiP_Orange, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = EiP_Orange, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FlipCard(
    padding: PaddingValues = PaddingValues(22.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(padding)) { content() }
    }
}

// --- 自定義按鍵 ---

@Composable
private fun FlipCustomizeCard(
    state: FlipUiState,
    selectedTab: Int,
    isReady: Boolean,
    innerWide: Boolean,
    chinese: Boolean,
    pendingPlatforms: PendingPlatforms,
    flip: FlipKeyboardController,
    onSelectTab: (Int) -> Unit,
    onRequestReset: (FlipMode) -> Unit,
    onTitleClick: () -> Unit
) {
    FlipCard {
        Text(
            stringResource(R.string.flip_custom_keys_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Text_Black,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { onTitleClick() }
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.flip_custom_keys_subtitle), fontSize = 14.sp, color = Flip_Secondary, lineHeight = 19.sp)
        Spacer(Modifier.height(20.dp))

        FlipPreviewPanel(state, selectedTab, isReady, innerWide, chinese, pendingPlatforms, onSelectTab, onRequestReset)

        for (tab in 0 until TAB_COUNT) {
            Spacer(Modifier.height(14.dp))
            FlipDeviceSection(
                tab = tab,
                state = state,
                isSelected = tab == selectedTab,
                isReady = isReady,
                innerWide = innerWide,
                chinese = chinese,
                pendingPlatforms = pendingPlatforms,
                flip = flip,
                onSelect = { onSelectTab(tab) },
                onRequestReset = onRequestReset
            )
        }
    }
}

/** 預覽區：左側四個裝置膠囊，右側三顆鍵帽與各鍵的平臺、功能。沿用原有的深色鍵帽底板。 */
@Composable
private fun FlipPreviewPanel(
    state: FlipUiState,
    selectedTab: Int,
    isReady: Boolean,
    roomy: Boolean,
    chinese: Boolean,
    pendingPlatforms: PendingPlatforms,
    onSelectTab: (Int) -> Unit,
    onRequestReset: (FlipMode) -> Unit
) {
    val selectedMode = FlipMode.entries.getOrNull(selectedTab)
    // 直向時右欄佔掉一部分寬度，預覽區用較緊湊的尺寸才放得下；橫向（roomy）則放寬。
    val capWidth = if (roomy) 92.dp else 76.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Flip_KeyPanel)
            .padding(if (roomy) 22.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (roomy) 32.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.width(if (roomy) 160.dp else 132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (tab in 0 until TAB_COUNT) {
                val mode = FlipMode.entries.getOrNull(tab)
                FlipDevicePill(
                    name = stringResource(tabNameRes(tab)),
                    isSelected = tab == selectedTab,
                    isActive = mode != null && mode == state.activeMode,
                    // 接線模式沒有可重設的設定，不放選單。
                    canReset = mode?.let { isReady && state.systemChosen[it.index] },
                    onClick = { onSelectTab(tab) },
                    onReset = { mode?.let(onRequestReset) }
                )
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (roomy) 20.dp else 12.dp)) {
                for (keyIndex in 0 until 3) {
                    val code = if (selectedMode != null) {
                        displayedCode(state.modes[selectedMode.index], keyIndex, pendingPlatforms[pendingKey(selectedMode, keyIndex)])
                    } else WIRED_KEY_CODES[keyIndex]
                    val function = if (code == 0x00) null else FlipFunctionCatalog.function(code)
                    Column(
                        modifier = Modifier.width(capWidth + 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        FlipKeyCap(shortcutKeyCapRes(keyIndex), capWidth)
                        Text(
                            if (function != null) platformName(platformOf(function)) else stringResource(R.string.flip_not_selected),
                            fontSize = 12.sp, color = Color(0xFFB3B3B3), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            when {
                                code == 0x00 -> "-"
                                selectedMode != null -> flipFunctionLabel(code, state.modes[selectedMode.index].system, chinese)
                                else -> flipFunctionName(code, chinese)
                            },
                            fontSize = 12.sp, color = Color(0xFFB3B3B3), maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // 接線模式的三顆鍵是固定的，在預覽區就先講清楚，不必展開卡片才知道。
            if (selectedMode == null) FlipLockedNotice(stringResource(R.string.flip_wired_preview_notice))
        }
    }
}

/** 預覽區的裝置膠囊。橘色為目前展開的裝置；「啟用中」標籤為鍵盤實際使用中的裝置。 */
@Composable
private fun FlipDevicePill(
    name: String,
    isSelected: Boolean,
    isActive: Boolean,
    canReset: Boolean?,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) EiP_Orange else Flip_Pill)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            if (isActive) {
                Text(
                    stringResource(R.string.flip_active),
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1E9E45),
                    modifier = Modifier
                        .background(Color(0xFFDCF7E1), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp)
                )
            }
        }
        if (canReset != null) {
            Box {
                Text(
                    "•••",
                    fontSize = 12.sp,
                    color = Color.White,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { menuOpen = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        enabled = canReset,
                        text = { Text(stringResource(R.string.flip_reset_device)) },
                        leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            menuOpen = false
                            onReset()
                        }
                    )
                }
            }
        }
    }
}

// --- 可展開的裝置卡 ---

@Composable
private fun FlipDeviceSection(
    tab: Int,
    state: FlipUiState,
    isSelected: Boolean,
    isReady: Boolean,
    innerWide: Boolean,
    chinese: Boolean,
    pendingPlatforms: PendingPlatforms,
    flip: FlipKeyboardController,
    onSelect: () -> Unit,
    onRequestReset: (FlipMode) -> Unit
) {
    val mode = FlipMode.entries.getOrNull(tab)
    // 鍵盤只會回報三台藍牙裝置中的哪一台在使用，協議沒有「目前為接線」的資訊，
    // 所以接線模式不會顯示為啟用中。
    val isActive = mode != null && mode == state.activeMode
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 未展開的裝置比照設計稿淡化，讓目前在設定的那一台最顯眼。
            .alpha(if (isSelected) 1f else 0.45f)
            .clip(shape)
            .border(1.dp, Flip_Border, shape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (mode != null) {
                FlipKeyCap(deviceKeyCapRes(tab), 104.dp)
            } else {
                Box(modifier = Modifier.size(104.dp, 104.dp * 220f / 300f), contentAlignment = Alignment.Center) {
                    FlipWiredGlyph(modifier = Modifier.size(38.dp), color = Text_Black)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(tabNameRes(tab)), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Text_Black)
                Text(
                    // 藍牙裝置顯示目前的系統，接線模式顯示「不支援自訂」。
                    when {
                        mode == null -> stringResource(R.string.flip_wired_subtitle)
                        state.systemChosen[mode.index] -> state.modes[mode.index].system.displayName
                        else -> stringResource(R.string.flip_system_not_set)
                    },
                    fontSize = 14.sp,
                    color = Flip_Secondary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(10.dp).background(if (isActive) Flip_Green else Flip_Border, CircleShape))
                Text(
                    stringResource(if (isActive) R.string.flip_active else R.string.flip_inactive),
                    fontSize = 13.sp,
                    color = if (isActive) Flip_Green else Flip_Secondary
                )
            }
        }

        if (isSelected) {
            HorizontalDivider(color = Flip_Border)
            if (mode != null) {
                FlipModeDetail(mode, state, isReady, innerWide, chinese, pendingPlatforms, flip, onRequestReset)
            } else {
                FlipWiredDetail(innerWide, chinese)
            }
        }
    }
}

/** 系統模式與快捷鍵的排列：橫向為左右兩欄，直向為上下兩段。 */
@Composable
private fun FlipDetailLayout(
    innerWide: Boolean,
    title: @Composable () -> Unit,
    hint: String,
    systemControl: @Composable () -> Unit,
    shortcuts: @Composable () -> Unit
) {
    if (innerWide) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Column(modifier = Modifier.width(250.dp).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                title()
                Text(hint, fontSize = 12.sp, color = Flip_Secondary, lineHeight = 16.sp)
                systemControl()
            }
            VerticalDivider(color = Flip_Border)
            Box(modifier = Modifier.weight(1f).padding(22.dp)) { shortcuts() }
        }
    } else {
        Row(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            title()
            Text(hint, fontSize = 12.sp, color = Flip_Secondary, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            systemControl()
        }
        HorizontalDivider(color = Flip_Border)
        Box(modifier = Modifier.padding(22.dp)) { shortcuts() }
    }
}

@Composable
private fun FlipSystemTitle(required: Boolean) {
    Row {
        Text(stringResource(R.string.flip_system_mode), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text_Black)
        // 必選項的星號。
        if (required) Text("*", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF3B30))
    }
}

@Composable
private fun FlipCapsule(text: String, showChevron: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .widthIn(min = 96.dp)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Text_Black)
        if (showChevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Text_Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun FlipModeDetail(
    mode: FlipMode,
    state: FlipUiState,
    isReady: Boolean,
    innerWide: Boolean,
    chinese: Boolean,
    pendingPlatforms: PendingPlatforms,
    flip: FlipKeyboardController,
    onRequestReset: (FlipMode) -> Unit
) {
    val config = state.modes[mode.index]
    val systemChosen = state.systemChosen[mode.index]
    // 系統未選或未連線時，快捷鍵區整塊反灰、不可操作。
    val canEdit = isReady && systemChosen

    if (canEdit && !isAcceptedSystem(config.system)) {
        // 槽位記的系統不適合這台裝置：一鍵改為建議的系統（沿用各系統鍵碼暫存，不丟其他系統的設定）。
        FlipWarningBanner(
            message = stringResource(R.string.flip_system_mismatch_message, config.system.displayName, expectedSystem.displayName),
            button = stringResource(R.string.flip_system_mismatch_switch, expectedSystem.displayName),
            onClick = {
                for (keyIndex in 0 until 3) pendingPlatforms.remove(pendingKey(mode, keyIndex))
                flip.setSystem(mode, expectedSystem)
            }
        )
        HorizontalDivider(color = Flip_Border)
    } else if (canEdit && config.foreignKeyIndices.isNotEmpty()) {
        // 系統正確，但某幾顆鍵的功能碼屬於別的系統（鍵盤端用 Fn 切系統不會清鍵碼）。
        val foreign = config.foreignKeyIndices
        // 不符的鍵一定是表中有定義的功能，直接查表取名；不會是停用或未定義。
        val names = foreign.joinToString("、") { FlipFunctionCatalog.function(config.keyCodes[it])?.name(chinese) ?: "?" }
        val otherSystem = FlipFunctionCatalog.function(config.keyCodes[foreign.first()])?.system?.displayName ?: "—"
        FlipWarningBanner(
            message = stringResource(R.string.flip_foreign_keys_message, foreign.size, otherSystem, names, config.system.displayName),
            button = stringResource(R.string.flip_foreign_keys_clear),
            onClick = {
                for (keyIndex in foreign) pendingPlatforms.remove(pendingKey(mode, keyIndex))
                flip.replaceForeignKeys(mode)
            }
        )
        HorizontalDivider(color = Flip_Border)
    }

    FlipDetailLayout(
        innerWide = innerWide,
        title = { FlipSystemTitle(required = true) },
        hint = stringResource(R.string.flip_system_mode_hint, "F${9 + mode.cmd}", stringResource(tabNameRes(mode.index))),
        systemControl = {
            var menuOpen by remember { mutableStateOf(false) }
            Box(modifier = Modifier.alpha(if (isReady) 1f else 0.5f)) {
                FlipCapsule(
                    text = if (systemChosen) config.system.displayName else stringResource(R.string.flip_not_selected),
                    showChevron = true,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Flip_FieldGray)
                        .clickable(enabled = isReady) { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    FlipSystem.entries.forEach { system ->
                        FlipMenuItem(system.displayName, checked = systemChosen && system == config.system) {
                            menuOpen = false
                            // 換系統等於換一組功能碼，畫面上暫存的平臺選擇也跟著作廢。
                            for (keyIndex in 0 until 3) pendingPlatforms.remove(pendingKey(mode, keyIndex))
                            flip.setSystem(mode, system)
                        }
                    }
                }
            }
        },
        shortcuts = {
            Column(modifier = Modifier.alpha(if (canEdit) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.flip_shortcuts), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text_Black, modifier = Modifier.weight(1f))
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = canEdit) { onRequestReset(mode) }.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Refresh, null, tint = EiP_Orange, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.flip_reset), fontSize = 13.sp, color = EiP_Orange)
                    }
                }

                for (keyIndex in 0 until 3) {
                    val key = pendingKey(mode, keyIndex)
                    val currentPlatform = selectedPlatform(config, keyIndex, pendingPlatforms[key])
                    val code = displayedCode(config, keyIndex, pendingPlatforms[key])
                    val platforms = platformsOf(config.system)
                    val functions = functionsOf(config.system, currentPlatform)

                    FlipShortcutRow(
                        keyIndex = keyIndex,
                        platform = {
                            FlipDropdown(
                                text = currentPlatform?.let { platformName(it) } ?: stringResource(R.string.flip_not_selected),
                                enabled = canEdit,
                                items = platforms.map { platformName(it) to (it == currentPlatform) },
                                onPick = { index ->
                                    val picked = platforms[index]
                                    // 只記在畫面上；功能碼才是寫進鍵盤的東西，等選了功能再送。
                                    if (picked != currentPlatform) pendingPlatforms[key] = picked
                                }
                            )
                        },
                        function = {
                            FlipDropdown(
                                text = flipFunctionLabel(code, config.system, chinese),
                                // 還沒選平臺就沒有功能清單可選。
                                enabled = canEdit && currentPlatform != null,
                                // 第一項「未選擇」即停用該鍵（功能碼 0x00）。
                                items = listOf(stringResource(R.string.flip_not_selected) to (code == 0x00)) +
                                    functions.map { it.name(chinese) to (it.code == code) },
                                onPick = { index ->
                                    pendingPlatforms.remove(key)
                                    flip.setKeyCode(mode, keyIndex, if (index == 0) 0x00 else functions[index - 1].code)
                                }
                            )
                        }
                    )
                }
            }
        }
    )
}

/** 卡片頂端的橘色提醒條：左邊警示圖示、中間說明、右邊一顆動作按鈕。 */
@Composable
private fun FlipWarningBanner(message: String, button: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Flip_NoticeBg)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Warning, null, tint = EiP_Orange, modifier = Modifier.size(20.dp))
        Text(message, fontSize = 13.sp, lineHeight = 18.sp, color = Text_Black, modifier = Modifier.weight(1f))
        Text(
            button,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(CircleShape)
                .background(EiP_Orange)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun FlipShortcutRow(
    keyIndex: Int,
    platform: @Composable () -> Unit,
    function: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // 用與實體按鍵相同的鍵帽圖，對照 F1~F3。
        FlipKeyCap(shortcutKeyCapRes(keyIndex), 80.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.flip_platform), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Text_Black)
            platform()
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.flip_function), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Text_Black)
            function()
        }
    }
}

@Composable
private fun FlipMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal) },
        trailingIcon = if (checked) {
            { Icon(Icons.Default.Check, null, tint = EiP_Orange, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick
    )
}

/**
 * 下拉選單：白底細框，右側向下箭頭。
 * locked 為接線模式用的樣式：灰底、箭頭換成鎖頭，看得出不是可以點開的選單。
 *
 * @param items 選項文字與是否為目前選取。
 */
@Composable
private fun FlipDropdown(
    text: String,
    enabled: Boolean,
    items: List<Pair<String, Boolean>> = emptyList(),
    locked: Boolean = false,
    onPick: (Int) -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(shape)
                .background(if (locked) Flip_FieldGray else Color.White)
                .border(1.dp, Flip_Border, shape)
                .clickable(enabled = enabled && !locked) { menuOpen = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                fontSize = 15.sp,
                color = if (locked) Flip_Secondary else Text_Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (locked) Icons.Default.Lock else Icons.Default.KeyboardArrowDown,
                null,
                tint = if (locked) Flip_Secondary else Text_Black,
                modifier = Modifier.size(if (locked) 15.dp else 20.dp)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            items.forEachIndexed { index, (label, checked) ->
                FlipMenuItem(label, checked) {
                    menuOpen = false
                    onPick(index)
                }
            }
        }
    }
}

// --- 接線模式 ---

/** 鎖頭加說明文字的提醒條，預覽區與接線模式卡片共用。 */
@Composable
private fun FlipLockedNotice(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Flip_NoticeBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Lock, null, tint = Flip_NoticeText, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = Flip_NoticeText)
    }
}

/**
 * 接線模式為固定行為、不可操作，只用來告知三顆鍵的預設功能。
 * 最上方以橫幅明講「無法更換」，欄位則全部顯示為上鎖。
 */
@Composable
private fun FlipWiredDetail(innerWide: Boolean, chinese: Boolean) {
    FlipLockedNotice(
        stringResource(R.string.flip_wired_locked),
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp)
    )
    FlipDetailLayout(
        innerWide = innerWide,
        title = { FlipSystemTitle(required = false) },
        hint = stringResource(R.string.flip_system_mode_hint_wired),
        systemControl = {
            FlipCapsule("-", showChevron = false, modifier = Modifier.clip(CircleShape).background(Flip_FieldGray))
        },
        shortcuts = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.flip_shortcuts), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Text_Black, modifier = Modifier.weight(1f))
                    // 沒有可重設的東西，原本「重設」的位置改放鎖定標示。
                    Icon(Icons.Default.Lock, null, tint = Flip_Secondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.flip_wired_locked_badge), fontSize = 13.sp, color = Flip_Secondary)
                }
                for (keyIndex in 0 until 3) {
                    FlipShortcutRow(
                        keyIndex = keyIndex,
                        platform = { FlipDropdown(platformName(PLATFORM_GENERAL), enabled = false, locked = true) },
                        function = { FlipDropdown(flipFunctionName(WIRED_KEY_CODES[keyIndex], chinese), enabled = false, locked = true) }
                    )
                }
            }
        }
    )
}

// --- 鍵帽 ---

/** 實體按鍵的鍵帽圖，讓畫面上的選項與手邊的按鍵長得一樣。 */
@Composable
private fun FlipKeyCap(@DrawableRes imageRes: Int, width: Dp) {
    Image(
        painterResource(imageRes),
        null,
        modifier = Modifier.size(width, width * 220f / 300f),   // 維持素材的 300×220 比例
        contentScale = ContentScale.Fit
    )
}

/** 接線模式的插頭圖示。內建圖示集沒有對應的圖，直接畫。 */
@Composable
private fun FlipWiredGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 兩支插腳
        drawRect(color, topLeft = Offset(w * 0.32f, h * 0.06f), size = Size(w * 0.10f, h * 0.24f))
        drawRect(color, topLeft = Offset(w * 0.58f, h * 0.06f), size = Size(w * 0.10f, h * 0.24f))
        // 插頭本體
        drawRoundRect(
            color,
            topLeft = Offset(w * 0.22f, h * 0.28f),
            size = Size(w * 0.56f, h * 0.36f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
        // 線
        drawLine(color, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.5f, h * 0.96f), strokeWidth = w * 0.10f)
    }
}

// --- 自動休眠 ---

@Composable
private fun FlipSleepCard(state: FlipUiState, flip: FlipKeyboardController, isReady: Boolean) {
    val range = FlipProtocol.SLEEP_RANGE
    FlipCard {
        Text(stringResource(R.string.flip_auto_sleep), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Text_Black)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.flip_auto_sleep_subtitle), fontSize = 14.sp, color = Flip_Secondary)
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.flip_sleep_timer), fontSize = 14.sp, color = Flip_Secondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.flip_minutes, state.sleepMinutes), fontSize = 14.sp, color = EiP_Orange)
        }
        // 協議只接受 1~15 的整數分鐘，steps 讓拖曳不會產生小數。
        // 放開滑桿才送出，拖曳途中的每一格不必都寫進鍵盤。
        Slider(
            value = state.sleepMinutes.toFloat(),
            onValueChange = { flip.setSleepMinutes(it.roundToInt()) },
            onValueChangeFinished = { flip.commitSleep() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            enabled = isReady,
            colors = SliderDefaults.colors(thumbColor = EiP_Orange, activeTrackColor = EiP_Orange)
        )
        Row {
            Text(stringResource(R.string.flip_minutes, range.first), fontSize = 14.sp, color = Flip_Secondary, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.flip_minutes, range.last), fontSize = 14.sp, color = Flip_Secondary)
        }
    }
}

// --- Debug Logs ---

/** 與筆的設定頁相同的通訊記錄面板，連點「自定義按鍵」標題 6 下才顯示。 */
@Composable
private fun FlipDebugLogCard(viewModel: BluetoothViewModel) {
    val communicationLog by viewModel.communicationLog.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appVersion = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" } }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    FlipCard(padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Debug Logs", fontSize = 13.sp, color = Text_LightGray, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                // 複製時前置環境資訊：回報時最需要知道的就是哪台裝置、哪個 Android 版本
                val header = buildString {
                    appendLine("eiP Manager $appVersion | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Flip Keyboard | ${communicationLog.size} entries")
                    append("----------------------------------------")
                }
                val body = communicationLog.joinToString("\n") { "[${fmt.format(Date(it.timestamp))}] ${it.message}" }
                clipboard.setText(AnnotatedString(header + "\n" + body))
                // Android 13+ 系統自己會顯示複製確認，再跳 Toast 會重複
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "Copied ${communicationLog.size} entries", Toast.LENGTH_SHORT).show()
                }
            }) { Text("Copy", fontSize = 12.sp, color = EiP_Orange) }
            TextButton(onClick = { viewModel.clearLogs() }) { Text("Clear", fontSize = 12.sp, color = EiP_Orange) }
        }
        Surface(modifier = Modifier.fillMaxWidth().height(320.dp), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(8.dp)) {
            val listState = rememberLazyListState()
            LaunchedEffect(communicationLog.size) {
                if (communicationLog.isNotEmpty()) listState.animateScrollToItem(communicationLog.size - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(communicationLog) { log ->
                    Text(
                        text = "[${fmt.format(Date(log.timestamp))}] ${log.message}",
                        color = when (log.type) {
                            LogType.SENT -> Color(0xFF64D2FF)
                            LogType.RECEIVED -> Flip_Green
                            LogType.ERROR -> Color(0xFFFF453A)
                            LogType.INFO -> Color.White
                        },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
