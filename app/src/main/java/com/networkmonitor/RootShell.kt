package com.networkmonitor

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object RootShell {
    private const val TAG = "RootShell"
    private var hasRoot: Boolean? = null

    fun checkRoot(): Boolean {
        if (hasRoot != null) return hasRoot!!

        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = process.outputStream
            outputStream.write("id\n".toByteArray())
            outputStream.flush()
            outputStream.write("exit\n".toByteArray())
            outputStream.flush()
            outputStream.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            reader.close()

            process.waitFor()

            hasRoot = output.contains("uid=0") || process.exitValue() == 0
            Log.d(TAG, "Root check: $hasRoot")
            return hasRoot!!
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            hasRoot = false
            return false
        }
    }

    /**
     * Отключает 3G (WCDMA), оставляет GSM + LTE
     * Использует Samsung-специфичные свойства для блокировки 3G на уровне загрузки модема
     */
    fun disable3G(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "Нет root-доступа")

        val commands = listOf(
            // Стандартные Android настройки (режим 9 = GSM+LTE only)
            "settings put global preferred_network_mode 9",
            "settings put global preferred_network_mode0 9",
            "settings put global preferred_network_mode1 9",
            "settings put global mobile_data 1",

            // Samsung-специфичные свойства (читаются RIL при загрузке модема)
            "setprop persist.radio.preferred_network_mode 9",
            "setprop persist.vendor.radio.network_mode 9",
            "setprop persist.vendor.ril.network_type 9",
            "setprop persist.telephony.default_network 9",

            // Блокируем WCDMA частоты через RIL
            "setprop persist.radio.wcdma.disabled 1",
            "setprop persist.vendor.radio.wcdma_disable 1",

            // Принудительный LTE
            "setprop persist.radio.lte_enabled 1",
            "setprop persist.vendor.radio.lte_only 0",

            // Режим только 2G/4G (без 3G) для Samsung Exynos
            "setprop persist.vendor.radio.force_lte 1",

            // Перезапуск RIL для применения настроек
            "stop ril-daemon",
            "sleep 1",
            "start ril-daemon",

            // Перезагрузка модема для применения всех изменений
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            "sleep 3",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        )

        val (exitCode, output) = execute(commands)
        return if (exitCode == 0) {
            Pair(true, "3G отключён (GSM+LTE only)\nТребуется перезагрузка для полного применения.\n$output")
        } else {
            Pair(false, "Ошибка: $output")
        }
    }

    /**
     * Более агрессивное отключение 3G через перезапись конфигурации
     * Вызывать если обычный disable3G не помогает
     */
    fun forceDisable3G(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "Нет root-доступа")

        val commands = listOf(
            // Сначала применяем обычные настройки
            "settings put global preferred_network_mode 9",
            "settings put global preferred_network_mode0 9",
            "setprop persist.radio.preferred_network_mode 9",
            "setprop persist.vendor.radio.network_mode 9",

            // Агрессивная блокировка WCDMA
            "setprop persist.radio.wcdma.disabled 1",
            "setprop persist.vendor.radio.wcdma_disable 1",
            "setprop persist.radio.hspa_disabled 1",

            // Отключаем UMTS/HSPA+
            "setprop persist.vendor.radio.umts_disable 1",
            "setprop persist.radio.umts.disabled 1",

            // Принудительно включаем только GSM и LTE
            "setprop gsm.network.type GSM,LTE",
            "setprop persist.radio.network.type GSM,LTE",

            // Перезапуск telephony service
            "cmd phone reset",

            // Перезагрузка модема через cbd (Samsung modem daemon)
            "setprop vendor.cbd.modem_reset 1",

            // Авиарежим для перезапуска радиоинтерфейса
            "settings put global airplane_mode_on 1",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            "sleep 5",
            "settings put global airplane_mode_on 0",
            "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        )

        val (exitCode, output) = execute(commands)
        return if (exitCode == 0) {
            Pair(true, "Принудительное отключение 3G выполнено\nРекомендуется перезагрузка устройства.\n$output")
        } else {
            Pair(false, "Ошибка: $output")
        }
    }

    fun disable5G(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "Нет root-доступа")

        val commands = listOf(
            "settings put global nr_dual_connectivity_enabled 0",
            "settings put global nr_state_tracking_enabled 0",
            "settings put global vo5g_enabled 0",
            "settings put global vonr_enabled 0",
            "setprop persist.radio.is_vonr_enabled_0 false",
            "setprop persist.vendor.radio.nsac.mode 0"
        )

        val (exitCode, output) = execute(commands)
        return if (exitCode == 0) {
            Pair(true, "5G отключён\n$output")
        } else {
            Pair(false, "Ошибка: $output")
        }
    }

    fun enableAllNetworks(): Pair<Boolean, String> {
        if (!checkRoot()) return Pair(false, "Нет root-доступа")

        val commands = listOf(
            "settings put global preferred_network_mode 12",
            "settings put global preferred_network_mode0 12",
            "settings put global preferred_network_mode1 12",
            "setprop persist.radio.preferred_network_mode 12",
            "settings put global nr_dual_connectivity_enabled 1",
            "settings put global nr_state_tracking_enabled 1"
        )

        val (exitCode, output) = execute(commands)
        return if (exitCode == 0) {
            Pair(true, "Все сети включены\n$output")
        } else {
            Pair(false, "Ошибка: $output")
        }
    }

    fun getNetworkStatus(): String {
        val commands = listOf(
            "settings get global preferred_network_mode",
            "settings get global nr_dual_connectivity_enabled",
            "getprop persist.radio.preferred_network_mode"
        )
        val (_, output) = execute(commands)
        return output
    }

    fun execute(command: String): Pair<Int, String> = execute(listOf(command))

    fun execute(commands: List<String>): Pair<Int, String> {
        try {
            val process = Runtime.getRuntime().exec("su")
            val outputStream = process.outputStream
            val writer = outputStream.writer()

            commands.forEach { cmd ->
                writer.write("$cmd\n")
            }
            writer.write("exit\n")
            writer.flush()
            writer.close()
            outputStream.close()

            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            val errors = StringBuilder()

            var line: String? = outputReader.readLine()
            while (line != null) {
                output.append(line).append("\n")
                line = outputReader.readLine()
            }
            outputReader.close()

            line = errorReader.readLine()
            while (line != null) {
                errors.append(line).append("\n")
                line = errorReader.readLine()
            }
            errorReader.close()

            val exitCode = process.waitFor()

            val combinedOutput = if (errors.isNotEmpty()) {
                "${output}\n[STDERR]\n$errors"
            } else {
                output.toString()
            }

            return Pair(exitCode, combinedOutput)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            return Pair(-1, "Exception: ${e.message}")
        }
    }
}
