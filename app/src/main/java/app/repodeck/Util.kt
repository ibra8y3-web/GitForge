package app.repodeck

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.io.File

val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonElement?.o(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

fun JsonObject.s(k: String): String {
    val p = this[k] as? JsonPrimitive
    return if (p == null || p is JsonNull) "" else p.content
}

fun JsonObject.b(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.n(k: String): Long = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
fun JsonObject.obj(k: String): JsonObject = this[k].o()
fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray)?.map { it.o() } ?: emptyList()
fun JsonElement.items(): List<JsonObject> = (this as? JsonArray)?.map { it.o() } ?: emptyList()

fun obj(vararg p: Pair<String, Any?>): JsonObject = buildJsonObject {
    p.forEach { (k, v) ->
        when (v) {
            null -> {}
            is Boolean -> put(k, v)
            is Number -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}

fun encPath(path: String): String = path.split("/").joinToString("/") { Uri.encode(it) }

object Fmt {
    fun err(e: Throwable): String = when (e) {
        is GitHubApi.ApiException -> "${e.code}: ${e.message}"
        is java.net.UnknownHostException -> "No network connection"
        is java.net.SocketTimeoutException -> "Connection timed out"
        else -> e.message ?: e::class.simpleName ?: "Error"
    }

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    fun time(iso: String?): String = iso?.replace("T", " ")?.removeSuffix("Z")?.take(16) ?: ""
}

fun Uri.displayName(ctx: Context): String {
    ctx.contentResolver.query(this, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) return c.getString(i)
    }
    return lastPathSegment?.substringAfterLast('/') ?: "file"
}

fun Uri.fileSize(ctx: Context): Long {
    ctx.contentResolver.query(this, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.SIZE)
        if (i >= 0 && c.moveToFirst()) return c.getLong(i)
    }
    return -1
}

object ZipTools {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i -> val buf = ByteArray(65536); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Reads every entry fully (CRC is checked by the JDK) and returns a human readable report. */
    fun verify(f: File): String {
        ZipFile(f).use { z ->
            var count = 0
            var total = 0L
            val apks = mutableListOf<String>()
            for (e in z.entries()) {
                z.getInputStream(e).use { s ->
                    val buf = ByteArray(65536)
                    while (true) { val n = s.read(buf); if (n < 0) break; total += n }
                }
                count++
                if (e.name.endsWith(".apk") || e.name.endsWith(".aab")) apks += e.name
            }
            return "SHA-256: ${sha256(f)}\nEntries: $count, ${Fmt.size(total)}\n" + apks.joinToString("\n")
        }
    }

    fun extractApk(f: File, outDir: File): File? {
        outDir.mkdirs()
        ZipFile(f).use { z ->
            val e = z.entries().toList().firstOrNull { it.name.endsWith(".apk") } ?: return null
            val out = File(outDir, File(e.name).name)
            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            return out
        }
    }

    fun extractApks(f: File, outDir: File, prefix: String): List<File> {
        outDir.mkdirs()
        val out = mutableListOf<File>()
        ZipFile(f).use { z ->
            for (e in z.entries().toList().filter { !it.isDirectory && it.name.endsWith(".apk") }) {
                val dst = File(outDir, "$prefix-${File(e.name).name}")
                z.getInputStream(e).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                out += dst
            }
        }
        return out
    }

    fun readLogZip(f: File, maxChars: Int = 200_000): String {
        val sb = StringBuilder()
        ZipFile(f).use { z ->
            for (e in z.entries().toList().filter { !it.isDirectory }.sortedBy { it.name }) {
                sb.append("\n===== ${e.name} =====\n")
                sb.append(z.getInputStream(e).bufferedReader().readText())
            }
        }
        return if (sb.length > maxChars) "…(truncated)…\n" + sb.takeLast(maxChars) else sb.toString()
    }
}
