package engineering.cute.cutekit.clion.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.util.Key
import com.intellij.openapi.ui.Messages
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.io.File
import java.io.IOException

class SetActiveCutekitComponentAction : AnAction(ACTION_TEXT), DumbAware {
    override fun update(event: AnActionEvent) {
        val directory = targetDirectory(event)
        val manifestFile = directory?.findChild(MANIFEST_FILE_NAME)
        val componentId = manifestFile?.let { readComponentId(it) }

        event.presentation.isVisible = componentId != null
        event.presentation.isEnabled = componentId != null
        event.presentation.putClientProperty(COMPONENT_ID_KEY, componentId)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = targetDirectory(event) ?: return
        val manifestFile = directory.findChild(MANIFEST_FILE_NAME)
        val componentId = event.presentation.getClientProperty(COMPONENT_ID_KEY) ?: manifestFile?.let { readComponentId(it) }

        if (manifestFile == null || componentId.isNullOrBlank()) {
            Messages.showErrorDialog(project, "Unable to read component id from manifest.json.", ACTION_TEXT)
            return
        }

        val workingDirectory = project.basePath?.let(::File) ?: File(directory.path)
        val command = "ck export idea-workspace $componentId"

        ApplicationManager.getApplication().invokeLater({
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val title = "CuteKit: $componentId"
            val widget = terminalManager.createLocalShellWidget(workingDirectory.path, title)
            widget.executeCommand(command)
            notify(project, "Generating IDE build configuration for '$componentId'", NotificationType.INFORMATION)
        }, ModalityState.nonModal())
    }

    private fun notify(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message, type)
            .notify(project)
    }

    private fun readComponentId(virtualFile: VirtualFile): String? {
        return try {
            val text = VfsUtilCore.loadText(virtualFile)
            COMPONENT_ID_REGEX.find(text)?.groupValues?.getOrNull(1)
        } catch (_: IOException) {
            null
        }
    }

    private fun targetDirectory(event: AnActionEvent): VirtualFile? {
        val primary = event.getData(CommonDataKeys.VIRTUAL_FILE)
        if (primary?.isDirectory == true) {
            return primary
        }

        val selection = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return null
        val directories = selection.filter { it.isDirectory }
        return directories.singleOrNull()
    }

    companion object {
        private const val ACTION_TEXT = "Generate IDE Build Config"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val NOTIFICATION_GROUP_ID = "Cutekit"
        private val COMPONENT_ID_REGEX = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        private val COMPONENT_ID_KEY: Key<String> = Key.create("engineering.cute.cutekit.activeComponentId")
    }
}
