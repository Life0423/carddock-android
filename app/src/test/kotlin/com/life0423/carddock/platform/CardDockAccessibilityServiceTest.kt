package com.life0423.carddock.platform

import android.app.Application
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAccessibilityService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [CurrentWindowShadow::class])
class CardDockAccessibilityServiceTest {
    @Test fun feedbackInterruptionKeepsConnectionButUnbindClearsIt() {
        val controller = Robolectric.buildService(CardDockAccessibilityService::class.java).create()
        val service = controller.get()
        CardDockAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        service.onInterrupt()
        assertSame(service, CardDockAccessibilityService.instance)
        service.onUnbind(Intent())
        assertNull(CardDockAccessibilityService.instance)
        controller.destroy()
    }

    @Test fun destructionClearsConnection() {
        val controller = Robolectric.buildService(CardDockAccessibilityService::class.java).create()
        CardDockAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(controller.get())
        controller.destroy()
        assertNull(CardDockAccessibilityService.instance)
    }

    @Test fun descriptionOnlyAnchorsAreReadFromActualNodes() {
        val controller = Robolectric.buildService(CardDockAccessibilityService::class.java).create()
        val root = node()
        shadowOf(root).addChild(node(description = "取包裹"))
        shadowOf(root).addChild(node(description = "暂无待取包裹"))
        CurrentWindowShadow.root = root
        val observed = controller.get().observe()!!
        assertEquals(setOf("取包裹", "暂无待取包裹"), observed.labels)
        assertEquals(CainiaoPage.EMPTY, classifyCainiaoPage(observed))
        controller.destroy()
        CurrentWindowShadow.root = null
    }

    @Test fun hiddenPasswordAndForeignNodesDoNotContributeLabels() {
        val controller = Robolectric.buildService(CardDockAccessibilityService::class.java).create()
        val root = node()
        shadowOf(root).addChild(node(description = "暂无待取包裹").apply { isVisibleToUser = false })
        shadowOf(root).addChild(node(description = "取包裹").apply { packageName = "other.app" })
        val password = node(description = "取包裹").apply { isPassword = true }
        shadowOf(password).addChild(node(description = "暂无待取包裹"))
        shadowOf(root).addChild(password)
        shadowOf(root).addChild(node(description = "普通私人页面正文"))
        CurrentWindowShadow.root = root
        val observed = controller.get().observe()!!
        assertTrue(observed.labels.isEmpty())
        assertTrue(observed.password)
        assertEquals(CainiaoPage.AUTH, classifyCainiaoPage(observed))
        controller.destroy()
        CurrentWindowShadow.root = null
    }

    @Test fun meituanMatchesOnlyTheVisibleNumberInsideTheCompletedOrder() {
        val controller = Robolectric.buildService(CardDockAccessibilityService::class.java).create()
        val service = controller.get()
        val expected = "1234567890123456"

        fun orderPage(number: String, numberInDetail: Boolean, status: String): AccessibilityNodeInfo {
            val root = node().apply { packageName = MEITUAN_PACKAGE }
            fun add(parent: AccessibilityNodeInfo, label: String? = null, id: String? = null) =
                node(description = label).apply {
                    packageName = MEITUAN_PACKAGE
                    viewIdResourceName = id?.let { "$MEITUAN_PACKAGE:id/$it" }
                    shadowOf(parent).addChild(this)
                }
            val detail = add(root, id = "ifs")
            add(add(detail, id = "r19"), status)
            val info = add(if (numberInDetail) detail else root)
            add(info, "订单信息")
            val row = add(info)
            add(row, "订单号码")
            add(row, number)
            return root
        }

        try {
            CurrentWindowShadow.root = orderPage("1234 5678 9012 3456", true, "订单已完成")
            assertEquals(MeituanPage.CONFIRMED, service.inspectMeituanPage(expected, allowExpansion = false))
            CurrentWindowShadow.root = orderPage("9876543210987654", true, "订单已完成")
            assertEquals(MeituanPage.WRONG_ORDER, service.inspectMeituanPage(expected, allowExpansion = false))
            CurrentWindowShadow.root = orderPage(expected, false, "订单已完成")
            assertEquals(MeituanPage.WAITING, service.inspectMeituanPage(expected, allowExpansion = false))
            CurrentWindowShadow.root = orderPage(expected, true, "配送中")
            assertEquals(MeituanPage.WAITING, service.inspectMeituanPage(expected, allowExpansion = false))
            CurrentWindowShadow.root = node().apply { packageName = "com.vivo.appfilter" }
            assertEquals(MeituanPage.NEEDS_JUMP_APPROVAL, service.inspectMeituanPage(expected, allowExpansion = true))
        } finally {
            CurrentWindowShadow.root = null
            controller.destroy()
        }
    }

    @Test fun meituanTargetRestrictsTheIntentToTheConfiguredRouteAndApp() {
        val route = "1234567890123456789"
        val number = "1234567890123456"
        val target = MeituanOrderTarget.parse(route, "1234 5678 9012 3456")!!
        val intent = MeituanOrderTarget.fromKey(target.key)!!.openIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(MEITUAN_PACKAGE, intent.`package`)
        assertEquals(setOf("oid"), intent.data!!.queryParameterNames)
        assertEquals(route, intent.data!!.getQueryParameter("oid"))
        assertEquals(number, target.orderNumber)
        assertNull(MeituanOrderTarget.parse("$route&other=1", number))
    }

    @Suppress("DEPRECATION")
    private fun node(description: String? = null) = AccessibilityNodeInfo.obtain().apply {
        packageName = CAINIAO_PACKAGE
        isVisibleToUser = true
        contentDescription = description
    }
}

/** 只替换系统提供根节点的入口，节点遍历和过滤运行真实服务代码。 */
@Implements(AccessibilityService::class)
class CurrentWindowShadow : ShadowAccessibilityService() {
    @Implementation
    protected fun getRootInActiveWindow(): AccessibilityNodeInfo? = root

    companion object {
        var root: AccessibilityNodeInfo? = null
    }
}
