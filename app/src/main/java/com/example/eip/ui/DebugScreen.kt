// v1.3.0 | 2026-09-01 | HOME 移至進階組；選單移除 Gemini
//
// changelog:
//   v1.3.0 | 2026-09-01 | HOME 從 Universal 移到 Advanced；Gemini 不再提供選擇。
//                         Gemini 仍保留在 Function.mapping 中——若筆上早已設定
//                         Gemini，畫面要能正確顯示名稱，不能變成 "No Function"。
//   v1.2.0 | 2026-09-01 | 設定面板原本是不可捲動的 Column，橫向時面板變矮，
//                         超出的內容（含 Debug Logs）直接搆不到。改為可捲動；
//                         Debug Logs 的 weight(1f) 必須改成固定高度，weight 不能
//                         用在 verticalScroll 的 Column 裡。
//   v1.1.0 | 2026-08-31 | Debug Logs 加 Copy 按鈕；複製內容前置 App 版本、
//                         裝置型號、Android 版本、韌體版本，方便回報時直接貼上。
//   v1.0.0 | —          | 初版（進版控前）
package com.example.eip.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eip.device.R
import com.example.eip.ble.BleDevice
import com.example.eip.ble.BleProtocol
import com.example.eip.ble.BluetoothViewModel
import com.example.eip.ble.DeviceConnectionState
import com.example.eip.ble.LogType
import com.example.eip.ui.components.DrawingTestBoard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// --- Styling ---
val EiP_Orange = Color(0xFFFF9800)
val Bg_Gray = Color(0xFFF9F9FB)
val Text_Black = Color(0xFF1C1C1E)
val Text_LightGray = Color(0xFFAEAEB2)

enum class DetailPanel { NONE, ERASER, MAPPING, SIGNAL, SETTINGS }

// 第一次連上筆時顯示的教學導覽。圖片檔名對應 res/drawable 下的 tutorial_*，
// 文案(title / subtitle)直接改這裡的字串。
private val tutorialPages = listOf(
    TutorialPage(
        "tutorial_1_master",
        "Master Your Stylus",
        "Configure your button shortcuts and customize your pen for your workflow."
    ),
    TutorialPage(
        "tutorial_2_nib",
        "Fine-Tune Nib Sensitivity",
        "Calibrate signal strength for a smooth, pixel-perfect writing experience."
    ),
    TutorialPage(
        "tutorial_3_eraser",
        "Eraser Sensor Control",
        "Customize your eraser sensor for effortless switching between writing and erasing."
    ),
    TutorialPage(
        "tutorial_4_sleep",
        "Smart Auto-Sleep",
        "Set your idle time to conserve battery when the pen is not in use."
    ),
    TutorialPage(
        "tutorial_5_ataglance",
        "At-a-Glance Status",
        "Instantly check your active button mappings and current settings in one view."
    )
)

@Composable
fun DebugScreen(viewModel: BluetoothViewModel) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val activeDevice = discoveredDevices.firstOrNull { it.connectionState == DeviceConnectionState.READY }
    val context = LocalContext.current
    var showTutorial by rememberSaveable { mutableStateOf(false) }
    // 第一次成功連上筆(且沒看過教學)時，跳出彈窗教學
    LaunchedEffect(activeDevice?.id) {
        if (activeDevice != null && !hasSeenTutorial(context)) showTutorial = true
    }
    var activePanel by remember { mutableStateOf(DetailPanel.NONE) }
    var targetKeyCode by remember { mutableStateOf(BleProtocol.Pencil.KeyCode.TOP_SINGLE) }
    
    // 用於隱藏 Debug Logs 的計數器
    var debugClickCount by rememberSaveable { mutableIntStateOf(0) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    Box(modifier = Modifier.fillMaxSize().background(Bg_Gray)) {
        if (activeDevice == null) {
            DeviceScannerOverlay(discoveredDevices, viewModel)
        } else {
            val isPanelOpen = activePanel != DetailPanel.NONE
            val panelWidth = if (isLandscape) 480.dp else 320.dp

            val stylusShift by animateDpAsState(
                targetValue = if (isPanelOpen) (if (isLandscape) (-80).dp else (-50).dp) else 0.dp,
                animationSpec = tween(400),
                label = "stylusShift"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = stylusShift),
                contentAlignment = Alignment.Center
            ) {
                StylusVerticalView(
                    viewModel = viewModel,
                    activePanel = activePanel,
                    targetKeyCode = targetKeyCode,
                    isLandscape = isLandscape,
                    onActionSelect = { panel, keyCode ->
                        activePanel = panel
                        targetKeyCode = keyCode
                    }
                )
            }

            Box(modifier = Modifier.fillMaxHeight().width(100.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                SideNavBar(activePanel) { target ->
                    activePanel = if (activePanel == target) DetailPanel.NONE else target
                }
            }

            AnimatedVisibility(
                visible = isPanelOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = tween(400)),
                exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = tween(400))
            ) {
                Surface(
                    modifier = Modifier.fillMaxHeight().width(panelWidth),
                    color = Color.White,
                    shadowElevation = 8.dp
                ) {
                    Box(modifier = Modifier.padding(24.dp)) {
                        DetailPanelContent(
                            panel = activePanel,
                            targetKeyCode = targetKeyCode,
                            viewModel = viewModel,
                            debugClickCount = debugClickCount,
                            onDebugClick = { debugClickCount++ },
                            onShowTutorial = { showTutorial = true },
                            onDismiss = { activePanel = DetailPanel.NONE }
                        )
                    }
                }
            }

            Box(modifier = Modifier.padding(start = 24.dp, top = 24.dp)) {
                TopHeader(activeDevice, viewModel)
            }
        }

        // 彈窗式教學（疊在最上層）
        if (showTutorial) {
            TutorialOverlay(tutorialPages) {
                markTutorialSeen(context)
                showTutorial = false
            }
        }
    }
}

@Composable
fun TopHeader(device: BleDevice, viewModel: BluetoothViewModel) {
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val devices by viewModel.discoveredDevices.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val isReady = device.connectionState == DeviceConnectionState.READY
    Column {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { menuOpen = true }
            ) {
                Text("USI 2.0 Ultra", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Text_Black)
                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.padding(start = 2.dp).size(16.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                devices.forEach { d ->
                    val isActive = d.id == device.id
                    val selectable = isActive || d.isNearby
                    DropdownMenuItem(
                        enabled = selectable,
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.widthIn(min = 180.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(d.name, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, color = if (selectable) Text_Black else Text_LightGray)
                                    Text(
                                        when {
                                            isActive -> "Connected"
                                            d.connectionState == DeviceConnectionState.CONNECTING -> "Connecting…"
                                            !d.isNearby -> "Offline"
                                            else -> "Tap to switch"
                                        },
                                        fontSize = 11.sp,
                                        color = if (isActive) Color(0xFF34C759) else Text_LightGray
                                    )
                                }
                                if (isActive) Icon(Icons.Default.Check, null, tint = EiP_Orange, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                            }
                        },
                        onClick = {
                            menuOpen = false
                            if (!isActive) viewModel.switchTo(d)
                        }
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (isReady) Color(0xFF34C759) else Text_LightGray))
                Text(if (isReady) "Ready" else "Connecting", fontSize = 12.sp, color = if (isReady) Color(0xFF34C759) else Text_LightGray, fontWeight = FontWeight.Bold)
            }
            if (batteryLevel != null) {
                Text("🔋 $batteryLevel%", fontSize = 12.sp, color = Text_LightGray)
            }
        }
    }
}

@Composable
fun ButtonIndicator(modifier: Modifier = Modifier, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(width = 1.5.dp, color = Color.White, shape = CircleShape)
            .background(if (isSelected) EiP_Orange else Color.Black.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
fun StylusVerticalView(
    viewModel: BluetoothViewModel,
    activePanel: DetailPanel,
    targetKeyCode: Byte,
    isLandscape: Boolean,
    onActionSelect: (DetailPanel, Byte) -> Unit
) {
    val settings by viewModel.pencilSettings.collectAsState()
    val usiSettings by viewModel.usiSettings.collectAsState()
    val isEraserOn = usiSettings[BleProtocol.Pencil.CMD_TAIL_USI_QUERY] ?: true
    val signalLevel by viewModel.signalLevel.collectAsState()

    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val screenH = maxHeight
        val circle = 22.dp
        val topShift = if (isLandscape) 0.dp else (-11).dp
        val bottomShift = if (isLandscape) 0.dp else (-5.5).dp
        val midTopShift = if (isLandscape) (5.5.dp + (circle * 0.2f)) else (45.dp - (circle * 0.5f))
        val midBottomShift = if (isLandscape) (35.dp - (circle * 1.85f)) else (77.dp - (circle * 1.0f))

        Image(
            painter = painterResource(id = R.drawable.eip_usi_ultra),
            contentDescription = null,
            modifier = Modifier
                .requiredWidth(screenH * 0.92f)
                .rotate(-90f),
            contentScale = ContentScale.Fit
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val labelLeftX = (-70).dp
            val labelRightX = 70.dp
            val topIndicatorCenterY = (-screenH * 0.35f) + topShift
            val midTopIndicatorCenterY = 35.dp + midTopShift + 11.dp
            val midBottomIndicatorCenterY = 105.dp + midBottomShift + 11.dp
            val bottomIndicatorCenterY = (screenH * 0.30f) + bottomShift

            ButtonIndicator(modifier = Modifier.offset(y = topIndicatorCenterY - 11.dp), isSelected = activePanel == DetailPanel.ERASER, onClick = { onActionSelect(DetailPanel.ERASER, BleProtocol.Pencil.KeyCode.TAIL_SINGLE) })
            ButtonIndicator(modifier = Modifier.offset(y = midTopIndicatorCenterY - 11.dp), isSelected = activePanel == DetailPanel.MAPPING && targetKeyCode in listOf(BleProtocol.Pencil.KeyCode.TOP_SINGLE, BleProtocol.Pencil.KeyCode.TOP_DOUBLE, BleProtocol.Pencil.KeyCode.TOP_TRIPLE), onClick = { onActionSelect(DetailPanel.MAPPING, BleProtocol.Pencil.KeyCode.TOP_SINGLE) })
            ButtonIndicator(modifier = Modifier.offset(y = midBottomIndicatorCenterY - 11.dp), isSelected = activePanel == DetailPanel.MAPPING && targetKeyCode in listOf(BleProtocol.Pencil.KeyCode.BOTTOM_SINGLE, BleProtocol.Pencil.KeyCode.BOTTOM_DOUBLE, BleProtocol.Pencil.KeyCode.BOTTOM_TRIPLE), onClick = { onActionSelect(DetailPanel.MAPPING, BleProtocol.Pencil.KeyCode.TOP_SINGLE) })
            ButtonIndicator(modifier = Modifier.offset(y = bottomIndicatorCenterY - 11.dp), isSelected = activePanel == DetailPanel.SIGNAL, onClick = { onActionSelect(DetailPanel.SIGNAL, 0x00) })

            StylusLabel(title = "Eraser", value = if (isEraserOn) "On" else "Off", isSelected = activePanel == DetailPanel.ERASER, alignment = Alignment.End, modifier = Modifier.offset(x = labelLeftX, y = topIndicatorCenterY).layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { p.placeRelative(0, -p.height/2) } }, onClick = { onActionSelect(DetailPanel.ERASER, BleProtocol.Pencil.KeyCode.TAIL_SINGLE) })

            val topX = if (isLandscape) labelRightX else labelLeftX
            val topHAlign = if (isLandscape) Alignment.Start else Alignment.End
            ActionGroupLabel(keyCodes = listOf(BleProtocol.Pencil.KeyCode.TOP_SINGLE, BleProtocol.Pencil.KeyCode.TOP_DOUBLE, BleProtocol.Pencil.KeyCode.TOP_TRIPLE), settings = settings, activePanel = activePanel, targetKeyCode = targetKeyCode, alignment = topHAlign, modifier = Modifier.offset(x = topX, y = midTopIndicatorCenterY).layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { val yO = if (isLandscape) (-p.height / 2 + 11.dp.toPx().toInt()) else (-p.height + 60.dp.toPx().toInt()); p.placeRelative(0, yO) } }, onActionSelect = onActionSelect)

            ActionGroupLabel(keyCodes = listOf(BleProtocol.Pencil.KeyCode.BOTTOM_SINGLE, BleProtocol.Pencil.KeyCode.BOTTOM_DOUBLE, BleProtocol.Pencil.KeyCode.BOTTOM_TRIPLE), settings = settings, activePanel = activePanel, targetKeyCode = targetKeyCode, alignment = Alignment.End, modifier = Modifier.offset(x = labelLeftX, y = midBottomIndicatorCenterY).layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { val yO = if (isLandscape) (-p.height / 2 + 99.dp.toPx().toInt()) else (38.dp.toPx().toInt()); p.placeRelative(0, yO) } }, onActionSelect = onActionSelect)

            StylusLabel(title = "Level ${signalLevel + 1}", value = "Signal Strength", isSelected = activePanel == DetailPanel.SIGNAL, alignment = if (isLandscape) Alignment.Start else Alignment.End, modifier = Modifier.offset(x = if (isLandscape) labelRightX else labelLeftX, y = bottomIndicatorCenterY).layout { m, c -> val p = m.measure(c); layout(p.width, p.height) { p.placeRelative(0, -p.height/2) } }, onClick = { onActionSelect(DetailPanel.SIGNAL, 0x00) })
        }
    }
}

@Composable
fun ActionGroupLabel(keyCodes: List<Byte>, settings: Map<Byte, Byte>, activePanel: DetailPanel, targetKeyCode: Byte, alignment: Alignment.Horizontal, modifier: Modifier = Modifier, onActionSelect: (DetailPanel, Byte) -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = alignment) {
        keyCodes.forEach { keyCode ->
            val funcCode = settings[keyCode] ?: BleProtocol.Pencil.Function.NONE
            val funcName = BleProtocol.Pencil.Function.mapping[funcCode] ?: "No Function"
            val clickType = when(keyCode % 3) { 1 -> "Single Click"; 2 -> "Double Click"; else -> "Triple Click" }
            StylusLabel(title = funcName, value = clickType, isSelected = activePanel == DetailPanel.MAPPING && targetKeyCode == keyCode, alignment = alignment, onClick = { onActionSelect(DetailPanel.MAPPING, keyCode) })
        }
    }
}

@Composable
fun StylusLabel(title: String, value: String, isSelected: Boolean, alignment: Alignment.Horizontal, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tightTextStyle = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    Column(modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }, horizontalAlignment = alignment) {
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isSelected) EiP_Orange else Text_Black, lineHeight = 16.sp, style = tightTextStyle)
        if (value.isNotEmpty()) {
            Text(text = value, fontSize = 11.sp, color = if (value == "On") Color(0xFF34C759) else Text_LightGray, lineHeight = 12.sp, style = tightTextStyle)
        }
    }
}

@Composable
fun SideNavBar(activePanel: DetailPanel, onSelect: (DetailPanel) -> Unit) {
    Surface(modifier = Modifier.width(56.dp).wrapContentHeight(), shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 2.dp) {
        Column(modifier = Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NavIconButton(icon = Icons.Default.Menu, isSelected = activePanel != DetailPanel.SETTINGS) { onSelect(DetailPanel.NONE) }
            NavIconButton(icon = Icons.Default.Settings, isSelected = activePanel == DetailPanel.SETTINGS) { onSelect(DetailPanel.SETTINGS) }
        }
    }
}

@Composable
fun NavIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp).clip(CircleShape).background(if (isSelected) EiP_Orange else Color.Transparent)) {
        Icon(icon, null, tint = if (isSelected) Color.White else Text_LightGray, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun DetailPanelContent(
    panel: DetailPanel,
    targetKeyCode: Byte,
    viewModel: BluetoothViewModel,
    debugClickCount: Int,
    onDebugClick: () -> Unit,
    onShowTutorial: () -> Unit,
    onDismiss: () -> Unit
) {
    val usiSettings by viewModel.usiSettings.collectAsState()
    val settingsState by viewModel.pencilSettings.collectAsState()
    val shutdownTime by viewModel.shutdownTime.collectAsState()
    val signalLevel by viewModel.signalLevel.collectAsState()
    val firmwareVersion by viewModel.firmwareVersion.collectAsState()
    val communicationLog by viewModel.communicationLog.collectAsState()
    val context = LocalContext.current
    val appVersion = remember { try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.4" } catch (e: Exception) { "1.0.4" } }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = when(panel) { DetailPanel.ERASER -> "Tail Button Action"; DetailPanel.SIGNAL -> "Tip Signal Adjustment"; DetailPanel.SETTINGS -> "Support & Settings"; DetailPanel.MAPPING -> { val btn = if (targetKeyCode in 1..3) "Top Button" else "Bottom Button"; val type = when(targetKeyCode % 3) { 1 -> "Single Click"; 2 -> "Double Click"; else -> "Triple Click" }; "$btn $type" }; else -> "" }, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Text_Black, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
        Spacer(modifier = Modifier.height(16.dp))

        when (panel) {
            DetailPanel.ERASER -> {
                val isEraserOn = usiSettings[BleProtocol.Pencil.CMD_TAIL_USI_QUERY] ?: true
                DetailItemCard { Row(verticalAlignment = Alignment.CenterVertically) { Column(modifier = Modifier.weight(1f)) { Text("Tail Eraser Sensor", fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("Once enabled, flip the pen and hold the tail button to erase in supported apps.", fontSize = 12.sp, color = Text_LightGray) }; Switch(checked = isEraserOn, onCheckedChange = { viewModel.setUsiSwitch(BleProtocol.Pencil.CMD_TAIL_USI_SET, it) }, colors = SwitchDefaults.colors(checkedTrackColor = EiP_Orange)) } }
                Spacer(modifier = Modifier.height(20.dp)); Box(modifier = Modifier.weight(1f)) { DrawingTestBoard() }
            }
            DetailPanel.SIGNAL -> {
                Row { Text("Signal Strength", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text("Level ${signalLevel + 1}", color = EiP_Orange, fontWeight = FontWeight.Bold) }
                Slider(value = (signalLevel + 1).toFloat(), onValueChange = { viewModel.setSignalLevel(it.roundToInt()) }, valueRange = 1f..5f, steps = 3, colors = SliderDefaults.colors(thumbColor = EiP_Orange, activeTrackColor = EiP_Orange))
                Spacer(modifier = Modifier.height(20.dp)); Box(modifier = Modifier.weight(1f)) { DrawingTestBoard() }
            }
            DetailPanel.SETTINGS -> {
              // 橫向時面板高度不足，內容會超出可視範圍且無法捲動（T-11）
              Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Device Settings", fontSize = 13.sp, color = Text_LightGray); Spacer(modifier = Modifier.height(8.dp))
                DetailItemCard { Text("Auto Sleep Timer", fontWeight = FontWeight.Bold, fontSize = 15.sp); Slider(value = shutdownTime.toFloat(), onValueChange = { viewModel.setSmartShutdownTime(it.roundToInt()) }, valueRange = 1f..15f, colors = SliderDefaults.colors(thumbColor = EiP_Orange, activeTrackColor = EiP_Orange)); Text("$shutdownTime minutes", modifier = Modifier.align(Alignment.End), fontSize = 12.sp, color = Text_LightGray) }
                Spacer(modifier = Modifier.height(32.dp)); Text("Support & Guides", fontSize = 13.sp, color = Text_LightGray); Spacer(modifier = Modifier.height(8.dp))
                DetailItemCard(onClick = { onShowTutorial() }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tutorial", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Text_LightGray)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                DetailItemCard(onClick = {
                    try {
                        val mail = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:service-us@lemeng.com.tw")
                            putExtra(Intent.EXTRA_SUBJECT, "eiP Manager Support")
                        }
                        context.startActivity(mail)
                    } catch (e: Exception) { /* 沒有郵件 App 時忽略 */ }
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Help & Support", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Text_LightGray)
                    }
                }
                Spacer(modifier = Modifier.height(32.dp)); Text("Maintenance", fontSize = 13.sp, color = Text_LightGray); Spacer(modifier = Modifier.height(8.dp))
                DetailItemCard { 
                    Row(verticalAlignment = Alignment.CenterVertically) { 
                        Text(
                            text = "Factory Reset", 
                            fontWeight = FontWeight.Bold, 
                            fontSize = 15.sp, 
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onDebugClick() }
                        )
                        Button(onClick = { viewModel.factoryReset() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD70015)), shape = RoundedCornerShape(20.dp)) { Text("Reset", color = Color.White) } 
                    } 
                }
                Spacer(modifier = Modifier.height(32.dp)); Text("Version Info", fontSize = 13.sp, color = Text_LightGray); Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Row { Text("App Version", fontSize = 14.sp, color = Text_Black, modifier = Modifier.weight(1f)); Text(appVersion, fontSize = 14.sp, color = Text_LightGray) }; Row { Text("Firmware Version", fontSize = 14.sp, color = Text_Black, modifier = Modifier.weight(1f)); Text(firmwareVersion, fontSize = 14.sp, color = Text_LightGray) } }
                
                // 只有點擊 6 次後才會顯示 Debug Logs
                if (debugClickCount >= 6) {
                    Spacer(modifier = Modifier.height(24.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Debug Logs", fontSize = 13.sp, color = Text_LightGray, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            // 複製時前置環境資訊：回報時最需要知道的就是哪台裝置、哪個 Android 版本
                            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            val header = buildString {
                                appendLine("eiP Manager $appVersion | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                                appendLine("Firmware: $firmwareVersion | ${communicationLog.size} entries")
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
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(modifier = Modifier.fillMaxWidth().height(320.dp), color = Color(0xFF1C1C1E), shape = RoundedCornerShape(8.dp)) {
                        val listState = rememberLazyListState()
                        LaunchedEffect(communicationLog.size) { if (communicationLog.isNotEmpty()) { listState.animateScrollToItem(communicationLog.size - 1) } }
                        LazyColumn(state = listState, modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { items(communicationLog) { log -> Text(text = "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestamp))}] ${log.message}", color = when (log.type) { LogType.SENT -> Color(0xFF64D2FF); LogType.RECEIVED -> Color(0xFF34C759); LogType.ERROR -> Color(0xFFFF453A); LogType.INFO -> Color.White }, fontSize = 10.sp, fontFamily = FontFamily.Monospace) } }
                    }
                }
              }
            }
            DetailPanel.MAPPING -> {
                // HOME 移至進階組（不列在 universalCodes 即自動落入 advancedList）
                val universalCodes = listOf(BleProtocol.Pencil.Function.NONE, BleProtocol.Pencil.Function.BRIGHTNESS_UP, BleProtocol.Pencil.Function.BRIGHTNESS_DOWN, BleProtocol.Pencil.Function.VOLUME_UP, BleProtocol.Pencil.Function.VOLUME_DOWN, BleProtocol.Pencil.Function.COPY, BleProtocol.Pencil.Function.PASTE, BleProtocol.Pencil.Function.SELECT_ALL, BleProtocol.Pencil.Function.PREV_STEP)
                // 不提供選擇的功能。注意只從「選單」濾掉，Function.mapping 保持完整——
                // 筆上若已設定 Gemini，標籤仍需顯示正確名稱而非 "No Function"。
                val hiddenCodes = listOf(BleProtocol.Pencil.Function.GEMINI)
                val allFuncs = BleProtocol.Pencil.Function.mapping.toList().filter { it.first !in hiddenCodes }; val universalList = allFuncs.filter { it.first in universalCodes }; val advancedList = allFuncs.filter { it.first !in universalCodes }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text("Universal (All Tablets)", fontSize = 13.sp, color = Text_LightGray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }; items(universalList) { (code, name) -> DetailItemCard(onClick = { viewModel.setPencilFunctionWithRetry(targetKeyCode, code) }) { Row(verticalAlignment = Alignment.CenterVertically) { Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp); if (settingsState[targetKeyCode] == code) Icon(Icons.Default.Check, null, tint = EiP_Orange) } } }; item { Text("Advanced (Device Specific)", fontSize = 13.sp, color = Text_LightGray, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }; items(advancedList) { (code, name) -> DetailItemCard(onClick = { viewModel.setPencilFunctionWithRetry(targetKeyCode, code) }) { Row(verticalAlignment = Alignment.CenterVertically) { Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp); if (settingsState[targetKeyCode] == code) Icon(Icons.Default.Check, null, tint = EiP_Orange) } } } }
            }
            else -> {}
        }
    }
}

@Composable
fun DetailItemCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable { onClick() } else Modifier), colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F2F7).copy(alpha = 0.7f)), shape = RoundedCornerShape(12.dp)) { Column(modifier = Modifier.padding(16.dp)) { content() } }
}

@Composable
fun DeviceScannerOverlay(devices: List<BleDevice>, viewModel: BluetoothViewModel) {
    val context = LocalContext.current
    val isEmpty = devices.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp, vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.bluetoothlogo),
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (isEmpty) "Pair your eiP Pencil" else "Connect your eiP Pencil",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Text_Black,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Text(
            text = if (isEmpty) 
                "We couldn't find your pencil nearby.\nPlease go to Bluetooth Settings to pair it first." 
                else "Pencil detected.\nPlease turn on your pencil and keep it near this tablet.",
            fontSize = 14.sp,
            color = Text_LightGray,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        // 使用一個帶有最大寬度限制且置中的容器來包裹清單和按鈕
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isEmpty) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(devices) { device ->
                        val isConnecting = device.connectionState == DeviceConnectionState.CONNECTING
                        val isNearby = device.isNearby
                        val canConnect = isNearby && !isConnecting

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canConnect) { viewModel.connect(device) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = device.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f),
                                    color = if (isNearby) Text_Black else Text_LightGray
                                )
                                Text(
                                    text = when {
                                        isConnecting -> "Connecting..."
                                        !isNearby -> "Offline"
                                        else -> "Connect"
                                    },
                                    color = if (canConnect) EiP_Orange else Text_LightGray,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth(),
                border = BorderStroke(1.5.dp, if (isEmpty) EiP_Orange else Color.LightGray.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isEmpty) EiP_Orange else Text_LightGray
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bluetooth Settings", fontWeight = FontWeight.Bold)
            }
        }
        
        if (!isEmpty) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Not seeing your device? Check system settings.",
                fontSize = 12.sp,
                color = Text_LightGray.copy(alpha = 0.7f)
            )
        }
    }
}
