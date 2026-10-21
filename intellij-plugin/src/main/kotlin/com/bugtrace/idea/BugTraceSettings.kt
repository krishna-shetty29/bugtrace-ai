package com.bugtrace.idea

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.*

@Service(Service.Level.PROJECT)
@State(name = "BugTraceAIProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BugTraceSettings : PersistentStateComponent<BugTraceSettings.Data> {
    data class Data(var backend: String = "http://127.0.0.1:8080", var dashboard: String = "http://127.0.0.1:5173",
                    var projectId: String = "", var module: String = "fixtures/checkout-timeout",
                    var backendRepository: String = "", var lastInvestigation: String = "")
    private var data = Data()
    override fun getState() = data
    override fun loadState(state: Data) { data = state }
    fun connection() = Connection(data.backend, data.dashboard, data.projectId, data.module, data.backendRepository).checked()
    fun save(config: Connection) {
        val prior = data
        data = Data(config.backend, config.dashboard, config.projectId, config.module, config.backendRepository,
            if (prior.backend == config.backend && prior.projectId == config.projectId) prior.lastInvestigation else "")
    }
    fun remember(id: String) { data.lastInvestigation = uuid(id) }
}

object AccessKeys {
    private fun attributes(config: Connection) = CredentialAttributes(generateServiceName("BugTrace AI", "${config.backend}|${config.projectId}"))
    // Called only in background tasks. Never stored in workspace.xml or request bodies.
    fun read(config: Connection): String = PasswordSafe.instance.getPassword(attributes(config))
        ?: throw IllegalStateException("No personal key stored. Open Settings → Tools → BugTrace AI.")
    fun write(config: Connection, key: String?) {
        require(key == null || key.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Use the complete 43-character personal key." }
        PasswordSafe.instance.setPassword(attributes(config), key)
    }
}

class BugTraceConfigurable(private val project: Project) : SearchableConfigurable {
    private val backend = JBTextField()
    private val dashboard = JBTextField()
    private val projectId = JBTextField()
    private val module = JBTextField()
    private val repository = JBTextField()
    private val password = JBPasswordField()
    private val clear = JCheckBox("Remove the stored key for this connection/project")
    override fun getId() = "com.bugtrace.idea.settings"
    override fun getDisplayName() = "BugTrace AI"
    private fun config() = Connection(backend.text, dashboard.text, projectId.text.trim(), module.text, repository.text).checked()
    override fun createComponent(): JComponent {
        val form = JPanel(GridLayout(0, 1, 4, 4))
        fun field(label: String, component: JComponent) { form.add(JLabel(label)); form.add(component) }
        field("Backend origin", backend); field("Dashboard origin", dashboard)
        field("BugTrace project UUID", projectId); field("Committed module path relative to Git root", module)
        field("Backend repository path (blank = detected local Git root)", repository)
        field("Personal access key (Owner/Investigator to submit; blank keeps the stored key)", password)
        form.add(clear)
        form.add(JLabel("Keys use the IDE Password Safe. Your configured IDE password-storage policy applies."))
        form.add(JLabel("Only committed snapshots are imported; staged, unstaged, untracked and unsaved changes are excluded."))
        val test = JButton("Test connection and project")
        test.addActionListener {
            try {
                val connection = config(); val chars = password.password
                val typed = String(chars); chars.fill('\u0000')
                object : Task.Backgroundable(project, "Checking BugTrace connection", false) {
                    var name = ""
                    override fun run(indicator: ProgressIndicator) { name = BugTraceApi(connection, typed.ifBlank { AccessKeys.read(connection) }).checkConnection() }
                    override fun onSuccess() { Messages.showInfoMessage(project, "Connected to project: $name. Submission still requires Owner/Investigator permission.", "BugTrace AI") }
                    override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project, safeMessage(error), "BugTrace AI") }
                }.queue()
            } catch (error: Exception) { Messages.showErrorDialog(project, safeMessage(error), "BugTrace AI") }
        }
        form.add(test)
        reset()
        return JPanel(BorderLayout()).apply { add(form, BorderLayout.NORTH) }
    }
    override fun isModified(): Boolean {
        val current = project.service<BugTraceSettings>().getState()
        return backend.text != current.backend || dashboard.text != current.dashboard || projectId.text != current.projectId ||
            module.text != current.module || repository.text != current.backendRepository || password.password.isNotEmpty() || clear.isSelected
    }
    override fun apply() {
        val connection = try { config() } catch (error: Exception) { throw ConfigurationException(safeMessage(error)) }
        val chars = password.password; val replacement = String(chars); chars.fill('\u0000')
        if (replacement.isNotBlank() && !replacement.matches(Regex("[A-Za-z0-9_-]{43}"))) throw ConfigurationException("Use the complete 43-character personal key.")
        if (clear.isSelected && replacement.isNotBlank()) throw ConfigurationException("Choose either replacing or removing the key.")
        val remove = clear.isSelected
        project.service<BugTraceSettings>().save(connection)
        password.text = ""; clear.isSelected = false
        if (remove || replacement.isNotBlank()) {
            object : Task.Backgroundable(project, "Saving BugTrace credential", false) {
                override fun run(indicator: ProgressIndicator) { AccessKeys.write(connection, if (remove) null else replacement) }
                override fun onSuccess() { project.messageBus.syncPublisher(BugTraceEvents.TOPIC).changed() }
                override fun onThrowable(error: Throwable) { Messages.showErrorDialog(project,
                    "Connection settings saved, but Password Safe could not update the key. Reopen BugTrace AI settings and try again.", "BugTrace AI") }
            }.queue()
        } else project.messageBus.syncPublisher(BugTraceEvents.TOPIC).changed()
    }
    override fun reset() {
        val value = project.service<BugTraceSettings>().getState()
        backend.text = value.backend; dashboard.text = value.dashboard; projectId.text = value.projectId
        module.text = value.module; repository.text = value.backendRepository; password.text = ""; clear.isSelected = false
    }
    override fun disposeUIResources() { password.text = "" }
}

fun safeMessage(error: Throwable): String = when (error) {
    is IllegalArgumentException, is IllegalStateException -> error.message?.take(700) ?: "Operation rejected."
    is java.net.http.HttpTimeoutException -> "Backend request timed out. Submission outcome may be unknown; refresh investigations before retrying."
    is java.io.IOException -> "Could not communicate with Git/backend. Check the connection; refresh investigations before retrying a submission."
    else -> "Operation failed. Check your connection/settings and backend audit records. No automatic retry was made."
}
