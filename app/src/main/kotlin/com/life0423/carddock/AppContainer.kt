package com.life0423.carddock

import android.content.Context
import com.life0423.carddock.ai.MockRuleCompiler
import com.life0423.carddock.core.engine.RuleEngine
import com.life0423.carddock.core.session.PrototypeController
import com.life0423.carddock.data.AtomicFileCardStore
import com.life0423.carddock.demo.DemoDeviceGateway
import com.life0423.carddock.demo.TemplateCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** The single composition root: replace adapters here without changing UI or the rule engine. */
class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }
    private val device = DemoDeviceGateway()
    private val templates = TemplateCatalog.templates

    val controller = PrototypeController(
        store = AtomicFileCardStore(context.applicationContext, json),
        compiler = MockRuleCompiler(templates),
        engine = RuleEngine(device),
        templates = templates,
        scope = applicationScope,
        setDeviceScenario = device::setScenario,
    )

    init { applicationScope.launch { controller.initialize() } }
}
