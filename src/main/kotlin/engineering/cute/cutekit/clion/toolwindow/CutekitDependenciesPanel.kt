package engineering.cute.cutekit.clion.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.panels.NonOpaquePanel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
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
    private val model = CollectionListModel<CutekitDependency>()
    private val list = JBList(model)
    private val connection: MessageBusConnection = project.messageBus.connect(this)

    val component: JComponent = buildUi()

    init {
        registerListeners()
        refresh()
    }

    private fun buildUi(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : ColoredListCellRenderer<CutekitDependency>() {
            override fun customizeCellRenderer(
                list: JList<out CutekitDependency>,
                value: CutekitDependency?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                value ?: return
                append(value.id, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val details = buildList {
                    value.version?.let { add("version $it") }
                    value.git?.let { add(it) }
                    value.commit?.let { add(it.take(12)) }
                }
                if (details.isNotEmpty()) {
                    append(" — ")
                    append(details.joinToString(" | "))
                }

                val origin = value.origin.pathString
                append("  (${origin})", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

                if (value.names.isNotEmpty()) {
                    append("  names: ${value.names.joinToString()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }

        list.emptyText.text = "No CuteKit dependencies detected"

        val scrollPane = JScrollPane(list)
        val toolbar = buildToolbar()

        return NonOpaquePanel().apply {
            layout = java.awt.BorderLayout()
            add(toolbar, java.awt.BorderLayout.NORTH)
            add(scrollPane, java.awt.BorderLayout.CENTER)
        }
    }

    private fun buildToolbar(): JComponent {
        val refreshAction = object : DumbAwareAction("Refresh", "Rescan CuteKit externs", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                refresh()
            }
        }

        val actionGroup = com.intellij.openapi.actionSystem.DefaultActionGroup(refreshAction)
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("CutekitDependenciesToolbar", actionGroup, true)
        toolbar.targetComponent = list
        return toolbar.component
    }

    private fun registerListeners() {
        connection.subscribe(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: MutableList<out VFileEvent>) {
                if (events.any { it.file?.name in setOf("project", "project.lock") }) {
                    refresh()
                }
            }
        })
    }

    fun refresh() {
        list.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val dependencies = collector.collect()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    return@invokeLater
                }
                model.replaceAll(dependencies)
                if (dependencies.isEmpty()) {
                    list.emptyText.text = "No CuteKit dependencies detected"
                }
                list.isEnabled = true
            }
        }
    }

    override fun dispose() {
        // Nothing to dispose beyond the message bus connection handled by connect(this)
    }
}
