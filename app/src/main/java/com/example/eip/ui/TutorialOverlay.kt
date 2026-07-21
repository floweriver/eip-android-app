package com.example.eip.ui

import android.content.Context
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
 * 第一次連上觸控筆時顯示的全螢幕教學導覽。
 * 5 頁以 HorizontalPager 左右滑動；頂部返回列、頁數指示點，
 * 底部按鈕依目前頁面變化（第 1 頁只有 Next，中間頁 Previous+Next，最後頁 Previous+Get Started）。
 * 任何時候按返回列或走完最後一頁的 Get Started 都會呼叫 [onFinish]。
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
            .fillMaxWidth(0.82f)
            .fillMaxHeight(0.78f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
    ) {
        // 1. 頂部返回列
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

        // 2~4. 每頁：主圖 + 標題 + 副標
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { pageIndex ->
            TutorialPageContent(pages[pageIndex])
        }

        val current = pagerState.currentPage
        val isFirst = current == 0
        val isLast = current == pages.lastIndex

        // 5. 頁數指示點
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            pages.indices.forEach { i ->
                val selected = i == current
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

        // 6. 底部按鈕
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isFirst) {
                OutlinedButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.5.dp, TutorialOrange),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = TutorialOrange)
                ) {
                    Text("Previous", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            Button(
                onClick = {
                    if (isLast) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(current + 1) }
                },
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TutorialOrange)
            ) {
                Text(if (isLast) "Get Started" else "Next", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
      }
    }
}

@Composable
private fun TutorialPageContent(page: TutorialPage) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 主圖：淺灰圓角底、置中示意圖，約佔上半 55%
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.60f)
                .clip(RoundedCornerShape(24.dp))
                .background(TutorialImageBg),
            contentAlignment = Alignment.Center
        ) {
            TutorialImage(page.imageName, modifier = Modifier.fillMaxSize().padding(16.dp))
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = page.title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Text_Black,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = page.subtitle,
            fontSize = 14.sp,
            color = TutorialSubtitle,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        )
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
