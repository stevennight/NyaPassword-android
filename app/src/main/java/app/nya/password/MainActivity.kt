package app.nya.password

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.nya.password.ui.EditorScreen
import app.nya.password.ui.GeneratorScreen
import app.nya.password.ui.ItemDetailScreen
import app.nya.password.ui.KitScreen
import app.nya.password.ui.LockScreen
import app.nya.password.ui.NpwTheme
import app.nya.password.ui.Route
import app.nya.password.ui.SecurityScreen
import app.nya.password.ui.SecureActivity
import app.nya.password.ui.SettingsScreen
import app.nya.password.ui.SetupGuideScreen
import app.nya.password.ui.UpdateModel
import app.nya.password.ui.VaultScreen
import app.nya.password.ui.WelcomeScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** The vault: sign in / unlock, then 密码库 · 生成器 · 安全 · 设置 (bottom navigation; a rail on wide screens). */
class MainActivity : SecureActivity() {
    private enum class Tab(val label: String, val icon: ImageVector) {
        VAULT("密码库", Icons.Filled.Key),
        GENERATOR("生成器", Icons.Filled.Password),
        SECURITY("安全", Icons.Filled.Shield),
        SETTINGS("设置", Icons.Filled.Settings),
    }

    private val tab = mutableStateOf(Tab.VAULT)
    /** Pages on top of the tabs (item, editor, guide). */
    private val stack = mutableStateListOf<Route>()
    lateinit var updates: UpdateModel
        private set

    private var onScanned: ((String) -> Unit)? = null
    private val scanner = registerForActivityResult(ScanContract()) { r -> r.contents?.let { c -> onScanned?.invoke(c) } }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchScanner() else Toast.makeText(this, "没有相机权限，无法扫码", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updates = UpdateModel(this)
        savedInstanceState?.getString("tab")?.let { t -> Tab.entries.find { it.name == t }?.let { tab.value = it } }
        if (intent?.getBooleanExtra(EXTRA_SETUP, false) == true) stack.add(Route.Guide)
        setContent {
            NpwTheme {
                Surface(color = MaterialTheme.colorScheme.background) { Root() }
            }
        }
        if (vault.prefs.checkUpdates) updates.check(quiet = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("tab", tab.value.name)
    }

    /** Scans a QR code (asks for the camera first). */
    fun scanQr(then: (String) -> Unit) {
        onScanned = then
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("扫描紧急恢复包上的二维码")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    fun open(r: Route) {
        stack.add(r)
    }

    fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    @Composable
    private fun Root() {
        val v = vault
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { v.messages.collect { snack.showSnackbar(it) } }
        LaunchedEffect(Unit) { runCatching { v.refresh() } }
        val lock = v.lock
        LaunchedEffect(lock.unlocked) { if (!lock.unlocked) stack.clear() }
        Box(Modifier.fillMaxSize()) {
            when {
                !v.ready -> Unit
                !lock.signedIn -> WelcomeScreen(this@MainActivity, onScan = ::scanQr)
                v.newKit != null -> KitScreen(v.newKit!!, first = true, onDone = { v.newKit = null })
                !lock.unlocked -> LockScreen(this@MainActivity)
                else -> Unlocked()
            }
            SnackbarHost(
                snack,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 72.dp),
            )
        }
    }

    @Composable
    private fun Unlocked() {
        BackHandler(stack.isNotEmpty() || tab.value != Tab.VAULT) {
            if (stack.isNotEmpty()) back() else tab.value = Tab.VAULT
        }
        val top = stack.lastOrNull()
        if (top != null) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                when (top) {
                    is Route.Detail -> ItemDetailScreen(this@MainActivity, top.vaultId, top.itemId)
                    is Route.Edit -> EditorScreen(this@MainActivity, top.vaultId, top.itemId, top.template)
                    Route.Guide -> SetupGuideScreen(this@MainActivity)
                }
            }
            return
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= 720.dp) {
                Row(Modifier.fillMaxSize()) {
                    NavigationRail(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(selected = tab.value == t, onClick = { tab.value = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                        }
                    }
                    Box(
                        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.widthIn(max = 1000.dp)) { TabContent(PaddingValues()) }
                    }
                }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(selected = tab.value == t, onClick = { tab.value = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                            }
                        }
                    },
                ) { pad ->
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
                        TabContent(PaddingValues(bottom = pad.calculateBottomPadding()))
                    }
                }
            }
        }
    }

    @Composable
    private fun TabContent(pad: PaddingValues) {
        when (tab.value) {
            Tab.VAULT -> VaultScreen(this, pad)
            Tab.GENERATOR -> GeneratorScreen(pad)
            Tab.SECURITY -> SecurityScreen(this, pad)
            Tab.SETTINGS -> SettingsScreen(this, pad)
        }
    }

    companion object {
        /** Open the setup guide (from the autofill service's settings link). */
        const val EXTRA_SETUP = "setup"
    }
}
