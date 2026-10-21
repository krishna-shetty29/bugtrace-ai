package com.bugtrace.idea

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

data class ReviewReference(val id: String, val state: String) {
    override fun toString() = "$id · $state"
}
data class SavedReview(val id: String, val state: String, val bundleHash: String,
                      val reproduction: String, val verification: String, val diff: String)

data class SourceReference(val id: String, val path: String, val revision: String,
                           val first: Int, val last: Int, val hash: String, val text: String,
                           val isCode: Boolean) {
    override fun toString() = "$path · lines $first–$last · ${if (isCode) "snapshot source" else "context only"}"
    fun checked(): SourceReference {
        require(first >= 1 && last >= first && last <= 1_000_000) { "Invalid source line range." }
        require(hash.matches(Regex("[0-9a-fA-F]{64}")) && sha256(text).equals(hash, true)) { "Saved source hash mismatch." }
        require(text.length <= 200_000) { "Source excerpt is too large." }
        if (isCode) {
            relativeSourcePath(path)
            require(revision.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}"))) { "Invalid source revision." }
        }
        return this
    }
    companion object {
        fun parse(item: JsonObject): SourceReference = SourceReference(item["id"].asString,
            item["sourceRef"].asString, item["revision"].asString, item["startLine"].asInt,
            item["endLine"].asInt, item["sourceHash"].asString, item["text"].asString,
            item["origin"].asString == "CURRENT_SNAPSHOT_CODE" && item["lineKind"].asString == "SOURCE_LINE" &&
                item["reference"].asJsonObject["kind"].asString == "CODE").checked()
    }
}

fun relativeSourcePath(value: String): String {
    require(value.isNotBlank() && value.length <= 2000 && !value.contains('\\') && !value.contains(':') &&
        !value.startsWith('/') && value.none { it.code < 32 } &&
        value.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe source path rejected." }
    return value
}

// Evidence paths are relative to the imported module, never an arbitrary server/local path.
fun localSource(root: Path, module: String, reference: SourceReference): Path {
    reference.checked()
    require(reference.isCode) { "Context documents are not local source links." }
    relativeSourcePath(reference.path)
    if (module != ".") relativeSourcePath(module)
    val base = root.toRealPath()
    val moduleRoot = if (module == ".") base else base.resolve(module).normalize()
    val file = moduleRoot.resolve(reference.path).normalize()
    require(file.startsWith(moduleRoot) && moduleRoot.startsWith(base) && Files.isRegularFile(file)) { "Source file is unavailable in this project." }
    var current = file
    while (current != base) {
        require(!Files.isSymbolicLink(current) && current.toRealPath() == current) { "Linked source paths are not allowed." }
        current = current.parent
    }
    require(Files.size(file) <= 2_097_152) { "Local source exceeds the navigation limit." }
    return file
}

fun rangeMatches(document: String, ref: SourceReference): Boolean {
    val lines = document.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return ref.first >= 1 && ref.last <= lines.size &&
        lines.subList(ref.first - 1, ref.last).joinToString("\n") == ref.text
}

fun dashboardInvestigation(connection: Connection, id: String) =
    connection.checked().dashboard + "/#/investigations/" + uuid(id)
