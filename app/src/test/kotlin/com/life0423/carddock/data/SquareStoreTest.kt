package com.life0423.carddock.data

import android.app.Application
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 用真实 HTTP 与本机存储覆盖下载被拒、重试及榜单刷新失败的可见状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SquareStoreTest {
    @Test
    fun downloadRequiresServerAcceptanceAndSurvivesListingRefreshFailure() = runBlocking {
        val entry = SquareEntry("12345678-1234-1234-1234-123456789012", "alice", 2,
            "订单状态", "只显示状态", "meituan-status", 1)
        val acceptsDownload = AtomicBoolean(false)
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val responder = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: SocketException) { return@thread }
                socket.use { connection ->
                    val input = connection.getInputStream().bufferedReader()
                    val request = input.readLine().substringBefore(" HTTP/")
                    var contentLength = 0
                    for (header in generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }) {
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toInt()
                        }
                    }
                    repeat(contentLength) { input.read() }
                    val (status, body) = when {
                        request == "POST /account/login" -> "200 OK" to """{"name":"bob","id":"user-b","legacy":false,"token":"reader-session"}"""
                        request == "GET /account" -> "200 OK" to """{"name":"bob"}"""
                        request == "POST /templates/${entry.id}/download" && acceptsDownload.get() ->
                            "200 OK" to Json.encodeToString(entry)
                        request == "POST /templates/${entry.id}/download" ->
                            "404 Not Found" to """{"message":"此模板已下架。"}"""
                        request.startsWith("GET /templates?") ->
                            "503 Service Unavailable" to """{"message":"榜单暂不可用。"}"""
                        else -> "404 Not Found" to "{}"
                    }
                    val bytes = body.toByteArray()
                    val headers = "HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().apply {
                        write(headers.toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        try {
            val context = RuntimeEnvironment.getApplication()
            val address = "http://127.0.0.1:${server.localPort}"
            val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
            val vault = PrivateCipherFile(java.io.File(context.noBackupFilesDir, "test-session.enc"), "test") { key }
            val store = SquareStore(context, address, this, vault)
            withTimeout(5000) { store.state.first { !it.busy } }
            store.signIn("bob", "test-password", false)
            assertTrue(withTimeout(5000) { store.state.first { !it.busy } }.signedIn)

            store.download(entry.copy(version = 1))
            val rejected = withTimeout(5000) { store.state.first { !it.busy } }
            assertEquals("此模板已下架。", rejected.message)
            assertTrue(rejected.installed.isEmpty())

            acceptsDownload.set(true)
            store.download(entry.copy(version = 1))
            val accepted = withTimeout(5000) { store.state.first { !it.busy } }
            assertEquals(listOf(entry), accepted.installed)
            assertEquals("已加入「模板 → 已下载」，广场列表暂未更新。", accepted.message)

            val reopened = SquareStore(context, address, this, vault)
            assertEquals(listOf(entry), withTimeout(5000) { reopened.state.first { !it.busy } }.installed)
            acceptsDownload.set(false)
            store.download(entry)
            assertEquals(listOf(entry), withTimeout(5000) { store.state.first { !it.busy } }.installed)
        } finally {
            server.close()
            responder.join()
        }
    }
}
