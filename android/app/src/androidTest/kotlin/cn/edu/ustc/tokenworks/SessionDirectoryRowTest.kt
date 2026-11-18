package cn.edu.ustc.tokenworks

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionDirectoryRowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pinPersistenceIsIsolatedByOwnerAndRuntime() {
        val context = rule.activity.applicationContext
        val owner = UUID.randomUUID().toString()
        val runtime = UUID.randomUUID().toString()
        try {
            SessionDirectoryPins(context, owner, runtime).toggle("shared-id")
            assertEquals(setOf("shared-id"), SessionDirectoryPins(context, owner, runtime).read())
            assertEquals(emptySet<String>(), SessionDirectoryPins(context, "other-$owner", runtime).read())
            assertEquals(emptySet<String>(), SessionDirectoryPins(context, owner, "other-$runtime").read())
            assertEquals(emptySet<String>(), SessionDirectoryPins(context, owner, runtime).toggle("shared-id"))
        } finally {
            context.deleteSharedPreferences(directoryPinFile(owner, runtime))
        }
    }

    @Test fun twoSwipeDirectionsAndSemanticActionsLeaveTheRowInPlace() {
        var archives = 0
        var renames = 0
        val selecting = mutableStateOf(false)
        val checked = mutableStateOf(false)
        val title = "Audit test row"
        val entry = SessionDirectoryEntry.from(DshSessionListResultItemsItem(
            sessionId = "row", agentAvailable = true, updatedAt = 1.0, running = false, blank = false,
            projections = DshOptional.Value(DshSessionListResultItemsItemProjections("cached", 1.0,
                DshSessionListResultItemsItemProjectionsValues(title = DshOptional.Value(title)))),
        ))
        rule.setContent {
            TokenWorksTheme {
                Box(Modifier.padding(24.dp)) {
                    SessionDirectoryRow(entry, 0, 1, opened = false, unread = false, archived = false, pinned = false,
                        selecting = selecting.value, checked = checked.value, enabled = true, preview = null, snippet = null,
                        open = { checked.value = !checked.value }, select = { selecting.value = true }, pin = null,
                        rename = { renames++ }, fork = null, archive = { archives++ }, collectionIndex = 0)
                }
            }
        }
        val row = rule.onNodeWithText(title)
        row.performTouchInput { swipeRight() }.assertIsDisplayed()
        row.performTouchInput { swipeLeft() }.assertIsDisplayed()
        val config = row.fetchSemanticsNode().config
        val actions = config[SemanticsActions.CustomActions]
        val archiveLabel = rule.activity.getString(R.string.directory_archive_action)
        val selectLabel = rule.activity.getString(R.string.directory_select)
        assertEquals(0, config[SemanticsProperties.CollectionItemInfo].rowIndex)
        rule.runOnIdle {
            assertEquals(1, archives)
            assertEquals(1, renames)
            actions.single { it.label == archiveLabel }.action()
            assertEquals(2, archives)
            actions.single { it.label == selectLabel }.action()
        }
        row.performClick().assertIsOn()
    }
}
