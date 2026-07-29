package com.tricreta.scopewa.data.repository.contacts.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvParserTest {

    @Test
    fun `parses a plain csv into headers and rows`() {
        val table = CsvParser.parse("name,phone\nJoy,0712345678\nOtieno,0722000111\n")

        assertEquals(listOf("name", "phone"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Joy", table.rows[0]["name"])
        assertEquals("0722000111", table.rows[1]["phone"])
    }

    @Test
    fun `quoted fields keep their commas`() {
        val table = CsvParser.parse("name,town\n\"Wanjiku, Mary\",Nakuru\n")

        assertEquals("Wanjiku, Mary", table.rows[0]["name"])
        assertEquals("Nakuru", table.rows[0]["town"])
    }

    @Test
    fun `escaped double quotes collapse to one`() {
        val table = CsvParser.parse("name\n\"She said \"\"hi\"\"\"\n")

        assertEquals("She said \"hi\"", table.rows[0]["name"])
    }

    @Test
    fun `a newline inside a quoted field does not split the row`() {
        val table = CsvParser.parse("name,note\n\"Joy\",\"line one\nline two\"\n")

        assertEquals(1, table.rows.size)
        assertEquals("line one\nline two", table.rows[0]["note"])
    }

    @Test
    fun `crlf endings and a leading BOM are handled`() {
        val table = CsvParser.parse("\uFEFFname,phone\r\nJoy,0712345678\r\n")

        assertEquals(listOf("name", "phone"), table.headers)
        assertEquals("Joy", table.rows[0]["name"])
    }

    @Test
    fun `a final row without a trailing newline is not dropped`() {
        val table = CsvParser.parse("name,phone\nJoy,0712345678")

        assertEquals(1, table.rows.size)
        assertEquals("0712345678", table.rows[0]["phone"])
    }

    @Test
    fun `blank lines are skipped`() {
        val table = CsvParser.parse("name\nJoy\n\n\nOtieno\n")

        assertEquals(2, table.rows.size)
    }

    @Test
    fun `short rows fill missing columns with blanks instead of throwing`() {
        val table = CsvParser.parse("name,phone,town\nJoy,0712345678\n")

        assertEquals("", table.rows[0]["town"])
    }

    @Test
    fun `empty input yields no headers and no rows`() {
        val table = CsvParser.parse("")

        assertTrue(table.headers.isEmpty())
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun `duplicate headers are renamed so no column is silently swallowed`() {
        val table = CsvParser.parse("phone,phone,PHONE\n1,2,3\n")

        assertEquals(listOf("phone", "phone_2", "PHONE_3"), table.headers)
        assertEquals("1", table.rows[0]["phone"])
        assertEquals("2", table.rows[0]["phone_2"])
    }

    @Test
    fun `blank headers get a positional name`() {
        val table = CsvParser.parse("name,,phone\nJoy,x,0712345678\n")

        assertEquals(listOf("name", "column_2", "phone"), table.headers)
        assertEquals("x", table.rows[0]["column_2"])
    }
}
