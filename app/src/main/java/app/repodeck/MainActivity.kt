package app.repodeck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : AppCompatActivity() {
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)

        unlocked = !Graph.store.lock
        setContent {
            RepoDeckTheme {
                if (unlocked) AppRoot() else LockScreen { authenticate() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i?.data?.takeIf { it.scheme == "repodeck" && it.host == "oauth" }?.let { Graph.oauthCallback = it }
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked) authenticate()
    }

    private fun authenticate() {
        val auth = if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_WEAK or DEVICE_CREDENTIAL else BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true // device has no screen lock / biometrics: nothing to verify against
            return
        }
        val b = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.app_name)).setAllowedAuthenticators(auth)
        if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText(getString(R.string.cancel))
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        }).authenticate(b.build())
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, Modifier.padding(16.dp))
            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
        }
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val start = if (Graph.store.activeLogin != null) "repos" else "login"
    NavHost(nav, startDestination = start) {
        composable("login") { LoginScreen(onDone = { nav.navigate("repos") { popUpTo(0) } }) }
        composable("repos") { ReposScreen(nav) }
        composable("repo/{owner}/{name}") { e ->
            RepoScreen(nav, e.arguments!!.getString("owner")!!, e.arguments!!.getString("name")!!)
        }
        composable("upload") { UploadScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("logs") { LogsScreen(nav) }
        composable("downloads") { DownloadsScreen(nav) }
    }
}
