# YouTrack issue: public API to repaint an editor tab's colour

File this on the `IDEA` project at <https://youtrack.jetbrains.com/newIssue?project=IDEA>, under
subsystem **Editor. Editor Tabs**, type **Feature**. It asks for the one platform API PrismTabs
cannot do without and cannot get publicly; item 15 of `release-readiness-gaps.md` is the wider task
it belongs to, and the README section "Internal API: what the Marketplace asked for" is the
evidence behind it.

Everything below the rule is the issue body, ready to paste.

---

**Summary:** `editorTabColorProvider` has no public "my answer has changed" call

**Description:**

`com.intellij.editorTabColorProvider` lets a plugin colour editor tabs, but the platform caches the
result and there is no public API to invalidate it. A plugin whose colours change in response to
anything other than opening a file has no supported way to get the strip to re-ask.

The only call that works is `FileEditorManagerImpl.queueUpdateFile(VirtualFile)`, which is
`@ApiStatus.Internal` and declared on the impl class rather than on `FileEditorManagerEx`. JetBrains
Marketplace refuses uploads that use it, so the extension point is effectively unusable for any
plugin whose colours are not static.

**What is cached, and by what**

Tracing 2024.2 and 2026.3 bytecode, the tab background comes from
`EditorsSplitters.updateFileBackgroundColorAsync`, which reads
`EditorTabPresentationUtil.getEditorTabBackgroundColor(project, file)` and writes
`TabInfo.setTabColor`. The only route to it is `FileEditorManagerImpl`'s `fileUpdateChannel`, fed by
`queueUpdateFile`.

The public calls that look like they should work do not:

| Call | What it reaches |
| --- | --- |
| `FileStatusManager.fileStatusChanged(file)` | `updateFileColor` + icon — the VCS foreground `ColorKey`, not the tab background |
| `FileEditorManagerEx.refreshIcons()` | Icons only |
| `FileEditorManagerEx.updateFileName(file)` | Titles only |

**Requested API**

A public method on `FileEditorManager` or `FileEditorManagerEx` that asks the platform to recompute
one file's tab presentation, along the lines of:

```java
void updateFilePresentation(@NotNull VirtualFile file);
```

The existing internal behaviour is the right behaviour: queue the file, recompute off the EDT, apply
on it. Only the visibility and the declaring type need to change.

**Why a plugin needs it**

PrismTabs (<https://github.com/prismtabs/prismtabs-feedback>) groups editor tabs under a
user-chosen colour. Every one of these has to repaint tabs that did not themselves change:

- a file is added to or removed from a group;
- a group is recoloured or deleted;
- a newly opened file auto-joins the active group;
- a grouped file is renamed or moved, and the assignment follows it;
- the plugin is disabled or uninstalled, and its colours must come off the strip — without this,
  tabs keep the colours of a plugin that is no longer installed until the next IDE start, since a
  disable never prompts for a restart.

Without an invalidation call, each of these leaves the strip showing a stale colour until the
platform happens to refresh that tab for its own reasons.
