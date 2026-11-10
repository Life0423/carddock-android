package com.life0423.carddock.core.rule

import com.life0423.carddock.core.model.CardRecord
import com.life0423.carddock.core.model.CardSnapshot
import com.life0423.carddock.core.model.FieldValue
import com.life0423.carddock.core.model.RunInfo
import com.life0423.carddock.core.model.RunPhase
import com.life0423.carddock.core.model.TemplateKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun savedCardRoundTripsWithRuleSnapshotAndLastRun() {
        val original = CardRecord(
            id = "card-1",
            title = "待取快递",
            kind = TemplateKind.EXPRESS,
            rule = exampleRule(),
            snapshot = CardSnapshot(listOf(FieldValue("code", "取件码", "A3-6281")), fetchedAt = 1234L),
            run = RunInfo("run-2", RunPhase.CANCELLED, "已取消", startedAt = 2000L, endedAt = 2001L),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CardRecord>(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("\"type\":\"assertTarget\""))
        assertTrue(encoded.contains("\"type\":\"readField\""))
        assertTrue(encoded.contains("\"type\":\"requireField\""))
    }

    @Test
    fun unknownActionCannotBeDeserializedIntoAnExecutableStep() {
        val encoded = json.encodeToString(exampleRule()).replace("assertTarget", "arbitraryScript")

        assertThrows(SerializationException::class.java) {
            json.decodeFromString<RuleSpec>(encoded)
        }
    }

    @Test
    fun unknownSchemaRemainsVisibleToSemanticValidation() {
        val encoded = json.encodeToString(exampleRule().copy(schemaVersion = 9))
        val decoded = json.decodeFromString<RuleSpec>(encoded)

        assertEquals("UNSUPPORTED_SCHEMA", RuleValidator.validate(decoded)?.code)
    }

    private fun exampleRule() = RuleSpec(
        id = "express-pickup",
        sourceId = "demo.express",
        targetKey = "parcel-demo-001",
        steps = listOf(
            RuleStep.AssertTarget("parcel-demo-001"),
            RuleStep.ReadField("pickupCode", "code", "取件码"),
            RuleStep.RequireField("code"),
        ),
    )
}
