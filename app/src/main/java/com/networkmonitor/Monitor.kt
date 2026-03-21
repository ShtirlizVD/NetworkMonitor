package com.networkmonitor

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.telephony.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

// ============= DATA =============

data class NetworkState(
    val isInService: Boolean = false,
    val serviceStateName: String = "Unknown",
    val networkTypeName: String = "Unknown",
    val operatorName: String = "",
    val signalLevel: Int = 0,
    val isImsRegistered: Boolean = false,
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
        val channel = NotificationChannel("monitor", "Network Monitor", NotificationManager.IMPORTANCE_LOW)
        val alarmChannel = NotificationChannel("alarm", "Network Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
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
        
        startForeground(1, createNotification())
        isRunning.value = true
        lastState = -1
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
                onStateChange(lastState, stateValue)
            }
            lastState = stateValue
            
            state.value = NetworkState(
                isInService = inService,
                serviceStateName = getStateName(stateValue),
                networkTypeName = getNetName(netType ?: 0),
                operatorName = tm?.networkOperatorName ?: "",
                signalLevel = signal?.level ?: 0,
                isImsRegistered = checkIms(),
                offlineSeconds = offline
            )
        } catch (e: Exception) {
            Log.e("Monitor", "Update error", e)
        }
    }
    
    private fun onStateChange(old: Int, new: Int) {
        val ts = timeFormat.format(Date())
        
        when {
            // Offline
            new == ServiceState.STATE_OUT_OF_SERVICE || new == ServiceState.STATE_EMERGENCY_ONLY -> {
                offlineStart = System.currentTimeMillis()
                lossCount++
                addEvent("LOSS", getStateName(old), getStateName(new), 0)
                alarmTriggered = false
            }
            // Online
            old == ServiceState.STATE_OUT_OF_SERVICE && new == ServiceState.STATE_IN_SERVICE -> {
                val dur = if (offlineStart > 0) (System.currentTimeMillis() - offlineStart) / 1000 else 0
                totalOffline += dur
                longestOffline = maxOf(longestOffline, dur)
                restoreCount++
                addEvent("RESTORE", getStateName(old), getStateName(new), dur)
                offlineStart = 0
                alarmTriggered = false
                stopAlarm()
            }
        }
        
        stats.value = NetworkStats(lossCount, restoreCount, totalOffline, longestOffline)
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
            .setContentTitle("⚠️ NETWORK OFFLINE!")
            .setContentText("No network for ${formatTime(state.value.offlineSeconds)}")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .addAction(0, "Stop", pi)
            .build()
        
        getSystemService(NotificationManager::class.java).notify(2, n)
    }
    
    private fun createNotification(): Notification {
        val s = state.value
        val status = if (s.isInService) "Online: ${s.networkTypeName}" else "OFFLINE"
        val color = if (s.isInService) 0xFF4CAF50.toInt() else 0xFFF44336.toInt()
        
        val offlineText = if (!s.isInService && s.offlineSeconds > 0) " | ${formatTime(s.offlineSeconds)}" else ""
        
        return NotificationCompat.Builder(this, "monitor")
            .setContentTitle("$status$offlineText")
            .setContentText("${s.operatorName} | Signal: ${s.signalLevel}/4")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setColor(color)
            .build()
    }
    
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(1, createNotification())
    }
    
    private fun checkIms(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            getSystemService(android.telephony.ims.ImsManager::class.java)?.isImsRegistered(0) ?: false
        else false
    } catch (e: Exception) { false }
    
    private fun getStateName(s: Int?) = when (s) {
        ServiceState.STATE_IN_SERVICE -> "IN_SERVICE"
        ServiceState.STATE_OUT_OF_SERVICE -> "OUT_OF_SERVICE"
        ServiceState.STATE_EMERGENCY_ONLY -> "EMERGENCY_ONLY"
        ServiceState.STATE_POWER_OFF -> "POWER_OFF"
        else -> "UNKNOWN"
    }
    
    private fun getNetName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> "Type$t"
    }
    
    private fun formatTime(s: Long) = if (s < 60) "${s}s" else if (s < 3600) "${s/60}m ${s%60}s" else "${s/3600}h ${(s%3600)/60}m"
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
    
    val alarmEnabled: Boolean get() = MonitorService.alarmEnabled
    
    fun toggleAlarm() { MonitorService.alarmEnabled = !MonitorService.alarmEnabled }
    
    fun checkPerms(ctx: Context): Boolean {
        val perms = mutableListOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val ok = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        _hasPerms.value = ok
        return ok
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
    
    fun formatDuration(s: Long) = if (s < 60) "${s}s" else if (s < 3600) "${s/60}m ${s%60}s" else "${s/3600}h ${(s%3600)/60}m"
    
    fun export(): String {
        return buildString {
            appendLine("=== NETWORK MONITOR ===")
            appendLine("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine()
            appendLine("Stats: ${stats.value.lossCount} losses, ${stats.value.restoreCount} restores")
            appendLine("Total offline: ${formatDuration(stats.value.totalOfflineSeconds)}")
            appendLine("Longest offline: ${formatDuration(stats.value.longestOfflineSeconds)}")
            appendLine()
            appendLine("Events:")
            events.value.forEach { e ->
                appendLine("[${e.timestamp}] ${e.type}: ${e.previousState} -> ${e.newState} (${formatDuration(e.durationSeconds)})")
            }
        }
    }
}
