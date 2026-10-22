package app.repodeck

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

class LineMonitor(private val out: (String) -> Unit) : ProgressMonitor {
    private var task = ""
    private var total = 0
    private var done = 0
    private var lastPct = -1
    override fun start(totalTasks: Int) {}
    override fun beginTask(title: String, totalWork: Int) { task = title; total = totalWork; done = 0; lastPct = -1; out(title) }
    override fun update(completed: Int) {
        done += completed
        if (total > 0) {
            val pct = done * 100 / total / 10 * 10
            if (pct != lastPct) { lastPct = pct; out("$task $pct%") }
        }
    }
    override fun endTask() {}
    override fun isCancelled() = false
}

object GitOps {
    fun creds(token: String) = UsernamePasswordCredentialsProvider("x-access-token", token)
    fun dir(ctx: Context, owner: String, name: String) = File(ctx.filesDir, "repos/$owner/$name")
    fun exists(d: File) = File(d, ".git").exists()

    suspend fun clone(ctx: Context, owner: String, name: String, token: String, out: (String) -> Unit): File =
        withContext(Dispatchers.IO) {
            val d = dir(ctx, owner, name)
            if (exists(d)) throw IllegalStateException("Already cloned")
            d.mkdirs()
            Git.cloneRepository().setURI("https://github.com/$owner/$name.git").setDirectory(d)
                .setCredentialsProvider(creds(token)).setProgressMonitor(LineMonitor(out)).call().close()
            d
        }

    suspend fun pull(d: File, token: String, out: (String) -> Unit): String = withContext(Dispatchers.IO) {
        Git.open(d).use { g ->
            val r = g.pull().setCredentialsProvider(creds(token)).setProgressMonitor(LineMonitor(out)).call()
            if (r.isSuccessful) "OK: ${r.mergeResult?.mergeStatus ?: "fetched"}" else "Failed: ${r.mergeResult?.mergeStatus ?: r.rebaseResult?.status}"
        }
    }

    suspend fun status(d: File): String = withContext(Dispatchers.IO) {
        Git.open(d).use { g ->
            val s = g.status().call()
            val sb = StringBuilder("Branch: ${g.repository.branch}\n")
            fun sec(t: String, set: Set<String>) { if (set.isNotEmpty()) sb.append("$t (${set.size}):\n").append(set.sorted().take(60).joinToString("\n") { "  $it" }).append("\n") }
            sec("Added", s.added); sec("Changed", s.changed); sec("Removed", s.removed)
            sec("Modified", s.modified); sec("Missing", s.missing); sec("Untracked", s.untracked); sec("Conflicting", s.conflicting)
            if (s.isClean) sb.append("Clean")
            sb.toString()
        }
    }

    suspend fun branches(d: File): List<String> = withContext(Dispatchers.IO) {
        Git.open(d).use { g -> g.branchList().call().map { it.name.removePrefix("refs/heads/") } }
    }

    suspend fun checkout(d: File, branch: String) = withContext(Dispatchers.IO) {
        Git.open(d).use { g -> g.checkout().setName(branch).call(); Unit }
    }

    suspend fun commitPush(d: File, token: String, msg: String, name: String, email: String, out: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            Git.open(d).use { g ->
                g.add().addFilepattern(".").call()
                g.add().addFilepattern(".").setUpdate(true).call()
                if (!g.status().call().isClean) g.commit().setMessage(msg).setAuthor(name, email).setCommitter(name, email).call()
                val res = g.push().setCredentialsProvider(creds(token)).setProgressMonitor(LineMonitor(out)).call()
                res.flatMap { it.remoteUpdates }.joinToString("\n") { "${it.remoteName}: ${it.status}" }.ifEmpty { "OK" }
            }
        }
}

object GitIgnore {
    fun detect(rootNames: Set<String>): String = when {
        "build.gradle" in rootNames || "build.gradle.kts" in rootNames || "settings.gradle" in rootNames || "settings.gradle.kts" in rootNames -> "android"
        "pubspec.yaml" in rootNames -> "flutter"
        "package.json" in rootNames -> "node"
        "requirements.txt" in rootNames || "pyproject.toml" in rootNames || "setup.py" in rootNames -> "python"
        "Cargo.toml" in rootNames -> "rust"
        "go.mod" in rootNames -> "go"
        else -> "generic"
    }

    fun content(type: String): String? = when (type) {
        "android" -> "*.iml\n.gradle/\n/local.properties\n/.idea/\n.DS_Store\n/build/\n/app/build/\n/captures/\n.externalNativeBuild/\n.cxx/\n*.jks\n*.keystore\nkeystore.properties\n.env\n"
        "flutter" -> ".dart_tool/\n.packages\nbuild/\n.flutter-plugins\n.flutter-plugins-dependencies\n*.jks\n*.keystore\n.env\n"
        "node" -> "node_modules/\ndist/\n.env\n.env.*\n!.env.example\nnpm-debug.log*\n.DS_Store\ncoverage/\n"
        "python" -> "__pycache__/\n*.pyc\n.venv/\nvenv/\n.env\n*.egg-info/\ndist/\nbuild/\n.pytest_cache/\n"
        "rust" -> "/target\n**/*.rs.bk\n.env\n"
        "go" -> "bin/\n*.exe\n*.test\n*.out\n.env\n"
        else -> ".DS_Store\n.env\n*.log\n"
    }
}

object SecretScanner {
    private val nameRules: List<Pair<Regex, String>> = listOf(
        Regex("^\\.env(\\..+)?$") to "environment file",
        Regex(".*\\.(jks|keystore|p12|pfx|pem|key)$", RegexOption.IGNORE_CASE) to "key / certificate file",
        Regex("^id_(rsa|dsa|ecdsa|ed25519)(\\.pub)?$") to "SSH key",
        Regex("^(local|keystore|secrets?)\\.properties$") to "local secrets file",
        Regex("^(\\.netrc|\\.npmrc|\\.pypirc)$") to "credentials file",
        Regex("^(google-services\\.json|GoogleService-Info\\.plist)$") to "Firebase config"
    )
    private val allowedNames = setOf(".env.example", ".env.sample", ".env.template")

    private val contentRules: List<Pair<Regex, String>> = listOf(
        Regex("AKIA[0-9A-Z]{16}") to "AWS access key",
        Regex("gh[pousr]_[A-Za-z0-9]{36,}") to "GitHub token",
        Regex("github_pat_[A-Za-z0-9_]{50,}") to "GitHub fine-grained token",
        Regex("AIza[0-9A-Za-z_\\-]{35}") to "Google API key",
        Regex("-----BEGIN (RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY") to "private key",
        Regex("xox[baprs]-[A-Za-z0-9-]{10,}") to "Slack token",
        Regex("sk_live_[0-9a-zA-Z]{24,}") to "Stripe live key",
        Regex("sk-(ant-)?[A-Za-z0-9_\\-]{32,}") to "AI provider API key",
        Regex("(?i)(password|passwd|secret|api[_-]?key|access[_-]?token)\\s*[:=]\\s*[\"'][^\"'\\s]{12,}[\"']") to "hard-coded credential"
    )

    fun byName(n: String): String? {
        if (n in allowedNames) return null
        return nameRules.firstOrNull { it.first.matches(n) }?.second
    }

    fun byContent(cr: ContentResolver, uri: Uri): String? = runCatching {
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.take(8000).any { it.toInt() == 0 }) return null
        val text = String(bytes, Charsets.UTF_8)
        contentRules.firstOrNull { it.first.containsMatchIn(text) }?.second
    }.getOrNull()
}

object UploadEngine {
    val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".dart_tool", "Pods", "DerivedData", ".cxx", "venv", ".venv", ".externalNativeBuild")

    class Entry(val rel: String, val uri: Uri, val size: Long)
    class Report(val files: List<Entry>, val skipped: List<String>, val flagged: List<Pair<String, String>>, val total: Long, val type: String)

    suspend fun scan(ctx: Context, tree: Uri): Report = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: throw IllegalStateException("Cannot open folder")
        val files = mutableListOf<Entry>()
        val skipped = mutableListOf<String>()
        val flagged = mutableListOf<Pair<String, String>>()
        val rootNames = mutableSetOf<String>()
        fun walk(d: DocumentFile, rel: String) {
            for (c in d.listFiles()) {
                val n = c.name ?: continue
                val r = if (rel.isEmpty()) n else "$rel/$n"
                if (c.isDirectory) {
                    if (n in SKIP_DIRS) skipped += "$r/" else walk(c, r)
                } else if (c.isFile) {
                    if (rel.isEmpty()) rootNames += n
                    val size = c.length()
                    val reason = SecretScanner.byName(n)
                        ?: if (size > 100L * 1024 * 1024) "over 100 MB (GitHub limit, needs Git LFS)"
                        else if (size <= 1_000_000) SecretScanner.byContent(ctx.contentResolver, c.uri) else null
                    if (reason != null) flagged += r to reason else files += Entry(r, c.uri, size)
                }
            }
        }
        walk(root, "")
        Report(files, skipped, flagged, files.sumOf { it.size }, GitIgnore.detect(rootNames))
    }

    suspend fun upload(
        ctx: Context, report: Report, owner: String, repo: String, branch: String, message: String,
        force: Boolean, addIgnore: Boolean, token: String, author: Pair<String, String>, out: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "work/$owner/$repo")
        dir.deleteRecursively(); dir.mkdirs()
        out("Cloning…")
        Git.cloneRepository().setURI("https://github.com/$owner/$repo.git").setDirectory(dir)
            .setCredentialsProvider(GitOps.creds(token)).setProgressMonitor(LineMonitor(out)).call().use { git ->
                val r = git.repository
                val born = r.resolve("HEAD") != null
                if (r.branch != branch) {
                    if (r.findRef("refs/remotes/origin/$branch") != null)
                        git.checkout().setCreateBranch(true).setName(branch).setStartPoint("origin/$branch").call()
                    else if (born) git.checkout().setCreateBranch(true).setName(branch).call()
                    else r.updateRef(Constants.HEAD).link("refs/heads/$branch")
                }
                dir.listFiles()!!.filter { it.name != ".git" }.forEach { it.deleteRecursively() }
                report.files.forEachIndexed { i, e ->
                    val f = File(dir, e.rel)
                    f.parentFile!!.mkdirs()
                    ctx.contentResolver.openInputStream(e.uri)!!.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                    if (i % 50 == 0) out("Copy ${i + 1}/${report.files.size}")
                }
                if (addIgnore && !File(dir, ".gitignore").exists()) GitIgnore.content(report.type)?.let { File(dir, ".gitignore").writeText(it) }
                out("Staging…")
                git.add().addFilepattern(".").call()
                git.add().addFilepattern(".").setUpdate(true).call()
                if (git.status().call().isClean) return@use "NOTHING_TO_COMMIT"
                git.commit().setMessage(message).setAuthor(author.first, author.second).setCommitter(author.first, author.second).call()
                out("Pushing…")
                val res = git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/$branch")).setForce(force)
                    .setCredentialsProvider(GitOps.creds(token)).setProgressMonitor(LineMonitor(out)).call()
                res.forEach { pr ->
                    pr.remoteUpdates.forEach { u ->
                        if (u.status != RemoteRefUpdate.Status.OK && u.status != RemoteRefUpdate.Status.UP_TO_DATE)
                            throw IllegalStateException("Push ${u.status}: ${u.message ?: ""}")
                    }
                }
                "PUSHED"
            }.also { dir.deleteRecursively() }
    }
}
