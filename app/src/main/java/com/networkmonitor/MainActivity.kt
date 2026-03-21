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
    val alarmEnabled by vm.alarmEnabled.collectAsState()
    val hasRoot by vm.hasRoot.collectAsState()
    val rootMessage by vm.rootMessage.collectAsState()
    val uploadStatus by vm.uploadStatus.collectAsState()
    val githubToken by vm.githubToken.collectAsState()
    
    var showExport by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!it.values.all { b -> b }) Toast.makeText(ctx, "Требуются разрешения", Toast.LENGTH_SHORT).show()
    }
    
    LaunchedEffect(Unit) {
        vm.loadToken(ctx)
        val hasPerms = vm.checkPerms(ctx)
        if (!hasPerms) {
            val perms = mutableListOf(
                Manifest.permission.READ_PHONE_STATE, 
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(perms.toTypedArray())
        }
        vm.checkRoot()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CellTower, null, Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Монитор сети")
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
                    IconButton(onClick = { 
                        tokenInput = githubToken
                        showTokenDialog = true 
                    }) { 
                        Icon(
                            if (githubToken.isNotBlank()) Icons.Default.CheckCircle else Icons.Default.Settings, 
                            "Токен",
                            tint = if (githubToken.isNotBlank()) Color(0xFF4CAF50) else Color.Gray
                        ) 
                    }
                    IconButton(onClick = { showExport = true }) { Icon(Icons.Default.Share, "Экспорт") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (alarmActive) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, Modifier.size(32.dp), Color(0xFFF44336))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("⚠️ НЕТ СЕТИ!", fontWeight = FontWeight.Bold, color = Color(0xFFF44336))
                                Text("Тревога • ${vm.formatDuration(state.offlineSeconds)}", style = MaterialTheme.typography.bodySmall)
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
                                Text(if (state.isInService) "В СЕТИ" else "ОФФЛАЙН", fontWeight = FontWeight.Bold, color = color)
                            }
                            Text(state.networkTypeName, fontWeight = FontWeight.Bold)
                        }
                        
                        if (!state.isInService && state.offlineSeconds > 0) {
                            Spacer(Modifier.height(8.dp))
                            Text("⏱ Офлайн: ${vm.formatDuration(state.offlineSeconds)}", 
                                fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
                        }
                        
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Оператор", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(state.operatorName.ifEmpty { "—" })
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Сигнал", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
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
                                    if (!alarmEnabled) "Сигнал ВЫКЛ"
                                    else if (timeLeft > 0) "Сигнал через ${vm.formatDuration(timeLeft)}"
                                    else "СИГНАЛ!",
                                    fontWeight = FontWeight.Bold,
                                    color = if (!alarmEnabled) Color.Gray else if (imminent) Color(0xFFFF9800) else Color(0xFF2196F3)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(), 
                                color = if (imminent) Color(0xFFFF9800) else Color(0xFF2196F3)
                            )
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
                        Text("Статистика", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            Stat("Потери", stats.lossCount.toString(), Color(0xFFF44336), Modifier.weight(1f))
                            Stat("Восстановления", stats.restoreCount.toString(), Color(0xFF4CAF50), Modifier.weight(1f))
                            Stat("Офлайн", vm.formatDuration(stats.totalOfflineSeconds), Color(0xFFFF9800), Modifier.weight(1f))
                        }
                        if (stats.longestOfflineSeconds > 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                Stat("Максимум", vm.formatDuration(stats.longestOfflineSeconds), Color(0xFF9C27B0), Modifier.weight(1f))
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
                            Text("Мониторинг", fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Сигнал", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(4.dp))
                                Switch(alarmEnabled, { vm.toggleAlarm() })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                            AppButton({ vm.start(ctx) }, !running, Modifier.weight(1f), Color(0xFF4CAF50)) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Старт")
                            }
                            AppButton({ vm.stop(ctx) }, running, Modifier.weight(1f), Color(0xFFF44336)) {
                                Icon(Icons.Default.Stop, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Стоп")
                            }
                        }
                        if (events.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton({ vm.clear() }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Очистить")
                            }
                        }
                    }
                }
            }
            
            // ROOT Controls
            item {
                Card(colors = CardDefaults.cardColors(
                    containerColor = when (hasRoot) {
                        true -> Color(0xFFE8F5E9)
                        false -> Color(0xFFFFEBEE)
                        null -> Color(0xFFFFF3E0)
                    }
                )) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when (hasRoot) {
                                    true -> Icons.Default.CheckCircle
                                    false -> Icons.Default.Cancel
                                    null -> Icons.Default.Help
                                },
                                null,
                                tint = when (hasRoot) {
                                    true -> Color(0xFF4CAF50)
                                    false -> Color(0xFFF44336)
                                    null -> Color(0xFFFF9800)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (hasRoot) {
                                    true -> "ROOT доступ есть"
                                    false -> "ROOT недоступен"
                                    null -> "Проверка ROOT..."
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        if (hasRoot == true) {
                            Spacer(Modifier.height(12.dp))
                            Text("Управление сетями:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Spacer(Modifier.height(8.dp))
                            
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                AppButton({ vm.disable3G() }, true, Modifier.weight(1f), Color(0xFFFF9800)) {
                                    Text("GSM+LTE", style = MaterialTheme.typography.labelMedium)
                                }
                                AppButton({ vm.disable5G() }, true, Modifier.weight(1f), Color(0xFF2196F3)) {
                                    Text("БЕЗ 5G", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                                AppButton({ vm.forceDisable3G() }, true, Modifier.weight(1f), Color(0xFFF44336)) {
                                    Text("ПРИНУД. БЕЗ 3G", style = MaterialTheme.typography.labelMedium)
                                }
                                AppButton({ vm.enableAllNetworks() }, true, Modifier.weight(1f), Color(0xFF4CAF50)) {
                                    Text("ВСЕ СЕТИ", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton({ vm.getNetworkStatus() }, Modifier.fillMaxWidth()) {
                                Text("Показать статус сети", style = MaterialTheme.typography.labelMedium)
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton({ vm.uploadLogs(ctx) }, Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.CloudUpload, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Загрузить логи на GitHub", style = MaterialTheme.typography.labelMedium)
                            }
                            
                            if (uploadStatus.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(uploadStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color(0xFF2196F3))
                            }
                            
                            if (rootMessage.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(rootMessage, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        } else if (hasRoot == false) {
                            Spacer(Modifier.height(8.dp))
                            Text("Для управления сетями нужен ROOT", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
            
            // Events
            if (events.isNotEmpty()) {
                item { Text("События (${events.size})", fontWeight = FontWeight.Bold) }
                items(events.take(50)) { e ->
                    val bg = when (e.type) { 
                        "ПОТЕРЯ" -> Color(0x33F44336)
                        "ВОССТАНОВЛЕНИЕ" -> Color(0x334CAF50)
                        "СМЕНА ТИПА" -> Color(0x332196F3)
                        else -> Color(0x339C27B0) 
                    }
                    val fg = when (e.type) { 
                        "ПОТЕРЯ" -> Color(0xFFF44336)
                        "ВОССТАНОВЛЕНИЕ" -> Color(0xFF4CAF50)
                        "СМЕНА ТИПА" -> Color(0xFF2196F3)
                        else -> Color(0xFF9C27B0) 
                    }
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
                                Text("Длительность: ${vm.formatDuration(e.durationSeconds)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
            
            // Info
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Как работает", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("• Мониторит состояние сети\n• Записывает потери и восстановления\n• Отслеживает смену типа (LTE↔3G↔2G)\n• СИГНАЛ: 2 мин офлайн → 10 сек звук\n\nROOT управление:\n• GSM+LTE - отключает 3G\n• ПРИНУД. БЕЗ 3G - агрессивное отключение\n• Рекомендуется перезагрузка после смены режима", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    
    if (showExport) {
        AlertDialog({ showExport = false }, title = { Text("Экспорт") }, text = { Text("Экспортировать ${events.size} событий?") },
            confirmButton = {
                TextButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, vm.export())
                    }, "Экспорт"))
                    showExport = false
                }) { Text("Экспорт") }
            },
            dismissButton = { TextButton({ showExport = false }) { Text("Отмена") } }
        )
    }
    
    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("GitHub Token") },
            text = {
                Column {
                    Text("Введите GitHub Personal Access Token с правом 'gist'.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Создать: github.com/settings/tokens/new", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2196F3))
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Token") },
                        placeholder = { Text("ghp_xxxx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton({
                    vm.saveToken(ctx, tokenInput)
                    showTokenDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton({ showTokenDialog = false }) { Text("Отмена") }
            }
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
fun AppButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier, color: Color, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        content = content
    )
}
