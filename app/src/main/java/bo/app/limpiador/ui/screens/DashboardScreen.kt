@file:OptIn(ExperimentalMaterial3Api::class)

package bo.app.limpiador.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bo.app.limpiador.data.model.CategoryUsage
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.ui.AppsSummary
import bo.app.limpiador.ui.ScanState
import bo.app.limpiador.ui.StorageStats
import bo.app.limpiador.ui.formatBytes
import bo.app.limpiador.ui.components.formatDate

@Composable
fun DashboardScreen(
    storage: StorageStats,
    scanState: ScanState,
    categories: List<CategoryUsage>,
    duplicateGroups: Int,
    duplicateWaste: Long,
    staleCount: Int,
    appsSummary: AppsSummary?,
    hasMediaPermission: Boolean,
    canRequestDeepAccess: Boolean,
    snackbar: SnackbarHostState,
    onRequestPermission: () -> Unit,
    onRequestDeepAccess: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onPickFolder: () -> Unit,
    onOpenDuplicates: () -> Unit,
    onOpenStale: () -> Unit,
    onOpenResidual: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenLargest: () -> Unit,
    onOpenCategory: (FileCategory) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Limpiador") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StorageCard(storage) }

            if (!hasMediaPermission) {
                item {
                    NoticeCard(
                        title = "Permiso necesario",
                        text = "Para analizar fotos, videos y audio, concede acceso a los archivos multimedia.",
                        action = "Conceder acceso",
                        onAction = onRequestPermission,
                    )
                }
            }

            item { ScanCard(scanState, hasMediaPermission, onScan, onCancelScan, onPickFolder) }

            item {
                Text("Limpieza", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            item {
                ShortcutRow(
                    icon = Icons.Filled.Build,
                    title = "Aplicaciones",
                    subtitle = appsSummary?.let {
                        "${it.count} apps · ${formatBytes(it.total)} (caché ${formatBytes(it.cache)})"
                    } ?: "Qué apps ocupan más: instalador, datos y caché",
                    onClick = onOpenApps,
                )
            }
            item {
                ShortcutRow(
                    icon = Icons.Filled.List,
                    title = "Archivos más grandes",
                    subtitle = "Todos los archivos ordenados por tamaño",
                    onClick = onOpenLargest,
                )
            }
            item {
                ShortcutRow(
                    icon = Icons.Filled.Search,
                    title = "Duplicados",
                    subtitle = if (duplicateGroups == 0) "Sin duplicados detectados"
                    else "$duplicateGroups grupos · ${formatBytes(duplicateWaste)} recuperables",
                    onClick = onOpenDuplicates,
                )
            }
            item {
                ShortcutRow(
                    icon = Icons.Filled.Delete,
                    title = "Archivos inactivos",
                    subtitle = "$staleCount sin uso en más de 90 días",
                    onClick = onOpenStale,
                )
            }
            item {
                ShortcutRow(
                    icon = Icons.Filled.Warning,
                    title = "Residuales",
                    subtitle = "Temporales, registros, descargas incompletas y APKs",
                    onClick = onOpenResidual,
                )
            }

            if (canRequestDeepAccess) {
                item {
                    NoticeCard(
                        title = "Análisis profundo (opcional)",
                        text = "Sin acceso total solo se ven fotos, videos y audio. Para incluir documentos, APKs y " +
                            "archivos residuales, activa \"Acceso a todos los archivos\" en Ajustes.",
                        action = "Abrir Ajustes",
                        onAction = onRequestDeepAccess,
                    )
                }
            }

            if (categories.isNotEmpty()) {
                item {
                    Text("Por categoría", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                val max = categories.maxOf { it.bytes }.coerceAtLeast(1)
                items(categories, key = { it.category.name }) { c ->
                    CategoryRow(c, c.bytes.toFloat() / max) { onOpenCategory(c.category) }
                }
            }
        }
    }
}

@Composable
private fun StorageCard(s: StorageStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Almacenamiento interno", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val fraction = if (s.totalBytes > 0) s.usedBytes.toFloat() / s.totalBytes else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                color = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Ocupado: ${formatBytes(s.usedBytes)}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Libre: ${formatBytes(s.freeBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Total ${formatBytes(s.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScanCard(
    state: ScanState,
    hasPermission: Boolean,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onPickFolder: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is ScanState.Scanning -> {
                    Text(state.phase, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val f = state.fraction
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.total > 0) "${state.processed} de ${state.total}" else "${state.processed} archivos",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.detail.isNotEmpty()) {
                        Text(
                            state.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancelar") }
                }
                is ScanState.Deleting -> {
                    Text("Eliminando ${state.count} archivos…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> {
                    val subtitle = when (state) {
                        is ScanState.Ready ->
                            if (state.finishedAt > 0) "${state.indexed} archivos indexados · ${formatDate(state.finishedAt)}"
                            else "${state.indexed} archivos en el índice guardado"
                        else -> "Analiza el almacenamiento para encontrar duplicados y archivos sin uso."
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onScan, enabled = hasPermission, modifier = Modifier.weight(1f)) {
                            Text(
                                if (state is ScanState.Ready) "Volver a analizar" else "Analizar",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                            Text("Analizar carpeta", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun CategoryRow(c: CategoryUsage, fraction: Float, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(c.category.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${formatBytes(c.bytes)} · ${c.count}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun NoticeCard(title: String, text: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
        }
    }
}
