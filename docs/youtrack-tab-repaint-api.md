# YouTrack issue: public API to repaint an editor tab's colour

File this on the `IDEA` project at <https://youtrack.jetbrains.com/newIssue?project=IDEA>, under
subsystem **UI. Tabs**, type **Feature**. It asks for the one platform API PrismTabs cannot do
without and cannot get publicly; item 15 of `release-readiness-gaps.md` is the wider task it
belongs to, and the README section "Internal API: what the Marketplace asked for" is the evidence
behind it.

Before filing, check <https://youtrack.jetbrains.com/issues?q=%23Feature%20%23Unresolved%20> for
an existing request covering `editorTabColorProvider` invalidation; vote/comment there instead if
one exists.

**Title:** `editorTabColorProvider` has no public "my answer has changed" call

Everything below the rule is the issue body, in JetBrains' own template, ready to paste.

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
