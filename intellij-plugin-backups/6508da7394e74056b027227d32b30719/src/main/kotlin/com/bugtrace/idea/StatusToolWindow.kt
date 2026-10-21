package com.bugtrace.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.Topic
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

interface BugTraceEvents {
    fun changed()
    companion object { val TOPIC = Topic.create("BugTrace AI records/settings changed", BugTraceEvents::class.java) }
}

class StatusToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StatusPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

private class StatusPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val model = DefaultListModel<SavedInvestigation>()
    private val list = JBList(model)
    private val detail = JBTextArea("Configure Settings → Tools → BugTrace AI, then click Refresh.").apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val notice = JLabel("Status is read from the backend. Refresh does not run experiments.")
    private val refresh = JButton("Refresh")
    private val open = JButton("Open in dashboard")
    private val verify = JButton("Verify snapshot")
    private var busy = false
    private var generation = 0
    init {
        val settings = JButton("Settings").apply { addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, BugTraceConfigurable::class.java) } }
        val buttons = JPanel().apply { add(settings); add(refresh); add(open); add(verify) }
        add(JPanel(BorderLayout()).apply { add(buttons, BorderLayout.NORTH); add(notice, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, JBScrollPane(list), JBScrollPane(detail)).apply { resizeWeight = 0.4; preferredSize = Dimension(460, 550) }
        add(split)
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }
        refresh.addActionListener { reload() }
        open.addActionListener {
            try { val config = project.service<BugTraceSettings>().connection(); val id = list.selectedValue?.id ?: return@addActionListener
                BrowserUtil.browse(config.dashboard + "/#/investigations/" + uuid(id))
            } catch (error: Exception) { notice.text = safeMessage(error) }
        }
        verify.addActionListener { verifySelected() }
        project.messageBus.connect(this).subscribe(BugTraceEvents.TOPIC, object : BugTraceEvents {
            override fun changed() {
                generation++
                model.clear(); detail.text = "Settings or records changed; refresh required."; showSelected()
                reload()
            }
        })
        showSelected()
    }
    private fun showSelected() {
        val record = list.selectedValue
        open.isEnabled = record != null && !busy; verify.isEnabled = record != null && !busy
        if (record != null) detail.text = "Investigation: ${record.id}\nSnapshot: ${record.snapshot}\nWorkflow: ${record.state}\nOutcome: ${record.outcome.ifBlank { "Not recorded" }}\n\n${record.raw}"
    }
    private fun reload() {
        if (busy || project.isDisposed) return
        val settings = project.service<BugTraceSettings>()
        val config = try { settings.connection() } catch (error: Exception) { notice.text = safeMessage(error); return }
        val previous = list.selectedValue?.id ?: settings.getState().lastInvestigation
        val requestGeneration = generation
        model.clear(); detail.text = "Loading project investigations…"; busy = true; refresh.isEnabled = false; showSelected()
        object : Task.Backgroundable(project, "Loading BugTrace investigation status", false) {
            var records = emptyList<SavedInvestigation>()
            override fun run(indicator: ProgressIndicator) { records = BugTraceApi(config, AccessKeys.read(config)).list() }
            override fun onSuccess() {
                if (project.isDisposed) return
                busy = false; refresh.isEnabled = true
                val current = try { settings.connection() } catch (_: Exception) { null }
                if (current != config || requestGeneration != generation) { model.clear(); detail.text = "Connection changed; refreshing the new project."; reload(); return }
                records.forEach(model::addElement)
                val index = records.indexOfFirst { it.id == previous }
                if (records.isNotEmpty()) list.selectedIndex = if (index >= 0) index else 0
                else detail.text = "No investigations in this project. Use Tools → Submit to BugTrace AI to create one."
                notice.text = "Loaded ${records.size} records for ${config.projectId}. No model calls or experiments started."
                showSelected()
            }
            override fun onThrowable(error: Throwable) {
                busy = false; refresh.isEnabled = true; model.clear(); detail.text = safeMessage(error); notice.text = "Status unavailable; cached contents cleared."; showSelected()
                if (requestGeneration != generation && !project.isDisposed) reload()
            }
        }.queue()
    }
    private fun verifySelected() {
        val record = list.selectedValue ?: return
        if (busy) return
        val config = try { project.service<BugTraceSettings>().connection() } catch (error: Exception) { notice.text = safeMessage(error); return }
        val requestGeneration = generation
        busy = true; refresh.isEnabled = false; showSelected()
        object : Task.Backgroundable(project, "Verifying saved BugTrace snapshot", false) {
            var result = ""
            override fun run(indicator: ProgressIndicator) { result = BugTraceApi(config, AccessKeys.read(config)).verify(record.id) }
            override fun onSuccess() { busy = false; refresh.isEnabled = true; if (requestGeneration != generation) reload() else { notice.text = result; showSelected() } }
            override fun onThrowable(error: Throwable) { busy = false; refresh.isEnabled = true; if (requestGeneration != generation) reload() else { notice.text = safeMessage(error); showSelected() } }
        }.queue()
    }
    override fun dispose() { model.clear(); detail.text = "" }
}
