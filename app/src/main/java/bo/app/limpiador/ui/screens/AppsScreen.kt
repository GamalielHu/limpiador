@file:OptIn(ExperimentalMaterial3Api::class)

package bo.app.limpiador.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import bo.app.limpiador.data.apps.AppInfo
import bo.app.limpiador.ui.AppKind
import bo.app.limpiador.ui.AppSort
import bo.app.limpiador.ui.AppsFilter
import bo.app.limpiador.ui.AppsLoad
import bo.app.limpiador.ui.components.formatDate
import bo.app.limpiador.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val unusedOptions = listOf(0 to "Cualquier uso", 30 to "Sin uso 30 días", 90 to "Sin uso 90 días", 180 to "Sin uso 6 meses")

@Composable
fun AppsScreen(
    apps: List<AppInfo>,
    filter: AppsFilter,
    load: AppsLoad,
    usageAccess: Boolean,
    selection: Set<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFilterChange: (AppsFilter) -> Unit,
    onToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onUninstall: (List<String>) -> Unit,
    onOpenSettings: (String) -> Unit,
    onLaunch: (String) -> Unit,
) {
    var detail by remember { mutableStateOf<AppInfo?>(null) }
    var searching by remember { mutableStateOf(false) }
    val selectedApps = remember(apps, selection) { apps.filter { it.packageName in selection } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Aplicaciones") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = { searching = !searching }) { Icon(Icons.Filled.Search, "Buscar") }
                    IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Actualizar") }
                },
            )
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("${selection.size} seleccionadas", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                formatBytes(selectedApps.sumOf { it.totalBytes }),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Row {
                            TextButton(onClick = onClearSelection) { Text("Quitar") }
                            Button(
                                onClick = { onUninstall(selection.toList()) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) { Text("Desinstalar") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!usageAccess) {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Falta \"Acceso de uso\"", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Sin ese permiso solo se ve el tamaño del instalador. Actívalo para ver datos, caché y " +
                                "cuándo usaste cada app por última vez.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRequestUsageAccess) { Text("Activar en Ajustes") }
                    }
                }
            }

            if (searching) {
                OutlinedTextField(
                    value = filter.query,
                    onValueChange = { onFilterChange(filter.copy(query = it)) },
                    placeholder = { Text("Buscar app") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            ChipRow {
                AppKind.entries.forEach { k ->
                    FilterChip(filter.kind == k, { onFilterChange(filter.copy(kind = k)) }, { Text(k.label) })
                }
            }
            ChipRow {
                AppSort.entries.forEach { s ->
                    FilterChip(filter.sort == s, { onFilterChange(filter.copy(sort = s)) }, { Text(s.label) })
                }
            }
            if (usageAccess) {
                ChipRow {
                    unusedOptions.forEach { (d, label) ->
                        FilterChip(filter.unusedDays == d, { onFilterChange(filter.copy(unusedDays = d)) }, { Text(label) })
                    }
                }
            }

            when (load) {
                is AppsLoad.Loading -> {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (load.total > 0) "Midiendo aplicaciones… ${load.processed} de ${load.total}" else "Midiendo aplicaciones…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (load.total > 0) {
                            LinearProgressIndicator(
                                progress = { load.processed.toFloat() / load.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                else -> Summary(apps)
            }
            HorizontalDivider()

            if (apps.isEmpty() && load == AppsLoad.Loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ninguna app con este filtro", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val max = apps.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1) ?: 1
                LazyColumn(Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { a ->
                        AppRow(
                            app = a,
                            fraction = a.totalBytes.toFloat() / max,
                            checked = a.packageName in selection,
                            onCheck = { onToggle(a.packageName) },
                            onClick = { detail = a },
                        )
                    }
                }
            }
        }
    }

    detail?.let { a ->
        AppDetailDialog(
            app = a,
            onDismiss = { detail = null },
            onUninstall = { detail = null; onUninstall(listOf(a.packageName)) },
            onOpenSettings = { detail = null; onOpenSettings(a.packageName) },
            onLaunch = { detail = null; onLaunch(a.packageName) },
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Summary(apps: List<AppInfo>) {
    val app = apps.sumOf { it.appBytes }
    val data = apps.sumOf { it.dataBytes ?: 0 }
    val cache = apps.sumOf { it.cacheBytes ?: 0 }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${apps.size} apps · ${formatBytes(app + data + cache)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Apps ${formatBytes(app)} · Datos ${formatBytes(data)} · Caché ${formatBytes(cache)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AppIcon(packageName: String, size: Int = 44) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(size.dp)) }
    }
}

@Composable
private fun AppRow(app: AppInfo, fraction: Float, checked: Boolean, onCheck: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheck() }, enabled = !app.isSystem)
        AppIcon(app.packageName)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatBytes(app.totalBytes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                buildString {
                    append("App ${formatBytes(app.appBytes)}")
                    app.dataBytes?.let { append(" · Datos ${formatBytes(it)}") }
                    app.cacheBytes?.let { append(" · Caché ${formatBytes(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Último uso: " + (app.lastUsed?.let { formatDate(it) } ?: "sin registro"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AppDetailDialog(
    app: AppInfo,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunch: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { AppIcon(app.packageName, 56) },
        title = { Text(app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailLine("Paquete", app.packageName)
                app.versionName?.let { DetailLine("Versión", it) }
                DetailLine("Aplicación", formatBytes(app.appBytes))
                DetailLine("Datos", app.dataBytes?.let { formatBytes(it) } ?: "requiere Acceso de uso")
                DetailLine("Caché", app.cacheBytes?.let { formatBytes(it) } ?: "requiere Acceso de uso")
                DetailLine("Total", formatBytes(app.totalBytes))
                DetailLine("Último uso", app.lastUsed?.let { formatDate(it) } ?: "sin registro")
                DetailLine("Instalada", formatDate(app.installedAt))
                DetailLine("Actualizada", formatDate(app.updatedAt))
                if (app.isSystem) {
                    Text(
                        "App del sistema: no se puede desinstalar, pero sí borrar su caché o datos desde Ajustes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Para liberar caché o datos sin desinstalar, usa \"Ajustes de la app\" → Almacenamiento.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = onOpenSettings) { Text("Ajustes de la app") }
                if (app.launchable) TextButton(onClick = onLaunch) { Text("Abrir") }
                if (!app.isSystem) {
                    TextButton(onClick = onUninstall) { Text("Desinstalar", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
