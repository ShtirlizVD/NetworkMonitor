package com.networkmonitor

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
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
        val board: String = Build.BOARD,
        val androidVersion: String = Build.VERSION.RELEASE,
        val sdkVersion: Int = Build.VERSION.SDK_INT,
        val buildId: String = Build.ID,
        val buildFingerprint: String = Build.FINGERPRINT,
        val radioVersion: String = Build.getRadioVersion() ?: "unknown",
        val kernelVersion: String = "unknown"
    )
    
    data class NetworkState(
        val networkType: String = "unknown",
        val networkOperator: String = "unknown",
        val networkOperatorName: String = "unknown",
        val simState: String = "unknown",
        val signalStrength: Int = -1,
        val isRoaming: Boolean = false
    )

    fun collectAll(compact: Boolean = false): LogCollectionResult {
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
            logs["ims_state"] = collectImsState()
            logs["modem_state"] = collectModemState()
        } else {
            logs["radio_logcat"] = collectRadioLogcat()
            logs["modem_dmesg"] = collectModemDmesg()
            logs["telephony_registry"] = collectTelephonyRegistry()
            logs["modem_properties"] = collectModemProperties()
            logs["ril_logs"] = collectRilLogs()
            logs["modem_stats"] = collectModemStats()
            logs["network_interfaces"] = collectNetworkInterfaces()
            logs["last_kmsg"] = collectLastKmsg()
            logs["modem_crash_logs"] = collectModemCrashLogs()
            logs["ims_state"] = collectImsState()
            logs["modem_state"] = collectModemState()
            logs["network_settings"] = collectNetworkSettings()
            logs["service_state"] = collectServiceState()
            logs["cell_info"] = collectCellInfo()
        }
        
        val deviceInfo = collectDeviceInfo()
        val networkState = collectCurrentNetworkState()
        
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
    
    private fun collectModemCrashLogs(): String {
        val commands = listOf(
            "ls -la /data/tombstones/ 2>/dev/null || echo 'No tombstones'",
            "ls -la /data/vendor/radio/logs/ 2>/dev/null || echo 'No radio logs'"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectImsState(): String {
        val commands = listOf(
            "dumpsys isms | head -50",
            "dumpsys ims | head -100",
            "getprop | grep -i ims",
            "settings get global ims_volte_enable",
            "settings get global volte_avail_ovr"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectModemState(): String {
        val commands = listOf(
            "cat /sys/class/modem/*/state 2>/dev/null || echo 'No modem state'",
            "cat /sys/class/power_supply/*/status 2>/dev/null | head -5",
            "getprop vendor.modem.state",
            "getprop vendor.ril.state",
            "cat /proc/interrupts | grep -iE 'modem|hsi2c|i2c' | head -10",
            "ls -la /dev/cdc-wdm* /dev/qcqmi* /dev/ttyUSB* 2>/dev/null"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectNetworkSettings(): String {
        val commands = listOf(
            "settings get global preferred_network_mode",
            "settings get global preferred_network_mode0",
            "settings get global nr_dual_connectivity_enabled",
            "settings get global volte_vt_enabled",
            "settings get global wfc_ims_enabled",
            "getprop persist.radio.networkmode",
            "getprop persist.vendor.radio.preferred_network_mode",
            "getprop persist.vendor.radio.wcdma_disabled",
            "getprop persist.vendor.radio.wcdma_supported",
            "getprop persist.vendor.radio.disable_csfb",
            "getprop persist.dbg.ims_volte_enable"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectServiceState(): String {
        val commands = listOf(
            "dumpsys telephony.registry | grep -E 'mServiceState|mDataConnectionState|mSignalStrength'",
            "dumpsys carrier_config | head -50",
            "dumpsys phone | grep -E 'networkType|dataState|serviceState' | head -30"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectCellInfo(): String {
        val commands = listOf(
            "dumpsys telephony.registry | grep -A 20 'mCellInfo'",
            "cat /proc/net/wireless"
        )
        val (_, output) = RootShell.execute(commands)
        return output
    }
    
    private fun collectDeviceInfo(): DeviceInfo {
        val kernelVersion = try {
            val process = Runtime.getRuntime().exec("uname -r")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            reader.readLine() ?: "unknown"
        } catch (e: Exception) { "unknown" }
        
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            androidVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            buildId = Build.ID,
            buildFingerprint = Build.FINGERPRINT,
            radioVersion = Build.getRadioVersion() ?: "unknown",
            kernelVersion = kernelVersion
        )
    }
    
    private fun collectCurrentNetworkState(): NetworkState {
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
        
        sb.appendLine("=== МОНИТОР СЕТИ - ПОЛНЫЙ ЛОГ ===")
        sb.appendLine("Время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine()
        
        sb.appendLine("=== СТАТИСТИКА ===")
        sb.appendLine("Потери сети: ${MonitorService.stats.value.lossCount}")
        sb.appendLine("Восстановления: ${MonitorService.stats.value.restoreCount}")
        sb.appendLine("Всего офлайн: ${formatDuration(MonitorService.stats.value.totalOfflineSeconds)}")
        sb.appendLine("Максимум офлайн: ${formatDuration(MonitorService.stats.value.longestOfflineSeconds)}")
        sb.appendLine()
        
        sb.appendLine("=== ДЕТАЛЬНЫЕ СОБЫТИЯ ===")
        MonitorService.events.value.forEach { e ->
            sb.appendLine("--- ${e.timestamp} ---")
            sb.appendLine("Тип: ${e.type}")
            sb.appendLine("Смена: ${e.previousState} → ${e.newState}")
            if (e.durationSeconds > 0) sb.appendLine("Длительность: ${formatDuration(e.durationSeconds)}")
            sb.appendLine("Сигнал: ${e.signalLevel}/4")
            sb.appendLine("Тип сети: ${e.networkType}")
            sb.appendLine("Оператор: ${e.operatorName}")
            sb.appendLine("SIM: ${e.simState}")
            sb.appendLine("IMS: ${if (e.imsRegistered) "Зарегистрирован" else "Не зарегистрирован"}")
            sb.appendLine("Cell Info: ${e.cellInfo}")
            sb.appendLine()
        }
        sb.appendLine()
        
        sb.appendLine("=== ИНФОРМАЦИЯ ОБ УСТРОЙСТВЕ ===")
        sb.appendLine("Устройство: ${result.deviceInfo.manufacturer} ${result.deviceInfo.model}")
        sb.appendLine("Кодовое имя: ${result.deviceInfo.device}")
        sb.appendLine("Платформа: ${result.deviceInfo.board}")
        sb.appendLine("Android: ${result.deviceInfo.androidVersion} (SDK ${result.deviceInfo.sdkVersion})")
        sb.appendLine("Build ID: ${result.deviceInfo.buildId}")
        sb.appendLine("Fingerprint: ${result.deviceInfo.buildFingerprint}")
        sb.appendLine("Версия радио: ${result.deviceInfo.radioVersion}")
        sb.appendLine("Версия ядра: ${result.deviceInfo.kernelVersion}")
        sb.appendLine()
        
        sb.appendLine("=== ТЕКУЩЕЕ СОСТОЯНИЕ СЕТИ ===")
        sb.appendLine("Тип: ${result.networkState.networkType}")
        sb.appendLine("Оператор: ${result.networkState.networkOperatorName} (${result.networkState.networkOperator})")
        sb.appendLine("SIM: ${result.networkState.simState}")
        sb.appendLine("Сигнал: ${result.networkState.signalStrength}/4")
        sb.appendLine("Роуминг: ${if (result.networkState.isRoaming) "Да" else "Нет"}")
        sb.appendLine()
        
        result.logs.forEach { (name, content) ->
            sb.appendLine("=== $name ===")
            sb.appendLine(content.take(8000))
            sb.appendLine()
        }
        
        if (result.errors.isNotEmpty()) {
            sb.appendLine("=== ОШИБКИ ===")
            result.errors.forEach { sb.appendLine("❌ $it") }
        }
        
        return sb.toString()
    }
    
    private fun formatDuration(s: Long) = if (s < 60) "${s}с" else if (s < 3600) "${s/60}м ${s%60}с" else "${s/3600}ч ${(s%3600)/60}м"
    
    fun saveToFile(result: LogCollectionResult, directory: File): File {
        val filename = "modem_log_${result.timestamp}.txt"
        val file = File(directory, filename)
        file.writeText(formatForGist(result))
        return file
    }
}
