// v1.0.0 | 2026-10-01 | 初版：eiP Flip Keyboard（KL122）設定畫面
//
// changelog:
//   v1.0.0 | 2026-10-01 | 自 iOS 版 FlipKeyboardView / FlipFunctionSelector 移植。
//                         三台裝置 × 三顆自訂鍵，外加整機共用的休眠時間。
//                         首次使用教學尚未移植。
package com.example.eip.ui

import android.os.Build
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eip.device.R
import com.example.eip.ble.BleDevice
import com.example.eip.ble.BluetoothViewModel
import com.example.eip.ble.FlipFunctionCatalog
import com.example.eip.ble.FlipKeyboardController
import com.example.eip.ble.FlipMode
import com.example.eip.ble.FlipProtocol
import com.example.eip.ble.FlipStatus
import com.example.eip.ble.FlipSystem
import com.example.eip.ble.FlipUiState
import com.example.eip.ble.LogType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Flip_Green = Color(0xFF34C759)
private val Flip_KeyPanel = Color(0xFF292929)
private val Flip_KeyCap = Color(0xFF555555)   // 取自鍵帽素材的底色

// 裝置分頁：0~2 為裝置1~3，3 為接線模式。
// 接線模式不是協議中的 Cmd，鍵盤以固定行為運作、App 無法變更，純粹是畫面上的一個分頁。
private const val TAB_WIRED = 3
private const val TAB_COUNT = 4

@StringRes
private fun tabNameRes(tab: Int): Int = when (tab) {
    0 -> R.string.flip_device_1
    1 -> R.string.flip_device_2
    2 -> R.string.flip_device_3
    else -> R.string.flip_wired_mode
}

@DrawableRes
private fun tabKeyCapRes(tab: Int): Int? = when (tab) {
    0 -> R.drawable.keycap_device_1
    1 -> R.drawable.keycap_device_2
    2 -> R.drawable.keycap_device_3
    else -> null   // 接線模式沒有素材，以圖示繪製
}

@DrawableRes
private fun shortcutKeyCapRes(keyIndex: Int): Int = when (keyIndex) {
    0 -> R.drawable.keycap_shortcut_1
    1 -> R.drawable.keycap_shortcut_2
    else -> R.drawable.keycap_shortcut_3
}

/** 功能碼的顯示名稱。未定義的碼才顯示十六進位值，方便對照韌體。 */
@Composable
private fun flipFunctionName(code: Int, chinese: Boolean): String = when {
    code == FlipFunctionCatalog.ERROR_CODE -> stringResource(R.string.flip_function_error)
    else -> FlipFunctionCatalog.function(code)?.name(chinese)
        ?: stringResource(R.string.flip_function_undefined, code)
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

@Composable
fun FlipKeyboardScreen(device: BleDevice, viewModel: BluetoothViewModel) {
    val flip = viewModel.flip
    val state by flip.state.collectAsState()
    val chinese = booleanResource(R.bool.flip_use_chinese_names)

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 接線模式的系統選擇。獨立存放，不影響三台藍牙裝置的設定。
    var wiredSystem by rememberSaveable { mutableStateOf(FlipSystem.ANDROID) }
    // 正在編輯的自訂鍵；非 null 時顯示功能選擇面板。
    var editingKey by remember { mutableStateOf<Int?>(null) }
    // 比照筆的設定頁：連點 6 下才顯示 Debug Logs
    var debugClickCount by rememberSaveable { mutableIntStateOf(0) }

    // 查詢讀回當前裝置後，畫面直接跳到那一台。
    LaunchedEffect(state.activeReportCount) {
        val active = state.activeMode
        if (state.activeReportCount > 0 && active != null) selectedTab = active.index
    }

    val isWired = selectedTab == TAB_WIRED
    val selectedMode = FlipMode.entries.getOrNull(selectedTab)

    Column(modifier = Modifier.fillMaxSize().background(Bg_Gray)) {
        Box(modifier = Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp)) {
            TopHeader(device, viewModel, title = device.name)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 橫向時單欄會讓卡片過寬、下半部大量留白，改為左設定右狀態兩欄。
            val isWide = maxWidth > maxHeight
            // 右欄在空間不足時等比縮小，避免窄畫面下左欄被擠爆。
            val sideWidth = minOf(340.dp, maxWidth * 0.34f)

            val modeCard: @Composable () -> Unit = {
                FlipModeCard(
                    state = state,
                    selectedTab = selectedTab,
                    wiredSystem = wiredSystem,
                    chinese = chinese,
                    onSelectTab = { selectedTab = it },
                    onSelectSystem = { system ->
                        if (selectedMode != null) flip.setSystem(selectedMode, system) else wiredSystem = system
                    },
                    onEditKey = { editingKey = it },
                    onSend = { selectedMode?.let { flip.sendMode(it) } }
                )
            }
            val sideCards: @Composable () -> Unit = {
                FlipStatusCard(device, state, onReload = { flip.query() }, onTitleClick = { debugClickCount++ })
            }
            val sleepCard: @Composable () -> Unit = { FlipSleepCard(state, flip) }
            val debugCard: @Composable () -> Unit = {
                if (debugClickCount >= 6) FlipDebugLogCard(viewModel)
            }

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.weight(1f)) { modeCard() }
                    Column(modifier = Modifier.width(sideWidth), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        sideCards(); sleepCard(); debugCard()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    sideCards(); modeCard(); sleepCard(); debugCard()
                }
            }
        }
    }

    val keyIndex = editingKey
    if (keyIndex != null && selectedMode != null && !isWired) {
        val config = state.modes[selectedMode.index]
        FlipFunctionSelector(
            system = config.system,
            keyTitle = stringResource(R.string.flip_shortcut_key, keyIndex + 1),
            currentCode = config.keyCodes[keyIndex],
            chinese = chinese,
            onSelect = { code -> flip.setKeyCode(selectedMode, keyIndex, code) },
            onDismiss = { editingKey = null }
        )
    }
}

@Composable
private fun FlipCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

// --- 連線狀態 ---

@Composable
private fun FlipStatusCard(device: BleDevice, state: FlipUiState, onReload: () -> Unit, onTitleClick: () -> Unit) {
    val (icon, color) = when (state.status.kind) {
        FlipStatus.Kind.WARNING -> Icons.Default.Warning to EiP_Orange
        FlipStatus.Kind.SUCCESS -> Icons.Default.CheckCircle to Flip_Green
        FlipStatus.Kind.INFO -> Icons.Default.Info to Text_LightGray
    }
    FlipCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                device.name,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Text_Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTitleClick() }
            )
            TextButton(onClick = onReload) {
                Text(stringResource(R.string.flip_reload), fontSize = 13.sp, color = EiP_Orange)
            }
        }
        // 狀態訊息獨立成一列並上色，成敗回饋才看得出來。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Text(flipStatusText(state.status), fontSize = 14.sp, color = color)
        }
        state.activeMode?.let { active ->
            Text(
                stringResource(R.string.flip_currently_in_use, stringResource(tabNameRes(active.index))),
                fontSize = 12.sp,
                color = Text_LightGray
            )
        }
    }
}

// --- 裝置設定 ---

@Composable
private fun FlipModeCard(
    state: FlipUiState,
    selectedTab: Int,
    wiredSystem: FlipSystem,
    chinese: Boolean,
    onSelectTab: (Int) -> Unit,
    onSelectSystem: (FlipSystem) -> Unit,
    onEditKey: (Int) -> Unit,
    onSend: () -> Unit
) {
    val selectedMode = FlipMode.entries.getOrNull(selectedTab)
    val isWired = selectedMode == null
    val tabName = stringResource(tabNameRes(selectedTab))
    val currentSystem = if (selectedMode != null) state.modes[selectedMode.index].system else wiredSystem
    // 接線模式為固定的複製／貼上／全選。
    val currentKeyCodes = if (selectedMode != null) state.modes[selectedMode.index].keyCodes
        else FlipFunctionCatalog.wiredKeyCodes(wiredSystem)

    FlipCard {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 依可用寬度算出鍵帽尺寸，讓四顆裝置一定排得下：直立／橫向、
            // 橫向時左欄較窄、小尺寸機種與分割視窗都會壓縮可用空間。
            val spacing = 16.dp
            val capWidth = ((maxWidth - 36.dp - spacing * (TAB_COUNT - 1)) / TAB_COUNT).coerceIn(60.dp, 92.dp)

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // 步驟 1：選裝置
                FlipStepHeader(1, stringResource(R.string.flip_step1_title), stringResource(R.string.flip_step1_subtitle))

                // 鍵帽放在深色底板上，與實體鍵盤的樣子一致。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Flip_KeyPanel)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing)
                ) {
                    for (tab in 0 until TAB_COUNT) {
                        val isSelected = tab == selectedTab
                        Column(
                            modifier = Modifier
                                .width(capWidth)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelectTab(tab) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FlipKeyCap(tabKeyCapRes(tab), capWidth, isSelected = isSelected)
                            Text(
                                stringResource(tabNameRes(tab)),
                                fontSize = if (capWidth < 76.dp) 12.sp else 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color(0xFFB3B3B3),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                // 步驟 2：選系統
                FlipStepHeader(2, stringResource(R.string.flip_step2_title), stringResource(R.string.flip_step2_subtitle, tabName))
                FlipSystemSelector(currentSystem, onSelectSystem)

                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))

                // 步驟 3：設三顆快捷鍵
                FlipStepHeader(
                    3,
                    stringResource(if (isWired) R.string.flip_step3_title_wired else R.string.flip_step3_title),
                    if (isWired) stringResource(R.string.flip_step3_subtitle_wired)
                    else stringResource(R.string.flip_step3_subtitle, currentSystem.displayName)
                )

                for (keyIndex in 0 until 3) {
                    val code = currentKeyCodes[keyIndex]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isWired) { onEditKey(keyIndex) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 用與實體按鍵相同的鍵帽圖，對照 F1~F3。
                        FlipKeyCap(shortcutKeyCapRes(keyIndex), capWidth, isSelected = false, selectable = false)
                        Text(
                            flipFunctionName(code, chinese),
                            fontSize = 15.sp,
                            color = if (code == 0x00) Text_LightGray else Text_Black,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (isWired) Icons.Default.Lock else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            null,
                            tint = Text_LightGray,
                            modifier = Modifier.size(if (isWired) 16.dp else 22.dp)
                        )
                    }
                    if (keyIndex < 2) HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                }

                if (selectedMode == null) {
                    // 接線模式沒有可送出的設定，以說明取代送出按鈕。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF2F2F7), RoundedCornerShape(10.dp))
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, null, tint = Text_LightGray, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.flip_wired_cannot_change), fontSize = 14.sp, color = Text_LightGray)
                    }
                } else {
                    val hasUnsent = state.hasUnsentChanges(selectedMode)
                    if (state.modes[selectedMode.index].isDisabled) {
                        Text(stringResource(R.string.flip_all_disabled, tabName), fontSize = 12.sp, color = Text_LightGray)
                    }
                    if (hasUnsent) FlipUnsentLabel()
                    Button(
                        onClick = onSend,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        // 沒有待送變更時降低強調，避免使用者反覆按已經生效的設定。
                        colors = ButtonDefaults.buttonColors(containerColor = if (hasUnsent) EiP_Orange else Text_LightGray)
                    ) {
                        Text(stringResource(R.string.flip_send_settings, tabName), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** 步驟標題。設定有先後順序（先選裝置、再選系統，功能清單才會對），編號讓使用者知道走到哪一步。 */
@Composable
private fun FlipStepHeader(number: Int, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.size(22.dp).background(EiP_Orange, CircleShape), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White, lineHeight = 13.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Text_Black)
            Text(subtitle, fontSize = 12.sp, color = Text_LightGray, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun FlipSystemSelector(current: FlipSystem, onSelect: (FlipSystem) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFEFEFF4), RoundedCornerShape(10.dp))
            .padding(3.dp)
    ) {
        FlipSystem.entries.forEach { system ->
            val isOn = system == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isOn) Color.White else Color.Transparent)
                    .clickable { onSelect(system) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    system.displayName,
                    fontSize = 13.sp,
                    fontWeight = if (isOn) FontWeight.Bold else FontWeight.Normal,
                    color = if (isOn) Text_Black else Color(0xFF6E6E73),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FlipUnsentLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Default.Edit, null, tint = EiP_Orange, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.flip_unsent_changes), fontSize = 12.sp, color = EiP_Orange)
    }
}

/**
 * 實體按鍵的鍵帽圖，讓畫面上的選項與手邊的按鍵長得一樣。
 *
 * 鍵帽本身是深色去背圖，無法靠染色表示選取，因此未選取者降低不透明度，選取者加上外框。
 * 素材畫布含陰影，實心鍵帽僅佔 95.3% 寬、81.4% 高，外框與自繪版本都比照內縮。
 */
@Composable
private fun FlipKeyCap(@DrawableRes imageRes: Int?, width: Dp, isSelected: Boolean, selectable: Boolean = true) {
    val height = width * 220f / 300f   // 維持素材的 300×220 比例
    val capWidth = width * 0.953f
    val capHeight = height * 0.814f
    val shape = RoundedCornerShape(capHeight * 0.12f)
    Box(
        modifier = Modifier.size(width, height).alpha(if (!selectable || isSelected) 1f else 0.5f),
        contentAlignment = Alignment.Center
    ) {
        if (imageRes != null) {
            Image(painterResource(imageRes), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Box(modifier = Modifier.size(capWidth, capHeight).background(Flip_KeyCap, shape), contentAlignment = Alignment.Center) {
                FlipWiredGlyph(modifier = Modifier.size(capHeight * 0.5f))
            }
        }
        if (isSelected && selectable) {
            Box(modifier = Modifier.size(capWidth, capHeight).border(3.dp, EiP_Orange, shape))
        }
    }
}

/** 接線模式的插頭圖示。內建圖示集沒有對應的圖，直接畫。 */
@Composable
private fun FlipWiredGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // 兩支插腳
        drawRect(Color.White, topLeft = Offset(w * 0.32f, h * 0.06f), size = Size(w * 0.10f, h * 0.24f))
        drawRect(Color.White, topLeft = Offset(w * 0.58f, h * 0.06f), size = Size(w * 0.10f, h * 0.24f))
        // 插頭本體
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * 0.22f, h * 0.28f),
            size = Size(w * 0.56f, h * 0.36f),
            cornerRadius = CornerRadius(w * 0.08f)
        )
        // 線
        drawLine(Color.White, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.5f, h * 0.96f), strokeWidth = w * 0.10f)
    }
}

// --- 休眠 ---

@Composable
private fun FlipSleepCard(state: FlipUiState, flip: FlipKeyboardController) {
    val range = FlipProtocol.SLEEP_RANGE
    FlipCard {
        Text(stringResource(R.string.flip_sleep), fontSize = 13.sp, color = Text_LightGray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.flip_sleep_timer), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Text_Black, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.flip_minutes, state.sleepMinutes), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = EiP_Orange)
        }
        // 協議只接受 1~15 的整數分鐘，steps 讓拖曳不會產生小數。
        Slider(
            value = state.sleepMinutes.toFloat(),
            onValueChange = { flip.setSleepMinutes(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            colors = SliderDefaults.colors(thumbColor = EiP_Orange, activeTrackColor = EiP_Orange)
        )
        Row {
            Text(stringResource(R.string.flip_minutes, range.first), fontSize = 11.sp, color = Text_LightGray, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.flip_minutes, range.last), fontSize = 11.sp, color = Text_LightGray)
        }
        if (state.hasUnsentSleepChange) FlipUnsentLabel()
        OutlinedButton(
            onClick = { flip.sendSleep() },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (state.hasUnsentSleepChange) EiP_Orange else Text_LightGray
            )
        ) {
            Text(stringResource(R.string.flip_send_sleep), fontWeight = FontWeight.Bold)
        }
    }
}

// --- 功能選擇面板 ---

/**
 * 選擇單顆自訂鍵功能的面板：搜尋、分組分頁、清單勾選，按「完成」才套用，誤點可以取消。
 * iOS 有 74 個功能，用巢狀選單層層點開很難找，因此分組做成面板內的分頁。
 */
@Composable
private fun FlipFunctionSelector(
    system: FlipSystem,
    keyTitle: String,
    currentCode: Int,
    chinese: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(system) { FlipFunctionCatalog.groups(system) }
    var search by remember { mutableStateOf("") }
    // 開啟時停在目前功能所屬的分組，使用者不必自己找。
    var selectedGroup by remember { mutableStateOf(FlipFunctionCatalog.function(currentCode)?.group ?: groups.firstOrNull()) }
    var pendingCode by remember { mutableIntStateOf(currentCode) }

    val keyword = search.trim()
    val functions = remember(system, keyword, selectedGroup, chinese) {
        val all = FlipFunctionCatalog.functions(system)
        when {
            // 搜尋時跨分組尋找，否則使用者得先猜功能在哪一組。
            keyword.isNotEmpty() -> all.filter { it.name(chinese).contains(keyword, ignoreCase = true) }
            groups.isNotEmpty() && selectedGroup != null -> all.filter { it.group == selectedGroup }
            else -> all
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.92f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Column {
                Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.flip_cancel), color = Text_LightGray) }
                    Text(
                        stringResource(R.string.flip_select_function, keyTitle),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Text_Black,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (pendingCode != currentCode) onSelect(pendingCode)
                        onDismiss()
                    }) { Text(stringResource(R.string.flip_done), color = EiP_Orange, fontWeight = FontWeight.Bold) }
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.flip_search_function), color = Text_LightGray) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Text_LightGray) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = "" }) { Icon(Icons.Default.Clear, null, tint = Text_LightGray) }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                )

                if (groups.isNotEmpty() && keyword.isEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        groups.forEach { group ->
                            val isOn = group == selectedGroup
                            Text(
                                // 分組存英文鍵值，只有「General」需要依語言轉換，其餘是 App 名稱。
                                if (group == "General") stringResource(R.string.flip_group_general) else group,
                                fontSize = 14.sp,
                                fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isOn) Color.White else Text_Black,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isOn) EiP_Orange else Color(0xFFE5E5EA))
                                    .clickable { selectedGroup = group }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    // 「停用」不屬於任何分組，固定放最前面。
                    if (keyword.isEmpty()) {
                        item(key = FlipFunctionCatalog.DISABLED.code) {
                            FlipFunctionRow(FlipFunctionCatalog.DISABLED.name(chinese), pendingCode == 0x00) { pendingCode = 0x00 }
                        }
                    }
                    items(functions, key = { it.code }) { function ->
                        FlipFunctionRow(function.name(chinese), pendingCode == function.code) { pendingCode = function.code }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlipFunctionRow(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) EiP_Orange.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 15.sp, color = Text_Black, modifier = Modifier.weight(1f))
        if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = EiP_Orange, modifier = Modifier.size(20.dp))
    }
}

// --- Debug Logs ---

/** 與筆的設定頁相同的通訊記錄面板，連點鍵盤名稱 6 下才顯示。 */
@Composable
private fun FlipDebugLogCard(viewModel: BluetoothViewModel) {
    val communicationLog by viewModel.communicationLog.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appVersion = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" } }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    FlipCard {
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
