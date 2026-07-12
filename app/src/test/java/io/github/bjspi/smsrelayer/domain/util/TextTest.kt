package io.github.bjspi.smsrelayer.domain.util

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {

    @Test
    fun `collapses whitespace and truncates`() {
        assertEquals("a b c", "  a\n\tb   c  ".compactSingleLine(100))
        assertEquals("abc", "abcdef".compactSingleLine(3))
        assertEquals("", (null as String?).compactSingleLine(10))
    }

    @Test
    fun `compact message includes type and detail`() {
        assertEquals("IOException: boom", IOException("boom").compactMessage())
        assertEquals("IOException", IOException().compactMessage())
    }
}
