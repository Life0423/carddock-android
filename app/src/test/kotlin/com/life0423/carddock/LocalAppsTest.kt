package com.life0423.carddock

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.life0423.carddock.core.port.DiscoveryResult
import com.life0423.carddock.core.rule.OfficialAction
import org.junit.Assert.*
import org.junit.Test

/** 新应用名只来自当次设备清单；固定假数据验证同名、安装变化与旧路线的分工。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalAppsTest {
    private val novel = LocalApp("浮光 ABC", "test.first/.Start")
    private val other = LocalApp("浮光工具", "test.second/.Main")
    private val apps = listOf(novel, other, LocalApp("淘宝", "test.shop/.Home"), LocalApp("麦当劳", "test.meal/.Home"))

    @Test fun uniqueExactUsesDeviceIdentity() {
        val result = appProposal("请帮我启动 浮光ａｂｃ", apps)?.result as DiscoveryResult.Plans
        val rule = result.choices.single().rule
        assertEquals(OfficialAction.OPEN_APP, rule.officialAction)
        assertEquals(novel.key, rule.targetKey)
    }

    @Test fun partialAndDuplicateRequireSelection() {
        val partial = appProposal("进入浮光", apps)?.result as DiscoveryResult.Plans
        assertEquals(2, partial.choices.size)
        val same = novel.copy(key = "test.third/.Alias")
        val duplicate = appProposal("打开浮光ABC", apps + same)?.result as DiscoveryResult.Plans
        assertEquals(setOf(novel.key, same.key), duplicate.choices.map { it.rule.targetKey }.toSet())
    }

    @Test fun unknownNeverOpensNeighbour() {
        assertTrue((appProposal("打开浮光不存在", apps)?.result as DiscoveryResult.Incomplete).reason.startsWith("应用识别失败"))
        assertNull(appProposal("不要打开淘宝", apps))
        assertNull(appProposal("核对麦当劳套餐", apps))
    }

    @Test fun compoundPreservesDemandAndAllowsSpecializedRoutingFirst() {
        val demand = "帮我在淘宝里推荐一些好看的小说并给我相应的购买链接以及推荐理由"
        assertTrue(requireNotNull(appProposal(demand, apps)).compound)
        val result = appProposal(demand, apps)?.result as DiscoveryResult.Plans
        assertEquals(demand, result.choices.single().examplePrompt)
        assertTrue(result.question.contains("后续操作"))
        assertNull(appProposal("打开麦当劳（测试餐厅）选择「套餐」", apps))
        assertNull(appProposal("打开麦当劳灰焰圆筒", apps))
        assertNull(appProposal("打开麦当劳选一份随心配套餐加入购物车", apps))
        assertEquals("test.shop/.Home", (appProposal("在淘宝里查看取件身份码", apps)?.result as DiscoveryResult.Plans).choices.single().rule.targetKey)
    }

    @Test fun dispatchUsesLauncherTaskAndReportsSystemRejection() {
        val context = RuntimeEnvironment.getApplication()
        val entry = context.launchableApps(context.packageName).first()
        val denied = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                assertEquals(entry.key, intent.component?.flattenToShortString())
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                throw SecurityException()
            }
        }
        assertTrue(requireNotNull(denied.launchSavedApp(entry.key)).contains("启动失败"))
        val removed = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertTrue(requireNotNull(removed.launchSavedApp(entry.key)).contains("入口已变化"))
        assertTrue(requireNotNull(context.launchSavedApp("absent.pkg/.Gone")).contains("应用识别失败"))
    }

    @Test fun savedEntryKeepsIdentityAfterUpdateOrUninstall() {
        assertEquals(novel, apps.resolveSaved(novel.key))
        assertEquals(novel, apps.resolveSaved("test.first/.Old"))
        assertNull(apps.resolveSaved("test.absent/.Start"))
        assertNull((apps + novel.copy(key = "test.first/.Other")).resolveSaved("test.first/.Old"))
    }
}
