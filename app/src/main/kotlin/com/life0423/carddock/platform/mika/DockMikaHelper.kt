package com.life0423.carddock.platform.mika

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.content.Context
import android.content.pm.ProviderInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Surface
import com.life0423.carddock.platform.CAINIAO_PACKAGE
import com.life0423.carddock.platform.MEITUAN_PACKAGE
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/**
 * 复用 Mika ShellService 的可信屏配置及 HelperProcess 的 Binder 交接方式（Apache-2.0）。
 * 入口通过 adb 的 app_process 载入同一个 CardDock APK。屏幕和启动能力只交给该 APK 的 UID。
 */
@RequiresApi(34)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
object DockMikaHelper {
    @JvmStatic
    @Suppress("DEPRECATION") // app_process 使用本入口时尚无 Android 主 Looper。
    fun main(args: Array<String>) {
        check(Process.myUid() == DockMikaHandoff.SHELL_UID && Build.VERSION.SDK_INT >= 34)
        val app = args.single()
        check(app == "com.life0423.carddock.prototype" || app == "com.life0423.carddock")
        Looper.prepareMainLooper()
        val context = ShellContext.create()
        val uid = context.packageManager.getApplicationInfo(app, 0).uid
        val service = DisplayService(context, uid)
        val owner = attach(app, uid, service)
        owner.linkToDeath({ exitProcess(0) }, 0)
        Looper.loop()
    }

    private fun attach(app: String, uid: Int, service: DisplayService): IBinder {
        val authority = "$app.mika"
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val user = uid / 100_000
        val holder = am.javaClass.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(am, authority, user, token, "CardDockMika") ?: error("CardDock provider unavailable")
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
            return requireNotNull(result?.getBinder("app")) { "CardDock rejected assistant" }
        } finally {
            am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java,
                Int::class.javaPrimitiveType).invoke(am, authority, token, user)
        }
    }
}

/** 助手只接受卡坞发来的请求。可信屏保留主屏焦点，系统不支持时直接失败。 */
@RequiresApi(34)
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
private class DisplayService(private val shell: Context, private val uid: Int) : ICardDockDisplay.Stub() {
    private val displayManager = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(shell)
    private val displays = mutableMapOf<Int, VirtualDisplay>()
    private val tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    private val taskInfo = Class.forName("android.app.TaskInfo")

    /** 通过所选应用的标准启动入口打开独立查询屏。 */
    override fun openLauncher(surface: Surface, width: Int, height: Int, density: Int, targetPackage: String): Int {
        check(Binder.getCallingUid() == uid)
        val launcher = shell.packageManager.getLaunchIntentForPackage(targetPackage) ?: return -1
        return open(surface, width, height, density, targetPackage, launcher)
    }

    /** 多步回放从自己的新查询任务进入，避免把上次停留的筛选弹层当作启动首页。 */
    override fun openIndependentLauncher(surface: Surface, width: Int, height: Int, density: Int, targetPackage: String): Int {
        check(Binder.getCallingUid() == uid)
        val launcher = shell.packageManager.getLaunchIntentForPackage(targetPackage) ?: return -1
        launcher.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        return open(surface, width, height, density, targetPackage, launcher)
    }

    override fun openCainiao(surface: Surface, width: Int, height: Int, density: Int): Int =
        open(surface, width, height, density, CAINIAO_PACKAGE,
            Intent(Intent.ACTION_VIEW, Uri.parse("guoguo://go/home_page"))
                .setClassName(CAINIAO_PACKAGE, "$CAINIAO_PACKAGE.homepage.view.activity.HomePageActivity"))

    override fun openMeituan(surface: Surface, width: Int, height: Int, density: Int, oid: String): Int {
        if (!oid.matches(Regex("[0-9]{19}"))) return -1
        val intent = Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("imeituan")
            .authority("www.meituan.com").path("/waimaiorder").appendQueryParameter("oid", oid).build())
            .setPackage(MEITUAN_PACKAGE)
        return open(surface, width, height, density, MEITUAN_PACKAGE, intent)
    }

    /** 从美团主入口进入订单中心，单独记录允许点击的列表查询屏。 */
    @Synchronized
    override fun openMeituanOrders(surface: Surface, width: Int, height: Int, density: Int): Int {
        val route = Uri.Builder().scheme("imeituan").authority("www.meituan.com").path("/msc")
            .appendQueryParameter("appId", "003f9bc374244937").appendQueryParameter("targetPath", "/pages/order/index").build()
        val intent = Intent(Intent.ACTION_VIEW, route).setPackage(MEITUAN_PACKAGE)
        return open(surface, width, height, density, MEITUAN_PACKAGE, intent)
    }

    @Synchronized
    @SuppressLint("WrongConstant") // Mika 的可信屏焦点标志不在公开 SDK 常量表内。
    private fun open(surface: Surface, width: Int, height: Int, density: Int, target: String, intent: Intent): Int {
        check(Binder.getCallingUid() == uid)
        val identity = Binder.clearCallingIdentity()
        try {
            // 与 Mika 一样，在分屏场景也不能搬走用户正在使用的主屏任务。
            val getTasks = tasks.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount == 4 }
                ?: return -1
            val main = getTasks.invoke(tasks, 64, false, false, 0) as List<*>
            val user = uid / 100_000
            if (main.filterNotNull().any {
                    taskInfo.getField("displayId").getInt(it) == 0 &&
                        taskInfo.getField("userId").getInt(it) == user &&
                        taskInfo.getField("isVisible").getBoolean(it) &&
                        listOf("baseActivity", "topActivity").any { field ->
                            (taskInfo.getField(field).get(it) as? ComponentName)?.packageName == target
                        }
                }) return -2
            val vd = displayManager.createVirtualDisplay("carddock-mika", width, height, density, surface, DISPLAY_FLAGS)
                ?: return -1
            val id = vd.display.displayId
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
                val start = tasks.javaClass.methods.firstOrNull { it.name == "startActivityAsUser" && it.parameterCount == 12 }
                    ?: return -1
                val result = start.invoke(tasks, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                    0, 0, null, options, user) as Int
                if (result < 0) return -1
                displays[id] = vd
                return id
            } finally {
                if (id !in displays) vd.release()
            }
        } catch (error: ReflectiveOperationException) {
            Log.w("CardDockMika", "Launch failed for $target: ${error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName}")
            return -1
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    override fun closeDisplay(displayId: Int) {
        check(Binder.getCallingUid() == uid)
        displays.remove(displayId)?.release()
    }

    companion object {
        // Mika Android 14+：可信独立焦点、禁止抢顶层焦点、移除时销毁内容。
        private const val DISPLAY_FLAGS = 1 or (1 shl 3) or (1 shl 6) or (1 shl 7) or
            (1 shl 8) or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14) or (1 shl 16)
    }
}
