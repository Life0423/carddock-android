package com.life0423.carddock

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File

class CleanAcceptanceWorkspace {
    @Test fun inspect() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val app = ins.targetContext.applicationContext
        fun note(name: String, text: String) = ins.sendStatus(0, Bundle().apply { putString(name, text) })
        if (args.getString("mode") in setOf("resetTestData", "verifyEmpty")) {
            val clear = args.getString("mode") == "resetTestData"
            val prefs = { name: String -> app.getSharedPreferences(name, android.content.Context.MODE_PRIVATE) }
            val classic = com.life0423.carddock.data.AtomicFileCardStore(app, kotlinx.serialization.json.Json)
            val personal = com.life0423.carddock.data.AtomicTemplateStore(app, kotlinx.serialization.json.Json)
            val routes = com.wangzan101.mika.task.DockRoutes(app)
            val square = prefs("square-v1")
            val pairing = prefs("phone_link")
            val pairedAt = pairing.getLong("pairedAt", 0)
            fun modelFiles() = (File(app.filesDir, "settings").walkTopDown().filter { it.isFile }.toList() +
                listOf(File(app.filesDir, "model-v1.enc")).filter(File::isFile)).associate {
                    it.name to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
                }
            val modelBefore = modelFiles()
            val dbFile = app.getDatabasePath("remote-tasks.db")
            android.database.sqlite.SQLiteDatabase.openDatabase(dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                fun receiptCount() = android.database.DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM receipts", null)
                if (clear) {
                    check(args.getString("confirmed") == "preserve-settings")
                    note("before", "routeRows=${routes.all().size} receipts=${receiptCount()}")
                    kotlinx.coroutines.runBlocking { classic.save(emptyList()); personal.save(emptyList()) }
                    com.life0423.carddock.platform.LocalAlarms(app).sync(emptyMap())
                    for (name in listOf("mika_route_cards_v1", "mika_free_templates", "mika_submission", "official-price-history")) {
                        check(prefs(name).edit().clear().commit())
                    }
                    val installed = square.edit()
                    square.all.keys.filter { it.endsWith(":installed") }.forEach(installed::remove)
                    check(installed.commit())
                    check(prefs("space").edit().remove("shopping_favorite").commit())
                    db.delete("receipts", null, null)
                    for (name in listOf("runs", "conversations", "routes", "traces", "runs-v3.json", "run-metrics.jsonl",
                            "shopping-v1.json", "outbox-v3.json", "children-v3.json", "followups-v1.json")) {
                        val target = File(app.filesDir, name)
                        if (target.exists()) check(target.deleteRecursively())
                        if (target.isFile || '.' in name) for (suffix in listOf(".bak", ".new")) {
                            val sidecar = File(app.filesDir, name + suffix)
                            if (sidecar.exists()) check(sidecar.delete())
                        }
                    }
                    val traces = File(app.noBackupFilesDir, "harness-traces")
                    if (traces.exists()) check(traces.deleteRecursively())
                    app.getExternalFilesDir(null)?.listFiles().orEmpty().filter {
                        it.name in setOf("filter-button.png", "route29-check.png") ||
                        it.name.startsWith("inspection-") || it.name.startsWith("replay-home-") || it.name.startsWith("replay-proof-")
                    }.forEach { check(it.delete()) }
                }
                val cardCount = kotlinx.coroutines.runBlocking { classic.load()?.size }
                val personalCount = kotlinx.coroutines.runBlocking { personal.load().size }
                check(cardCount == 0 && personalCount == 0 && routes.all().isEmpty() && receiptCount() == 0L)
                check(com.wangzan101.mika.record.RunStore(app).list().isEmpty())
                check(com.wangzan101.mika.chat.ConversationStore(app).list().isEmpty())
                check(File(app.noBackupFilesDir, "harness-traces").list().isNullOrEmpty())
                check(square.all.keys.none { it.endsWith(":installed") })
                check(pairing.getBoolean("paired", false) && pairing.getLong("pairedAt", 0) == pairedAt)
                check(modelBefore.isNotEmpty() && modelBefore == modelFiles())
                note("after", "classicCards=0 personalTemplates=0 routeRows=0 receipts=0 runs=0 conversations=0 installedTemplates=0 modelFilesRetained=true paired=true")
            }
        }
        if (args.getString("mode") == "inventory") {
            note("files", app.filesDir.list().orEmpty().sorted().joinToString())
            note("prefs", File(app.applicationInfo.dataDir, "shared_prefs").list().orEmpty().sorted().joinToString())
            note("db", app.databaseList().sorted().joinToString())
            note("ext", app.getExternalFilesDir(null)?.list().orEmpty().sorted().joinToString())
        }
        if (args.getString("mode") == "recountReceipt") {
            val mika = app as com.wangzan101.mika.MikaApp
            val source = requireNotNull(mika.remoteTasks.localTasks.get(requireNotNull(args.getString("task"))))
            val answer = (source.state as com.wangzan101.mika.core.agent.TaskState.Succeeded).answer
            val events = com.wangzan101.mika.record.RunStore(app).events(requireNotNull(source.runId))
            events.filterIsInstance<com.wangzan101.mika.core.record.RunEvent.State>().filter { it.name == "list_count_check" }.forEach { note("countCheck", it.detail) }
            val results = events.filterIsInstance<com.wangzan101.mika.core.record.RunEvent.ToolResult>()
            val tap = results.indexOfLast { it.tool == "phone_tap" }
            for (result in results.drop(tap.coerceAtLeast(0))) {
                val data = runCatching { org.json.JSONObject(result.resultJson) }.getOrNull() ?: continue
                val page = data.optJSONObject("screen") ?: data
                val pkg = page.optString("currentApp").takeIf { it.isNotEmpty() } ?: continue
                val array = page.optJSONArray("elements") ?: continue
                val rows = (0 until array.length()).mapNotNull { i ->
                    val n = array.getJSONObject(i)
                    val b = n.optJSONArray("bounds") ?: return@mapNotNull null
                    com.wangzan101.mika.core.uistate.UiElement(n.optString("ref"), n.optString("role"), n.optString("name"), 0, 0,
                        (0 until b.length()).map(b::getInt), null, false, false, false)
                }
                val screen = com.wangzan101.mika.core.uistate.ScreenSnapshot("receipt", pkg, rows, 1000, 1000)
                val checked = com.wangzan101.mika.core.verify.ListQuantityCheck.check(answer, screen) ?: continue
                note("counts", "${checked.evidence} checked=${checked.checked} corrected=${checked.corrected}")
                note("answer", checked.answer)
                return
            }
            error("未从该回执识别出受支持的列表汇总")
        }
        if (args.getString("mode") == "originalCheck") {
            val events = com.wangzan101.mika.record.RunStore(app).events(requireNotNull(args.getString("run")))
            events.forEach { e -> when (e) {
                is com.wangzan101.mika.core.record.RunEvent.AnswerCheck -> note("grounding", "claims=${e.claims} gaps=${e.unmatched.size}")
                is com.wangzan101.mika.core.record.RunEvent.Verifier -> note("verdict", e.verdict)
                else -> Unit
            } }
        }
    }
}
