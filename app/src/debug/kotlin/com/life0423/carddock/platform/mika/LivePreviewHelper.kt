package com.life0423.carddock.platform.mika

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/** 从用户授权的 ADB 会话启动。Binder 交接和可信屏配置来自 Mika，见 APK 内的许可证与声明。 */
@RequiresApi(34)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object LivePreviewHelper {
    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        check(Process.myUid() == LivePreviewHandoff.SHELL_UID && Build.VERSION.SDK_INT >= 34)
        val app = args.single()
        check(app == "com.life0423.carddock.prototype")
        Looper.prepareMainLooper()
        val shell = ShellContext.create()
        val uid = shell.packageManager.getApplicationInfo(app, 0).uid
        val owner = attach(app, uid, DisplayService(shell, uid))
        owner.linkToDeath({ exitProcess(0) }, 0)
        Looper.loop()
    }

    private fun attach(app: String, uid: Int, service: DisplayService): IBinder {
        val authority = "$app.preview"
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val user = uid / 100_000
        val holder = am.javaClass.getMethod(
            "getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java,
            String::class.java,
        ).invoke(am, authority, user, token, "CardDockPreview") ?: error("CardDock preview provider unavailable")
        try {
            val info = holder.javaClass.getField("info").get(holder) as ProviderInfo
            check(info.packageName == app && info.applicationInfo.uid == uid)
            val provider = holder.javaClass.getField("provider").get(holder)
            val call = provider.javaClass.methods.first {
                it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
            }
            val caller = AttributionSource.Builder(Process.myUid()).setPackageName(ShellContext.SHELL_PACKAGE).build()
            val extras = Bundle().apply { putBinder("display", service) }
            val result = call.invoke(provider, caller, authority, "attach", null, extras) as Bundle?
            return requireNotNull(result?.getBinder("app")) { "CardDock preview assistant unavailable or already connected" }
        } finally {
            am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                Int::class.javaPrimitiveType).invoke(am, authority, token, user)
        }
    }
}

/** 只有卡坞 UID 可发请求。触摸限定会话屏和目标 App UID，原应用换到前台时只移动同一任务。 */
@RequiresApi(34)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private class DisplayService(private val shell: Context, private val ownerUid: Int) : ILivePreviewDisplay.Stub() {
    private data class Session(
        val display: VirtualDisplay,
        val targetPackage: String,
        val targetUid: Int,
        var attached: Boolean = true,
    )

    private val user = ownerUid / 100_000
    private val displayManager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(shell)
    private val tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private val taskInfo = Class.forName("android.app.TaskInfo")
    private val input = Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
    private val inject = input.javaClass.getMethod("injectInputEvent", InputEvent::class.java,
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val roots = tasks.javaClass.getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType)
    private var session: Session? = null

    /** 显式选择来源后才启动。原应用仍在主屏可见时保留原任务，不把它搬进预览。 */
    @Synchronized
    @SuppressLint("WrongConstant")
    override fun openPreview(surface: Surface, width: Int, height: Int, density: Int, targetPackage: String, url: String?): Int =
        asShell(-1) {
            if (session != null) return@asShell -1
            if (visibleTask(0, targetPackage) != null) return@asShell -2
            val intent = if (url == null) shell.packageManager.getLaunchIntentForPackage(targetPackage) else {
                val uri = Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@asShell -1
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(targetPackage)
            } ?: return@asShell -1
            val resolved = intent.resolveActivity(shell.packageManager) ?: return@asShell -1
            if (resolved.packageName != targetPackage) return@asShell -1
            val targetUid = shell.packageManager.getApplicationInfo(targetPackage, 0).uid
            val launch = tasks.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" && it.parameterCount == 12 }
                ?: return@asShell -1

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val display = displayManager.createVirtualDisplay("carddock-live-preview", width, height, density, surface, DISPLAY_FLAGS)
                ?: return@asShell -1
            try {
                val id = display.display.displayId
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
                val status = launch.invoke(tasks, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, user) as Int
                if (status < 0) return@asShell -1
                session = Session(display, targetPackage, targetUid)
                id
            } finally {
                if (session == null) display.release()
            }
        }

    @Synchronized
    override fun attachSurface(displayId: Int, surface: Surface?): Boolean = withSession(displayId, false) { state ->
        state.display.surface = surface
        state.attached = surface != null
        true
    }

    @Synchronized
    override fun showsTarget(displayId: Int): Boolean = withSession(displayId, false) { state ->
        state.attached && visibleTask(displayId, state.targetPackage) != null
    }

    @Synchronized
    override fun touch(displayId: Int, event: MotionEvent) = withSession(displayId, Unit) { state ->
        try {
            if (state.attached) send(event, displayId, state.targetUid)
        } finally {
            event.recycle()
        }
    }

    @Synchronized
    override fun back(displayId: Int): Boolean = withSession(displayId, false) { state ->
        if (!state.attached || visibleTask(displayId, state.targetPackage) == null) return@withSession false
        val now = SystemClock.uptimeMillis()
        send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), displayId, state.targetUid) &&
            send(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), displayId, state.targetUid)
    }

    @Synchronized
    override fun openOnMainDisplay(displayId: Int): Boolean = withSession(displayId, false) { state ->
        val task = visibleTask(displayId, state.targetPackage) ?: return@withSession false
        val taskId = taskInfo.getField("taskId").getInt(task)
        tasks.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(tasks, taskId, 0)
        true
    }

    @Synchronized
    override fun closePreview(displayId: Int) = withSession(displayId, Unit) { state ->
        session = null
        state.display.release()
    }

    @Synchronized
    override fun stopHelper() {
        check(Binder.getCallingUid() == ownerUid)
        session?.display?.release()
        exitProcess(0)
    }

    private fun visibleTask(displayId: Int, targetPackage: String): Any? {
        val list = roots.invoke(tasks, displayId) as List<*>
        return list.filterNotNull().singleOrNull { task ->
            taskInfo.getField("isVisible").getBoolean(task) &&
                taskInfo.getField("userId").getInt(task) == user &&
                (taskInfo.getField("topActivity").get(task) as? ComponentName)?.packageName == targetPackage
        }
    }

    private fun send(event: InputEvent, displayId: Int, targetUid: Int): Boolean {
        event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        // 1 是 WAIT_FOR_RESULT，确保系统接受目标 UID 再允许同一手势继续派发。
        return inject.invoke(input, event, 1, targetUid) as Boolean
    }

    private inline fun <T> withSession(displayId: Int, fallback: T, use: (Session) -> T): T = asShell(fallback) {
        val state = session ?: return@asShell fallback
        if (state.display.display.displayId != displayId) return@asShell fallback
        use(state)
    }

    /** 只在认证后的 Binder 调用期间用 shell 身份访问系统服务。 */
    private inline fun <T> asShell(fallback: T, call: () -> T): T {
        check(Binder.getCallingUid() == ownerUid)
        val identity = Binder.clearCallingIdentity()
        try {
            return call()
        } catch (_: ReflectiveOperationException) {
            return fallback
        } catch (_: SecurityException) {
            return fallback
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    companion object {
        // 复用 Mika 可信屏配置。保留独立焦点，释放屏时销毁仍留在该屏上的任务。
        private const val DISPLAY_FLAGS = 1 or (1 shl 3) or (1 shl 6) or (1 shl 7) or
            (1 shl 8) or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14) or (1 shl 16)
    }
}
