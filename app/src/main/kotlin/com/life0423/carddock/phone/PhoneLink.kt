package com.life0423.carddock.phone

import android.content.Context
import com.life0423.carddock.platform.mika.DockMikaHandoff
import com.wangzan101.mika.core.adb.AdbConnection
import com.wangzan101.mika.core.adb.AdbConnectionException
import com.wangzan101.mika.core.adb.AdbPairing
import com.wangzan101.mika.core.adb.AdbPairingException
import com.wangzan101.mika.core.adb.AdbServiceTypes
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 配对仅使用用户在系统窗口确认的本机端口和一次性码。连接后只启动本包已有的助手。 */
internal object PhoneLink {
    private class ConnectionIssue(message: String) : IOException(message)
    private val active = MutableStateFlow(false)
    val busy = active.asStateFlow()
    private val feedback = MutableStateFlow("")
    val message = feedback.asStateFlow()
    fun prefs(c: Context) = c.getSharedPreferences("phone_link", Context.MODE_PRIVATE)

    suspend fun pair(c: Context, port: Int?, code: String): Boolean = operation {
        val target = port ?: NsdAdbServiceFinder(c).findPorts(AdbServiceTypes.PAIRING, 2400).singleOrNull()
            ?: throw ConnectionIssue("未能确定本机配对端口。保持系统配对窗口打开，再回复「配对端口 六位码」。")
        AdbPairing(AndroidAdb.tls(AndroidAdb.pairingIdentity(c))).pair("127.0.0.1", target, code, 6000)
        prefs(c).edit().putBoolean("paired", true).putBoolean("connectOnReturn", true)
            .putLong("pairedAt", System.currentTimeMillis()).putBoolean("discoveredPairPort", port == null).apply()
        "本机配对成功，返回卡坞会自动连接助手。"
    }

    suspend fun connect(c: Context, manualPort: Int?) = operation {
        val ports = manualPort?.let { listOf(it) }
            ?: NsdAdbServiceFinder(c).findPorts(AdbServiceTypes.CONNECT, 3500)
        if (ports.isEmpty()) throw ConnectionIssue("未找到本机连接端口。请在开发者选项开启「无线调试」并允许当前 Wi-Fi。若已开启，可手动填写连接端口。")
        val tls = AndroidAdb.tls(AndroidAdb.identity(c))
        var lastError: IOException? = null
        for (port in ports) {
            val connection = try {
                AdbConnection.open("127.0.0.1", port, tls, 5000)
            } catch (e: IOException) {
                lastError = e
                continue
            }
            connection.use {
                // exec 流关闭时 adbd 会回收同组进程。沿用上游的独立会话及等待交接做法；
                // 助手绑定本包的进程令牌，卡坞退出后仍由 Binder 死亡通知结束助手。
                val command = "CLASSPATH='${c.applicationInfo.sourceDir}' setsid app_process /system/bin " +
                    "--nice-name=carddock-mika com.life0423.carddock.platform.mika.DockMikaHelper " +
                    "'${c.packageName}' </dev/null >/dev/null 2>&1 & read _"
                it.exec(command).use {
                    if (withTimeoutOrNull(8000) { DockMikaHandoff.display.filterNotNull().first() } == null)
                        throw ConnectionIssue("本机连接成功，助手尚未接入。回到卡坞重试；仍失败请核对系统版本。")
                }
            }
            prefs(c).edit().putLong("connectedAt", System.currentTimeMillis()).apply()
            return@operation ""
        }
        throw lastError ?: IOException("no connection")
    }

    suspend fun forget(c: Context) = operation {
        DockMikaHandoff.display.value?.stopHelper()
        AndroidAdb.forget(c)
        prefs(c).edit().clear().apply()
        "已清除卡坞本机配对。可在系统无线调试的已配对设备中移除 CardDock。"
    }

    /** 通知回复和界面操作共用一个执行入口，避免同时更换正在使用的配对身份。 */
    private suspend fun operation(block: suspend () -> String): Boolean = withContext(Dispatchers.IO) {
        if (!active.compareAndSet(false, true)) return@withContext false
        try {
            feedback.value = block()
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            feedback.value = when (e) {
                is ConnectionIssue -> e.message.orEmpty()
                is AdbPairingException -> "配对未完成。请打开新的系统配对窗口，重新填写新的六位码。"
                is AdbConnectionException -> when (e.reason) {
                    AdbConnectionException.Reason.UNAUTHORIZED -> "系统未接受连接。请在无线调试中核对已配对设备，若已移除卡坞，请重新配对。"
                    else -> "未能连接。请在开发者选项开启「无线调试」并允许当前 Wi-Fi，再回卡坞重试。"
                }
                else -> "操作未完成。请检查无线调试和本机授权后重试。"
            }
            false
        } finally {
            active.value = false
        }
    }
}

