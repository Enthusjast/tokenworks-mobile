package cn.edu.ustc.tokenworks

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLayoutTest {
    @Test fun invalidDimensionsNeverReserveColumns() {
        for (viewport in listOf(WorkspaceViewport(0, 960), WorkspaceViewport(1280, 0),
            WorkspaceViewport(-1, 960), WorkspaceViewport(1280, -1))) {
            assertEquals(
                WorkspaceLayoutSpec(WorkspaceSize.COMPACT, WorkspaceLeftPane.NONE, 0.dp, 0.dp, false),
                workspaceLayout(viewport, hasSession = true, panelRequested = true, navigationExpanded = true),
            )
        }
    }

    @Test fun shortLandscapeRemainsCompactEvenAtDesktopWidths() {
        for (width in listOf(320, 600, 840, 1280)) {
            assertEquals(WorkspaceSize.COMPACT,
                workspaceLayout(WorkspaceViewport(width, 479), true, true, true).size)
        }
        assertEquals(WorkspaceSize.EXPANDED, workspaceLayout(WorkspaceViewport(840, 480), true, true, true).size)
    }

    @Test fun everyWidthBoundaryUsesTheSpecifiedClass() {
        val expected = listOf(
            320 to WorkspaceSize.COMPACT,
            599 to WorkspaceSize.COMPACT,
            600 to WorkspaceSize.MEDIUM,
            839 to WorkspaceSize.MEDIUM,
            840 to WorkspaceSize.EXPANDED,
            1119 to WorkspaceSize.EXPANDED,
            1120 to WorkspaceSize.EXPANDED,
            1280 to WorkspaceSize.EXPANDED,
        )
        for ((width, size) in expected) {
            assertEquals("width=$width", size, workspaceLayout(WorkspaceViewport(width, 960), false, false, true).size)
        }
    }

    @Test fun compactAndMediumNeverReservePermanentColumns() {
        for (width in listOf(320, 599, 600, 839)) {
            for (session in listOf(false, true)) {
                for (panel in listOf(false, true)) {
                    for (expanded in listOf(false, true)) {
                        val layout = workspaceLayout(WorkspaceViewport(width, 960), session, panel, expanded)
                        assertEquals(WorkspaceLeftPane.NONE, layout.leftPane)
                        assertEquals(0.dp, layout.navigationWidth)
                        assertEquals(0.dp, layout.panelWidth)
                        assertEquals(false, layout.panelShown)
                    }
                }
            }
        }
    }

    @Test fun expandedColumnsMatchTheReferenceRules() {
        data class Case(
            val width: Int, val session: Boolean, val panel: Boolean, val expanded: Boolean,
            val pane: WorkspaceLeftPane, val navigation: Int, val panelWidth: Int, val shown: Boolean,
        )
        val cases = listOf(
            // 840 keeps the panel at its 360 dp floor while chat still holds 412 dp.
            Case(840, true, true, true, WorkspaceLeftPane.RAIL, 68, 360, true),
            Case(840, true, false, true, WorkspaceLeftPane.RAIL, 68, 360, false),
            Case(960, true, false, true, WorkspaceLeftPane.SIDEBAR, 320, 360, false),
            Case(960, true, true, true, WorkspaceLeftPane.RAIL, 68, 360, true),
            Case(1119, true, false, true, WorkspaceLeftPane.SIDEBAR, 320, 360, false),
            Case(1119, true, true, true, WorkspaceLeftPane.RAIL, 68, 360, true),
            Case(1120, true, false, true, WorkspaceLeftPane.SIDEBAR, 320, 360, false),
            Case(1120, true, true, true, WorkspaceLeftPane.RAIL, 68, 360, true),
            Case(1280, true, false, true, WorkspaceLeftPane.SIDEBAR, 320, 384, false),
            Case(1280, true, true, true, WorkspaceLeftPane.RAIL, 68, 384, true),
            // Only past ~1320 dp can a 320 dp sidebar and the panel coexist with 600 dp of chat.
            Case(1340, true, true, true, WorkspaceLeftPane.SIDEBAR, 320, 400, true),
            Case(1440, true, true, true, WorkspaceLeftPane.SIDEBAR, 320, 420, true),
            Case(1280, true, true, false, WorkspaceLeftPane.RAIL, 68, 384, true),
            Case(1280, false, true, true, WorkspaceLeftPane.SIDEBAR, 320, 384, false),
            Case(1280, false, false, true, WorkspaceLeftPane.SIDEBAR, 320, 384, false),
        )
        for (case in cases) {
            val layout = workspaceLayout(
                WorkspaceViewport(case.width, 960), case.session, case.panel, case.expanded,
            )
            assertEquals("width=${case.width} panel=${case.panel} expanded=${case.expanded}",
                listOf(case.pane, case.navigation.dp, case.panelWidth.dp, case.shown),
                listOf(layout.leftPane, layout.navigationWidth, layout.panelWidth, layout.panelShown))
            assertEquals(WorkspaceSize.EXPANDED, layout.size)
        }
    }

    @Test fun panelWidthIsThirtyPercentSnappedAndClamped() {
        val expected = listOf(840 to 360, 960 to 360, 1280 to 384, 1340 to 400, 1440 to 420)
        for ((width, panel) in expected) {
            assertEquals("width=$width", panel.dp,
                workspaceLayout(WorkspaceViewport(width, 960), true, false, true).panelWidth)
        }
    }

    @Test fun chatNeverDropsBelowFourHundredWhenThePanelIsShown() {
        for (width in 840..1600 step 8) {
            val layout = workspaceLayout(WorkspaceViewport(width, 960), true, true, true)
            if (!layout.panelShown) continue
            assertTrue("width=$width", width.dp - layout.navigationWidth - layout.panelWidth >= 400.dp)
        }
    }

    @Test fun sidebarOnlyUnfoldsWhenChatKeepsSixHundred() {
        for (width in 840..1600 step 8) {
            for (panel in listOf(false, true)) {
                val layout = workspaceLayout(WorkspaceViewport(width, 960), true, panel, true)
                if (layout.leftPane != WorkspaceLeftPane.SIDEBAR) continue
                val chat = width.dp - layout.navigationWidth - (if (layout.panelShown) layout.panelWidth else 0.dp)
                assertTrue("width=$width panel=$panel chat=$chat", chat >= 600.dp)
            }
        }
    }

    @Test fun collapsedPreferenceKeepsTheRailWithAndWithoutPanel() {
        for (width in listOf(840, 1119, 1120, 1280, 1440)) {
            for (panel in listOf(false, true)) {
                val layout = workspaceLayout(WorkspaceViewport(width, 960), true, panel, false)
                assertEquals(WorkspaceLeftPane.RAIL, layout.leftPane)
                assertEquals(68.dp, layout.navigationWidth)
                assertEquals(panel, layout.panelShown)
            }
        }
    }
}
