package com.bugtrace.idea

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.*

class SubmitAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) { event.presentation.isEnabledAndVisible = event.project != null }
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val config = try { project.service<BugTraceSettings>().connection() } catch (_: Exception) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, BugTraceConfigurable::class.java); return
        }
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem && !it.isDirectory }
        val editor = event.getData(CommonDataKeys.EDITOR)
        val selection = editor?.selectionModel
        val text = if (editor != null && selection?.hasSelection() == true && editor.document.textLength <= 2_000_000) editor.document.text else null
        val editorCapture = EditorCapture(file?.let { Path.of(it.path) }, text, selection?.selectionStart ?: 0, selection?.selectionEnd ?: 0)
        val base = project.basePath?.let(Path::of) ?: return
        object : Task.Backgroundable(project, "Capturing committed BugTrace context", true) {
            lateinit var capture: Capture
            override fun run(indicator: ProgressIndicator) { indicator.checkCanceled(); capture = GitCapture.capture(base, editorCapture, config.module); indicator.checkCanceled() }
            override fun onSuccess() {
                if (project.isDisposed) return
                if (project.service<BugTraceSettings>().connection() != config) {
                    Messages.showWarningDialog(project, "Connection settings changed during capture. Start the action again.", "BugTrace AI"); return
                }
                val dialog = SubmissionDialog(project, config, capture)
                if (dialog.showAndGet()) submit(project, config, capture, dialog.report())
            }
            override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project, safeMessage(error), "BugTrace AI") }
        }.queue()
    }
}

private class SubmissionDialog(project: Project, private val config: Connection, private val capture: Capture) : DialogWrapper(project) {
    private val reportTitle = JBTextField("Bug in ${capture.selectedFile ?: capture.module}")
    private val description = JBTextArea(5, 65).apply { lineWrap = true; wrapStyleWord = true }
    private val expected = JBTextArea(3, 65).apply { lineWrap = true; wrapStyleWord = true }
    private val observed = JBTextArea(3, 65).apply { lineWrap = true; wrapStyleWord = true }
    private val include = JCheckBox("Include selected code verified against committed HEAD", false).apply { isEnabled = capture.selection != null }
    private val importSnapshot = JCheckBox("Import the captured committed module after saving the investigation", true)
    private val acknowledge = JCheckBox("I understand staged, unstaged, untracked and unsaved changes are excluded.", false)
    init { title = "Submit to BugTrace AI"; setOKButtonText("Submit committed snapshot"); init() }
    override fun createCenterPanel(): JComponent {
        val identity = JBTextArea("Backend: ${config.backend}\nBugTrace project: ${config.projectId}\nRepository: ${capture.root}\nCommit: ${capture.commit}\nModule: ${capture.module}\nSelected file: ${capture.selectedFile ?: "None"}\nWorking tree has changes: ${capture.dirty}\n${capture.selectionNotice}").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        val form = FormBuilder.createFormBuilder().addComponent(identity)
            .addLabeledComponent("Title", reportTitle).addLabeledComponent("Bug report", JBScrollPane(description))
            .addLabeledComponent("Expected behavior", JBScrollPane(expected)).addLabeledComponent("Observed behavior", JBScrollPane(observed))
            .addComponent(include)
        if (capture.selection != null) form.addComponent(JBScrollPane(JBTextArea(capture.selection).apply { isEditable = false }).apply { preferredSize = Dimension(600, 110) })
        form.addComponent(importSnapshot).addComponent(acknowledge)
        return JPanel(BorderLayout()).apply { add(JBScrollPane(form.panel)); preferredSize = Dimension(740, 660) }
    }
    override fun doOKAction() {
        if (!acknowledge.isSelected) { Messages.showWarningDialog("Acknowledge that uncommitted changes are excluded.", "BugTrace AI"); return }
        if (reportTitle.text.isBlank() || reportTitle.text.length > 300 || description.text.isBlank() || expected.text.isBlank() || observed.text.isBlank()) {
            Messages.showWarningDialog("Fill in all report fields. Title limit: 300 characters.", "BugTrace AI"); return
        }
        super.doOKAction()
    }
    fun report() = Report(reportTitle.text, description.text, expected.text, observed.text, include.isSelected, importSnapshot.isSelected)
}

private fun submit(project: Project, config: Connection, capture: Capture, report: Report) {
    object : Task.Backgroundable(project, "Submitting committed BugTrace investigation", false) {
        lateinit var result: SubmissionResult
        override fun run(indicator: ProgressIndicator) {
            require(GitCapture.head(Path.of(capture.root)) == capture.commit) { "Git HEAD changed after the form opened. Submit again to capture the new commit." }
            val api = BugTraceApi(config, AccessKeys.read(config))
            api.checkConnection()
            val created = api.create(capture, report)
            result = SubmissionResult(created, false, "Investigation saved. Snapshot import was not requested.")
            if (report.importSnapshot) {
                result = try { api.importSnapshot(created.id, capture); val verification = api.verify(created.id)
                    SubmissionResult(created, true, "Investigation saved. Committed snapshot imported. $verification")
                } catch (error: Exception) { SubmissionResult(created, false, "Investigation ${created.id} was saved, but snapshot import/verification did not complete: ${safeMessage(error)}. Do not resubmit blindly.") }
            }
        }
        override fun onSuccess() {
            if (project.isDisposed) return
            project.service<BugTraceSettings>().remember(result.investigation.id)
            project.messageBus.syncPublisher(BugTraceEvents.TOPIC).changed()
            ToolWindowManager.getInstance(project).getToolWindow("BugTrace AI")?.show()
            Messages.showInfoMessage(project, "${result.notice}\nInvestigation UUID: ${result.investigation.id}", "BugTrace AI")
        }
        override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project, safeMessage(error), "BugTrace AI — submission not confirmed") }
    }.queue()
}
