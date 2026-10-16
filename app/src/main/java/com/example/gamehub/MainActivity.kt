package com.example.gamehub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import rikka.shizuku.Shizuku

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
                Surface(modifier = Modifier.fillMaxSize()) {
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
    var status by remember { mutableStateOf("Ready") }
    var dnd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Game Hub", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                val n = booster.clearBackgroundApps(null)
                status = "Cleared background processes of $n apps"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Boost now") }

        Spacer(Modifier.height(8.dp))
        if (powerMode) {
            Text("Power mode ON: apps are fully force-stopped", style = MaterialTheme.typography.bodySmall)
        } else {
            OutlinedButton(
                onClick = {
                    if (!ShizukuHelper.isRunning()) {
                        status = "Start Shizuku first (see SHIZUKU_SETUP.md), then tap again"
                        booster.context.packageManager
                            .getLaunchIntentForPackage(ShizukuHelper.SHIZUKU_PACKAGE)
                            ?.let { booster.context.startActivity(it) }
                    } else {
                        ShizukuHelper.requestPermission()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enable power mode (Shizuku)") }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Silence notifications while gaming")
            Switch(checked = dnd, onCheckedChange = { on ->
                if (!booster.hasDndAccess()) {
                    status = "Grant Do Not Disturb access, then toggle again"
                    booster.context.startActivity(booster.dndSettingsIntent())
                } else {
                    dnd = on
                    if (on) booster.enableGameDnd() else booster.restoreDnd()
                }
            })
        }

        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Text("Your games", style = MaterialTheme.typography.titleMedium)
        if (games.isEmpty()) {
            Text("No games detected. Android only tags apps that declare themselves as games.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(games) { game ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Image(
                            bitmap = game.icon.toBitmap(96, 96).asImageBitmap(),
                            contentDescription = game.label,
                            modifier = Modifier.size(48.dp),
                        )
                        Text(game.label, Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val n = booster.clearBackgroundApps(game.packageName)
                            if (dnd) booster.enableGameDnd()
                            status = "Cleared $n apps, launching ${game.label}"
                            booster.setPerformanceMode(game.packageName)
                            booster.launch(game)
                        }) { Text("Boost & Play") }
                    }
                }
            }
        }
    }
}
