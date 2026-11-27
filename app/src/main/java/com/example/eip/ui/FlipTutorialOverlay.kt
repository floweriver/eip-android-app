// v1.0.0 | 2026-11-27 | 初版：eiP Flip Keyboard（KL122）首次使用教學
//
// changelog:
//   v1.0.0 | 2026-11-27 | 自 iOS 版 FlipTutorialManager / FlipTutorialView 移植。
//                         五頁：認識鍵盤、依系統切換模式、Type-C 有線、設定快捷鍵、休眠時間。
//                         版面比照觸控筆的 TutorialOverlay（置中白卡＋半透明遮罩、橘色按鈕），
//                         但標題置中、說明拆成條列短句左對齊、每頁右上角可關閉。
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eip.device.R
import kotlinx.coroutines.launch

// --- 「只跳一次」的持久化旗標（與觸控筆教學分開記，兩種產品各看一次）---
private const val KEY_HAS_SEEN_FLIP_TUTORIAL = "has_seen_flip_tutorial"

fun hasSeenFlipTutorial(context: Context): Boolean =
    context.getSharedPreferences("eip_prefs", Context.MODE_PRIVATE)
        .getBoolean(KEY_HAS_SEEN_FLIP_TUTORIAL, false)

fun markFlipTutorialSeen(context: Context) {
    context.getSharedPreferences("eip_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_HAS_SEEN_FLIP_TUTORIAL, true).apply()
}

/** 教學頁數。增減頁數只需改這裡與字串資源，圖片依此自動索引。 */
private const val FLIP_TUTORIAL_PAGES = 5

private val TutorialOrange = Color(0xFFF5A623)
private val TutorialImageBg = Color(0xFFF5F5F5)
private val TutorialSubtitle = Color(0xFF8A8A8E)

private fun pageTitleRes(page: Int): Int = when (page) {
    0 -> R.string.flip_tutorial_page1_title
    1 -> R.string.flip_tutorial_page2_title
    2 -> R.string.flip_tutorial_page3_title
    3 -> R.string.flip_tutorial_page4_title
    else -> R.string.flip_tutorial_page5_title
}

private fun pageDescriptionRes(page: Int): Int = when (page) {
    0 -> R.string.flip_tutorial_page1_description
    1 -> R.string.flip_tutorial_page2_description
    2 -> R.string.flip_tutorial_page3_description
    3 -> R.string.flip_tutorial_page4_description
    else -> R.string.flip_tutorial_page5_description
}

/**
 * 圖片資源名稱 `flip_tutorial_{頁碼}`。語言與方向交給資源系統挑：
 * drawable-zh-rTW-nodpi / drawable-ja-nodpi / drawable-nodpi（英文），橫向加 -land。
 *
 * 注意：-land 資料夾裡的圖是**直式**構圖——裝置橫向時版面是「文案在左、圖片在右」，
 * 圖片區是直立的窄長條；無方向限定的圖用於裝置直立、圖片在上方，是**橫式**構圖。
 */
private fun pageImageName(page: Int): String = "flip_tutorial_${page + 1}"

/**
 * 第一次連上 eiP Flip Keyboard 時顯示的教學（置中白卡 + 半透明遮罩）。
 * 五頁以 HorizontalPager 左右滑動；每頁右上角都有 ⨉，不必翻到最後一頁才能關。
 */
@Composable
fun FlipTutorialOverlay(onFinish: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { FLIP_TUTORIAL_PAGES })
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 半透明遮罩，攔截點擊避免穿透到底下 UI。刻意不做「點背景關閉」——誤觸關掉反而要從頭找。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.9f else 0.88f)
                .fillMaxHeight(if (isLandscape) 0.9f else 0.84f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val controls: @Composable (fill: Boolean) -> Unit = { fill ->
                    FlipTutorialControls(
                        page = page,
                        fill = fill,
                        onSkip = onFinish,
                        onPrev = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                        onNext = {
                            if (page == FLIP_TUTORIAL_PAGES - 1) onFinish()
                            else scope.launch { pagerState.animateScrollToPage(page + 1) }
                        }
                    )
                }
                if (isLandscape) FlipLandscapePage(page, controls) else FlipPortraitPage(page, controls)
            }

            // 每一頁右上角都能關閉。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5E5EA))
                    .clickable(onClick = onFinish),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.flip_tutorial_close),
                    tint = TutorialSubtitle,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** 直向：圖片在上（約 55% 高）、文案在下，指示點與按鈕收底部。 */
@Composable
private fun FlipPortraitPage(page: Int, controls: @Composable (fill: Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(55f)
                .background(TutorialImageBg),
            contentAlignment = Alignment.Center
        ) {
            FlipTutorialImage(page, modifier = Modifier.fillMaxSize())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(45f)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FlipTutorialText(page)
            }
            Spacer(Modifier.height(12.dp))
            FlipTutorialDots(page, Modifier.fillMaxWidth(), Arrangement.Center)
            Spacer(Modifier.height(14.dp))
            controls(true)
        }
    }
}

/** 橫向：文案在左（垂直置中）、圖片在右填滿。 */
@Composable
private fun FlipLandscapePage(page: Int, controls: @Composable (fill: Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 32.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FlipTutorialText(page)
            }
            Spacer(Modifier.height(16.dp))
            FlipTutorialDots(page, Modifier.fillMaxWidth(), Arrangement.Center)
            Spacer(Modifier.height(16.dp))
            controls(true)
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().background(TutorialImageBg),
            contentAlignment = Alignment.Center
        ) {
            FlipTutorialImage(page, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 標題置中；說明以換行拆成多條，每條一個圓點、左對齊，讀起來比一整段置中輕鬆。 */
@Composable
private fun FlipTutorialText(page: Int) {
    val lines = stringResource(pageDescriptionRes(page)).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    Text(
        stringResource(pageTitleRes(page)),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        color = Text_Black,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(22.dp))
    Column(
        modifier = Modifier.widthIn(max = 440.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (line in lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(5.dp)
                        .background(TutorialSubtitle.copy(alpha = 0.7f), CircleShape)
                )
                Text(line, fontSize = 15.sp, lineHeight = 21.sp, color = TutorialSubtitle)
            }
        }
    }
}

@Composable
private fun FlipTutorialDots(index: Int, modifier: Modifier, arrangement: Arrangement.Horizontal) {
    Row(modifier = modifier, horizontalArrangement = arrangement, verticalAlignment = Alignment.CenterVertically) {
        repeat(FLIP_TUTORIAL_PAGES) { i ->
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

/** 第一頁左邊是「略過」、其餘是「上一步」；右邊是「下一步」，最後一頁為「開始使用」。 */
@Composable
private fun FlipTutorialControls(page: Int, fill: Boolean, onSkip: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
    val isFirst = page == 0
    val isLast = page == FLIP_TUTORIAL_PAGES - 1
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isFirst) {
            TextButton(onClick = onSkip, modifier = Modifier.weight(1f).height(50.dp)) {
                Text(stringResource(R.string.flip_tutorial_skip), color = TutorialSubtitle, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        } else {
            OutlinedButton(
                onClick = onPrev,
                modifier = Modifier.weight(1f).height(50.dp),
                shape = RoundedCornerShape(25.dp),
                border = BorderStroke(1.5.dp, TutorialOrange),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = TutorialOrange)
            ) {
                Text(stringResource(R.string.flip_tutorial_previous), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Button(
            onClick = onNext,
            modifier = Modifier.weight(1f).height(50.dp),
            shape = RoundedCornerShape(25.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TutorialOrange)
        ) {
            Text(
                stringResource(if (isLast) R.string.flip_tutorial_done else R.string.flip_tutorial_next),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
            )
        }
    }
}

/** 教學圖；該頁圖片尚未放進專案時顯示淺灰佔位，不顯示錯誤文字。 */
@Composable
private fun FlipTutorialImage(page: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val name = pageImageName(page)
    val resId = remember(name) { context.resources.getIdentifier(name, "drawable", context.packageName) }
    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier = modifier.background(TutorialImageBg))
    }
}
