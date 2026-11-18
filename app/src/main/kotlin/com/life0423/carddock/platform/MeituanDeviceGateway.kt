package com.life0423.carddock.platform

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.life0423.carddock.MainActivity
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import com.life0423.carddock.core.port.DeviceSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal const val MEITUAN_PACKAGE = "com.sankuai.meituan"
private const val TESTED_VERSION = "12.68.402"
private val routePattern = Regex("[0-9]{19}")
internal val meituanOrderNumberPattern = Regex("[0-9]{15,30}")

/** 路由值和可见订单号码分别用于跳转和核验。只持久化在设备内的规则目标中。 */
internal class MeituanOrderTarget private constructor(val route: String, val orderNumber: String) {
    val key: String get() = "$route:$orderNumber"

    fun openIntent(): Intent = Intent(Intent.ACTION_VIEW,
        Uri.Builder().scheme("imeituan").authority("www.meituan.com").path("/waimaiorder")
            .appendQueryParameter("oid", route).build(),
    ).setPackage(MEITUAN_PACKAGE)

    companion object {
        fun parse(route: String, orderNumber: String): MeituanOrderTarget? {
            val trimmedRoute = route.trim()
            val normalizedNumber = orderNumber.filterNot(Char::isWhitespace)
            if (!routePattern.matches(trimmedRoute) || !meituanOrderNumberPattern.matches(normalizedNumber)) return null
            return MeituanOrderTarget(trimmedRoute, normalizedNumber)
        }

        fun fromKey(key: String) = parse(key.substringBefore(':'), key.substringAfter(':', ""))
    }
}

/** 迁移 Mika 实验中的前台订单链路。规则只传入目标，App 内导航限制在此处。 */
internal class MeituanDeviceGateway(private val context: Context) : DeviceGateway {
    @Suppress("DEPRECATION")
    override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse {
        val order = MeituanOrderTarget.fromKey(targetKey)
            ?: return DeviceResponse.Failed("订单配置无效，请重新创建卡片。")
        val version = try {
            context.packageManager.getPackageInfo(MEITUAN_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            return DeviceResponse.NeedsUser("请先安装并登录美团，再核验历史订单。")
        }
        if (version != TESTED_VERSION) {
            return DeviceResponse.Failed("当前只支持已采样的美团 $TESTED_VERSION；这个版本尚未适配。")
        }
        val service = CardDockAccessibilityService.instance
            ?: return DeviceResponse.NeedsUser("请先在「我的」中打开无障碍设置，启用卡坞后再试。")
        val task = context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull {
            it.taskInfo?.baseIntent?.component?.className == MainActivity::class.java.name
        } ?: return DeviceResponse.NeedsUser("请回到卡坞后再试。")
        if (!service.awaitCardDock()) return DeviceResponse.NeedsUser("请留在卡坞页面，再开始核验。")

        var expanded = false
        var retriedFromHome = false
        var homeReads = 0
        var wrongOrder = false
        var handOff = false
        try {
            task.startActivity(context, order.openIntent(), null)
            val result = withTimeoutOrNull(10_000) {
                while (true) {
                    delay(250)
                    if (CardDockAccessibilityService.instance !== service) {
                        return@withTimeoutOrNull DeviceResponse.NeedsUser("卡坞无障碍服务已断开，请启用后再试。")
                    }
                    val page = service.inspectMeituanPage(order.orderNumber, allowExpansion = !expanded)
                    homeReads = if (page == MeituanPage.HOME) homeReads + 1 else 0
                    when (page) {
                        MeituanPage.CONFIRMED -> return@withTimeoutOrNull DeviceResponse.Ready(DeviceSnapshot(
                            sourceId = MEITUAN_PACKAGE, targetKey = targetKey,
                            fields = mapOf("status" to "订单已完成"), sourceLabel = "美团 · 历史订单",
                        ))
                        MeituanPage.NEEDS_LOGIN -> {
                            handOff = true
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("请在美团完成登录或验证，再回卡坞重试。")
                        }
                        MeituanPage.NEEDS_JUMP_APPROVAL -> {
                            handOff = true
                            return@withTimeoutOrNull DeviceResponse.NeedsUser("请在手机上确认跨应用跳转，再回卡坞重新核验。")
                        }
                        MeituanPage.WRONG_ORDER -> wrongOrder = true
                        MeituanPage.EXPANDED -> expanded = true
                        MeituanPage.HOME -> if (!retriedFromHome && homeReads == 8) {
                            // 实验版遇到过路由只打开首页，回到卡坞后允许重发一次。
                            retriedFromHome = true
                            service.returnToCardDock()
                            if (service.awaitCardDock()) task.startActivity(context, order.openIntent(), null)
                        }
                        MeituanPage.WAITING -> Unit
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            } ?: DeviceResponse.Failed(if (wrongOrder) "打开的订单号码与卡片不一致，请核对订单配置。"
                else "未核验到已完成的订单。请检查登录状态、跳转提示和订单编号。")
            if (!handOff && service.activePackage() == MEITUAN_PACKAGE) service.returnToCardDock()
            if (result is DeviceResponse.Ready && !service.awaitCardDock()) {
                return DeviceResponse.NeedsUser("订单已核对，但系统未返回卡坞。请手动返回后重试。")
            }
            return result
        } catch (_: ActivityNotFoundException) {
            return DeviceResponse.Failed("美团未接收订单跳转，请核对安装版本。")
        } catch (_: SecurityException) {
            return DeviceResponse.NeedsUser("系统限制了应用跳转，请在卡坞前台重试并确认手机提示。")
        }
    }

    private suspend fun CardDockAccessibilityService.awaitCardDock() = withTimeoutOrNull(1_500) {
        while (activePackage() != context.packageName) delay(100)
        true
    } == true

    private fun CardDockAccessibilityService.returnToCardDock() {
        startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
    }
}
