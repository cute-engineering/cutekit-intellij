package engineering.cute.cutekit.clion.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.CopyPasteDelegator
import com.intellij.ide.IdeView
import com.intellij.ide.TreeExpander
import com.intellij.ide.DeleteProvider
import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlin.LazyThreadSafetyMode
import kotlin.collections.ArrayDeque
import kotlin.io.path.pathString

private const val GIT_VCS_NAME = "Git"

class CutekitDependenciesToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CutekitDependenciesPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

private class CutekitDependenciesPanel(private val project: Project) : Disposable {
    private val collector = CutekitDependencyCollector(project)
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = object : Tree(treeModel), UiDataProvider {
        override fun uiDataSnapshot(sink: DataSink) {
            provideUiData(sink)
        }
    }
    private val copyPasteDelegator = CopyPasteDelegator(project, tree)
    private val connection: MessageBusConnection = project.messageBus.connect(this)
    @Volatile
    private var observedRootPaths: Set<String> = emptySet()
    @Volatile
    private var pendingSelectionPath: String? = null
    @Volatile
    private var registeredGitRoots: Set<String> = emptySet()

    val component: JComponent = buildUi()

    init {
        registerListeners()
        refresh()
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            selectCurrentEditorFile()
            applyPendingSelection()
        }
    }

    private fun buildUi(): JComponent {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.emptyText.text = "No CuteKit dependencies detected"
        tree.cellRenderer = CutekitTreeCellRenderer()
        TreeSpeedSearch(tree) { path ->
            val item = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TreeItem
            item?.speedSearchText() ?: ""
        }

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val path = tree.getClosestPathForLocation(event.x, event.y) ?: return false
                val row = tree.getRowForPath(path)
                if (row < 0) return false
                val bounds = tree.getRowBounds(row) ?: return false
                if (event.y < bounds.y || event.y > bounds.y + bounds.height) return false
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return false
                val file = (node.userObject as? TreeItem.File)?.file ?: return false
                if (file.isDirectory) {
                    return false
                }
                FileEditorManager.getInstance(project).openFile(file, true)
                return true
            }
        }.installOn(tree)

        PopupHandler.installPopupHandler(
            tree,
            ActionManager.getInstance().getAction(IdeActions.GROUP_PROJECT_VIEW_POPUP) as ActionGroup,
            ActionPlaces.UNKNOWN,
            ActionManager.getInstance()
        )

        val scrollPane = JScrollPane(tree)
        val toolbar = buildToolbar()

        return NonOpaquePanel().apply {
            layout = BorderLayout()
            add(toolbar, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun buildToolbar(): JComponent {
        val refreshAction = object : DumbAwareAction("Refresh", "Rescan CuteKit externs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                refresh()
            }
        }

        val treeExpander = object : TreeExpander {
            override fun expandAll() {
                TreeUtil.expandAll(tree)
            }

            override fun canExpand(): Boolean = tree.rowCount > 0

            override fun collapseAll() {
                TreeUtil.collapseAll(tree, 0)
            }

            override fun canCollapse(): Boolean = tree.rowCount > 0
        }

        val commonActions = CommonActionsManager.getInstance()
        val expandAll = commonActions.createExpandAllAction(treeExpander, tree)
        val collapseAll = commonActions.createCollapseAllAction(treeExpander, tree)

        val actionGroup = DefaultActionGroup(refreshAction, expandAll, collapseAll)
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("CutekitDependenciesToolbar", actionGroup, true)
        toolbar.targetComponent = tree
        return toolbar.component
    }

    private fun registerListeners() {
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any(::shouldRefreshOnEvent)) {
                    refresh()
                }
            }
        })

        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                selectFileInTreeLater(event.newFile)
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                selectFileInTreeLater(file)
            }
        })
    }

    fun refresh() {
        val previousState = captureTreeState()
        tree.isEnabled = false
        tree.emptyText.text = "Loading Cutekit dependencies…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val dependencies = collector.collect()
            val rootNode = buildTree(dependencies)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                treeModel.setRoot(rootNode)
                treeModel.reload()
                if (dependencies.isEmpty()) {
                    tree.emptyText.text = "No CuteKit dependencies detected"
                } else {
                    tree.emptyText.clear()
                }

                observedRootPaths = computeObservedRootPaths(dependencies)
                updateGitMappings(dependencies)
                restoreTreeState(previousState)
                if (previousState.expandedKeys.isEmpty()) {
                    TreeUtil.expand(tree, 1)
                }
                tree.isEnabled = true
                selectCurrentEditorFile()
                applyPendingSelection()
            }
        }
    }

    override fun dispose() {
        // Nothing to dispose beyond the message bus connection handled by connect(this)
    }

    private fun shouldRefreshOnEvent(event: VFileEvent): Boolean {
        val candidatePaths = collectCandidatePaths(event)
        if (candidatePaths.isEmpty()) {
            return false
        }

        val roots = observedRootPaths
        if (roots.isEmpty()) {
            return false
        }

        return candidatePaths.any { path ->
            roots.any { root -> isPathUnderRoot(path, root) }
        }
    }

    private fun buildTree(dependencies: List<CutekitDependency>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        if (dependencies.isEmpty()) {
            return root
        }

        val localFileSystem = LocalFileSystem.getInstance()

        ReadAction.run<RuntimeException> {
            project.basePath
                ?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
                ?.takeIf { it.isValid }
                ?.let { projectRoot ->
                    val projectNode = DefaultMutableTreeNode(TreeItem.ProjectRoot(projectRoot))
                    buildVirtualFileChildren(projectNode, projectRoot, mutableSetOf<String>())
                    root.add(projectNode)
                }

            dependencies.sortedWith(compareBy({ it.id.lowercase() }, { it.origin.pathString }))
                .forEach { dependency ->
                    val virtualRoot = dependency.contentRoot?.let { localFileSystem.refreshAndFindFileByNioFile(it) }
                    val dependencyNode = DefaultMutableTreeNode(TreeItem.Dependency(dependency, virtualRoot))
                    if (virtualRoot != null && virtualRoot.isValid) {
                        buildVirtualFileChildren(dependencyNode, virtualRoot, mutableSetOf<String>())
                    } else {
                        dependencyNode.add(DefaultMutableTreeNode(TreeItem.Message("No local checkout found")))
                    }
                    root.add(dependencyNode)
                }
        }

        return root
    }

    private fun buildVirtualFileChildren(
        parent: DefaultMutableTreeNode,
        directory: VirtualFile,
        visited: MutableSet<String>
    ) {
        val key = directory.canonicalPath ?: directory.path
        if (!visited.add(key)) {
            return
        }

        val children = directory.children ?: return
        val sorted = children.sortedWith(
            compareBy<VirtualFile> { !it.isDirectory }
                .thenBy { it.name.lowercase() }
        )

        for (child in sorted) {
            if (!child.isValid) continue
            val node = DefaultMutableTreeNode(TreeItem.File(child))
            parent.add(node)
            if (child.isDirectory) {
                buildVirtualFileChildren(node, child, visited)
            }
        }
    }

    private fun provideUiData(sink: DataSink) {
        sink.set(CommonDataKeys.PROJECT, project)

        val selectedFiles = selectedVirtualFiles()
        if (selectedFiles.isNotEmpty()) {
            sink.set(CommonDataKeys.VIRTUAL_FILE_ARRAY, selectedFiles.toTypedArray())
            val navigatables = selectedFiles
                .filterNot { it.isDirectory }
                .map { OpenFileDescriptor(project, it) }
            if (navigatables.isNotEmpty()) {
                sink.set(CommonDataKeys.NAVIGATABLE_ARRAY, navigatables.toTypedArray())
            } else {
                sink.lazyNull(CommonDataKeys.NAVIGATABLE_ARRAY)
            }
        } else {
            sink.lazyNull(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            sink.lazyNull(CommonDataKeys.NAVIGATABLE_ARRAY)
        }

        val psiElementsLazy = lazy(LazyThreadSafetyMode.PUBLICATION) {
            selectedPsiElements(selectedFiles).toTypedArray()
        }

        sink.lazy(LangDataKeys.PSI_ELEMENT_ARRAY) {
            val psiElements = psiElementsLazy.value
            if (psiElements.isEmpty()) null else psiElements
        }

        sink.lazy(LangDataKeys.PSI_ELEMENT) {
            psiElementsLazy.value.firstOrNull()
        }

        sink.lazy(LangDataKeys.IDE_VIEW) {
            val directories = buildTargetDirectories(psiElementsLazy.value.toList())
            if (directories.isEmpty()) return@lazy null
            object : IdeView {
                override fun getDirectories(): Array<PsiDirectory> = directories.toTypedArray()

                override fun getOrChooseDirectory(): PsiDirectory? {
                    val array = getDirectories()
                    if (array.isEmpty()) return null
                    return array.first()
                }

                override fun selectElement(element: PsiElement) {
                    val vFile = when (element) {
                        is PsiDirectory -> element.virtualFile
                        else -> element.containingFile?.virtualFile
                    } ?: return
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
            }
        }

        sink.set(PlatformDataKeys.COPY_PROVIDER, copyPasteDelegator.copyProvider)
        sink.set(PlatformDataKeys.CUT_PROVIDER, copyPasteDelegator.cutProvider)
        sink.set(PlatformDataKeys.PASTE_PROVIDER, copyPasteDelegator.pasteProvider)

        if (selectedFiles.isEmpty()) {
            sink.lazyNull(PlatformDataKeys.DELETE_ELEMENT_PROVIDER)
        } else {
            val deleteProvider = object : DeleteProvider {
                override fun deleteElement(dataContext: DataContext) {
                    val elements = psiElementsLazy.value
                    if (elements.isEmpty()) return
                    DeleteHandler.deletePsiElement(elements, project)
                }

                override fun canDeleteElement(dataContext: DataContext): Boolean {
                    val elements = psiElementsLazy.value
                    return elements.isNotEmpty()
                }
            }
            sink.set(PlatformDataKeys.DELETE_ELEMENT_PROVIDER, deleteProvider)
        }
    }

    private fun selectedVirtualFiles(): List<VirtualFile> {
        val selectedNodes = tree.selectionPaths
            ?.mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
            ?: return emptyList()

        return selectedNodes.mapNotNull { node ->
            when (val item = node.userObject as? TreeItem) {
                is TreeItem.ProjectRoot -> item.file
                is TreeItem.Dependency -> item.root
                is TreeItem.File -> item.file
                else -> null
            }
        }.filter { it.isValid }.distinctBy { it.path }
    }

    private fun selectedPsiElements(files: List<VirtualFile>): List<PsiElement> {
        if (files.isEmpty()) return emptyList()
        return ReadAction.compute<List<PsiElement>, RuntimeException> {
            val psiManager = PsiManager.getInstance(project)
            files.mapNotNull { file ->
                when {
                    !file.isValid -> null
                    file.isDirectory -> psiManager.findDirectory(file)
                    else -> psiManager.findFile(file)
                }
            }
        }
    }

    private fun selectFileInTreeLater(file: VirtualFile?) {
        if (file == null || !file.isValid) return
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            selectFileInTree(file)
            applyPendingSelection()
        }
    }

    private fun selectFileInTree(file: VirtualFile) {
        if (!file.isValid) return
        val normalized = normalizePathOrNull(file.path) ?: return
        if (normalized == currentSelectionNormalizedPath()) {
            pendingSelectionPath = null
            return
        }

        if (applySelectionForPath(normalized)) {
            pendingSelectionPath = null
        } else {
            pendingSelectionPath = normalized
        }
    }

    private fun selectCurrentEditorFile() {
        val file = FileEditorManager.getInstance(project).selectedFiles.lastOrNull()
        if (file != null) {
            selectFileInTree(file)
        }
    }

    private fun applyPendingSelection() {
        val normalized = pendingSelectionPath ?: return
        if (applySelectionForPath(normalized)) {
            pendingSelectionPath = null
        }
    }

    private fun applySelectionForPath(normalizedPath: String): Boolean {
        val current = currentSelectionNormalizedPath()
        if (current != null && current == normalizedPath) {
            return true
        }

        val rootNode = tree.model.root as? DefaultMutableTreeNode ?: return false
        val targetPath = findTreePathForNormalizedPath(rootNode, normalizedPath) ?: return false
        TreeUtil.selectPath(tree, targetPath)
        tree.scrollPathToVisible(targetPath)
        return true
    }

    private fun findTreePathForNormalizedPath(
        rootNode: DefaultMutableTreeNode,
        normalizedPath: String
    ): TreePath? {
        val queue = ArrayDeque<TreePath>()
        queue.add(TreePath(rootNode))
        while (queue.isNotEmpty()) {
            val currentPath = queue.removeFirst()
            val node = currentPath.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val item = node.userObject as? TreeItem
            val itemPath = item?.let { normalizedPathForItem(it) }
            if (itemPath != null && itemPath == normalizedPath) {
                return currentPath
            }

            val children = node.children()
            while (children.hasMoreElements()) {
                val child = children.nextElement() as DefaultMutableTreeNode
                queue.add(currentPath.pathByAddingChild(child))
            }
        }
        return null
    }

    private fun normalizedPathForItem(item: TreeItem): String? = when (item) {
        is TreeItem.ProjectRoot -> normalizePathOrNull(item.file.path)
        is TreeItem.File -> normalizePathOrNull(item.file.path)
        else -> null
    }

    private fun currentSelectionNormalizedPath(): String? {
        val node = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val item = node.userObject as? TreeItem ?: return null
        return normalizedPathForItem(item)
    }

    private fun buildTargetDirectories(elements: List<PsiElement>): List<PsiDirectory> {
        if (elements.isEmpty()) return emptyList()
        val directories = elements.mapNotNull { element ->
            when (element) {
                is PsiDirectory -> element
                is PsiFile -> element.parent
                else -> null
            }
        }
        return directories
            .filter { it.isValid }
            .distinctBy { it.virtualFile.path }
    }

    private fun captureTreeState(): TreeState {
        val expandedKeys = TreeUtil.collectExpandedPaths(tree)
            .mapNotNull { treePathKey(it) }
            .toSet()
        val selectedKeys = tree.selectionPaths
            ?.mapNotNull { treePathKey(it) }
            ?.toSet()
            ?: emptySet()
        return TreeState(expandedKeys, selectedKeys)
    }

    private fun restoreTreeState(state: TreeState) {
        val rootNode = tree.model.root as? DefaultMutableTreeNode ?: return
        if (state.isEmpty()) {
            tree.selectionModel.clearSelection()
            return
        }

        val selectionPaths = mutableListOf<TreePath>()
        traverseTree(rootNode) { node ->
            val path = TreeUtil.getPath(rootNode, node)
            val key = treePathKey(path) ?: return@traverseTree
            if (state.expandedKeys.contains(key)) {
                tree.expandPath(path)
            }
            if (state.selectedKeys.contains(key)) {
                selectionPaths += path
            }
        }

        if (selectionPaths.isNotEmpty()) {
            tree.selectionPaths = selectionPaths.toTypedArray()
        } else {
            tree.selectionModel.clearSelection()
        }
    }

    private fun traverseTree(node: DefaultMutableTreeNode, action: (DefaultMutableTreeNode) -> Unit) {
        action(node)
        val children = node.children()
        while (children.hasMoreElements()) {
            traverseTree(children.nextElement() as DefaultMutableTreeNode, action)
        }
    }

    private fun treePathKey(path: TreePath?): String? {
        if (path == null) return null
        val keys = path.path
            .mapNotNull { element ->
                val node = element as? DefaultMutableTreeNode ?: return@mapNotNull null
                nodeKey(node)
            }
        if (keys.isEmpty()) return null
        return keys.joinToString("|")
    }

    private fun nodeKey(node: DefaultMutableTreeNode): String? {
        val item = node.userObject as? TreeItem ?: return null
        return item.keySegment()
    }

    private data class TreeState(
        val expandedKeys: Set<String>,
        val selectedKeys: Set<String>
    ) {
        fun isEmpty(): Boolean = expandedKeys.isEmpty() && selectedKeys.isEmpty()
    }

    private fun computeObservedRootPaths(dependencies: List<CutekitDependency>): Set<String> {
        val roots = mutableSetOf<String>()
        project.basePath
            ?.let(::normalizePathOrNull)
            ?.let(roots::add)

        dependencies.forEach { dependency ->
            dependency.contentRoot
                ?.let { normalizePathOrNull(it.toAbsolutePath().normalize().toString()) }
                ?.let(roots::add)
        }

        return roots
    }

    private fun updateGitMappings(dependencies: List<CutekitDependency>) {
        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        if (vcsManager.findVcsByName(GIT_VCS_NAME) == null) {
            return
        }

        val desiredRoots = dependencies.mapNotNull { dependency ->
            dependency.contentRoot
                ?.toAbsolutePath()
                ?.normalize()
                ?.toString()
                ?.let(::normalizePathOrNull)
        }.toSet()

        val basePath = project.basePath?.let(::normalizePathOrNull)
        val filteredDesired = desiredRoots.filterNot { it == basePath }.toSet()

        val existingMappings = vcsManager.directoryMappings.toMutableList()
        val existingByPath = existingMappings.associateBy { mapping ->
            normalizePathOrNull(mapping.directory)
        }

        val newManagedRoots = registeredGitRoots.toMutableSet()

        val rootsToRemove = registeredGitRoots - filteredDesired
        if (rootsToRemove.isNotEmpty()) {
            existingMappings.removeIf { mapping ->
                val path = normalizePathOrNull(mapping.directory)
                path != null && rootsToRemove.contains(path) && mapping.vcs == GIT_VCS_NAME
            }
            newManagedRoots.removeAll(rootsToRemove)
        }

        val rootsToAdd = filteredDesired.filter { normalized ->
            val mapping = existingByPath[normalized]
            mapping == null || mapping.vcs != GIT_VCS_NAME
        }

        if (rootsToAdd.isEmpty() && rootsToRemove.isEmpty()) {
            registeredGitRoots = newManagedRoots
            return
        }

        rootsToAdd.forEach { path ->
            existingMappings.add(VcsDirectoryMapping(path, GIT_VCS_NAME))
            newManagedRoots.add(path)
        }

        vcsManager.directoryMappings = existingMappings
        registeredGitRoots = newManagedRoots
    }

    private fun collectCandidatePaths(event: VFileEvent): Set<String> {
        val paths = linkedSetOf<String>()

        normalizePathOrNull(event.path)?.let(paths::add)
        event.file?.path?.let { filePath ->
            normalizePathOrNull(filePath)?.let(paths::add)
        }

        when (event) {
            is VFileMoveEvent -> {
                event.newParent?.path?.let { parent ->
                    val name = event.file.name
                    normalizePathOrNull(Paths.get(parent, name).toString())?.let(paths::add)
                }
            }

            is VFileCopyEvent -> {
                event.newParent?.path?.let { parent ->
                    normalizePathOrNull(Paths.get(parent, event.newChildName).toString())?.let(paths::add)
                }
            }

            is VFilePropertyChangeEvent -> {
                if (VirtualFile.PROP_NAME == event.propertyName) {
                    val parent = event.file?.parent?.path
                    if (parent != null) {
                        (event.newValue as? String)
                            ?.let { normalizePathOrNull(Paths.get(parent, it).toString()) }
                            ?.let(paths::add)
                        (event.oldValue as? String)
                            ?.let { normalizePathOrNull(Paths.get(parent, it).toString()) }
                            ?.let(paths::add)
                    }
                }
            }
        }

        return paths
    }

    private fun normalizePathOrNull(rawPath: String?): String? {
        if (rawPath.isNullOrBlank()) return null
        return try {
            val normalized = Paths.get(rawPath).toAbsolutePath().normalize().toString().trimEnd('/', '\\')
            if (normalized.isEmpty()) "/" else normalized
        } catch (_: InvalidPathException) {
            null
        }
    }

    private fun isPathUnderRoot(path: String, root: String): Boolean {
        if (root == "/") return true
        if (path == root) return true
        if (!path.startsWith(root)) return false
        if (path.length == root.length) return true
        val separator = path[root.length]
        return separator == '/' || separator == '\\'
    }
}

private sealed interface TreeItem {
    fun speedSearchText(): String
    fun keySegment(): String

    data class ProjectRoot(val file: VirtualFile) : TreeItem {
        override fun speedSearchText(): String = file.name
        override fun keySegment(): String = "project:${file.path}"
    }

    data class Dependency(val dependency: CutekitDependency, val root: VirtualFile?) : TreeItem {
        override fun speedSearchText(): String = dependency.id
        override fun keySegment(): String = buildString {
            append("dependency:")
            append(dependency.id)
            append(':')
            append(dependency.origin.pathString)
        }
    }

    data class File(val file: VirtualFile) : TreeItem {
        override fun speedSearchText(): String = file.name
        override fun keySegment(): String = "file:${file.path}"
    }

    data class Message(val text: String) : TreeItem {
        override fun speedSearchText(): String = text
        override fun keySegment(): String = "message:$text"
    }
}

private class CutekitTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: javax.swing.JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val item = node.userObject as? TreeItem) {
            is TreeItem.ProjectRoot -> {
                icon = AllIcons.Nodes.Project
                append(item.file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is TreeItem.Dependency -> {
                icon = AllIcons.Nodes.Module
                append(item.dependency.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }

            is TreeItem.File -> {
                icon = IconUtil.getIcon(item.file, Iconable.ICON_FLAG_READ_STATUS, null)
                append(item.file.name)
            }

            is TreeItem.Message -> {
                icon = AllIcons.General.BalloonWarning
                append(item.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            else -> {
                append(value?.toString() ?: "")
            }
        }
    }
}
