package com.example.eip.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// --- 教學頁資料 ---
data class TutorialPage(val imageName: String, val title: String, val subtitle: String)

// --- 版面顏色（依規格）---
private val TutorialOrange = Color(0xFFF5A623)
private val TutorialImageBg = Color(0xFFF5F5F5)
private val TutorialSubtitle = Color(0xFF8A8A8E)

// --- 「只跳一次」的持久化旗標 ---
private const val TUTORIAL_PREFS = "eip_prefs"
private const val KEY_HAS_SEEN_TUTORIAL = "has_seen_tutorial"

fun hasSeenTutorial(context: Context): Boolean =
    context.getSharedPreferences(TUTORIAL_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_HAS_SEEN_TUTORIAL, false)

fun markTutorialSeen(context: Context) {
    context.getSharedPreferences(TUTORIAL_PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_HAS_SEEN_TUTORIAL, true).apply()
}

/**
 * 第一次連上觸控筆時顯示的教學導覽（置中視窗 + 半透明遮罩）。
 * 5 頁以 HorizontalPager 左右滑動；頂部返回列共用，每頁內容依螢幕方向自動切換
 * 直向(上下佈局) / 橫向(左右分欄)兩種排版，共用同一份文案與圖片。
 */
@Composable
fun TutorialOverlay(pages: List<TutorialPage>, onFinish: () -> Unit) {
    if (pages.isEmpty()) { onFinish(); return }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // 半透明遮罩，攔截點擊避免穿透到底下 UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.82f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
        ) {
            // 頂部返回列（共用，固定高度）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinish() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Back", tint = Text_Black, modifier = Modifier.size(28.dp))
                Text("Tutorial", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Text_Black)
            }

            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { i ->
                TutorialPageContent(
                    page = pages[i],
                    index = i,
                    total = pages.size,
                    isFirst = i == 0,
                    isLast = i == pages.lastIndex,
                    onPrev = { scope.launch { pagerState.animateScrollToPage(i - 1) } },
                    onNext = {
                        if (i == pages.lastIndex) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(i + 1) }
                    }
                )
            }
        }
    }
}

@Composable
private fun TutorialPageContent(
    page: TutorialPage,
    index: Int,
    total: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        LandscapePage(page, index, total, isFirst, isLast, onPrev, onNext)
    } else {
        PortraitPage(page, index, total, isFirst, isLast, onPrev, onNext)
    }
}

/** 直向：上下佈局。加大灰色圖卡（佔約 62% 高、iPad 上下留灰底），下方標題副標在上、指示點按鈕收底部 */
@Composable
private fun PortraitPage(
    page: TutorialPage, index: Int, total: Int, isFirst: Boolean, isLast: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        // 圖片區：淺灰圓角卡片、置中、Fit，佔可用高度約 62%；iPad 上下留灰底
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(62f)
                .clip(RoundedCornerShape(24.dp))
                .background(TutorialImageBg),
            contentAlignment = Alignment.Center
        ) {
            TutorialImage(page.imageName, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp))
        }

        // 圖片以下（約 38%）：標題+副標在上，指示點+按鈕收到底部，中間用彈性間距撐開
        Column(
            modifier = Modifier.fillMaxWidth().weight(38f).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(page.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Text_Black, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            Text(page.subtitle, fontSize = 14.sp, color = TutorialSubtitle, textAlign = TextAlign.Center, lineHeight = 20.sp, maxLines = 2, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))

            Spacer(modifier = Modifier.weight(1f))
            TutorialDots(index, total, Arrangement.Center, Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            TutorialButtons(isFirst, isLast, onPrev, onNext, Modifier.fillMaxWidth(), fill = true)
        }
    }
}

/** 橫向：左右分欄，左邊文字/控制（垂直置中、靠左），右邊圖片填滿。按鈕固定寬度、靠左 */
@Composable
private fun LandscapePage(
    page: TutorialPage, index: Int, total: Int, isFirst: Boolean, isLast: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // 左半邊：內容垂直置中、靠左
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start
        ) {
            Text(page.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Text_Black, textAlign = TextAlign.Start)
            Spacer(modifier = Modifier.height(10.dp))
            Text(page.subtitle, fontSize = 14.sp, color = TutorialSubtitle, textAlign = TextAlign.Start, lineHeight = 20.sp, maxLines = 2)
            Spacer(modifier = Modifier.height(24.dp))
            TutorialDots(index, total, Arrangement.Start, Modifier)
            Spacer(modifier = Modifier.height(24.dp))
            TutorialButtons(isFirst, isLast, onPrev, onNext, Modifier.fillMaxWidth(), fill = false)
        }
        // 右半邊：圖片填滿高度、靠右
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 24.dp, end = 16.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            TutorialImage(page.imageName, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun TutorialDots(index: Int, total: Int, arrangement: Arrangement.Horizontal, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val selected = i == index
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .height(6.dp)
                    .width(if (selected) 20.dp else 6.dp)
                    .clip(if (selected) RoundedCornerShape(3.dp) else CircleShape)
                    .background(if (selected) TutorialOrange else TutorialSubtitle.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun TutorialButtons(
    isFirst: Boolean, isLast: Boolean, onPrev: () -> Unit, onNext: () -> Unit,
    modifier: Modifier = Modifier, fill: Boolean = true
) {
    // fill=true（直向）：按鈕用 weight 撐滿一半寬；fill=false（橫向）：固定寬度、靠左，避免按鈕過大
    // 注意：Modifier.weight() 只能在 RowScope 內使用，所以在 Row 內部才決定
    Row(
        modifier = modifier,
        horizontalArrangement = if (fill) Arrangement.spacedBy(12.dp) else Arrangement.spacedBy(12.dp, Alignment.Start)
    ) {
        if (!isFirst) {
            OutlinedButton(
                onClick = onPrev,
                modifier = if (fill) Modifier.weight(1f).height(52.dp) else Modifier.width(150.dp).height(48.dp),
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.5.dp, TutorialOrange),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = TutorialOrange)
            ) {
                Text("Previous", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Button(
            onClick = onNext,
            modifier = if (fill) Modifier.weight(1f).height(52.dp) else Modifier.width(150.dp).height(48.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TutorialOrange)
        ) {
            Text(if (isLast) "Get Started" else "Next", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun TutorialImage(imageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resId = remember(imageName) {
        context.resources.getIdentifier(imageName, "drawable", context.packageName)
    }
    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        // 缺圖佔位（圖片尚未放進 drawable 時顯示）
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("$imageName\n(圖片待放入 drawable)", color = TutorialSubtitle, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}
