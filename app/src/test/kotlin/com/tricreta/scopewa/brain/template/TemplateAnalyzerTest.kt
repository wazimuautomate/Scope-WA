package com.tricreta.scopewa.brain.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateAnalyzerTest {

    private val known = setOf("name", "first_name")

    @Test
    fun `blocks are classified the way the engine reads them`() {
        val template = "{Hi|Hello} {name|there}, code {receipt_code}{}"

        val analysis = TemplateAnalyzer.analyze(template, known)

        assertEquals(4, analysis.blocks.size)
        assertEquals(
            TemplateBlock.Spintax("{Hi|Hello}", listOf("Hi", "Hello")),
            analysis.blocks[0]
        )
        assertEquals(
            TemplateBlock.Variable("{name|there}", "name", listOf("there"), known = true),
            analysis.blocks[1]
        )
        assertEquals(
            TemplateBlock.Variable("{receipt_code}", "receipt_code", emptyList(), known = false),
            analysis.blocks[2]
        )
        assertEquals(TemplateBlock.Empty("{}"), analysis.blocks[3])
    }

    /**
     * The analyzer duplicates [TemplateEngine]'s `{...}` rules. This is the test
     * that catches the two drifting apart: it asserts the engine actually does
     * what the analyzer said each block would do.
     */
    @Test
    fun `analyzer classification agrees with what the engine renders`() {
        val template = "{Hi|Hello} {name|there}, code {receipt_code}{}"
        val analysis = TemplateAnalyzer.analyze(template, known)
        val rendered = TemplateEngine(random = { 0.0 }).render(template, emptyMap(), known)

        // Spintax → first option; known-but-blank variable → its fallback;
        // unknown bare variable → left in braces; empty block → nothing.
        assertEquals("Hi there, code {receipt_code}", rendered)
        assertEquals(listOf("receipt_code"), analysis.unknownVariableNames)
        assertEquals(1, analysis.spintax.size)
        assertEquals(2, analysis.variables.size)
    }

    @Test
    fun `case does not change whether a name counts as known`() {
        val analysis = TemplateAnalyzer.analyze("Hi {NAME|there}", setOf("name"))

        val variable = analysis.blocks.single() as TemplateBlock.Variable
        assertTrue(variable.known)
        assertEquals(listOf("there"), variable.fallbacks)
    }

    @Test
    fun `combinations multiply out across spintax blocks`() {
        val analysis = TemplateAnalyzer.analyze(
            "{Hi|Hello|Habari|Niaje} {first_name}, {tuko na|kuna} offer {mpya|fresh|new}",
            known
        )

        assertEquals(4L * 2L * 3L, analysis.combinations)
        assertFalse(analysis.isCombinationCountCapped)
    }

    @Test
    fun `a template with no spintax has exactly one combination`() {
        val analysis = TemplateAnalyzer.analyze("Hi {first_name}, welcome.", known)

        assertEquals(1L, analysis.combinations)
        assertTrue(analysis.spintax.isEmpty())
    }

    @Test
    fun `combination count is capped instead of overflowing`() {
        val analysis = TemplateAnalyzer.analyze("{a|b}".repeat(40), emptySet())

        assertEquals(TemplateAnalyzer.MAX_COMBINATIONS, analysis.combinations)
        assertTrue(analysis.isCombinationCountCapped)
    }

    @Test
    fun `previews are stable for a given seed`() {
        val template = "{Hi|Hello|Habari|Niaje} {first_name}, {tuko na|kuna} offer mpya"
        val values = mapOf("first_name" to "Amina")

        val first = TemplateAnalyzer.previews(template, values, known, seed = 7L)
        val second = TemplateAnalyzer.previews(template, values, known, seed = 7L)

        assertEquals(TemplateAnalyzer.DEFAULT_PREVIEW_COUNT, first.size)
        assertEquals(first, second)
    }

    @Test
    fun `previews of a spintax-rich template are not all identical`() {
        // 4^5 = 1024 combinations, so five identical draws is a ~1-in-10^12 event.
        val template = "{a|b|c|d} ".repeat(5)

        val previews = TemplateAnalyzer.previews(template, emptyMap(), emptySet(), seed = 3L)

        assertTrue(previews.distinct().size >= 2)
    }

    @Test
    fun `a template with no variation makes every message an exact duplicate`() {
        val result = TemplateAnalyzer.estimateUniqueness(
            template = "Hi {first_name}, this is Skylink.",
            values = mapOf("first_name" to "Amina"),
            knownVariableNames = known,
            campaignSize = 200
        )

        assertEquals(200, result.total)
        assertEquals(0, result.uniqueCount)
        assertEquals(0, result.uniquePercent)
        assertTrue(result.hasWarning)
    }

    @Test
    fun `heavy spintax pushes most of a small campaign to unique text`() {
        // ~1M combinations against 20 recipients — the meter should read high.
        val template = "{a|b|c|d|e|f|g|h|i|j} ".repeat(6)

        val result = TemplateAnalyzer.estimateUniqueness(
            template = template,
            values = emptyMap(),
            knownVariableNames = emptySet(),
            campaignSize = 20
        )

        assertEquals(20, result.total)
        assertTrue("expected a high uniqueness reading, got ${result.uniquePercent}%", result.uniquePercent >= 60)
    }

    @Test
    fun `an empty template scores nothing rather than crashing`() {
        val result = TemplateAnalyzer.estimateUniqueness("   ", emptyMap(), known, campaignSize = 200)

        assertEquals(0, result.total)
        assertFalse(result.hasWarning)
        assertEquals(TemplateAnalysis.EMPTY.combinations, TemplateAnalyzer.analyze("", known).combinations)
    }

    @Test
    fun `the client's real message is read as one variable-only template`() {
        val template =
            "{name}, this is Skylink. Thanks for signing up for the Data Challenge. " +
                "Kindly find your receipt {receipt_code}, and use it before {date}. " +
                "Thanks. Reply STOP to never receive this."

        val analysis = TemplateAnalyzer.analyze(
            template,
            setOf("name", "receipt_code", "date")
        )

        assertEquals(3, analysis.variables.size)
        assertTrue(analysis.spintax.isEmpty())
        assertTrue(analysis.unknownVariableNames.isEmpty())
        // No spintax at all — exactly the shape the uniqueness meter must warn about.
        assertEquals(1L, analysis.combinations)
    }
}
