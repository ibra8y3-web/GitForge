package app.gitforge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class Account(val login: String, val token: String, val avatar: String = "")

/** Tokens live only in EncryptedSharedPreferences (Android Keystore backed). */
class Store(private val ctx: Context) {
    private val prefs: SharedPreferences = open()

    private fun create(): SharedPreferences {
        val k = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "secure", k,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun open(): SharedPreferences = try { create() } catch (e: Exception) {
        ctx.deleteSharedPreferences("secure"); create()
    }

    var activeLogin by mutableStateOf<String?>(prefs.getString("active", null))
    var accounts by mutableStateOf(readAccounts())

    private fun readAccounts(): List<Account> =
        runCatching { json.decodeFromString<List<Account>>(prefs.getString("accounts", "[]")!!) }.getOrDefault(emptyList())

    fun activeToken(): String? = accounts.firstOrNull { it.login == activeLogin }?.token
    fun active(): Account? = accounts.firstOrNull { it.login == activeLogin }

    fun addAccount(a: Account) { accounts = accounts.filter { it.login != a.login } + a; activeLogin = a.login; persist() }
    fun switchTo(login: String) { activeLogin = login; persist() }
    fun remove(login: String) {
        accounts = accounts.filter { it.login != login }
        if (activeLogin == login) activeLogin = accounts.firstOrNull()?.login
        persist()
    }

    private fun persist() {
        prefs.edit().putString("accounts", json.encodeToString(accounts)).putString("active", activeLogin).apply()
    }

    var clientId: String
        get() = prefs.getString("client_id", "") ?: ""
        set(v) { prefs.edit().putString("client_id", v).apply() }

    var lock: Boolean
        get() = prefs.getBoolean("lock", false)
        set(v) { prefs.edit().putBoolean("lock", v).apply() }
}

data class LogEntry(val time: String, val ok: Boolean, val op: String, val detail: String)

object OpLog {
    val entries = mutableStateListOf<LogEntry>()
    private var file: File? = null

    fun init(c: Context) {
        file = File(c.filesDir, "ops.log")
        runCatching {
            file!!.readLines().takeLast(500).forEach {
                val p = it.split("\t", limit = 4)
                if (p.size == 4) entries.add(0, LogEntry(p[0], p[1] == "1", p[2], p[3]))
            }
        }
    }

    fun add(ok: Boolean, op: String, detail: String = "") {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val e = LogEntry(t, ok, op, detail.replace('\n', ' '))
        entries.add(0, e)
        if (entries.size > 500) entries.removeAt(entries.lastIndex)
        runCatching { file?.appendText("$t\t${if (ok) 1 else 0}\t$op\t${e.detail}\n") }
    }

    fun clear() { entries.clear(); runCatching { file?.writeText("") } }
}

object Notifier {
    const val CHANNEL = "builds"

    fun init(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun notify(c: Context, id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) } // fails silently if permission denied
    }
}

object Installer {
    fun install(ctx: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(ctx, R.string.allow_install, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

object Graph {
    lateinit var app: Application
    lateinit var store: Store
    val api: GitHubApi by lazy { GitHubApi { store.activeToken() } }

    fun init(a: Application) {
        app = a
        store = Store(a)
        OpLog.init(a)
        Notifier.init(a)
    }
}

class GitForgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}
