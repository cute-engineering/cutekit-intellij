package engineering.cute.cutekit.clion.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

class CutekitNewComponentAction : AnAction("CuteKit Component"), DumbAware {
    override fun update(event: AnActionEvent) {
        val directory = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val isDirectory = directory?.isDirectory == true
        event.presentation.isEnabled = isDirectory
        event.presentation.isVisible = isDirectory
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targetDirectory = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isDirectory } ?: return

        val dialog = CutekitNewComponentDialog(project)
        if (!dialog.showAndGet()) {
            return
        }

        val options = dialog.options
        val componentName = options.id

        if (componentName.isEmpty()) {
            Messages.showErrorDialog(project, "Component id must not be empty.", DIALOG_TITLE)
            return
        }

        if (targetDirectory.findChild(componentName) != null) {
            Messages.showErrorDialog(project, "A file or directory named '$componentName' already exists.", DIALOG_TITLE)
            return
        }

        val createResult = runCatching {
            WriteCommandAction.runWriteCommandAction(project, DIALOG_TITLE, null, Runnable {
                val componentDir = VfsUtil.createDirectoryIfMissing(targetDirectory, componentName)
                    ?: error("Failed to create directory '$componentName'.")

                createManifest(componentDir, options)
                createSources(componentDir, options.language)
                componentDir.refresh(false, true)
            })
        }

        if (createResult.isFailure) {
            val message = createResult.exceptionOrNull()?.let { it.localizedMessage ?: it.message } ?: "Unknown error"
            Messages.showErrorDialog(project, "Unable to create component: $message", DIALOG_TITLE)
        }
    }

    private fun createManifest(componentDir: VirtualFile, options: CutekitNewComponentDialog.Options) {
        val manifestFile = componentDir.findOrCreateChildData(this, MANIFEST_FILE_NAME)
        val manifestContent = buildString {
            appendLine("{")
            appendLine("  \"\$schema\": \"https://schemas.cute.engineering/stable/cutekit.manifest.component.v1\",")
            appendLine("  \"id\": \"${options.id}\",")
            appendLine("  \"type\": \"${options.type.key}\"")
            append("}")
        }
        VfsUtil.saveText(manifestFile, manifestContent)
    }

    private fun createSources(componentDir: VirtualFile, language: ComponentLanguage) {
        when (language) {
            ComponentLanguage.C -> {
                createEmptyFile(componentDir, "mod.c")
                createEmptyFile(componentDir, "main.c")
                createEmptyFile(componentDir, "mod.h")
            }

            ComponentLanguage.CPP -> {
                createEmptyFile(componentDir, "mod.cpp")
                createEmptyFile(componentDir, "main.cpp")
            }
        }
    }

    private fun createEmptyFile(parent: VirtualFile, name: String) {
        val file = parent.findChild(name) ?: parent.createChildData(this, name)
        VfsUtil.saveText(file, "")
    }

    private companion object {
        const val MANIFEST_FILE_NAME = "component.cutekit.json"
        const val DIALOG_TITLE = "Create CuteKit Component"
    }
}

private class CutekitNewComponentDialog(project: Project) : DialogWrapper(project) {
    private val idField = JBTextField()
    private val typeBox = JComboBox(DefaultComboBoxModel(ComponentType.values()))
    private val languageBox = JComboBox(DefaultComboBoxModel(ComponentLanguage.values()))

    val options: Options
        get() = Options(
            id = idField.text.trim(),
            type = typeBox.selectedItem as ComponentType,
            language = languageBox.selectedItem as ComponentLanguage
        )

    init {
        title = "New CuteKit Component"
        init()
    }

    override fun createCenterPanel(): JComponent {
        idField.emptyText.text = "my-component"
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Component ID", idField)
            .addLabeledComponent("Type", typeBox)
            .addLabeledComponent("Language", languageBox)
            .panel
    }

    override fun getPreferredFocusedComponent(): JComponent = idField

    override fun doValidate(): ValidationInfo? {
        val id = idField.text.trim()
        if (id.isEmpty()) {
            return ValidationInfo("Component ID must not be empty.", idField)
        }
        if (!ID_PATTERN.matches(id)) {
            return ValidationInfo("Component ID must contain only lowercase letters, digits, and hyphens.", idField)
        }
        return null
    }

    data class Options(val id: String, val type: ComponentType, val language: ComponentLanguage)

    companion object {
        private val ID_PATTERN = Regex("^[a-z0-9-]+$")
    }
}

enum class ComponentType(val key: String, private val label: String) {
    LIB("lib", "lib"),
    EXE("exe", "exe");

    override fun toString(): String = label
}

enum class ComponentLanguage(private val label: String) {
    CPP("C++"),
    C("C");

    override fun toString(): String = label
}
