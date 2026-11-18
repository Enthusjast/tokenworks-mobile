package cn.edu.ustc.tokenworks

import org.junit.Assert.*
import org.junit.Test

class TerminalEmulatorTest {
    private val esc = "\u001b"
    private val csi = "$esc["

    @Test fun streamingStylesAndWideCellsSurviveChunkBoundaries() {
        val term = TerminalEmulator(12, 2, 4)
        term.feed("${csi}1;31;48;5;")
        term.feed("236mA中\ud83d")
        term.feed("\ude42e\u0301${csi}0mZ")
        val line = term.display().lines.first()
        assertEquals("A中🙂éZ", line.text)
        assertEquals(1, line.cells[0]!!.style.foreground)
        assertEquals(236, line.cells[1]!!.style.background)
        assertTrue(line.cells[0]!!.style.has(TerminalStyle.BOLD))
        assertEquals(0, line.cells[2]!!.columns)
        assertEquals(2, line.cells[3]!!.columns)
        assertEquals(TerminalStyle.Plain, line.cells[6]!!.style)
        term.feed("${csi}38:2::12:34:56m${csi}2;1HX")
        assertEquals(TerminalStyle.RGB or 0x0c2238, term.display().lines[1].cells[0]!!.style.foreground)
        term.feed("${csi}1;3H!")
        assertEquals("A !🙂éZ", term.display().lines.first().text)
        assertEquals("A中🙂éZ", line.text)
    }

    @Test fun readySnapshotCanRestoreAnInteractiveAlternateScreen() {
        val term = TerminalEmulator(12, 4, 3)
        term.feed("main\r\n$ ${csi}?1049h${csi}H${csi}?1h${csi}?25l${csi}?2004h")
        term.feed("${csi}2;2H${csi}7mstatus${csi}0m${csi}2;2H${csi}2P")
        val alt = term.display()
        assertTrue(alt.alternate)
        assertNull(alt.cursor)
        assertTrue(alt.keyModes.applicationCursor)
        assertTrue(alt.keyModes.bracketedPaste)
        assertEquals(" atus", alt.lines[1].text)
        assertTrue(alt.lines[1].cells[1]!!.style.has(TerminalStyle.INVERSE))
        assertFalse(alt.text.contains("main"))
        term.resize(10, 3)
        assertEquals(" atus", term.display().lines[1].text)
        term.feed("${csi}?1049l${csi}?25h${csi}5 q!")
        val main = term.display()
        assertFalse(main.alternate)
        assertEquals("main\n$ !", main.text)
        assertEquals(TerminalCursorShape.BAR, main.cursor!!.shape)
    }

    @Test fun scrollRegionHistoryAndErasesAreSeparate() {
        val term = TerminalEmulator(8, 4, 2)
        term.feed("one\r\ntwo\r\nthree\r\nfour${csi}2;3r${csi}?6h${csi}2;1H\nNEW")
        assertEquals(listOf("one", "three", "NEW", "four"), term.display().lines.map { it.text })
        term.feed("${csi}?6l${csi}r${csi}4;1H\na\r\nb\r\nc")
        assertEquals(6, term.display().lines.size)
        val visible = term.display().lines.takeLast(4).map { it.text }
        term.feed("${csi}3J")
        assertEquals(visible, term.display().lines.map { it.text })
        term.feed("${csi}2J")
        assertTrue(term.display().text.isEmpty())
    }

    @Test fun delayedWrapAndReflowKeepCursorInsertionPointAndSnapshotImmutable() {
        val term = TerminalEmulator(4, 4, 3)
        term.feed("abcd")
        val before = term.display()
        assertFalse(before.lines[0].wraps)
        term.feed("ef")
        assertTrue(term.display().lines[0].wraps)
        assertFalse(before.lines[0].wraps)
        assertEquals("abcd", before.lines[0].text)
        term.resize(3, 4)
        term.feed("g")
        assertEquals("abcdefg", term.display().transcript())
        term.resize(8, 4)
        term.feed("h")
        assertEquals("abcdefgh", term.display().transcript())
    }

    @Test fun searchSpansSoftWrapsAndMapsUnicodeToCells() {
        val term = TerminalEmulator(6, 3, 5)
        term.feed("🙂 AB C\r\nNext")
        val display = term.display()
        val hit = display.search("ab c").single()
        assertEquals(listOf(
            TerminalSearchRange(display.lines[0].id, 3, 6),
            TerminalSearchRange(display.lines[1].id, 0, 1),
        ), hit.ranges)
        assertEquals(2, display.search("🙂").single().ranges.single().end)
        assertTrue(display.search("cNext").isEmpty())
        assertEquals("🙂 AB C\nNext", display.transcript())
    }

    @Test fun unhandledControlStringsDoNotBecomeTextOrChangeOtherModes() {
        val term = TerminalEmulator(30, 2, 0)
        term.feed("A${esc}]52;c;clipboard${esc}")
        term.feed("\\B${esc}Pignored\u0007more${esc}\\C${csi}${"9".repeat(1000)}zD")
        term.feed("${esc}(0lqk${esc}(B")
        assertEquals("ABCD┌─┐", term.display().text)
        assertEquals(TerminalKeyModes(), term.display().keyModes)
    }

    @Test fun scrollbackAndUnicodeClusterMemoryStayBounded() {
        val term = TerminalEmulator(8, 2, 3)
        repeat(12) { term.feed("line$it\r\n") }
        assertEquals(5, term.display().lines.size)
        assertTrue(term.display().text.contains("line11"))
        assertFalse(term.display().text.contains("line0"))
        term.feed("a" + "\u0301".repeat(100))
        assertEquals(32, term.display().lines.last().cells[0]!!.text.length)
    }

    @Test fun modeAwareKeysAndControlLettersSendExactTerminalInput() {
        val modes = TerminalKeyModes(applicationCursor = true, applicationKeypad = true)
        assertEquals("${csi}A", TerminalKeyModes().encode(TerminalKey.UP))
        assertEquals("${esc}OA", modes.encode(TerminalKey.UP))
        assertEquals("${esc}OD", modes.encode(TerminalKey.LEFT))
        assertEquals("${esc}OC", modes.encode(TerminalKey.RIGHT))
        assertEquals("${csi}1;6B", modes.encode(TerminalKey.DOWN, TerminalModifiers(ctrl = true, shift = true)))
        assertEquals("${csi}Z", modes.encode(TerminalKey.TAB, TerminalModifiers(shift = true)))
        assertEquals("${esc}OM", modes.encode(TerminalKey.KP_ENTER))
        assertEquals("\u0003", terminalTypedText("c", TerminalModifiers(ctrl = true)))
        assertEquals("\u0004", terminalTypedText("d", TerminalModifiers(ctrl = true)))
        assertEquals("${esc}x", terminalTypedText("x", TerminalModifiers(alt = true)))
    }
}
