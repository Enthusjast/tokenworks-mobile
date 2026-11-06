package cn.edu.ustc.tokenworks

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceLayoutTest {
    @Test fun invalidDimensionsNeverReserveColumns() {
        for (viewport in listOf(WorkspaceViewport(0, 960), WorkspaceViewport(1280, 0),
            WorkspaceViewport(-1, 960), WorkspaceViewport(1280, -1))) {
            assertEquals(WorkspaceLayoutSpec(WorkspaceSize.COMPACT, 0.dp, 0.dp),
                workspaceLayout(viewport, inspectorRequested = true, navigationExpanded = true))
        }
    }

    @Test fun shortLandscapeRemainsCompactEvenAtDesktopWidths() {
        for (width in listOf(320, 600, 840, 1280)) {
            assertEquals(WorkspaceLayoutSpec(WorkspaceSize.COMPACT, 0.dp, 0.dp),
                workspaceLayout(WorkspaceViewport(width, 479), true, true))
        }
        assertEquals(WorkspaceSize.EXPANDED, workspaceLayout(WorkspaceViewport(840, 480), true, true).size)
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
            assertEquals("width=$width", size, workspaceLayout(WorkspaceViewport(width, 960), false, true).size)
        }
    }

    @Test fun compactAndMediumNeverReservePermanentColumns() {
        for (width in listOf(320, 599, 600, 839)) {
            for (details in listOf(false, true)) {
                for (expanded in listOf(false, true)) {
                    val layout = workspaceLayout(WorkspaceViewport(width, 960), details, expanded)
                    assertEquals(0.dp, layout.navigationWidth)
                    assertEquals(0.dp, layout.inspectorWidth)
                }
            }
        }
    }

    @Test fun inspectorForcesARailOnlyBelow1120() {
        assertEquals(WorkspaceLayoutSpec(WorkspaceSize.EXPANDED, 72.dp, 360.dp),
            workspaceLayout(WorkspaceViewport(840, 960), true, true))
        assertEquals(WorkspaceLayoutSpec(WorkspaceSize.EXPANDED, 72.dp, 360.dp),
            workspaceLayout(WorkspaceViewport(1119, 960), true, true))
        assertEquals(WorkspaceLayoutSpec(WorkspaceSize.EXPANDED, 304.dp, 360.dp),
            workspaceLayout(WorkspaceViewport(1120, 960), true, true))
    }

    @Test fun closingInspectorRestoresTheUnmodifiedNavigationPreference() {
        val viewport = WorkspaceViewport(960, 800)
        for (expanded in listOf(false, true)) {
            val before = workspaceLayout(viewport, false, expanded)
            assertEquals(72.dp, workspaceLayout(viewport, true, expanded).navigationWidth)
            assertEquals(before, workspaceLayout(viewport, false, expanded))
            assertEquals(if (expanded) 304.dp else 72.dp, before.navigationWidth)
        }
    }

    @Test fun explicitCollapseIsPreservedWithAndWithoutInspector() {
        for (width in listOf(840, 1119, 1120, 1280)) {
            for (details in listOf(false, true)) {
                val layout = workspaceLayout(WorkspaceViewport(width, 960), details, false)
                assertEquals(72.dp, layout.navigationWidth)
                assertEquals(if (details) 360.dp else 0.dp, layout.inspectorWidth)
            }
        }
    }

    @Test fun specifiedMinimumWidthsAreNotConsumedByDividers() {
        val narrow = workspaceLayout(WorkspaceViewport(840, 960), true, true)
        val wide = workspaceLayout(WorkspaceViewport(1120, 960), true, true)
        assertEquals(408.dp, 840.dp - narrow.navigationWidth - narrow.inspectorWidth)
        assertEquals(456.dp, 1120.dp - wide.navigationWidth - wide.inspectorWidth)
    }
}
