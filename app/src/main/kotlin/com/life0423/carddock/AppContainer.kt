package com.life0423.carddock

import android.content.Context
import com.life0423.carddock.ai.MockRuleCompiler
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.session.PrototypeController
import com.life0423.carddock.data.AtomicFileCardStore
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
import com.life0423.carddock.platform.CainiaoDeviceGateway
import com.life0423.carddock.core.port.DeviceGateway
import com.life0423.carddock.core.port.DeviceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The single composition root: replace adapters here without changing UI or the rule engine.
 *
 * scope 用 Main.immediate，这是控制器要求的串行调度器。immediate 让已经在主线程上的点击不必再投递一圈。
 * SupervisorJob 让初始化失败不会取消其余子任务。
 */
class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 演示文件给人读，所以排版。未知字段显式拒绝，避免以后多出来的键被静默丢掉。
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private val device = DemoDeviceGateway()
    private val nativeDevice = CainiaoDeviceGateway()
    private val templates = TemplateCatalog.templates + cainiaoEmptyTemplate
    private val routedDevice = object : DeviceGateway {
        override suspend fun inspect(sourceId: String, targetKey: String): DeviceResponse =
            if (sourceId == cainiaoEmptyTemplate.rule.sourceId) nativeDevice.inspect(sourceId, targetKey)
            else device.inspect(sourceId, targetKey)
    }

    val controller = PrototypeController(
        // applicationContext：文件跟进程走，不跟某个 Activity。
        store = AtomicFileCardStore(context.applicationContext, json),
        compiler = MockRuleCompiler(templates),
        engine = RuleEngine(routedDevice),
        templates = templates,
        scope = applicationScope,
        setDeviceScenario = device::setScenario,
    )

    // 不阻塞构造。读成功才发布磁盘或种子；读失败则结束 loading，集合为空且不可写。
    init { applicationScope.launch { controller.initialize() } }
}
