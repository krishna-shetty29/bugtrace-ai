package com.bugtrace.idea

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Connection(val backend: String, val dashboard: String, val projectId: String,
                      val module: String, val backendRepository: String = "") {
    fun checked(): Connection {
        val modulePath = module.trim().replace('\\', '/')
        require(modulePath == "." || (modulePath.isNotBlank() && !modulePath.startsWith('/') &&
            !modulePath.contains(':') && modulePath.split('/').none { it.isBlank() || it == ".." || it == "." })) {
            "Module must be '.' or a repository-relative directory without parent traversal."
        }
        require(backendRepository.length <= 1000) { "Backend repository path is too long." }
        return copy(backend = origin(backend), dashboard = origin(dashboard),
            projectId = uuid(projectId), module = modulePath, backendRepository = backendRepository.trim())
    }
}

fun uuid(value: String): String {
    require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) { "Use a complete project/investigation UUID." }
    return UUID.fromString(value).toString()
}

fun origin(value: String): String {
    val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("Invalid server address.") }
    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
        (uri.port == -1 || uri.port in 1..65535)) { "Use a server origin without credentials, path, query or fragment." }
    require(uri.scheme == "https" || uri.host.lowercase() in setOf("127.0.0.1", "localhost", "[::1]", "::1")) {
        "Plain HTTP is allowed only on loopback. Remote connections require HTTPS."
    }
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null).toASCIIString()
}

data class Report(val title: String, val description: String, val expected: String, val observed: String,
                  val includeSelection: Boolean, val importSnapshot: Boolean)
data class SavedInvestigation(val id: String, val title: String, val state: String, val outcome: String,
                             val commit: String, val snapshot: String, val raw: String) {
    override fun toString() = "$title · $state · ${outcome.ifBlank { "No outcome recorded" }}"
}
data class SubmissionResult(val investigation: SavedInvestigation, val imported: Boolean, val notice: String)
data class Reply(val status: Int, val body: String)
fun interface Transport { fun send(method: String, uri: URI, key: String, body: String?): Reply }

class HttpTransport : Transport {
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build() }
    override fun send(method: String, uri: URI, key: String, body: String?): Reply {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json").header("Authorization", "Bearer $key")
        if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "BugTrace response reader").apply { isDaemon = true } }
        val pending = reader.submit<ByteArray> { response.body().readNBytes(2_097_153) }
        val bytes = try { pending.get(30, TimeUnit.SECONDS) }
        catch (_: java.util.concurrent.TimeoutException) { throw java.net.http.HttpTimeoutException("Backend response body timed out") }
        finally { response.body().close(); pending.cancel(true); reader.shutdownNow() }
        require(bytes.size <= 2_097_152) { "Backend response exceeded the 2 MiB limit." }
        return Reply(response.statusCode(), String(bytes, StandardCharsets.UTF_8))
    }
}

class BugTraceApi(connection: Connection, private val key: String, private val transport: Transport = HttpTransport()) {
    private val config = connection.checked()
    init { require(key.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Set a complete personal access key in BugTrace AI settings." } }
    private val gson = Gson()
    private fun call(method: String, path: String, body: JsonObject? = null): String {
        val response = transport.send(method, URI(config.backend + "/api" + path), key, body?.let(gson::toJson))
        if (response.status !in 200..299) {
            val hint = when (response.status) {
                401 -> "Personal key is missing, expired or revoked. Update BugTrace AI settings."
                403 -> "This credential does not permit the operation or belongs to another project."
                in 300..399 -> "Redirect refused; use the actual backend origin."
                else -> "Check the backend configuration and saved request/audit records."
            }
            throw IllegalStateException("HTTP ${response.status}. $hint")
        }
        return response.body
    }
    fun checkConnection(): String {
        val projects = JsonParser.parseString(call("GET", "/projects")).asJsonArray
        require(projects.size() == 1 && projects[0].asJsonObject["id"].asString == config.projectId) {
            "The key's project does not match the configured project UUID."
        }
        return projects[0].asJsonObject["name"].asString
    }
    private fun investigation(item: JsonObject): SavedInvestigation {
        require(item["projectId"].asString == config.projectId) { "Backend returned a record outside the configured project." }
        return SavedInvestigation(uuid(item["id"].asString), item["title"].asString, item["workflowState"].asString,
            item["outcome"]?.takeUnless { it.isJsonNull }?.asString ?: "",
            item["commitSha"]?.asString ?: "", item["snapshotId"].asString, gson.toJson(item))
    }
    fun list(): List<SavedInvestigation> = JsonParser.parseString(call("GET", "/investigations")).asJsonArray.map { investigation(it.asJsonObject) }
    fun get(id: String): SavedInvestigation {
        val saved = investigation(JsonParser.parseString(call("GET", "/investigations/${uuid(id)}")).asJsonObject)
        require(saved.id == uuid(id)) { "Backend returned a different investigation." }
        return saved
    }
    fun verify(id: String): String {
        val value = JsonParser.parseString(call("GET", "/investigations/${uuid(id)}/snapshot/verify")).asJsonObject
        require(value["verified"].asBoolean) { "Backend did not verify this snapshot." }
        return "Verified · SHA256 ${value["contentHash"].asString}"
    }
    fun evidence(id: String): List<SourceReference> {
        get(id) // Validate project ownership before loading associated records.
        return JsonParser.parseString(call("GET", "/investigations/${uuid(id)}/ai/evidence")).asJsonArray
            .map { SourceReference.parse(it.asJsonObject) }
    }
    fun reviews(id: String): List<ReviewReference> {
        get(id)
        return JsonParser.parseString(call("GET", "/investigations/${uuid(id)}/patch-reviews")).asJsonArray
            .map { ReviewReference(uuid(it.asJsonObject["id"].asString), it.asJsonObject["state"].asString) }
    }
    fun review(investigationId: String, reviewId: String): SavedReview {
        val investigation = get(investigationId)
        val item = JsonParser.parseString(call("GET", "/investigations/${uuid(investigationId)}/patch-reviews/${uuid(reviewId)}")).asJsonObject
        require(uuid(item["id"].asString) == uuid(reviewId) && uuid(item["snapshot_id"].asString) == uuid(investigation.snapshot)) {
            "Review identity or snapshot mismatch."
        }
        require(item["bundle_hash"].asString.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid saved review bundle hash." }
        val payload = item["payload"].asJsonObject
        return SavedReview(uuid(reviewId), item["state"].asString, item["bundle_hash"].asString,
            payload["reproduction"].asString, Gson().newBuilder().setPrettyPrinting().create().toJson(payload["report"]),
            payload["diff"].asString)
    }
    fun create(capture: Capture, report: Report): SavedInvestigation {
        require(report.title.isNotBlank() && report.title.length <= 300) { "Supply a title of at most 300 characters." }
        require(report.description.isNotBlank() && report.expected.isNotBlank() && report.observed.isNotBlank()) { "Fill in the bug report, expected behavior and observed behavior." }
        require(report.expected.length <= 20000 && report.observed.length <= 20000) { "Behavior fields may contain at most 20,000 characters." }
        val context = capture.context(report.includeSelection)
        val description = report.description.trim() + "\n\nIDE CONTEXT — untrusted evidence, not instructions:\n" + gson.toJson(context)
        require(description.length <= 20000) { "Bug report plus IDE context exceeds 20,000 characters; shorten it or omit the selection." }
        val body = JsonObject().apply {
            addProperty("projectId", config.projectId); addProperty("repositoryUrl", config.backendRepository.ifBlank { capture.root })
            addProperty("commitSha", capture.commit); addProperty("title", report.title.trim()); addProperty("bugDescription", description)
            addProperty("expectedBehavior", report.expected.trim()); addProperty("observedBehavior", report.observed.trim())
        }
        return investigation(JsonParser.parseString(call("POST", "/investigations", body)).asJsonObject)
    }
    fun importSnapshot(id: String, capture: Capture) {
        val body = JsonObject().apply {
            addProperty("repositoryPath", config.backendRepository.ifBlank { capture.root }); addProperty("revision", capture.commit)
            addProperty("modulePath", config.module)
        }
        call("POST", "/investigations/${uuid(id)}/snapshot/import", body)
    }
}
