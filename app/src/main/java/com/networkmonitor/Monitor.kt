package com.networkmonitor

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.networkmonitor.network.GitHubUploader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

// ============= DATA =============

data class NetworkState(
    val isInService: Boolean = false,
    val serviceStateName: String = "Неизвестно",
    val networkTypeName: String = "Неизвестно",
    val operatorName: String = "",
    val signalLevel: Int = 0,
    val offlineSeconds: Long = 0
)

data class NetworkEvent(
    val timestamp: String,
    val type: String,
    val previousState: String,
    val newState: String,
    val durationSeconds: Long = 0
)

data class NetworkStats(
    val lossCount: Int = 0,
    val restoreCount: Int = 0,
    val totalOfflineSeconds: Long = 0,
    val longestOfflineSeconds: Long = 0
)

// ============= SERVICE =============

class MonitorService : Service() {
    
    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_STOP_ALARM = "STOP_ALARM"
        
        val isRunning = MutableStateFlow(false)
        val state = MutableStateFlow(NetworkState())
        val events = MutableStateFlow<List<NetworkEvent>>(emptyList())
        val stats = MutableStateFlow(NetworkStats())
        val isAlarmActive = MutableStateFlow(false)
        
        var alarmEnabled = true
        const val ALARM_AFTER_SECONDS = 120L  // 2 минуты
        const val ALARM_DURATION_SECONDS = 10L
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tm: TelephonyManager? = null
    private var vibrator: Vibrator? = null
    private var player: MediaPlayer? = null
    
    private var lastState = -1
    private var lastNetType = -1
    private var wasOffline = false  // Был ли ранее офлайн
    private var offlineStart = 0L
    private var totalOffline = 0L
    private var longestOffline = 0L
    private var lossCount = 0
    private var restoreCount = 0
    private var alarmTriggered = false
    private var alarmJob: Job? = null
    
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    override fun onCreate() {
        super.onCreate()
        tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        
        // Notification channel
        val channel = NotificationChannel("monitor", "Монитор сети", NotificationManager.IMPORTANCE_LOW)
        val alarmChannel = NotificationChannel("alarm", "Сигнал тревоги", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).run {
            createNotificationChannel(channel)
            createNotificationChannel(alarmChannel)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            ACTION_STOP_ALARM -> stopAlarm()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?) = null
    
    override fun onDestroy() {
        stop()
        scope.cancel()
        super.onDestroy()
    }
    
    private fun start() {
        if (isRunning.value) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }
        isRunning.value = true
        lastState = -1
        lastNetType = -1
        wasOffline = false
        alarmTriggered = false
        
        scope.launch {
            while (isRunning.value) {
                update()
                updateNotification()
                checkAlarm()
                delay(1000)
            }
        }
    }
    
    private fun stop() {
        isRunning.value = false
        stopAlarm()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun update() {
        try {
            val serviceState = tm?.serviceState
            val signal = tm?.signalStrength
            val netType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) tm?.dataNetworkType else tm?.networkType
            
            val stateValue = serviceState?.state ?: ServiceState.STATE_OUT_OF_SERVICE
            val inService = stateValue == ServiceState.STATE_IN_SERVICE
            
            // Offline time
            val offline = if (offlineStart > 0 && !inService) {
                (System.currentTimeMillis() - offlineStart) / 1000
            } else 0L
            
            // State change
            if (lastState != -1 && lastState != stateValue) {
                onStateChange(lastState, stateValue, inService)
            }
            lastState = stateValue
            
            // Network type change
            val netTypeValue = netType ?: 0
            if (lastNetType != -1 && lastNetType != netTypeValue && inService) {
                onNetTypeChange(lastNetType, netTypeValue)
            }
            lastNetType = netTypeValue
            
            state.value = NetworkState(
                isInService = inService,
                serviceStateName = getStateName(stateValue),
                networkTypeName = getNetName(netType ?: 0),
                operatorName = tm?.networkOperatorName ?: "",
                signalLevel = signal?.level ?: 0,
                offlineSeconds = offline
            )
        } catch (e: Exception) {
            Log.e("Monitor", "Update error", e)
        }
    }
    
    private fun onStateChange(old: Int, new: Int, inService: Boolean) {
        val oldOffline = !isStateOnline(old)
        val newOffline = !isStateOnline(new)
        
        when {
            // Переход в офлайн
            !newOffline && oldOffline -> {
                // Восстановление сети
                val dur = if (offlineStart > 0) (System.currentTimeMillis() - offlineStart) / 1000 else 0
                totalOffline += dur
                longestOffline = maxOf(longestOffline, dur)
                restoreCount++
                addEvent("ВОССТАНОВЛЕНИЕ", getStateName(old), getStateName(new), dur)
                offlineStart = 0
                wasOffline = false
                alarmTriggered = false
                stopAlarm()
            }
            // Переход в онлайн с офлайна
            newOffline && !oldOffline -> {
                // Потеря сети
                offlineStart = System.currentTimeMillis()
                lossCount++
                addEvent("ПОТЕРЯ", getStateName(old), getStateName(new), 0)
                wasOffline = true
                alarmTriggered = false
            }
            // Смена состояния в офлайне (например POWER_OFF -> OUT_OF_SERVICE)
            newOffline && oldOffline -> {
                // Просто фиксируем смену состояния
                addEvent("СМЕНА", getStateName(old), getStateName(new), 0)
            }
        }
        
        stats.value = NetworkStats(lossCount, restoreCount, totalOffline, longestOffline)
    }
    
    private fun isStateOnline(state: Int): Boolean {
        return state == ServiceState.STATE_IN_SERVICE
    }
    
    private fun onNetTypeChange(old: Int, new: Int) {
        addEvent("СМЕНА ТИПА", getNetName(old), getNetName(new), 0)
    }
    
    private fun addEvent(type: String, prev: String, next: String, dur: Long) {
        val list = events.value.toMutableList()
        list.add(0, NetworkEvent(timeFormat.format(Date()), type, prev, next, dur))
        events.value = list.take(100)
    }
    
    private fun checkAlarm() {
        val s = state.value
        
        if (alarmEnabled && !s.isInService && s.offlineSeconds >= ALARM_AFTER_SECONDS && !alarmTriggered) {
            alarmTriggered = true
            triggerAlarm()
        }
        
        if (s.isInService && isAlarmActive.value) {
            stopAlarm()
        }
    }
    
    private fun triggerAlarm() {
        if (!alarmEnabled) return
        
        isAlarmActive.value = true
        showAlarmNotification()
        
        alarmJob = scope.launch {
            val end = System.currentTimeMillis() + ALARM_DURATION_SECONDS * 1000
            
            while (System.currentTimeMillis() < end && isAlarmActive.value) {
                vibrate()
                playSound()
                delay(1000)
            }
            
            stopAlarm()
        }
    }
    
    private fun stopAlarm() {
        isAlarmActive.value = false
        alarmJob?.cancel()
        alarmJob = null
        
        player?.stop()
        player?.release()
        player = null
        vibrator?.cancel()
        
        getSystemService(NotificationManager::class.java).cancel(2)
    }
    
    private fun vibrate() {
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else @Suppress("DEPRECATION") it.vibrate(500)
        }
    }
    
    private fun playSound() {
        try {
            if (player == null) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    setDataSource(this@MonitorService, uri)
                    prepare()
                }
            }
            player?.start()
        } catch (e: Exception) { Log.e("Monitor", "Sound error", e) }
    }
    
    private fun showAlarmNotification() {
        val i = Intent(this, MonitorService::class.java).setAction(ACTION_STOP_ALARM)
        val pi = PendingIntent.getService(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
        
        val n = NotificationCompat.Builder(this, "alarm")
            .setContentTitle("⚠️ НЕТ СЕТИ!")
            .setContentText("Без сети ${formatTime(state.value.offlineSeconds)}")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(0, "Стоп", pi)
            .build()
        
        getSystemService(NotificationManager::class.java).notify(2, n)
    }
    
    private fun createNotification(): Notification {
        val s = state.value
        val status = if (s.isInService) "Онлайн: ${s.networkTypeName}" else "ОФФЛАЙН"
        val color = if (s.isInService) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        
        val offlineText = if (!s.isInService && s.offlineSeconds > 0) " | ${formatTime(s.offlineSeconds)}" else ""
        
        return NotificationCompat.Builder(this, "monitor")
            .setContentTitle("$status$offlineText")
            .setContentText("${s.operatorName} | Сигнал: ${s.signalLevel}/4")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setColor(color)
            .build()
    }
    
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, createNotification())
    }
    
    private fun getStateName(s: Int?) = when (s) {
        ServiceState.STATE_IN_SERVICE -> "В СЕТИ"
        ServiceState.STATE_OUT_OF_SERVICE -> "НЕТ СЕТИ"
        ServiceState.STATE_EMERGENCY_ONLY -> "ТОЛЬКО ЭКСТРЕН"
        ServiceState.STATE_POWER_OFF -> "РАДИО ВЫКЛ"
        else -> "НЕИЗВЕСТНО"
    }
    
    private fun getNetName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "1X"
        TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD-SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "Wi-Fi"
        else -> "Тип$t"
    }
    
    private fun formatTime(s: Long) = if (s < 60) "${s}с" else if (s < 3600) "${s/60}м ${s%60}с" else "${s/3600}ч ${(s%3600)/60}м"
}

// ============= VIEW MODEL =============

class MainViewModel : ViewModel() {
    val isRunning = MonitorService.isRunning
    val state = MonitorService.state
    val events = MonitorService.events
    val stats = MonitorService.stats
    val isAlarmActive = MonitorService.isAlarmActive
    
    private val _hasPerms = MutableStateFlow(false)
    val hasPerms: StateFlow<Boolean> = _hasPerms
    
    private val _hasRoot = MutableStateFlow<Boolean?>(null)
    val hasRoot: StateFlow<Boolean?> = _hasRoot
    
    private val _rootMessage = MutableStateFlow("")
    val rootMessage: StateFlow<String> = _rootMessage
    
    private val _uploadStatus = MutableStateFlow("")
    val uploadStatus: StateFlow<String> = _uploadStatus
    
    val alarmEnabled: Boolean get() = MonitorService.alarmEnabled
    
    fun toggleAlarm() { MonitorService.alarmEnabled = !MonitorService.alarmEnabled }
    
    fun checkPerms(ctx: Context): Boolean {
        val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val ok = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        _hasPerms.value = ok
        return ok
    }
    
    fun checkRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = RootShell.checkRoot()
            _hasRoot.value = result
        }
    }
    
    fun disable3G() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootMessage.value = "Отключаем 3G..."
            val (success, msg) = RootShell.disable3G()
            _rootMessage.value = if (success) "✅ $msg" else "❌ $msg"
        }
    }

    fun forceDisable3G() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootMessage.value = "Принудительно отключаем 3G..."
            val (success, msg) = RootShell.forceDisable3G()
            _rootMessage.value = if (success) "✅ $msg" else "❌ $msg"
        }
    }

    fun disable5G() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootMessage.value = "Отключаем 5G..."
            val (success, msg) = RootShell.disable5G()
            _rootMessage.value = if (success) "✅ $msg" else "❌ $msg"
        }
    }
    
    fun enableAllNetworks() {
        viewModelScope.launch(Dispatchers.IO) {
            _rootMessage.value = "Включаем все сети..."
            val (success, msg) = RootShell.enableAllNetworks()
            _rootMessage.value = if (success) "✅ $msg" else "❌ $msg"
        }
    }
    
    fun getNetworkStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = RootShell.getNetworkStatus()
            _rootMessage.value = "Статус сети:\n$status"
        }
    }
    
    fun uploadLogs(ctx: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _uploadStatus.value = "Собираем логи..."
            try {
                val collector = LogCollector(ctx)
                val result = collector.collectAll(compact = true)
                val content = collector.formatForGist(result)
                
                _uploadStatus.value = "Загружаем на GitHub..."
                val uploader = GitHubUploader(ctx)
                val url = uploader.uploadToGist(
                    filename = "modem_log_${result.timestamp}.txt",
                    content = content,
                    description = "ModemDoctor Log ${result.timestamp}"
                )
                
                if (url != null) {
                    _uploadStatus.value = "✅ Загружено: $url"
                    Log.d("MainViewModel", "Log uploaded: $url")
                } else {
                    _uploadStatus.value = "❌ Ошибка загрузки"
                }
            } catch (e: Exception) {
                _uploadStatus.value = "❌ Ошибка: ${e.message}"
                Log.e("MainViewModel", "Upload error", e)
            }
        }
    }
    
    fun start(ctx: Context) {
        if (!checkPerms(ctx)) return
        ctx.startForegroundService(Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_START))
    }
    
    fun stop(ctx: Context) {
        ctx.startService(Intent(ctx, MonitorService::class.java).setAction(MonitorService.ACTION_STOP))
    }
    
    fun clear() {
        MonitorService.events.value = emptyList()
        MonitorService.stats.value = NetworkStats()
    }
    
    fun formatDuration(s: Long) = if (s < 60) "${s}с" else if (s < 3600) "${s/60}м ${s%60}с" else "${s/3600}ч ${(s%3600)/60}м"
    
    fun export(): String {
        return buildString {
            appendLine("=== МОНИТОР СЕТИ ===")
            appendLine("Дата: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("Статистика: ${stats.value.lossCount} потерь, ${stats.value.restoreCount} восстановлений")
            appendLine("Всего офлайн: ${formatDuration(stats.value.totalOfflineSeconds)}")
            appendLine("Максимум офлайн: ${formatDuration(stats.value.longestOfflineSeconds)}")
            appendLine()
            appendLine("События:")
            events.value.forEach { e ->
                val dur = if (e.durationSeconds > 0) " (${formatDuration(e.durationSeconds)})" else ""
                appendLine("[${e.timestamp}] ${e.type}: ${e.previousState} → ${e.newState}$dur")
            }
        }
    }
}
