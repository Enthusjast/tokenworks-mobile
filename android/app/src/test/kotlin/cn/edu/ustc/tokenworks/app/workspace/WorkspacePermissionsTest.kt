package cn.edu.ustc.tokenworks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePermissionsTest {
    @Test fun followRequiresGlobalOrKindWideAuthority() {
        assertTrue(permissions("viewer", binding("global", null, "workspace/follow")).canFollow())
        assertTrue(permissions("viewer", binding("workspace", null, "workspace/follow")).canFollow())
        assertTrue(permissions("viewer", binding("session", null, "workspace/follow")).canFollow())
        assertFalse(permissions("viewer", binding("workspace", "one", "workspace/follow")).canFollow())
        assertFalse(permissions("viewer").canFollow())
    }

    @Test fun sessionReorderNeedsBothKindsAndDoesNotTreatEqualIdsAsEqualResources() {
        val workspace = binding("workspace", "same", "workspace/insertSessionBefore")
        val session = binding("session", "same", "workspace/insertSessionBefore")
        val other = binding("session", "other", "workspace/insertSessionBefore")
        assertFalse(permissions("operator", workspace).canWrite(WorkspaceAction.MOVE_SESSION, "same", "same"))
        assertFalse(permissions("operator", session).canWrite(WorkspaceAction.MOVE_SESSION, "same", "same"))
        assertFalse(permissions("operator", workspace, other).canWrite(WorkspaceAction.MOVE_SESSION, "same", "same"))
        assertTrue(permissions("operator", workspace, session).canWrite(WorkspaceAction.MOVE_SESSION, "same", "same"))
    }

    @Test fun writeRolesAndExactActionBindingsGateControls() {
        val create = binding("global", null, "workspace/create")
        val archive = binding("session", "one", "workspace/archiveSession", "session/follow", "session/list")
        val viewer = permissions("viewer", create, archive)
        assertFalse(viewer.canWrite(WorkspaceAction.CREATE))
        assertFalse(viewer.canWrite(WorkspaceAction.ARCHIVE, sessionId = "one"))
        assertTrue(viewer.canOpen("one"))
        assertTrue(viewer.canReadSessionSummary("one"))
        assertFalse(viewer.canReadSessionSummary("two"))
        val owner = permissions("owner", create, archive)
        assertTrue(owner.canWrite(WorkspaceAction.CREATE))
        assertTrue(owner.canWrite(WorkspaceAction.ARCHIVE, sessionId = "one"))
        assertFalse(owner.canWrite(WorkspaceAction.ARCHIVE, sessionId = "two"))
        assertFalse(owner.canWrite(WorkspaceAction.RESTORE, sessionId = "one"))
        assertFalse(owner.copy(writable = false).canWrite(WorkspaceAction.CREATE))
        assertFalse(owner.copy(expired = true).canWrite(WorkspaceAction.CREATE))
    }

    @Test fun pairedLanAuthorityDoesNotOverrideReadOnlyPresentation() {
        val paired = WorkspacePermissions(access = null, lanOwner = true, writable = true)
        assertTrue(paired.canFollow())
        assertTrue(paired.canWrite(WorkspaceAction.CREATE))
        assertFalse(paired.copy(writable = false).canWrite(WorkspaceAction.CREATE))
        assertFalse(paired.copy(lanOwner = false).canFollow())
        assertFalse(paired.copy(lanOwner = false).canWrite(WorkspaceAction.CREATE))
    }

    private fun binding(kind: String, id: String?, vararg actions: String): JsonObject {
        val resource = if (id == null) jsonObject("kind" to kind.json())
        else jsonObject("kind" to kind.json(), "id" to id.json())
        return jsonObject("resource" to resource, "actions" to JsonArray(actions.map(String::json)))
    }

    private fun permissions(role: String, vararg bindings: JsonObject): WorkspacePermissions = WorkspacePermissions(
        RuntimeDirectoryEntry.parse(jsonObject(
            "id" to "44444444-4444-4444-8444-444444444444".json(),
            "host_id" to "55555555-5555-4555-8555-555555555555".json(),
            "access" to jsonObject("role" to role.json(), "bindings" to JsonArray(bindings.toList())),
        )),
        lanOwner = false,
        writable = true,
    )
}
