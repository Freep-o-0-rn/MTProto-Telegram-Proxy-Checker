package com.example.telegramproxychecker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ProxyViewModel
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The scan can run even if the notification is hidden by Android. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[ProxyViewModel::class.java]

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = getSharedPreferences("notification_permission", MODE_PRIVATE)
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF229ED9),
                    onPrimary = Color.White,
                    secondary = Color(0xFF4ADE80),
                    background = Color(0xFF07111F),
                    surface = Color(0xFF192B3D),
                    onSurface = Color(0xFFF1F5F9)
                )
            ) {
                ProxyApp(viewModel)
            }
        }
    }
}

fun openTelegramProxy(context: Context, proxy: MtProxy) {
    val telegramUri = Uri.parse(
        "tg://proxy" +
                "?server=${Uri.encode(proxy.server)}" +
                "&port=${proxy.port}" +
                "&secret=${Uri.encode(proxy.secret)}"
    )

    val webUri = Uri.parse(proxy.originalUrl)

    val telegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.thunderdog.challegram"
    )

    for (packageName in telegramPackages) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri)
            .setPackage(packageName)

        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // пробуем следующий пакет Telegram
        }
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri)
        context.startActivity(intent)
        return
    } catch (_: Exception) {
        // fallback ниже
    }

    try {
        val intent = Intent(Intent.ACTION_VIEW, webUri)
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Telegram не найден",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun ProxyApp(viewModel: ProxyViewModel) {
    // Navigation and filters only. Scan state still belongs to ScanSession/foreground service.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(ProxyTab.WORKING) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedProxyKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.loadOnce() }
    LaunchedEffect(showSettings) {
        if (showSettings) viewModel.refreshSourceInventory()
    }
    BackHandler(enabled = showSettings) { showSettings = false }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            mtprotoEnabled = viewModel.mtprotoSourceEnabled,
            socks5Enabled = viewModel.socks5SourceEnabled,
            sourceSwitchEnabled = !viewModel.isLoading && viewModel.checkingProxyKeys.isEmpty(),
            inventoryCounts = viewModel.inventoryCounts,
            inventoryErrors = viewModel.inventoryErrors,
            inventoryRefreshing = viewModel.inventoryRefreshing,
            scanLimit = viewModel.scanLimit,
            scanAll = viewModel.scanAll,
            parallelChecks = viewModel.parallelChecks,
            autoConcurrency = viewModel.autoConcurrency,
            autoSuggestedWorkers = viewModel.autoSuggestedWorkers,
            onParallelChecksChange = viewModel::updateParallelChecks,
            onAutoConcurrencyChange = viewModel::updateAutoConcurrency,
            onScanLimitChange = viewModel::updateScanLimit,
            onScanAllChange = viewModel::updateScanAll,
            onRefreshInventory = viewModel::refreshSourceInventory,
            onMtprotoEnabledChange = viewModel::updateMtprotoSourceEnabled,
            onSocks5EnabledChange = viewModel::updateSocks5SourceEnabled
        )
    } else {
        ProxyDashboardScreen(
            viewModel = viewModel,
            listState = listState,
            selectedTab = selectedTab,
            onTabSelected = { tab ->
                if (selectedTab != tab) {
                    selectedTab = tab
                    expandedProxyKey = null
                    scope.launch { listState.scrollToItem(0) }
                }
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { query ->
                searchQuery = query
                scope.launch { listState.scrollToItem(0) }
            },
            searchVisible = searchVisible,
            onSearchVisibleChange = { show ->
                searchVisible = show
                if (!show) searchQuery = ""
            },
            expandedProxyKey = expandedProxyKey,
            onExpandProxy = { expandedProxyKey = it },
            onOpenSettings = { showSettings = true },
            onCopyDiagnostics = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val progress = viewModel.scanProgress
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "Telegram proxy diagnostics",
                        buildProxyDiagnostics(viewModel.proxies, progress.checked, progress.total)
                    )
                )
                Toast.makeText(context, "Диагностика скопирована без secret", Toast.LENGTH_SHORT).show()
            },
            onConnect = { proxy -> openTelegramProxy(context, proxy) }
        )
    }
}
