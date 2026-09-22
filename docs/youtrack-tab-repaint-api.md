# YouTrack issue: public API to repaint an editor tab's colour

**Filed 2026-09-22:**
[IJPL-256289](https://youtrack.jetbrains.com/issue/IJPL-256289/editorTabColorProvider-has-no-public-my-answer-has-changed-call)
— JetBrains routed it to `IJPL` (IntelliJ Platform), not `IDEA` as this draft assumed when it was
written. What follows is the draft as submitted, kept for the record; track the issue itself for
its current status, not this file.

**Invalid — close it 2026-09-22.** The call this issue asks for already exists:
`FileEditorManager.updateFilePresentation(VirtualFile)`, public, unannotated, present on both ends
of the matrix, and its implementation calls `queueUpdateFile` itself. A Marketplace reviewer
pointed it out in reply to the rejection. It was missed here because a `javap` dump of
`FileEditorManager` was read through `head -40` and the method sits below the cut — so the issue's
premise ("no public API to invalidate it") is simply false. Close it as invalid rather than leaving
JetBrains to triage a request for something they already ship. The body below is kept only as the
record of what was filed.

**Title:** `editorTabColorProvider` has no public "my answer has changed" call

Everything below the rule is the issue body as filed.

---

## Scenario

1. A plugin implements `com.intellij.editorTabColorProvider` to colour editor tabs from its own
   state (for example, a user-defined tab-grouping colour).
2. That state changes for reasons other than the file being opened — the tab's group is
   recoloured, the tab moves to a different group or out of one, or the plugin itself is disabled
   or uninstalled and needs to strip its colours off the strip.
3. The platform caches the tab's background colour rather than asking the provider again, and
   nothing public invalidates that cache for one file.
4. The only call that does invalidate it is `FileEditorManagerImpl.queueUpdateFile(VirtualFile)`,
   declared on the impl class and marked `@ApiStatus.Internal`. JetBrains Marketplace refuses
   uploads that use it, so the extension point is effectively unusable for any plugin whose
   colours are not static for the life of the tab.

Traced through 2024.2 and 2026.3 bytecode: the tab background comes from `EditorsSplitters
.updateFileBackgroundColorAsync`, which reads `EditorTabPresentationUtil
.getEditorTabBackgroundColor(project, file)` and writes `TabInfo.setTabColor`. The only route to
it is `FileEditorManagerImpl`'s `fileUpdateChannel`, fed by `queueUpdateFile`. The public calls
that look like they should work do not:

| Call | What it reaches |
| --- | --- |
| `FileStatusManager.fileStatusChanged(file)` | `updateFileColor` + icon — the VCS foreground `ColorKey`, not the tab background |
| `FileEditorManagerEx.refreshIcons()` | Icons only |
| `FileEditorManagerEx.updateFileName(file)` | Titles only |

**Actual result** (the result the user is seeing now): no public API exists to ask the platform to
recompute one file's tab colour. A plugin whose colouring depends on anything other than the file
itself either ships a call into `FileEditorManagerImpl` — internal API, and Marketplace refuses
the upload over it — or leaves stale colours on the strip until the platform happens to refresh
that tab for an unrelated reason (a file re-open, a VCS status change, and so on).

**Desirable result** (the desirable result after the feature will be implemented): a public method
on `FileEditorManager` or `FileEditorManagerEx` that asks the platform to recompute one file's tab
presentation, along the lines of:

```java
void updateFilePresentation(@NotNull VirtualFile file);
```

The existing internal behaviour is the right behaviour — queue the file, recompute off the EDT,
apply on it — only the visibility and the declaring type need to change.

## Additional information

**Why this is needed**, concretely: PrismTabs groups editor tabs under a user-chosen colour, and
every one of these has to repaint tabs that did not themselves change:

- a file is added to or removed from a group;
- a group is recoloured or deleted;
- a newly opened file auto-joins the active group;
- a grouped file is renamed or moved, and the assignment follows it;
- the plugin is disabled or uninstalled, and its colours must come off the strip — without this,
  tabs keep the colours of a plugin that is no longer installed until the next IDE start, since a
  disable never prompts for a restart.

Without an invalidation call, each of these leaves the strip showing a stale colour until the
platform happens to refresh that tab for its own reasons.
