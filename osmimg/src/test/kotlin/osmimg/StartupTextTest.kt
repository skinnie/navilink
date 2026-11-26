package osmimg

import kotlin.test.*

/** Garmin's template as an eTrex 10 and an Edge 1040 write it. */
private val TEMPLATE = ("﻿<!-- Edit this file to display a message while your unit is powering on                -->\r\n" +
    "<!-- Allow one full power cycle after editing for your message to be updated           -->\r\n\r\n" +
    "<!-- Set the display number to the minimum number of seconds your message is displayed -->\r\n" +
    "<display = 0>\r\n\r\n<!-- Type your message on the next line -->").toByteArray(Charsets.UTF_8)

class StartupTextTest {
    @Test fun readsGarminsTemplate() = assertEquals(StartupText(0, ""), readStartupText(TEMPLATE))

    @Test fun writesTheMessageAfterGarminsCommentsAndReadsItBack() {
        val t = StartupText(5, "If found please call\n+351 900 000 000")
        val out = writeStartupText(TEMPLATE, t)
        val s = String(out, Charsets.UTF_8)
        assertTrue(s.startsWith("﻿<!-- Edit this file"), "BOM and Garmin's comments kept")
        assertTrue("<display = 5>\r\n" in s)
        assertTrue(s.endsWith("-->\r\nIf found please call\r\n+351 900 000 000"), s)
        assertEquals(t, readStartupText(out))
        // Writing again replaces the message instead of adding to it.
        assertEquals(StartupText(0, "Owner: A."), readStartupText(writeStartupText(out, StartupText(0, "Owner: A."))))
        assertEquals(StartupText(0, ""), readStartupText(writeStartupText(out, StartupText(0, ""))))
    }

    @Test fun refusesWhatGarminDoesNotTake() {
        assertNotNull(startupTextProblem("x".repeat(257), 0))
        assertNotNull(startupTextProblem("ok", 99))
        assertNull(startupTextProblem("ok\nline two", 10))
        assertFailsWith<IllegalStateException> { writeStartupText(TEMPLATE, StartupText(0, "x".repeat(300))) }
    }
}
