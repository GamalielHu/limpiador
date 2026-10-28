package bo.app.limpiador

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import bo.app.limpiador.ui.AppsViewModel
import bo.app.limpiador.ui.CleanUpViewModel
import bo.app.limpiador.ui.SmartCleanViewModel
import bo.app.limpiador.ui.SmartEvent
import bo.app.limpiador.ui.StaleFilter
import bo.app.limpiador.ui.UiEvent
import bo.app.limpiador.ui.screens.AppsScreen
import bo.app.limpiador.ui.screens.DashboardScreen
import bo.app.limpiador.ui.screens.DuplicateCleanerScreen
import bo.app.limpiador.ui.screens.SmartCleanDashboardScreen
import bo.app.limpiador.ui.screens.StaleFilesScreen

private enum class Screen { Dashboard, Duplicates, Stale, Apps, Smart }

private val Colors = lightColorScheme(
    primary = Color(0xFF1B7F5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F2DF),
    onPrimaryContainer = Color(0xFF00382A),
    secondaryContainer = Color(0xFFE3EFE9),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = Colors) { App() } }
    }
}

/** Permisos de lectura según versión: granulares en 13+, almacenamiento clásico antes. */
private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO,
    )
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasMediaPermission(context: Context): Boolean =
    mediaPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Fallback explícito: solo se abre si el usuario pide el análisis profundo. */
private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(perApp)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

private fun openUsageAccessSettings(context: Context) {
    val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(withPackage)
    } catch (e: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
}

private fun openAppSettings(context: Context, pkg: String) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }
}

private fun launchApp(context: Context, pkg: String) {
    context.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { context.startActivity(it) } }
}

@Suppress("DEPRECATION")
private fun uninstallIntent(pkg: String): Intent =
    Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$pkg"))
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)

@Composable
private fun App(
    vm: CleanUpViewModel = viewModel(),
    appsVm: AppsViewModel = viewModel(),
    smartVm: SmartCleanViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var screen by remember { mutableStateOf(Screen.Dashboard) }
    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }

    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val categories by vm.categoryUsage.collectAsStateWithLifecycle()
    val duplicates by vm.duplicates.collectAsStateWithLifecycle()
    val dupSelection by vm.duplicateSelection.collectAsStateWithLifecycle()
    val staleFiles by vm.staleFiles.collectAsStateWithLifecycle()
    val staleSelection by vm.staleSelection.collectAsStateWithLifecycle()
    val staleFilter by vm.staleFilter.collectAsStateWithLifecycle()
    val staleLoading by vm.staleLoading.collectAsStateWithLifecycle()
    val allFiles by vm.allFilesAccess.collectAsStateWithLifecycle()
    val apps by appsVm.apps.collectAsStateWithLifecycle()
    val appsFilter by appsVm.filter.collectAsStateWithLifecycle()
    val appsLoad by appsVm.load.collectAsStateWithLifecycle()
    val usageAccess by appsVm.usageAccess.collectAsStateWithLifecycle()
    val appsSelection by appsVm.selection.collectAsStateWithLifecycle()
    val appsSummary by appsVm.summary.collectAsStateWithLifecycle()
    val smart by smartVm.state.collectAsStateWithLifecycle()

    val smartConsentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        smartVm.onConsentResult(r.resultCode == Activity.RESULT_OK)
    }
    val clearCacheLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        smartVm.onAppCacheCleared(r.resultCode == Activity.RESULT_OK)
    }

    // Desinstalación en cadena: el sistema confirma cada app, al volver se lanza la siguiente.
    var uninstallQueue by remember { mutableStateOf(emptyList<String>()) }
    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val rest = uninstallQueue.drop(1)
        uninstallQueue = rest
        if (rest.isEmpty()) {
            appsVm.clearSelection()
            appsVm.refresh(quiet = true)
            vm.refreshEnvironment()
        }
    }
    LaunchedEffect(uninstallQueue) {
        uninstallQueue.firstOrNull()?.let { uninstallLauncher.launch(uninstallIntent(it)) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result.values.any { it } || hasMediaPermission(context)
        if (hasPermission) vm.scanAll()
    }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onConsentResult(r.resultCode == Activity.RESULT_OK)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.scanTree(uri)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = hasMediaPermission(context)
        vm.refreshEnvironment()
        appsVm.onResume()
        smartVm.refreshEnvironment()
    }

    LaunchedEffect(Unit) {
        smartVm.uiEvents.collect { e ->
            when (e) {
                is SmartEvent.Message -> snackbar.showSnackbar(e.text)
                is SmartEvent.Consent -> smartConsentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
                SmartEvent.RequestDeepAccess -> openAllFilesSettings(context)
                SmartEvent.ClearAppCache -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            clearCacheLauncher.launch(Intent(android.os.storage.StorageManager.ACTION_CLEAR_APP_CACHE))
                        } catch (ex: Exception) {
                            snackbar.showSnackbar("Este teléfono no permite vaciar la caché desde otra app")
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            when (e) {
                is UiEvent.Message -> snackbar.showSnackbar(e.text)
                is UiEvent.Consent -> consentLauncher.launch(IntentSenderRequest.Builder(e.intentSender).build())
            }
        }
    }

    BackHandler(enabled = screen != Screen.Dashboard) { screen = Screen.Dashboard }

    when (screen) {
        Screen.Dashboard -> DashboardScreen(
            storage = storage,
            scanState = scanState,
            categories = categories,
            duplicateGroups = duplicates.size,
            duplicateWaste = duplicates.sumOf { it.wastedBytes },
            staleCount = if (staleFilter == StaleFilter()) staleFiles.size else 0,
            appsSummary = appsSummary,
            hasMediaPermission = hasPermission || allFiles,
            canRequestDeepAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !allFiles,
            snackbar = snackbar,
            onRequestPermission = { permissionLauncher.launch(mediaPermissions()) },
            onRequestDeepAccess = { openAllFilesSettings(context) },
            onScan = vm::scanAll,
            onCancelScan = vm::cancelScan,
            onPickFolder = { folderLauncher.launch(null) },
            onOpenDuplicates = { screen = Screen.Duplicates },
            onOpenStale = {
                vm.setStaleFilter(StaleFilter())
                screen = Screen.Stale
            },
            onOpenResidual = {
                vm.setStaleFilter(StaleFilter(residual = true))
                screen = Screen.Stale
            },
            onOpenApps = { screen = Screen.Apps },
            onOpenSmart = {
                screen = Screen.Smart
                if (smart.scanner is bo.app.limpiador.ui.state.SmartScannerStatus.Idle) smartVm.scan()
            },
            onOpenLargest = {
                vm.setStaleFilter(StaleFilter(days = 0))
                screen = Screen.Stale
            },
            onOpenCategory = {
                vm.openCategory(it)
                screen = Screen.Stale
            },
        )

        Screen.Duplicates -> DuplicateCleanerScreen(
            groups = duplicates,
            selection = dupSelection,
            scanState = scanState,
            snackbar = snackbar,
            onBack = { screen = Screen.Dashboard },
            onKeepOldest = vm::selectKeepOldest,
            onClearSelection = vm::clearDuplicateSelection,
            onToggle = vm::toggleDuplicate,
            onDelete = vm::deleteSelectedDuplicates,
        )

        Screen.Stale -> StaleFilesScreen(
            files = staleFiles,
            selection = staleSelection,
            filter = staleFilter,
            loading = staleLoading,
            scanState = scanState,
            snackbar = snackbar,
            onBack = { screen = Screen.Dashboard },
            onFilterChange = vm::setStaleFilter,
            onToggle = vm::toggleStale,
            onSelectAll = vm::selectAllStale,
            onDelete = vm::deleteSelectedStale,
        )

        Screen.Smart -> SmartCleanDashboardScreen(
            state = smart,
            snackbar = snackbar,
            onBack = { screen = Screen.Dashboard },
            onScan = smartVm::scan,
            onCancelScan = smartVm::cancelScan,
            onSafeClean = smartVm::safeClean,
            onToggleEnabled = smartVm::toggleEnabled,
            onToggleExpanded = smartVm::toggleExpanded,
            onToggleFile = smartVm::toggleFile,
            onSelectAll = smartVm::selectAll,
            onCleanCategory = smartVm::cleanCategory,
            onRequestDeepAccess = { openAllFilesSettings(context) },
            onRequestUsageAccess = { openUsageAccessSettings(context) },
        )

        Screen.Apps -> AppsScreen(
            apps = apps,
            filter = appsFilter,
            load = appsLoad,
            usageAccess = usageAccess,
            selection = appsSelection,
            onBack = { screen = Screen.Dashboard },
            onRefresh = { appsVm.refresh() },
            onFilterChange = appsVm::setFilter,
            onToggle = appsVm::toggle,
            onClearSelection = appsVm::clearSelection,
            onRequestUsageAccess = { openUsageAccessSettings(context) },
            onUninstall = { if (uninstallQueue.isEmpty()) uninstallQueue = it },
            onOpenSettings = { openAppSettings(context, it) },
            onLaunch = { launchApp(context, it) },
        )
    }
}
