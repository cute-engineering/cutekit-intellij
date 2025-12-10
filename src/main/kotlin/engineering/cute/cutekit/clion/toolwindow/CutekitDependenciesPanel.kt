package engineering.cute.cutekit.clion.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.IdeView
import com.intellij.ide.TreeExpander
import com.intellij.ide.DeleteProvider
import com.intellij.ide.util.DeleteHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
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
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import javax.swing.tree.TreePath
import kotlin.io.path.pathString

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
    private val tree = object : Tree(treeModel), DataProvider {
        override fun getData(dataId: String): Any? = this@CutekitDependenciesPanel.getData(dataId)
    }
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    val component: JComponent = buildUi()

    init {
        registerListeners()
        refresh()
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
                val path = tree.getPathForLocation(event.x, event.y) ?: return false
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

                restoreTreeState(previousState)
                if (previousState.expandedKeys.isEmpty()) {
                    TreeUtil.expand(tree, 1)
                }
                tree.isEnabled = true
            }
        }
    }

    override fun dispose() {
        // Nothing to dispose beyond the message bus connection handled by connect(this)
    }

    private fun shouldRefreshOnEvent(event: VFileEvent): Boolean {
        val file = event.file ?: return false
        if (file.name == "project" || file.name == "project.lock") {
            return true
        }
        val path = file.path
        return path.contains("/.cutekit/extern/")
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

    private fun getData(dataId: String): Any? {
        if (CommonDataKeys.PROJECT.`is`(dataId)) {
            return project
        }

        val selectedFiles = selectedVirtualFiles()
        val selectedPsi = selectedPsiElements(selectedFiles)

        if (CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId)) {
            return if (selectedFiles.isEmpty()) null else selectedFiles.toTypedArray()
        }

        if (CommonDataKeys.NAVIGATABLE_ARRAY.`is`(dataId)) {
            val navigatables = selectedFiles
                .filterNot { it.isDirectory }
                .map { OpenFileDescriptor(project, it) }
            return if (navigatables.isEmpty()) null else navigatables.toTypedArray()
        }

        if (LangDataKeys.PSI_ELEMENT.`is`(dataId)) {
            return selectedPsi.firstOrNull()
        }

        if (LangDataKeys.PSI_ELEMENT_ARRAY.`is`(dataId)) {
            return if (selectedPsi.isEmpty()) null else selectedPsi.toTypedArray()
        }

        if (LangDataKeys.IDE_VIEW.`is`(dataId)) {
            val directories = buildTargetDirectories(selectedPsi)
            if (directories.isEmpty()) {
                return null
            }
            return object : IdeView {
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

        if (PlatformDataKeys.DELETE_ELEMENT_PROVIDER.`is`(dataId)) {
            return object : DeleteProvider {
                override fun deleteElement(dataContext: DataContext) {
                    val elements = LangDataKeys.PSI_ELEMENT_ARRAY.getData(dataContext) ?: return
                    if (elements.isEmpty()) return
                    if (!DeleteHandler.shouldEnableDeleteAction(elements)) return
                    DeleteHandler.deletePsiElement(elements, project)
                }

                override fun canDeleteElement(dataContext: DataContext): Boolean {
                    val elements = LangDataKeys.PSI_ELEMENT_ARRAY.getData(dataContext)
                    return elements != null && elements.isNotEmpty() && DeleteHandler.shouldEnableDeleteAction(elements)
                }
            }
        }

        return null
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
