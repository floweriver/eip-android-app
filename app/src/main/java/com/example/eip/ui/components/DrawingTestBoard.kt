package com.example.eip.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.sqrt

// 資料類別定義
data class PointData(val offset: Offset, val rawPressure: Int)
data class DrawingStroke(val points: List<PointData>)
data class FadingPath(val start: Offset, val end: Offset, var alpha: Float = 0.4f)

@Composable
fun DrawingTestBoard(modifier: Modifier = Modifier) {
    val strokes = remember { mutableStateListOf<DrawingStroke>() }
    val currentPoints = remember { mutableStateListOf<PointData>() }
    val fadingEraserPaths = remember { mutableStateListOf<FadingPath>() }
    
    var isEraserActive by remember { mutableStateOf(false) }
    var lastEraserEndTime by remember { mutableLongStateOf(0L) }

    var liveX by remember { mutableStateOf(0) }
    var liveY by remember { mutableStateOf(0) }
    var livePressureRaw by remember { mutableStateOf(0) }
    var toolDesc by remember { mutableStateOf("No Input") }

    val ENGINEERING_MAX = 3500f
    val OUTPUT_MAX = 4096f
    val scaleFactor = OUTPUT_MAX / ENGINEERING_MAX

    // 淡出邏輯
    LaunchedEffect(fadingEraserPaths.size) {
        while (fadingEraserPaths.isNotEmpty()) {
            delay(50)
            val toRemove = mutableListOf<FadingPath>()
            for (i in fadingEraserPaths.indices) {
                val path = fadingEraserPaths[i]
                val newAlpha = path.alpha - 0.05f
                if (newAlpha <= 0f) {
                    toRemove.add(path)
                } else {
                    fadingEraserPaths[i] = path.copy(alpha = newAlpha)
                }
            }
            fadingEraserPaths.removeAll(toRemove)
        }
    }

    Column(modifier = modifier.fillMaxWidth().background(Color(0xFFF9F9FB), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Drawing Test Board", fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { strokes.clear(); currentPoints.clear(); fadingEraserPaths.clear() }) { Text("Clear Canvas", fontSize = 12.sp) }
        }
        
        Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(Color.White)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val now = System.currentTimeMillis()
                    if (now - lastEraserEndTime < 150 && down.type != PointerType.Eraser) {
                        return@awaitEachGesture
                    }

                    isEraserActive = (down.type == PointerType.Eraser)
                    toolDesc = if (isEraserActive) "Eraser" else "Stylus"
                    
                    val rawP = (down.pressure * 4096 * scaleFactor).toInt().coerceAtMost(4096)
                    currentPoints.add(PointData(down.position, rawP))
                    liveX = down.position.x.toInt(); liveY = down.position.y.toInt(); livePressureRaw = rawP

                    var lastPos = down.position

                    drag(down.id) { change ->
                        val dragRawP = (change.pressure * 4096 * scaleFactor).toInt().coerceAtMost(4096)
                        liveX = change.position.x.toInt(); liveY = change.position.y.toInt(); livePressureRaw = dragRawP
                        currentPoints.add(PointData(change.position, dragRawP))
                        
                        if (isEraserActive) {
                            // 即時加入淡出痕跡段
                            fadingEraserPaths.add(FadingPath(lastPos, change.position))
                            lastPos = change.position
                        }
                        
                        change.consume()
                    }
                    
                    if (isEraserActive) {
                        val eraserPath = currentPoints.toList()
                        strokes.removeAll { stroke ->
                            stroke.points.any { pt -> 
                                val ePos = eraserPos(eraserPath, pt.offset)
                                if (ePos == Offset.Infinite) false
                                else sqrt((pt.offset.x - ePos.x).pow(2) + (pt.offset.y - ePos.y).pow(2)) < 30f 
                            }
                        }
                        lastEraserEndTime = System.currentTimeMillis()
                    } else if (currentPoints.size > 1) {
                        strokes.add(DrawingStroke(currentPoints.toList()))
                    }
                    currentPoints.clear()
                    toolDesc = "Released"; livePressureRaw = 0
                }
            }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                strokes.forEach { stroke ->
                    val pts = stroke.points
                    for (i in 0 until pts.size - 1) {
                        drawLine(color = Color.Black, start = pts[i].offset, end = pts[i+1].offset, 
                                 strokeWidth = 1f + (pts[i].rawPressure / 4096f * 15f), cap = StrokeCap.Round)
                    }
                }
                
                fadingEraserPaths.forEach { fading ->
                    drawLine(
                        color = Color.Gray.copy(alpha = fading.alpha),
                        start = fading.start,
                        end = fading.end,
                        strokeWidth = 40f,
                        cap = StrokeCap.Round
                    )
                }

                if (!isEraserActive && currentPoints.size > 1) {
                    for (i in 0 until currentPoints.size - 1) {
                        drawLine(color = Color.Black, start = currentPoints[i].offset, end = currentPoints[i+1].offset,
                                 strokeWidth = 1f + (currentPoints[i].rawPressure / 4096f * 15f), cap = StrokeCap.Round)
                    }
                }
                if (isEraserActive && toolDesc != "Released") {
                    drawCircle(color = Color.Gray.copy(alpha = 0.3f), radius = 40f, center = Offset(liveX.toFloat(), liveY.toFloat()), style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)))
                }
            }
            Column(modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(8.dp)) {
                Text("Monitor:", color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("X=$liveX, Y=$liveY", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Pressure: $livePressureRaw / 4096", color = if(livePressureRaw >= 4096) Color.Cyan else Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text("Tool: $toolDesc", color = if(isEraserActive) Color.Magenta else Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun eraserPos(path: List<PointData>, target: Offset): Offset {
    if (path.isEmpty()) return Offset.Infinite
    return path.minByOrNull { sqrt((it.offset.x - target.x).pow(2) + (it.offset.y - target.y).pow(2)) }?.offset ?: Offset.Infinite
}
