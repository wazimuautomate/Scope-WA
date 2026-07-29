package com.tricreta.scopewa.brain.template

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateVariablesTest {

    // Wednesday, 5 August 2026, 21:41.
    private val now = LocalDateTime.of(2026, 8, 5, 21, 41)

    @Test
    fun `automatic variables are filled from the clock`() {
        val values = TemplateVariables.automaticValues(now, random = { 0.0 })

        assertEquals("5 Aug 2026", values.getValue("date"))
        assertEquals("6 Aug 2026", values.getValue("tmr_date"))
        assertEquals("21:41", values.getValue("time_24"))
        assertEquals("Wednesday", values.getValue("day_of_week"))
        assertEquals("Thursday", values.getValue("tmr_day_of_week"))
        assertEquals("5", values.getValue("day_of_month"))
        assertEquals("August", values.getValue("month"))
        assertEquals("September", values.getValue("next_month"))
        assertEquals("2026", values.getValue("year"))
        // 12-hour separator/marker wording varies by JDK CLDR data; the time itself must not.
        assertTrue(values.getValue("time_12").startsWith("9:41"))
    }

    @Test
    fun `random_number stays four digits at both ends of the draw`() {
        assertEquals("1000", TemplateVariables.automaticValues(now, random = { 0.0 })["random_number"])
        assertEquals("9999", TemplateVariables.automaticValues(now, random = { 0.9999999 })["random_number"])
    }

    @Test
    fun `every catalogued automatic variable actually gets a value`() {
        val values = TemplateVariables.automaticValues(now)

        val missing = TemplateVariables.automatic.map { it.name }.filterNot { values.containsKey(it) }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `an automatic variable renders through the engine end to end`() {
        val values = TemplateVariables.automaticValues(now, random = { 0.0 })
        val known = TemplateVariables.defaultKnownNames.toSet()

        val rendered = TemplateEngine().render(
            "Use it before {date}. Thanks.",
            values,
            known
        )

        assertEquals("Use it before 5 Aug 2026. Thanks.", rendered)
    }

    @Test
    fun `unknown columns get an obvious placeholder instead of an invented value`() {
        assertEquals("[bundle_size]", TemplateVariables.sampleFor("bundle_size"))
        assertEquals("Amina", TemplateVariables.sampleFor("first_name"))
        assertEquals("Amina", TemplateVariables.sampleFor("  First_Name  "))
    }

    @Test
    fun `sample values are keyed lowercase so the engine finds them`() {
        val samples = TemplateVariables.sampleValuesFor(listOf("First_Name", "Town"))

        assertEquals(setOf("first_name", "town"), samples.keys)
    }

    @Test
    fun `catalogue names are unique and brace-free`() {
        val names = TemplateVariables.all.map { it.name }

        assertEquals(names.size, names.distinct().size)
        assertTrue(names.none { it.contains("{") || it.contains("}") || it.contains("|") })
        assertEquals("{first_name}", TemplateVariables.csv.first { it.name == "first_name" }.placeholder)
    }
}
