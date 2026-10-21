package com.bugtrace.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.GridLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

class NavigationPanel(private val project: Project) : JPanel(BorderLayout()) {
    private var selected: SavedInvestigation? = null
    private var epoch = 0
    private var busy = false
    private val sources = DefaultListModel<SourceReference>()
    private val sourceList = JBList(sources)
    private val reviews = DefaultComboBoxModel<ReviewReference>()
    private val reviewList = JComboBox(reviews)
    private var review: SavedReview? = null
    private val notice = JTextArea("Select an investigation, then load its saved evidence and reviews.").apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3
    }
    private val load = JButton("Load evidence and reviews")
    private val source = JButton("Open referenced source")
    private val excerpt = JButton("Open saved excerpt")
    private val loadReview = JButton("Load selected review")
    private val reproduction = JButton("Open reproduction test")
    private val verification = JButton("Inspect verification")
    private val dashboard = JButton("Open patch review in dashboard")
    init {
        val top = JPanel(BorderLayout()).apply { add(load, BorderLayout.NORTH); add(notice, BorderLayout.CENTER) }
        add(top, BorderLayout.NORTH)
        val center = JPanel(BorderLayout()).apply {
            add(JBScrollPane(sourceList), BorderLayout.CENTER)
            add(JPanel(GridLayout(0, 1)).apply {
                add(source); add(excerpt); add(JLabel("Saved patch review:")); add(reviewList)
                add(loadReview); add(reproduction); add(verification); add(dashboard)
            }, BorderLayout.SOUTH)
        }
        add(center, BorderLayout.CENTER)
        sourceList.addListSelectionListener { enabled() }
        reviewList.addActionListener { review = null; enabled() }
        load.addActionListener { loadRecords() }
        loadReview.addActionListener { loadSelectedReview() }
        source.addActionListener { openSource() }
        excerpt.addActionListener { sourceList.selectedValue?.let { showExcerpt(it) } }
        reproduction.addActionListener { review?.let { preview("Reproduction-${it.id}.java", "Saved review ${it.id}; bundle ${it.bundleHash}. Read-only proposal/evidence; opening does not execute it.\n\n", it.reproduction) } }
        verification.addActionListener { review?.let { preview("Verification-${it.id}.json", "Saved verification for review ${it.id}; state ${it.state}; bundle ${it.bundleHash}. Inspect failures and limitations; review approval alone does not mean all tests passed.\n\n", it.verification) } }
        dashboard.addActionListener {
            val id = selected?.id ?: return@addActionListener
            try {
                BrowserUtil.browse(dashboardInvestigation(project.service<BugTraceSettings>().connection(), id))
                notice.text = "In the dashboard, find Developer review and patch export → Load saved reviews. Select review ${review?.id ?: (reviewList.selectedItem as? ReviewReference)?.id ?: "of interest"}. Browser sign-in is separate."
            } catch (e: Exception) { notice.text = safeMessage(e) }
        }
        enabled()
    }
    fun select(record: SavedInvestigation?) {
        if (selected?.id == record?.id && selected?.snapshot == record?.snapshot) return
        epoch++; selected = record; clear(); notice.text = "Select Load evidence and reviews. No model calls or experiments are started."; enabled()
    }
    fun invalidateRecords() { epoch++; selected = null; clear(); notice.text = "Connection or records changed; refresh required."; enabled() }
    private fun clear() { sources.clear(); reviews.removeAllElements(); review = null }
    private fun enabled() {
        load.isEnabled = selected != null && !busy
        source.isEnabled = sourceList.selectedValue?.isCode == true && !busy
        excerpt.isEnabled = sourceList.selectedValue != null && !busy
        loadReview.isEnabled = reviews.selectedItem != null && !busy
        reproduction.isEnabled = review != null && !busy
        verification.isEnabled = review != null && !busy
        dashboard.isEnabled = selected != null && !busy
    }
    private fun <T> background(title: String, work: (BugTraceApi, Connection) -> T, done: (T) -> Unit) {
        if (busy || project.isDisposed) return
        val settings = project.service<BugTraceSettings>()
        val config = try { settings.connection() } catch (e: Exception) { notice.text = safeMessage(e); return }
        val token = epoch
        busy = true; enabled(); notice.text = "$title…"
        object : Task.Backgroundable(project, title, false) {
            var result: T? = null
            override fun run(indicator: ProgressIndicator) { result = work(BugTraceApi(config, AccessKeys.read(config)), config) }
            override fun onSuccess() {
                busy = false
                if (project.isDisposed) return
                val current = try { settings.connection() } catch (_: Exception) { null }
                if (token != epoch || current != config) { enabled(); return }
                @Suppress("UNCHECKED_CAST")
                done(result as T); enabled()
            }
            override fun onThrowable(error: Throwable) {
                busy = false
                if (project.isDisposed) return
                if (token == epoch) { clear(); notice.text = safeMessage(error) + " Cached review/evidence contents cleared." }
                enabled()
            }
        }.queue()
    }
    private fun loadRecords() {
        val record = selected ?: return
        clear()
        background("Loading saved BugTrace evidence and reviews", { api, _ -> api.evidence(record.id) to api.reviews(record.id) }) { (evidence, saved) ->
            evidence.forEach(sources::addElement); saved.forEach(reviews::addElement)
            notice.text = "${evidence.size} evidence excerpts; ${saved.size} saved reviews. Empty lists mean no indexed evidence/reviews are available. Use the dashboard's existing preparation/review workflow if needed."
        }
    }
    private fun loadSelectedReview() {
        val id = selected?.id ?: return
        val ref = reviewList.selectedItem as? ReviewReference ?: return
        review = null
        background("Loading saved patch review", { api, _ -> api.review(id, ref.id) }) {
            review = it
            notice.text = "Review ${it.id} · ${it.state}\nBundle: ${it.bundleHash}\nSaved reproduction and verification are available. Detailed diff, limitations and decisions remain in the dashboard."
        }
    }
    private fun showExcerpt(ref: SourceReference) = preview("Evidence-${sha256(ref.id).take(12)}.txt",
        "Saved ${if (ref.isCode) "snapshot source" else "context"} excerpt\n${ref.path} · original lines ${ref.first}–${ref.last}\nRevision: ${ref.revision}\nSHA256: ${ref.hash}\nEvidence: ${ref.id}\n\n", ref.text)
    private fun preview(name: String, header: String, text: String) {
        val file = LightVirtualFile(name, PlainTextFileType.INSTANCE, header + text)
        file.isWritable = false
        FileEditorManager.getInstance(project).openFile(file, true)
    }
    private data class Target(val path: Path?, val text: String?, val reason: String)
    private fun openSource() {
        val ref = sourceList.selectedValue ?: return
        val id = selected?.id ?: return
        val base = project.basePath ?: return
        background("Validating source navigation", { api, config ->
            // Re-authorize the record and re-read evidence; stale links cannot bypass revocation.
            val fresh = api.evidence(id).firstOrNull { it.id == ref.id }
            require(fresh == ref) { "Saved evidence changed; reload it before navigating." }
            try {
                val capture = GitCapture.capture(Path.of(base), EditorCapture(null, null, 0, 0), config.module)
                require(capture.commit.equals(ref.revision, true)) { "Local HEAD differs from the evidence revision." }
                val path = localSource(Path.of(capture.root), config.module, ref)
                val text = Files.readString(path).replace("\r\n", "\n").replace('\r', '\n')
                require(rangeMatches(text, ref)) { "Local source differs from the saved evidence." }
                Target(path, text, "")
            } catch (e: Exception) { Target(null, null, safeMessage(e)) }
        }) { target ->
            val vf = target.path?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
            val document: Document? = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (vf != null && document != null && document.text.replace("\r\n", "\n").replace('\r', '\n') == target.text) {
                OpenFileDescriptor(project, vf, ref.first - 1, 0).navigate(true)
                notice.text = "Opened matching local source at line ${ref.first}. Evidence revision: ${ref.revision}."
            } else {
                showExcerpt(ref)
                notice.text = "Opened the saved read-only excerpt. Local navigation was unavailable or the editor differed. ${target.reason}"
            }
        }
    }
}
