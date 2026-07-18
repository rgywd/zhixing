package me.rerere.rikkahub.data.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkHtmlReportTest {
    @Test
    fun `injects restrictive CSP into a full document`() {
        val secured = secureHtmlReportDocument("报告", "<html><head><title>x</title></head><body>ok</body></html>")

        assertTrue(secured.contains("default-src 'none'"))
        assertTrue(secured.contains("script-src 'none'"))
        assertTrue(secured.indexOf("Content-Security-Policy") < secured.indexOf("<title>x</title>"))
    }

    @Test
    fun `wraps fragments and escapes title`() {
        val secured = secureHtmlReportDocument("<报告>", "<p>ok</p>")

        assertTrue(secured.contains("<body><p>ok</p></body>"))
        assertTrue(secured.contains("&lt;报告&gt;"))
        assertFalse(secured.contains("<title><报告></title>"))
    }
}
