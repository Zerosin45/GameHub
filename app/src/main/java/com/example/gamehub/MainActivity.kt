package com.example.gamehub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.absoluteValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private val NavyDeep = Color(0xFF050B1A)
private val Navy = Color(0xFF0A1530)
private val Cyan = Color(0xFF22E5FF)
private val Blue = Color(0xFF1E6BFF)
private val Green = Color(0xFF3DFF8F)
private val Amber = Color(0xFFFFA43D)
private val Red = Color(0xFFFF5470)
private val Muted = Color(0xFF8FA6D1)

class MainActivity : ComponentActivity() {
    private val powerMode = mutableStateOf(false)

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            powerMode.value = ShizukuHelper.hasPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val booster = Booster(this)
        val games = booster.findGames()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = NavyDeep) {
                    HubScreen(booster, games, powerMode.value)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        powerMode.value = ShizukuHelper.hasPermission()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}

@Composable
fun HubScreen(booster: Booster, games: List<GameApp>, powerMode: Boolean) {
    val context = booster.context
    var status by remember { mutableStateOf("Pick a game and press START") }
    var overlayOn by remember { mutableStateOf(booster.overlayEnabled) }

    val stats by produceState(initialValue = booster.readStats()) {
        while (true) {
            value = booster.readStats()
            delay(2000)
        }
    }

    val load by produceState(initialValue = LoadStats(null, null), powerMode) {
        while (true) {
            value = if (powerMode) {
                withContext(Dispatchers.IO) { booster.readLoad() }
            } else {
                LoadStats(null, null)
            }
            delay(2000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NavyDeep, Navy, NavyDeep)))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        // ----- header -----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "GAME HUB",
                color = Cyan,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
            )
            PowerChip(powerMode) {
                if (!ShizukuHelper.isRunning()) {
                    status = "Start Shizuku first (wireless debugging), then tap again"
                    context.packageManager
                        .getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PACKAGE)
                        ?.let { context.startActivity(it) }
                } else {
                    ShizukuHelper.requestPermission()
                }
            }
        }

        // ----- gauges -----
        val tempText = String.format("%.0f", stats.tempC) + "°"
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Gauge("RAM", "${stats.ramUsedPct}%", stats.ramUsedPct / 100f, if (stats.ramUsedPct > 85) Red else Green)
            Gauge("BATTERY", "${stats.batteryPct}%", stats.batteryPct / 100f, if (stats.batteryPct < 20) Red else Cyan)
            Gauge("TEMP", tempText, stats.tempC / 60f, if (stats.tempC > 42f) Red else Amber)
            Gauge("STORAGE", "${stats.storageUsedPct}%", stats.storageUsedPct / 100f, Blue)
        }

        // ----- CPU / GPU (Shizuku) -----
        val cpu = load.cpuPct
        val gpu = load.gpuPct
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Gauge("CPU", if (cpu != null) "$cpu%" else "--", (cpu ?: 0) / 100f, if ((cpu ?: 0) > 85) Red else Cyan)
            Gauge("GPU", if (gpu != null) "$gpu%" else "--", (gpu ?: 0) / 100f, if ((gpu ?: 0) > 85) Red else Green)
        }
        Text(
            when {
                !powerMode -> "Turn on POWER to see CPU and GPU load"
                gpu == null -> "GPU load is not readable on this phone"
                else -> "CPU and GPU load, updated every 2 seconds"
            },
            color = Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 16.dp),
        )

        // ----- game carousel -----
        if (games.isEmpty()) {
            Text(
                "No games found. Android only lists apps that declare themselves as games.",
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 48.dp),
            )
        } else {
            val pagerState = rememberPagerState(pageCount = { games.size })
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = 56.dp),
                pageSpacing = 12.dp,
                key = { games[it].packageName },
            ) { page ->
                val game = games[page]
                val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                    .absoluteValue
                    .coerceIn(0f, 1f)
                GameCard(
                    booster = booster,
                    game = game,
                    modifier = Modifier.graphicsLayer {
                        val s = 1f - 0.1f * offset
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.4f * offset
                    },
                    onStatus = { status = it },
                )
            }
        }

        Text(
            status,
            color = Muted,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
        )

        // ----- bottom controls -----
        val panelShape = RoundedCornerShape(22.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .background(Color(0xFF0E1A33), panelShape)
                .border(1.dp, Color(0x3322E5FF), panelShape)
                .padding(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Floating in-game panel", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Quick boost and silence over your game", color = Muted, fontSize = 12.sp)
                }
                Switch(
                    checked = overlayOn,
                    onCheckedChange = { on ->
                        if (on && !booster.canDrawOverlays()) {
                            status = "Allow 'Display over other apps' for Game Hub, then switch this on again"
                            context.startActivity(booster.overlaySettingsIntent())
                        } else {
                            overlayOn = on
                            booster.overlayEnabled = on
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NavyDeep,
                        checkedTrackColor = Cyan,
                    ),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val n = booster.clearBackgroundApps(null)
                        status = "Boost done: $n apps stopped"
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                ) { Text("Boost now") }
                OutlinedButton(
                    onClick = {
                        booster.restoreAll()
                        status = "Notifications restored, panel stopped"
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                ) { Text("Restore") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun PowerChip(on: Boolean, onClick: () -> Unit) {
    val color = if (on) Green else Amber
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .border(1.5.dp, color, shape)
            .clickable(enabled = !on) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            if (on) "POWER ON" else "POWER OFF",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun Gauge(label: String, valueText: String, fraction: Float, color: Color) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "gauge")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(74.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = Color(0x33FFFFFF),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(valueText, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    val color = if (on) Cyan else Color(0xFF5A6E99)
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (on) Color(0x3322E5FF) else Color.Transparent)
            .border(1.dp, color, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            (if (on) "● " else "○ ") + text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun GameCard(
    booster: Booster,
    game: GameApp,
    modifier: Modifier,
    onStatus: (String) -> Unit,
) {
    val context = booster.context
    val saved = remember(game.packageName) { booster.settings(game.packageName) }
    var forceStop by remember(game.packageName) { mutableStateOf(saved.forceStop) }
    var silent by remember(game.packageName) { mutableStateOf(saved.silent) }
    var launches by remember(game.packageName) { mutableStateOf(booster.launchCount(game.packageName)) }
    val icon = remember(game.packageName) { game.icon.toBitmap(192, 192).asImageBitmap() }
    val shape = RoundedCornerShape(28.dp)

    Column(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .background(Brush.verticalGradient(listOf(Color(0xFF14295A), Color(0xFF0A1530))), shape)
            .border(1.5.dp, Brush.linearGradient(listOf(Cyan, Blue)), shape)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = icon,
            contentDescription = game.label,
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            game.label,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            if (launches == 0) "Not launched from Hub yet" else "Launched $launches times from Hub",
            color = Muted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("Clear apps", forceStop) {
                forceStop = !forceStop
                booster.setForceStop(game.packageName, forceStop)
            }
            Toggle("Silent", silent) {
                if (!silent && !booster.hasDndAccess()) {
                    onStatus("Allow Do Not Disturb access for Game Hub, then tap Silent again")
                    context.startActivity(booster.dndSettingsIntent())
                } else {
                    silent = !silent
                    booster.setSilent(game.packageName, silent)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Brush.horizontalGradient(listOf(Blue, Cyan)))
                .clickable {
                    onStatus(booster.startGame(game))
                    launches = booster.launchCount(game.packageName)
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "START",
                color = NavyDeep,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
            )
        }
    }
}
