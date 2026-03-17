package com.networkmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сборщик логов модема и радио-интерфейса
 */
class LogCollector(private val context: Context) {
    
    private val tag = "LogCollector"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    data class LogCollectionResult(
        val timestamp: String,
        val logs: Map<String, String>,
        val deviceInfo: DeviceInfo,
        val networkState: NetworkState,
        val errors: List<String> = emptyList()
    )
    
    data class DeviceInfo(
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val device: String = Build.DEVICE,
        val androidVersion: String = Build.VERSION.RELEASE,
        val sdkVersion: Int = Build.VERSION.SDK_INT,
        val radioVersion: String = Build.getRadioVersion() ?: "unknown"
    )
    
    data class NetworkState(
        val networkType: String = "unknown",
        val networkOperator: String = "unknown",
        val networkOperatorName: String = "unknown",
        val simState: String = "unknown",
        val signalStrength: Int = -1,
        val isRoaming: Boolean = false
    )

    suspend fun collectAll(compact: Boolean = false): LogCollectionResult {
        val timestamp = dateFormat.format(Date())
        val errors = mutableListOf<String>()
        val logs = mutableMapOf<String, String>()
        
        if (!RootShell.checkRoot()) {
            errors.add("Root access not available")
        }
        
        if (compact) {
            logs["radio_logcat"] = collectRadioLogcatCompact()
            logs["telephony_registry"] = collectTelephonyRegistry()
            logs["modem_properties"] = collectModemProperties()
            logs["modem_dmesg"] = collectModemDmesgCompact()
        } else {
            logs["radio_logcat"] = collectRadioLogcat()
            logs["modem_dmesg"] = collectModemDmesg()
            logs["telephony_registry"] = collectTelephonyRegistry()
            logs["modem_properties"] = collectModemProperties()
            logs["ril_logs"] = collectRilLogs()
            logs["modem_stats"] = collectModemStats()
            logs["network_interfaces"] = collectNetworkInterfaces()
            logs["last_kmsg"] = collectLastKmsg()
            logs["modem_crash_logs"] = collectModemCrashLogsCompact()
        }
        
        val deviceInfo = collectDeviceInfo()
        val networkState = collectNetworkState()
        
        return LogCollectionResult(
            timestamp = timestamp,
            logs = logs.filter { it.value.isNotEmpty() },
            deviceInfo = deviceInfo,
            networkState = networkState,
            errors = errors
        )
    }
    
    private fun collectRadioLogcat(): String {
        val (_, output) = RootShell.execute("logcat -b radio -d -v time | tail -500")
        return output
    }
    
    private fun collectRadioLogcatCompact(): String {
        val (_, output) = RootShell.execute("logcat -b radio -d -v time | tail -200")
        return output
    }
    
    private fun collectModemDmesg(): String {
        val (_, output) = RootShell.execute("dmesg | grep -iE 'modem|radio|rild|qmi|imei|lte|5g|4g|3g|gsm|wcdma|nr|nsa|sa|exynos|s5100|s5300|hsi2c' | tail -100")
        return output
    }
    
    private fun collectModemDmesgCompact(): String {
        val (_, output) = RootShell.execute("dmesg | grep -iE 'modem|radio|rild|qmi|imei|lte|5g|4g|3g|gsm|wcdma|nr|nsa|sa|exynos|s5100|s5300|hsi2c' | tail -50")
        return output
    }
    
    private fun collectTelephonyRegistry(): String {
        val (_, output) = RootShell.execute("dumpsys telephony.registry")
        return output
    }
    
    private fun collectModemProperties(): String {
        val commands = listOf(
            "getprop | grep -iE 'gsm|ril|radio|modem|telephony|exynos|vendor.ril'",
            "getprop ro.hardware",
            "getprop ro.board.platform",
            "getprop gsm.version.ril-impl",
            "getprop gsm.network.type",
            "getprop vendor.modem.crash_count"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectRilLogs(): String {
        val commands = listOf(
            "ls -la /data/vendor/radio/",
            "cat /data/vendor/radio/rild.log 2>/dev/null || echo 'No rild.log'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectModemStats(): String {
        val commands = listOf(
            "cat /proc/net/arp",
            "ls -la /dev/cdc-wdm* 2>/dev/null || echo 'No cdc-wdm'",
            "ls -la /dev/qcqmi* 2>/dev/null || echo 'No qcqmi'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectNetworkInterfaces(): String {
        val (_, output) = RootShell.execute("ip link show")
        return output
    }
    
    private fun collectLastKmsg(): String {
        val commands = listOf(
            "cat /proc/last_kmsg 2>/dev/null || echo 'No last_kmsg'",
            "ls -la /sys/fs/pstore/ 2>/dev/null || echo 'No pstore'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectModemCrashLogsCompact(): String {
        val commands = listOf(
            "ls -la /data/tombstones/ 2>/dev/null || echo 'No tombstones'",
            "ls -la /data/vendor/radio/logs/ 2>/dev/null || echo 'No radio logs'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectDeviceInfo(): DeviceInfo = DeviceInfo()
    
    private fun collectNetworkState(): NetworkState {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        val networkType = when (telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G/LTE"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown (${telephonyManager.dataNetworkType})"
        }
        
        val simStateStr = when (telephonyManager.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_READY -> "READY"
            else -> "UNKNOWN"
        }
        
        return NetworkState(
            networkType = networkType,
            networkOperator = telephonyManager.networkOperator ?: "unknown",
            networkOperatorName = telephonyManager.networkOperatorName ?: "unknown",
            simState = simStateStr,
            signalStrength = telephonyManager.signalStrength?.level ?: -1,
            isRoaming = telephonyManager.isNetworkRoaming
        )
    }
    
    fun formatForGist(result: LogCollectionResult): String {
        val sb = StringBuilder()
        
        sb.appendLine("=== MONITORING EVENTS ===")
        sb.appendLine("Total events: ${MonitorService.events.value.size}")
        sb.appendLine("Network loss count: ${MonitorService.stats.value.lossCount}")
        sb.appendLine("Last network loss: ${MonitorService.stats.value.longestOfflineSeconds}s")
        sb.appendLine()
        
        MonitorService.events.value.take(20).forEach { e ->
            sb.appendLine("[${e.timestamp}] ${e.type}: ${e.previousState} → ${e.newState}")
        }
        sb.appendLine()
        
        sb.appendLine("=== DEVICE INFO ===")
        sb.appendLine("Device: ${result.deviceInfo.manufacturer} ${result.deviceInfo.model}")
        sb.appendLine("Android: ${result.deviceInfo.androidVersion}")
        sb.appendLine("Radio: ${result.deviceInfo.radioVersion}")
        sb.appendLine()
        
        sb.appendLine("=== NETWORK STATE ===")
        sb.appendLine("Type: ${result.networkState.networkType}")
        sb.appendLine("Operator: ${result.networkState.networkOperatorName}")
        sb.appendLine("Signal: ${result.networkState.signalStrength}/4")
        sb.appendLine()
        
        result.logs.forEach { (name, content) ->
            sb.appendLine("=== $name ===")
            sb.appendLine(content.take(5000))
            sb.appendLine()
        }
        
        if (result.errors.isNotEmpty()) {
            sb.appendLine("[STDERR]")
            result.errors.forEach { sb.appendLine(it) }
        }
        
        return sb.toString()
    }
    
    fun saveToFile(result: LogCollectionResult, directory: File): File {
        val filename = "modem_log_${result.timestamp}.txt"
        val file = File(directory, filename)
        file.writeText(formatForGist(result))
        return file
    }
}
