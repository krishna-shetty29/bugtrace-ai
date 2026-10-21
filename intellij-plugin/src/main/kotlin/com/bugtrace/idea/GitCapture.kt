package com.bugtrace.idea

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EditorCapture(val file: Path?, val text: String?, val start: Int, val end: Int)
data class Capture(val root: String, val commit: String, val module: String, val selectedFile: String?,
                   val dirty: Boolean, val fileInCommit: Boolean, val selection: String?,
                   val startLine: Int?, val endLine: Int?, val selectionNotice: String) {
    fun context(include: Boolean): Map<String, Any?> {
        require(!include || selection != null) { "Selected code is not available as committed evidence." }
        return linkedMapOf("repositoryRoot" to root, "commitSha" to commit, "modulePath" to module,
            "selectedFile" to selectedFile, "filePresentInCommit" to fileInCommit, "workingTreeDirty" to dirty,
            "snapshotPolicy" to "Only the captured Git commit is imported. Staged, unstaged, untracked and unsaved changes are excluded.",
            "selectedCodeOrigin" to if (include) "COMMITTED_HEAD_VERIFIED" else "NOT_INCLUDED",
            "startLine" to if (include) startLine else null, "endLine" to if (include) endLine else null,
            "selectedCode" to if (include) selection else null,
            "selectedCodeSha256" to if (include) sha256(selection!!) else null)
    }
}
fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

object GitCapture {
    private fun git(directory: Path, vararg args: String): Pair<Int, ByteArray> {
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-C", directory.toString()) + args)
            .redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BugTrace Git output").apply { isDaemon = true } }
        try {
            val output = executor.submit<ByteArray> { process.inputStream.use { it.readNBytes(2_097_153) } }
            require(process.waitFor(10, TimeUnit.SECONDS)) { "Git capture timed out. No submission was sent." }
            val bytes = output.get(2, TimeUnit.SECONDS)
            require(bytes.size <= 2_097_152) { "Git output exceeded the capture limit. No submission was sent." }
            return process.exitValue() to bytes
        } finally { process.destroyForcibly(); executor.shutdownNow() }
    }
    fun head(root: Path): String {
        val (exit, bytes) = git(root, "rev-parse", "--verify", "HEAD^{commit}")
        val commit = String(bytes, StandardCharsets.UTF_8).trim()
        require(exit == 0 && commit.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) { "A committed Git HEAD is required. No commit is created by this plugin." }
        return commit
    }
    fun capture(base: Path, editor: EditorCapture, module: String): Capture {
        val directory = editor.file?.parent ?: base
        val (exit, bytes) = git(directory, "rev-parse", "--show-toplevel")
        require(exit == 0) { "Open a file/project inside a Git repository. Git must be available on PATH." }
        val root = Path.of(String(bytes, StandardCharsets.UTF_8).trim()).toRealPath()
        val commit = head(root)
        val (statusExit, status) = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=normal", "--ignore-submodules=all")
        require(statusExit == 0) { "Could not check Git working-tree status." }
        val selected = editor.file?.toAbsolutePath()?.normalize()
        require(selected == null || (selected.startsWith(root) && selected.toRealPath() == selected)) { "Selected file must be an ordinary file inside this repository." }
        val relative = selected?.let { root.relativize(it).toString().replace('\\', '/') }
        require(relative == null || module == "." || relative.startsWith("$module/")) { "Selected file is outside the configured module. Select a file inside that module or change BugTrace AI settings." }
        var present = false
        var selection: String? = null
        var first: Int? = null
        var last: Int? = null
        var notice = "No editor code selected."
        if (relative != null) {
            val (kindExit, kind) = git(root, "cat-file", "-t", "$commit:$relative")
            present = kindExit == 0 && String(kind, StandardCharsets.UTF_8).trim() == "blob"
            if (!present) notice = "Selected file is absent from HEAD; its working copy and selected code are excluded."
            else if (editor.text != null && editor.end > editor.start) {
                val (showExit, source) = git(root, "show", "--no-ext-diff", "--no-textconv", "$commit:$relative")
                require(showExit == 0) { "Could not read the committed selected file." }
                val committed = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source)).toString().replace("\r\n", "\n")
                val document = editor.text.replace("\r\n", "\n")
                notice = "Selected code excluded: the editor differs from committed HEAD. Commit deliberately or submit without the selection."
                if (!committed.contains('\u0000') && committed == document && editor.start >= 0 && editor.end <= document.length) {
                    val snippet = committed.substring(editor.start, editor.end)
                    if (snippet.length <= 6000) {
                        selection = snippet
                        first = committed.take(editor.start).count { it == '\n' } + 1
                        last = committed.take(editor.end - 1).count { it == '\n' } + 1
                        notice = "Selection matches committed HEAD; inclusion is optional and off by default."
                    } else notice = "Selected code exceeds 6,000 characters; select a smaller range."
                }
            }
        }
        require(head(root) == commit) { "HEAD changed during capture. Start the action again." }
        return Capture(root.toString(), commit, module, relative, status.isNotEmpty(), present, selection, first, last, notice)
    }
}
