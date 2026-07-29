package com.tricreta.scopewa.data.repository.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactExporterTest {

    private val joy = ExportRecord(name = "Joy Wanjiru", phoneE164 = "+254712345678", saved = true)
    private val otieno = ExportRecord(name = "Otieno", phoneE164 = "+254722000111", optedOut = true)
    private val hidden = ExportRecord(name = "Hidden Member", hidden = true)

    @Test
    fun `csv has a BOM, a header row and CRLF endings so Excel opens it correctly`() {
        val csv = ContactExporter.toCsv(listOf(joy), ExportField.Slim)

        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(
            "\uFEFFName,Number\r\nJoy Wanjiru,+254712345678\r\n",
            csv
        )
    }

    @Test
    fun `csv quotes cells containing commas or quotes`() {
        val csv = ContactExporter.toCsv(
            listOf(ExportRecord(name = "Wanjiku, \"Mary\"", phoneE164 = "+254712345678")),
            ExportField.Slim
        )

        assertTrue(csv.contains("\"Wanjiku, \"\"Mary\"\"\""))
    }

    @Test
    fun `a hidden member exports as the word hidden rather than vanishing`() {
        val csv = ContactExporter.toCsv(listOf(hidden), ExportField.Slim)

        assertTrue(csv.contains("Hidden Member,hidden"))
    }

    @Test
    fun `the full field set carries opt-out state`() {
        val csv = ContactExporter.toCsv(listOf(otieno), ExportField.All)

        assertTrue(csv.lines().first().contains("Opted Out"))
        assertTrue(csv.contains("+254722000111,no,yes"))
    }

    @Test
    fun `txt is numbers only and skips hidden members`() {
        val txt = ContactExporter.toTxt(listOf(joy, hidden, otieno))

        assertEquals("+254712345678\r\n+254722000111\r\n", txt)
    }

    @Test
    fun `vcf produces one card per contact with a real number`() {
        val vcf = ContactExporter.toVcf(listOf(joy, hidden))

        assertEquals(1, Regex("BEGIN:VCARD").findAll(vcf).count())
        assertTrue(vcf.contains("FN:Joy Wanjiru"))
        assertTrue(vcf.contains("TEL;TYPE=CELL:+254712345678"))
        assertTrue(vcf.contains("END:VCARD"))
    }

    @Test
    fun `vcf escapes the characters vCard treats as structure`() {
        val vcf = ContactExporter.toVcf(
            listOf(ExportRecord(name = "Wanjiku, Mary; Ltd", phoneE164 = "+254712345678"))
        )

        assertTrue(vcf.contains("FN:Wanjiku\\, Mary\\; Ltd"))
    }

    @Test
    fun `a vcf round-trips back through our own parser`() {
        val vcf = ContactExporter.toVcf(listOf(joy, otieno))

        val reparsed = com.tricreta.scopewa.data.repository.contacts.parse.VcfParser.parse(vcf)

        assertEquals(listOf("+254712345678", "+254722000111"), reparsed.map { it.rawNumber })
    }

    @Test
    fun `json marks hidden numbers as null rather than empty text`() {
        val json = ContactExporter.toJson(listOf(hidden))

        assertTrue(json.contains("\"number\": null"))
        assertTrue(json.contains("\"hidden\": true"))
    }

    @Test
    fun `json escapes quotes in names`() {
        val json = ContactExporter.toJson(
            listOf(ExportRecord(name = "She said \"hi\"", phoneE164 = "+254712345678"))
        )

        assertTrue(json.contains("\\\"hi\\\""))
    }

    @Test
    fun `empty exports are still well formed`() {
        assertEquals("[]\n", ContactExporter.toJson(emptyList()))
        assertEquals("", ContactExporter.toTxt(emptyList()))
        assertEquals("", ContactExporter.toVcf(emptyList()))
        assertEquals("\uFEFFName,Number\r\n", ContactExporter.toCsv(emptyList(), ExportField.Slim))
    }

    @Test
    fun `file names are slugged and carry the format's extension`() {
        assertEquals("fifth-824.csv", ContactExporter.fileNameFor("Fifth 824", ExportFormat.Csv))
        assertEquals("b-group-4.vcf", ContactExporter.fileNameFor("B. GROUP 4", ExportFormat.Vcf))
        assertEquals("contacts.json", ContactExporter.fileNameFor("   ", ExportFormat.Json))
    }

    @Test
    fun `export dispatches to the right format`() {
        assertTrue(ContactExporter.export(listOf(joy), ExportFormat.Vcf).contains("BEGIN:VCARD"))
        assertFalse(ContactExporter.export(listOf(joy), ExportFormat.Txt).contains("BEGIN:VCARD"))
    }
}
