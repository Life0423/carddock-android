package com.life0423.carddock.platform

import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CainiaoDeviceGatewayTest {
    private val empty = CainiaoObservation(CAINIAO_PACKAGE, setOf("取包裹", "暂无待取包裹"), false)

    @Test fun onlyExplicitSameAppEvidenceSucceeds() {
        assertEquals(CainiaoPage.EMPTY, classifyCainiaoPage(empty))
        assertEquals(CainiaoPage.UNKNOWN, classifyCainiaoPage(empty.copy(labels = setOf("暂无待取包裹"))))
        assertEquals(CainiaoPage.OTHER, classifyCainiaoPage(empty.copy(packageName = "other")))
        assertEquals(CainiaoPage.AUTH, classifyCainiaoPage(empty.copy(password = true)))
        assertEquals(CainiaoPage.AUTH, classifyCainiaoPage(empty.copy(labels = empty.labels + "手机号登录")))
    }

    @Test fun readsLiveEvidenceAndMarksRealSource() = runTest {
        var page = empty.copy(packageName = "other")
        val gateway = CainiaoDeviceGateway({ true }, { page })
        val work = async { gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) }
        runCurrent()
        assertFalse(work.isCompleted)
        page = empty
        advanceTimeBy(250)
        runCurrent()
        val result = work.await() as DeviceResponse.Ready
        assertEquals("菜鸟 · 当前页面", result.snapshot.sourceLabel)
        assertEquals(mapOf("status" to "暂无待取包裹"), result.snapshot.fields)
    }

    @Test fun noServiceOrDisconnectRequiresUser() = runTest {
        var connected = false
        var reads = 0
        val gateway = CainiaoDeviceGateway({ connected }, { reads++; null })
        assertTrue(gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) is DeviceResponse.NeedsUser)
        assertEquals(0, reads)
        connected = true
        val work = async { gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) }
        runCurrent()
        connected = false
        advanceTimeBy(250)
        runCurrent()
        assertTrue(work.await() is DeviceResponse.NeedsUser)
    }

    @Test fun unknownPageTimesOutWithoutProducingEmptyStatus() = runTest {
        val gateway = CainiaoDeviceGateway({ true }, { empty.copy(labels = setOf("首页", "取包裹")) })
        val work = async { gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) }
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(work.await() is DeviceResponse.NeedsUser)
        assertEquals(20_000L, testScheduler.currentTime)
    }

    @Test fun cancellationStopsObservation() = runTest {
        var reads = 0
        val gateway = CainiaoDeviceGateway({ true }, { reads++; null })
        val work = async { gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) }
        runCurrent()
        work.cancel()
        runCurrent()
        val stoppedAt = reads
        advanceTimeBy(20_000)
        assertTrue(work.isCancelled)
        assertEquals(stoppedAt, reads)
    }

    @Test fun wrongTargetDoesNotReadAndLoginNeverSucceeds() = runTest {
        var reads = 0
        val gateway = CainiaoDeviceGateway({ true }, { reads++; empty.copy(password = true) })
        assertTrue(gateway.inspect(CAINIAO_PACKAGE, "parcel") is DeviceResponse.Failed)
        assertEquals(0, reads)
        assertTrue(gateway.inspect(CAINIAO_PACKAGE, CAINIAO_EMPTY_TARGET) is DeviceResponse.NeedsUser)
    }
}
