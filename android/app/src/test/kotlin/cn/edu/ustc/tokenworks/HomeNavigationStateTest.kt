package cn.edu.ustc.tokenworks

import io.github.enthusjast.tokenworks.command.CommandOwner
import io.github.enthusjast.tokenworks.network.SessionReadPhase
import io.github.enthusjast.tokenworks.network.SessionReadState
import org.junit.Assert.*
import org.junit.Test

class HomeNavigationStateTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private val owner = CommandOwner(id, id, "a".repeat(64), id, id)
    private fun state() = HomeState(phase = HomePhase.READY, presentationOwner = owner,
        selectedRuntimeId = id, selectedSessionId = id,
        runtimes = listOf(RuntimeRow(id, id, "public-fixture", "公开电脑")),
        reader = SessionReadState(SessionReadPhase.READY))

    @Test fun aStaleResourceTargetCannotAttachToTheNextSession() {
        val navigation = HomeNavigation()
        val first = state()
        navigation.openChatDetail(first, ChatDetailSection.COMMANDS, other)
        val original = requireNotNull(navigation.chatDetail)
        val next = first.copy(selectedSessionId = other)
        navigation.reconcileChatDetail(next)
        assertNull(navigation.chatDetail)
        navigation.setChatDetail(next, original)
        assertNull(navigation.chatDetail)
        navigation.openChatDetail(next, ChatDetailSection.FILES)
        assertEquals(other, navigation.chatDetail?.sessionId)
        assertEquals(ChatDetailSection.FILES, navigation.chatDetail?.section)
    }

    @Test fun identityHostRuntimeAndReaderRetirementImmediatelyRemoveThePanel() {
        val first = state()
        val changed = listOf(
            first.copy(presentationOwner = CommandOwner(other, id, "a".repeat(64), id, id)),
            first.copy(runtimes = listOf(RuntimeRow(id, other, "public-fixture", "公开电脑"))),
            first.copy(selectedRuntimeId = other),
            first.copy(reader = null),
            first.copy(reader = SessionReadState(SessionReadPhase.REVOKED)),
            first.copy(reader = SessionReadState(SessionReadPhase.CLOSED)),
            first.copy(phase = HomePhase.SIGNED_OUT),
        )
        for (next in changed) {
            val navigation = HomeNavigation()
            navigation.openChatDetail(first, ChatDetailSection.INFO)
            assertNotNull(navigation.chatDetail)
            navigation.reconcileChatDetail(next)
            assertNull(navigation.chatDetail)
        }
    }

    @Test fun presentationDoesNotRecreateAuthorityWhileOffline() {
        val navigation = HomeNavigation()
        val first = state()
        navigation.openChatDetail(first, ChatDetailSection.INFO)
        val target = navigation.chatDetail
        navigation.reconcileChatDetail(first.copy(reader = SessionReadState(SessionReadPhase.OFFLINE)))
        assertSame(target, navigation.chatDetail)
        navigation.closePresentationPanels()
        assertNull(navigation.chatDetail)
        assertFalse(navigation.drawerOpen)
        assertFalse(navigation.hostSwitcherOpen)
        navigation.openChatDetail(first.copy(presentationOwner = null), ChatDetailSection.INFO)
        assertNull(navigation.chatDetail)
    }
}
