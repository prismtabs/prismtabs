package io.prismtabs.uitest

/**
 * Throwaway files under the fixture project, created, renamed, moved and deleted through the VFS.
 *
 * Tests that change files on disk make their own rather than touching the committed `Sample.kt`
 * family: the sandbox opens `src/uiTest/resources/fixture-project` straight from the source tree, so
 * renaming or deleting one of those would change the working copy for good.
 *
 * Every mutation runs in a real write action, dispatched off the EDT with an explicit
 * `ModalityState.nonModal()`, exactly as [IdeaFrame.forceSaveSettings] documents at length: a
 * `remote-robot` script runs under a modality that is not write-safe, and
 * `ApplicationImpl.invokeAndWait` only establishes a write-safe context on its off-EDT branch.
 * Without it the VFS write still lands, but the platform logs a `TransactionGuardImpl`
 * "Write-unsafe context!" SEVERE for every call — the noise a log check then has to explain away.
 */
private fun IdeaFrame.inWriteAction(body: String) {
    runJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importPackage(com.intellij.openapi.vfs)
        importClass(com.intellij.openapi.application.ApplicationManager)
        importClass(com.intellij.openapi.application.ModalityState)

        var frameHelper = ProjectFrameHelper.getFrameHelper(component)
        var project = frameHelper.getProject()
        var app = ApplicationManager.getApplication()
        var task = new Runnable({
            run: function() {
                app.runWriteAction(new Runnable({
                    run: function() {
                        var root = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath())
                        $body
                    }
                }))
            }
        })
        app.invokeAndWait(task, ModalityState.nonModal())
        """,
        false,
    )
}

/** JS that leaves `dir` bound to the fixture-project directory [path] names, creating it if needed. */
private fun ensureDirectoryJs(path: String): String =
    """
    var dir = root
    var segments = '$path'.split('/')
    for (var i = 0; i < segments.length; i++) {
        if (segments[i] == '') continue
        var existingDir = dir.findChild(segments[i])
        dir = existingDir != null ? existingDir : dir.createChildDirectory(null, segments[i])
    }
    """

/**
 * Creates [path] under the fixture project on disk, parent directories included, replacing anything
 * an interrupted earlier run left behind. `.kt` files get a one-line `object` body, anything else a
 * line of plain text.
 */
fun IdeaFrame.createFixtureFile(path: String) {
    val parent = path.substringBeforeLast('/', "")
    val name = path.substringAfterLast('/')
    inWriteAction(
        """
        ${ensureDirectoryJs(parent)}
        var stale = dir.findChild('$name')
        if (stale != null) stale.delete(null)
        var created = dir.createChildData(null, '$name')
        VfsUtil.saveText(created, '$name'.endsWith('.kt') ? 'object ' + '$name'.replace('.kt', '') + '\n' : 'fixture\n')
        """,
    )
}

/**
 * Deletes [path] under the fixture project through the VFS — the same call the platform's own delete
 * action ends in, which is what [io.prismtabs.listener.TabGroupVfsListener.before] hangs off. A no-op
 * for a path that is already gone, so it is safe in a `finally`.
 */
fun IdeaFrame.deleteFixtureFile(path: String) {
    inWriteAction(
        """
        var target = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + '/$path')
        if (target != null) target.delete(null)
        """,
    )
}

/**
 * Renames the file or directory at [path] to [newName] through `VirtualFile.rename` — what every
 * rename gesture ends in, and what fires the `VFilePropertyChangeEvent` that
 * [io.prismtabs.listener.TabGroupVfsListener.after] remaps an assignment on.
 */
fun IdeaFrame.renameFixtureFile(path: String, newName: String) {
    inWriteAction(
        """
        var target = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + '/$path')
        if (target == null) throw new java.lang.IllegalStateException('no fixture file at $path')
        target.rename(null, '$newName')
        """,
    )
}

/**
 * Moves the file or directory at [path] into the fixture-project directory [toDirectory] (created if
 * needed) through `VirtualFile.move` — what both a Project view drag and Refactor | Move end in, and
 * what fires the `VFileMoveEvent` the remap listens for. Driven here rather than by a drag, which
 * `java.awt.Robot` performs unreliably, because the gesture is the platform's and the event is the
 * plugin's subject.
 */
fun IdeaFrame.moveFixtureFile(path: String, toDirectory: String) {
    inWriteAction(
        """
        var target = LocalFileSystem.getInstance().refreshAndFindFileByPath(project.getBasePath() + '/$path')
        if (target == null) throw new java.lang.IllegalStateException('no fixture file at $path')
        ${ensureDirectoryJs(toDirectory)}
        target.move(null, dir)
        """,
    )
}

/**
 * How many files are assigned to the group named [groupName], read straight off the service; `-1`
 * when there is no such group.
 *
 * [IdeaFrame.groupNameFor] cannot answer this once a file is gone: it resolves the path through
 * `LocalFileSystem.findFileByPath` to get a URL, and a deleted file resolves to null. Membership is
 * a URL -> group id map that outlives the `VirtualFile`, so counting members is the assertion that
 * still works after a deletion.
 */
fun IdeaFrame.memberCount(groupName: String): Int = callJs(
    """
    importPackage(com.intellij.openapi.wm.impl)
    importPackage(com.intellij.ide.plugins)
    importPackage(com.intellij.openapi.extensions)

    const frameHelper = ProjectFrameHelper.getFrameHelper(component)
    const project = frameHelper.getProject()
    const pluginLoader = PluginManagerCore.getPlugin(PluginId.getId('io.prismtabs')).getPluginClassLoader()
    const service = project.getService(java.lang.Class.forName('io.prismtabs.service.TabGroupService', true, pluginLoader))

    const groups = service.groups()
    let count = -1
    for (let i = 0; i < groups.size(); i++) {
        if (groups.get(i).getName() == '$groupName') count = service.membersOf(groups.get(i).getId()).size()
    }
    count
    """,
    true,
)

/**
 * Stops the IDE's Git integration ever raising its "Add File to Git" prompt in this project.
 *
 * Nothing to do with PrismTabs, and unavoidable here: the fixture project sits inside this
 * repository's own working copy (`.idea/vcs.xml` maps it), so every [createFixtureFile] makes the
 * Git integration offer to stage the new file. That offer is a **modal `DialogWrapper`**, and a
 * modal on the sandbox is fatal to a scripted run rather than merely inconvenient — it blocks the
 * EDT, and robot-server dispatches on the EDT, so the whole server stops answering and every
 * subsequent call in the run dies with `SocketTimeoutException`, in this class and in every class
 * that runs after it.
 *
 * Diagnosed from a `jstack` of the wedged sandbox, which parked the EDT in
 * `VcsVFSListener.performAddingWithConfirmation` → `selectFilesToAdd` →
 * `ConfirmationDialog.requestForConfirmation` → `Dialog.show`.
 *
 * Set to `DO_NOTHING_SILENTLY`, not `DO_ACTION_SILENTLY`: a test run must never stage anything into
 * the real repository. Applied to the shared confirmation *and* to every active VCS's, because
 * `VcsVFSListener` reads the one belonging to its own `AbstractVcs` and the two need not be the
 * same object.
 *
 * Not creating the dialog is the fix rather than dismissing it: by the time it is visible it has
 * already blocked the EDT, so a click could only ever land in the gap before the modal loop started.
 */
fun IdeaFrame.silenceAddToVcsConfirmation() {
    runJs(
        """
        importPackage(com.intellij.openapi.wm.impl)
        importClass(com.intellij.openapi.vcs.ProjectLevelVcsManager)
        importClass(com.intellij.openapi.vcs.VcsConfiguration)
        importClass(com.intellij.openapi.vcs.VcsShowConfirmationOption)

        var frameHelper = ProjectFrameHelper.getFrameHelper(component)
        var project = frameHelper.getProject()
        var manager = ProjectLevelVcsManager.getInstance(project)
        var silent = VcsShowConfirmationOption.Value.DO_NOTHING_SILENTLY
        var add = VcsConfiguration.StandardConfirmation.ADD

        manager.getStandardConfirmation(add, null).setValue(silent)

        // `var` in the loop body, never `const`/`let` — see RemoteRobotExtension's doc comment for
        // the measured Rhino scoping failure this avoids.
        var active = manager.getAllActiveVcss()
        for (var i = 0; i < active.length; i++) {
            manager.getStandardConfirmation(add, active[i]).setValue(silent)
        }
        """,
        true,
    )
}
