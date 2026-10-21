package com.bugtrace.idea

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun main(args: Array<String>) {
    var count = 0
    fun test(name: String, block: () -> Unit) { block(); count++; println("PASS: $name") }
    fun rejected(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; check(failed) { "Expected rejection" } }
    val project = UUID.randomUUID().toString()
    val otherProject = UUID.randomUUID().toString()
    val id = UUID.randomUUID().toString()
    val snapshot = UUID.randomUUID().toString()
    val config = Connection("http://127.0.0.1:8080", "http://127.0.0.1:5173", project, "module")
    val key = "K".repeat(43)
    test("loopback origins normalize without URL credentials") { check(origin("http://LOCALHOST:8080/") == "http://localhost:8080") }
    test("remote plaintext origins rejected") { rejected { origin("http://example.invalid") } }
    test("URL credentials, path, query and fragment rejected") {
        listOf("http://user:password@localhost:8080", "http://localhost:8080/api", "http://localhost:8080?key=x", "http://localhost:8080#x").forEach { rejected { origin(it) } }
    }
    test("UUID parser rejects traversal and abbreviated values") { listOf("../private", "abcd", "00000000-0-0-0-000000000000").forEach { rejected { uuid(it) } } }
    test("module boundaries reject absolute and parent paths") { listOf("../private", "/private", "C:/private", "module/../x", "module//x").forEach { rejected { config.copy(module = it).checked() } } }
    test("invalid or worker-like UUID used as key rejected") { rejected { BugTraceApi(config, id, Transport { _, _, _, _ -> error("No request allowed") }) } }
    val record = """{"id":"$id","projectId":"$project","snapshotId":"$snapshot","title":"Saved report","workflowState":"CREATED","outcome":null}"""
    test("connection key and project binding checked") {
        val api = BugTraceApi(config, key, Transport { method, uri, supplied, body ->
            check(method == "GET" && uri.path == "/api/projects" && supplied == key && body == null)
            Reply(200, """[{"id":"$project","name":"Project"}]""")
        }); check(api.checkConnection() == "Project")
    }
    test("foreign project credential metadata rejected") { rejected { BugTraceApi(config, key, Transport { _, _, _, _ -> Reply(200, """[{"id":"$otherProject","name":"Other"}]""") }).checkConnection() } }
    test("investigation list remains project bound") { check(BugTraceApi(config, key, Transport { _, _, _, _ -> Reply(200, "[$record]") }).list().single().id == id) }
    test("foreign project investigation response rejected") { rejected { BugTraceApi(config, key, Transport { _, _, _, _ -> Reply(200, "[${record.replace(project, otherProject)}]") }).list() } }
    test("401 and 403 stay failures without retry") {
        for (status in listOf(401, 403)) { var calls = 0; rejected { BugTraceApi(config, key, Transport { _, _, _, _ -> calls++; Reply(status, "untrusted body") }).list() }; check(calls == 1) }
    }
    test("redirect not accepted as successful API response") { rejected { BugTraceApi(config, key, Transport { _, _, _, _ -> Reply(302, "") }).list() } }
    val build = Path.of(args.single()).toAbsolutePath().normalize()
    val root = Files.createTempDirectory(build, "git-check-")
    fun git(vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "Test Git setup failed: $output" }
    }
    git("init", "--quiet")
    test("unborn Git HEAD rejected") { rejected { GitCapture.head(root) } }
    Files.createDirectories(root.resolve("module"))
    val file = root.resolve("module/Payment.kt")
    val committed = "class Payment { fun charge() = 1 }\n"
    Files.writeString(file, committed)
    git("add", "module/Payment.kt")
    git("-c", "user.name=BugTrace Test", "-c", "user.email=local-test@example.invalid", "-c", "core.hooksPath=", "commit", "--quiet", "-m", "Committed test source")
    val editor = EditorCapture(file, committed, 0, 13)
    lateinit var capture: Capture
    test("capture contains current committed SHA and relative selected file") { capture = GitCapture.capture(root, editor, "module"); check(capture.commit == GitCapture.head(root)); check(capture.selectedFile == "module/Payment.kt"); check(capture.fileInCommit) }
    test("committed optional selected code and one-based line range captured") { check(capture.selection == committed.substring(0, 13)); check(capture.startLine == 1 && capture.endLine == 1) }
    test("selection excluded by default from report context") { check(capture.context(false)["selectedCode"] == null) }
    test("included committed selection has matching content hash") { check(capture.context(true)["selectedCodeSha256"] == sha256(committed.substring(0, 13))) }
    test("unsaved editor changes are excluded from optional code") { val changed = GitCapture.capture(root, editor.copy(text = "unsaved modified content"), "module"); check(changed.selection == null); rejected { changed.context(true) } }
    test("staged and unstaged changes do not change captured commit") {
        Files.writeString(file, "class Payment { fun charge() = 99 }\n"); git("add", "module/Payment.kt")
        val changed = GitCapture.capture(root, editor.copy(text = Files.readString(file)), "module")
        check(changed.dirty && changed.commit == capture.commit && changed.selection == null)
    }
    test("untracked selected file is explicitly absent from snapshot") {
        val untracked = root.resolve("module/New.kt"); Files.writeString(untracked, "class New")
        val result = GitCapture.capture(root, EditorCapture(untracked, "class New", 0, 5), "module")
        check(!result.fileInCommit && result.selection == null && result.dirty)
    }
    test("selected file outside configured module rejected") { rejected { GitCapture.capture(root, editor, "another-module") } }
    test("creation uses existing API fields and committed context, never key in JSON") {
        val api = BugTraceApi(config, key, Transport { method, uri, _, body ->
            check(method == "POST" && uri.path == "/api/investigations")
            val sent = JsonParser.parseString(body).asJsonObject
            check(sent["commitSha"].asString == capture.commit && sent["projectId"].asString == project)
            check(!body!!.contains(key) && sent["bugDescription"].asString.contains("COMMITTED_HEAD_VERIFIED"))
            Reply(201, record)
        })
        check(api.create(capture, Report("Report", "Observed defect", "One charge", "Two charges", true, true)).id == id)
    }
    test("import pins captured SHA and registered module, no working tree upload") {
        BugTraceApi(config, key, Transport { method, uri, _, body ->
            val sent = JsonParser.parseString(body).asJsonObject
            check(method == "POST" && uri.path == "/api/investigations/$id/snapshot/import")
            check(sent.keySet() == setOf("repositoryPath", "revision", "modulePath"))
            check(sent["revision"].asString == capture.commit && sent["modulePath"].asString == "module")
            Reply(200, "{}")
        }).importSnapshot(id, capture)
    }
    test("oversized report rejected without network submission") {
        rejected { BugTraceApi(config, key, Transport { _, _, _, _ -> error("Must not send") }).create(capture,
            Report("Report", "x".repeat(20000), "Expected", "Observed", true, false)) }
    }
    println("Core checks passed: $count")
}
