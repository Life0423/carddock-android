package com.wangzan101.mika.experiment

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RuleSchemaTest {
    private val raw = File("src/debug/assets/rules/cainiao-empty-v1.json").readText()

    @Test fun realAssetDecodesAndRoundTrips() {
        val rule = ValidatedRule.parse(raw)
        assertEquals("cainiao-pickup-empty", rule.template.ruleId)
        assertEquals(rule.template, ValidatedRule.parse(rule.encode()).template)
    }
    @Test fun malformedJsonIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.dropLast(4)) } }
    @Test fun missingRequiredFieldIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("\"version\": 1,", "")) } }
    @Test fun unknownSchemaVersionIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) } }
    @Test fun unknownActionIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("CLICK_NODE", "SHELL")) } }
    @Test fun otherPackageIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("com.cainiao.wireless", "other.app")) } }
    @Test fun unverifiedLaunchEntryIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("HomePageActivity", "OtherActivity")) } }
    @Test fun emptySelectorIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("\"text\": \"取包裹\"", "\"text\": \"\"")) } }
    @Test fun excessiveTimeoutIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("8000", "10001")) } }
    @Test fun zeroTimeoutIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("8000", "0")) } }
    @Test fun wrongTypeIsRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("8000", "\"five\"")) } }
    @Test fun unknownFieldsAreRejected() { assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(raw.replace("\"version\": 1,", "\"version\": 1, \"script\": \"anything\",")) } }
    @Test fun wrongStepOrderIsRejected() {
        val rule = ValidatedRule.parse(raw).template
        val swapped = rule.copy(steps = listOf(rule.steps[1], rule.steps[0], rule.steps[2], rule.steps[3]))
        assertThrows(IllegalArgumentException::class.java) { ValidatedRule.parse(ruleJson.encodeToString(RuleTemplate.serializer(), swapped)) }
    }
    @Test fun changedAssertionIsKeptRatherThanReplacedByHardcodedText() {
        val changed = ValidatedRule.parse(raw.replace("暂无待取包裹", "不存在的空状态"))
        assertEquals("不存在的空状态", (changed.template.steps.last() as RuleStep.VerifyEmpty).emptyText)
    }
}
