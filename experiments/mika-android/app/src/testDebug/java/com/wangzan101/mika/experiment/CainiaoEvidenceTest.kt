package com.wangzan101.mika.experiment

import org.junit.Assert.assertEquals
import org.junit.Test

class CainiaoEvidenceTest {
    @Test fun exactEmptyEvidenceIsAccepted() {
        assertEquals(CainiaoPage.EMPTY, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "暂无待取包裹"), false))
    }

    @Test fun anotherAppCannotSupplyTheSameLabels() {
        assertEquals(CainiaoPage.OTHER_APP, classifyCainiaoPage("other.app", setOf("取包裹", "暂无待取包裹"), false))
    }

    @Test fun emptyAnchorWithoutPageTitleIsInsufficient() {
        assertEquals(CainiaoPage.UNKNOWN, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("暂无待取包裹"), false))
    }

    @Test fun helpTextDoesNotBecomeAnEmptyResult() {
        assertEquals(CainiaoPage.PICKUP, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "包裹看不到取件码？"), false))
    }

    @Test fun loadingPageDoesNotBecomeAnEmptyResult() {
        assertEquals(CainiaoPage.PICKUP, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "加载中"), false))
    }

    @Test fun realParcelTextIsNotHandledByTheEmptyExperiment() {
        assertEquals(CainiaoPage.PICKUP, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "待取件", "1-2-3456"), false))
    }

    @Test fun authenticationTakesPrecedenceOverCachedLabels() {
        assertEquals(CainiaoPage.AUTH, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "暂无待取包裹", "登录", "请输入手机号"), false))
    }

    @Test fun passwordFieldRequiresHandoff() {
        assertEquals(CainiaoPage.AUTH, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("取包裹", "暂无待取包裹"), true))
    }

    @Test fun homeHasItsOwnClassification() {
        assertEquals(CainiaoPage.HOME, classifyCainiaoPage(CAINIAO_PACKAGE, setOf("首页", "取包裹", "已签收"), false))
    }
}
