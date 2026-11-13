package store

// OwnerBindings is the product's supported native endpoint matrix for the
// Host's own user. A session or workspace binding without an id covers every
// resource of that kind; the Host expands it against its own registry. The
// Gateway still validates each call and the Host's policy still decides it.
func OwnerBindings() []GrantBinding {
	return []GrantBinding{
		{Resource: GrantResource{Kind: "global"}, Actions: []string{
			"remoteAccess/follow", "session/modelCatalog", "session/canOpenWorkspacePath",
			"session/workspacePathApplications", "session/initializeDefaultModel",
			"fileReferences/list", "skills/list", "fileUploads/upload",
			"directoryPicker/list", "directoryPicker/createDirectory", "directoryPicker/pick",
			"workspace/create", "workspace/initializeDefault",
			"terminal/list", "terminal/create", "terminal/close", "terminal/environment",
			"terminal/rename", "terminal/shells", "terminal/retain",
			// Forwarded \$events: scoped waterfalls reach answer peers; broadcasts
			// reach non-owner watchers.
			"event:approval/request", "event:user-questions/request",
			"event:approval/decided", "event:api-session/status", "event:api-session/activity",
		}},
		{Resource: GrantResource{Kind: "session"}, Actions: []string{
			"session/list", "session/search", "session/create", "session/follow", "session/page",
			"session/prompt", "session/cancel", "session/updateQueue", "session/selectModel",
			"session/fork", "session/rename", "session/attachment", "session/projections",
			"session/control", "session/openWorkspacePath",
			"userQuestions/answer", "userQuestions/attachWait",
		}},
		{Resource: GrantResource{Kind: "workspace"}, Actions: []string{
			"workspace/follow", "workspace/archiveSession", "workspace/unarchiveSession",
			"workspace/rename", "workspace/delete", "workspace/insertBefore",
			"workspace/insertSessionBefore", "workspace/pinSession", "workspace/unpinSession",
		}},
	}
}
