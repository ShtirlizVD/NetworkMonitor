package com.networkmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    
    val running by vm.isRunning.collectAsState()
    val state by vm.state.collectAsState()
    val events by vm.events.collectAsState()
    val stats by vm.stats.collectAsState()
    val alarmActive by vm.isAlarmActive.collectAsState()
    
    var showExport by remember { mutableStateOf(false) }
    
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!it.values.all { b -> b }) Toast.makeText(ctx, "Permissions required", Toast.LENGTH_SHORT).show()
    }
    
    LaunchedEffect(Unit) {
        if (!vm.checkPerms(ctx)) {
            val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CellTower, null, Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Network Monitor")
                        if (running) {
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(10.dp).background(
                                when { alarmActive -> Color(0xFFFF5722); !state.isInService -> Color(0xFFFFC107); else -> Color(0xFF4CAF50) },
                                RoundedCornerShape(50)
                            ))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                actions = {
                    IconButton(onClick = { showExport = true }) { Icon(Icons.Default.Share, "Export") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Alarm Alert
            if (alarmActive) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(32.dp), Color(0xFFF44336))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("⚠️ NETWORK OFFLINE!", fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                                Text("Alarm active • ${vm.formatDuration(state.offlineSeconds)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            
            // Current State
            item {
                val color = if (state.isInService) Color(0xFF4CAF50) else Color(0xFFF44336)
                Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(14.dp).background(color, RoundedCornerShape(50)))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.isInService) "CONNECTED" else "OFFLINE", fontWeight = FontWeight.Bold, color = color)
                            }
                            Text(state.networkTypeName, fontWeight = FontWeight.Bold)
                        }
                        
                        if (!state.isInService && state.offlineSeconds > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("⏱ Offline: ${vm.formatDuration(state.offlineSeconds)}", 
                                fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Operator", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(state.operatorName.ifEmpty { "N/A" })
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Signal", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text("${state.signalLevel}/4")
                            }
                        }
                    }
                }
            }
            
            // Alarm Timer
            if (!state.isInService && running) {
                item {
                    val threshold = 120L
                    val timeLeft = maxOf(0L, threshold - state.offlineSeconds)
                    val progress = (state.offlineSeconds.toFloat() / threshold).coerceIn(0f, 1f)
                    val imminent = timeLeft in 1..30
                    
                    Card(colors = CardDefaults.cardColors(containerColor = if (imminent) Color(0xFFFFF3E0) else Color(0xFFE3F2FD))) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (imminent) Icons.Default.Warning else Icons.Default.Timer, null, 
                                    tint = if (imminent) Color(0xFFFF9800) else Color(0xFF2196F3))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (!vm.alarmEnabled) "Alarm OFF"
                                    else if (timeLeft > 0) "Alarm in ${vm.formatDuration(timeLeft)}"
                                    else "ALARM!",
                                    fontWeight = FontWeight.Bold,
                                    color = if (!vm.alarmEnabled) Color.Gray else if (imminent) Color(0xFFFF9800) else Color(0xFF2196F3)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress, Modifier.fillMaxWidth(), 
                                color = if (imminent) Color(0xFFFF9800) else Color(0xFF2196F3))
                            Spacer(Modifier.height(8.dp))
                            Text("${vm.formatDuration(state.offlineSeconds)} / ${vm.formatDuration(threshold)}", 
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
            
            // Stats
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Statistics", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Stat("Losses", stats.lossCount.toString(), Color(0xFFF44336), Modifier.weight(1f))
                            Stat("Restores", stats.restoreCount.toString(), Color(0xFF4CAF50), Modifier.weight(1f))
                            Stat("Offline", vm.formatDuration(stats.totalOfflineSeconds), Color(0xFFFF9800), Modifier.weight(1f))
                        }
                        if (stats.longestOfflineSeconds > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                Stat("Longest", vm.formatDuration(stats.longestOfflineSeconds), Color(0xFF9C27B0), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            
            // Controls
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Monitoring", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Alarm", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(4.dp))
                                Switch(vm.alarmEnabled, { vm.toggleAlarm() })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Button({ vm.start(ctx) }, !running, Modifier.weight(1f), Color(0xFF4CAF50)) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Start")
                            }
                            Button({ vm.stop(ctx) }, running, Modifier.weight(1f), Color(0xFFF44336)) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                        if (events.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton({ vm.clear() }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Clear Events")
                            }
                        }
                    }
                }
            }
            
            // Events
            if (events.isNotEmpty()) {
                item { Text("Events (${events.size})", fontWeight = FontWeight.Bold) }
                items(events.take(50)) { e ->
                    val bg = when (e.type) { "LOSS" -> Color(0x33F44336); "RESTORE" -> Color(0x334CAF50); else -> Color.Transparent }
                    val fg = when (e.type) { "LOSS" -> Color(0xFFF44336); "RESTORE" -> Color(0xFF4CAF50); else -> Color(0xFF607D8B) }
                    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg),
                        colors = CardDefaults.cardColors(containerColor = bg.takeIf { it != Color.Transparent } ?: MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(e.type, fontWeight = FontWeight.Bold, color = fg, style = MaterialTheme.typography.labelMedium)
                                Text(e.timestamp, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.previousState, color = Color.Gray)
                                Icon(Icons.Default.ArrowForward, null, Modifier.size(14.dp), Color.Gray)
                                Text(e.newState, fontWeight = FontWeight.Bold)
                            }
                            if (e.durationSeconds > 0) {
                                Spacer(Modifier.height(4.dp))
                                Text("Duration: ${vm.formatDuration(e.durationSeconds)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            
            // Info
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("How it works", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("• NO ROOT required\n• Monitors network state\n• Records losses/restores\n• ALARM: 2 min offline → 10 sec alert", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    
    if (showExport) {
        AlertDialog({ showExport = false }, title = { Text("Export") }, text = { Text("Export ${events.size} events?") },
            confirmButton = {
                TextButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.export())
                    }, "Export"))
                    showExport = false
                }) { Text("Export") }
            },
            dismissButton = { TextButton({ showExport = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}

@Composable
fun Button(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, color: Color, content: @Composable RowScope.() -> Unit) {
    Button(onClick, enabled, modifier, colors = ButtonDefaults.buttonColors(containerColor = color), content = content)
}
