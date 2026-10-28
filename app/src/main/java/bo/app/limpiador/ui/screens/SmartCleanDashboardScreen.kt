@file:OptIn(ExperimentalMaterial3Api::class)

package bo.app.limpiador.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bo.app.limpiador.domain.model.RiskLevel
import bo.app.limpiador.domain.model.SuggestionCategory
import bo.app.limpiador.ui.components.ConfirmDeleteDialog
import bo.app.limpiador.ui.components.SuggestionCategoryCard
import bo.app.limpiador.ui.formatBytes
import bo.app.limpiador.ui.state.SmartCleanUiState
import bo.app.limpiador.ui.state.SmartScannerStatus

@Composable
fun SmartCleanDashboardScreen(
    state: SmartCleanUiState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onCancelScan: () -> Unit,
    onSafeClean: () -> Unit,
    onToggleEnabled: (SuggestionCategory) -> Unit,
    onToggleExpanded: (SuggestionCategory) -> Unit,
    onToggleFile: (SuggestionCategory, String) -> Unit,
    onSelectAll: (SuggestionCategory, Boolean) -> Unit,
    onCleanCategory: (SuggestionCategory) -> Unit,
    onRequestDeepAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
) {
    // Confirmación pendiente: null = ninguna; categoría = una tarjeta; SAFE_ALL = limpieza segura.
    var confirm by remember { mutableStateOf<Any?>(null) }
    val busy = state.scanning || state.deleting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limpieza inteligente") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    IconButton(onClick = onScan, enabled = !busy) { Icon(Icons.Filled.Refresh, "Volver a analizar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeroCard(state, busy, onScan, onCancelScan) { confirm = SAFE_ALL }
            }
            item { ProtectionNote() }

            if (state.needsDeepAccess) {
                item {
                    Notice(
                        "Sin acceso total solo se ven audios y archivos propios. Para encontrar APKs, descargas y " +
                            "temporales activa el Análisis profundo (las fotos y videos siguen protegidos).",
                        "Activar Análisis profundo",
                        onRequestDeepAccess,
                    )
                }
            }
            if (!state.usageAccess) {
                item {
                    Notice(
                        "Activa \"Acceso de uso\" para medir la caché de las aplicaciones.",
                        "Activar en Ajustes",
                        onRequestUsageAccess,
                    )
                }
            }

            if (state.scanner is SmartScannerStatus.Finished && state.categories.isEmpty()) {
                item {
                    Text(
                        "No se encontró nada prescindible. ¡Tu almacenamiento está limpio!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(state.categories, key = { it.category.name }) { c ->
                SuggestionCategoryCard(
                    state = c,
                    busy = busy,
                    onToggleEnabled = { onToggleEnabled(c.category) },
                    onToggleExpanded = { onToggleExpanded(c.category) },
                    onToggleFile = { onToggleFile(c.category, it) },
                    onSelectAll = { onSelectAll(c.category, it) },
                    onClean = { if (c.isSystemAction) onCleanCategory(c.category) else confirm = c.category },
                )
            }
        }
    }

    when (val target = confirm) {
        null -> Unit
        SAFE_ALL -> {
            val files = state.categories.filter { it.enabled && it.suggestion.risk == RiskLevel.SAFE && !it.isSystemAction }
            ConfirmDeleteDialog(files.sumOf { it.selectedCount }, state.safeReclaimableBytes, onSafeClean) { confirm = null }
        }
        is SuggestionCategory -> {
            val c = state.categories.firstOrNull { it.category == target }
            if (c == null) confirm = null
            else ConfirmDeleteDialog(c.selectedCount, c.selectedBytes, { onCleanCategory(target) }) { confirm = null }
        }
    }
}

private object SAFE_ALL

@Composable
private fun HeroCard(
    state: SmartCleanUiState,
    busy: Boolean,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    onSafeClean: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Espacio seguro para liberar hoy", style = MaterialTheme.typography.titleSmall)
            val scanner = state.scanner
            val big = when (scanner) {
                is SmartScannerStatus.Running -> scanner.reclaimableBytes
                else -> state.safeReclaimableBytes
            }
            Text(formatBytes(big), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            if (state.totalBytes > 0) {
                Text(
                    "Libre ${formatBytes(state.freeBytes)} de ${formatBytes(state.totalBytes)}" +
                        if (state.freedThisSession > 0) " · liberado hoy ${formatBytes(state.freedThisSession)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                scanner is SmartScannerStatus.Running -> {
                    Text(scanner.phase, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    val f = scanner.fraction
                    if (f != null) {
                        LinearProgressIndicator(
                            progress = { f },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                        )
                    } else {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (scanner.totalItems > 0) "${scanner.scannedItemsCount} de ${scanner.totalItems} elementos"
                        else "${scanner.scannedItemsCount} elementos",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (scanner.currentDirectory.isNotEmpty()) {
                        Text(
                            scanner.currentDirectory,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancelar", color = MaterialTheme.colorScheme.onPrimary) }
                }
                state.deleting -> {
                    Text("Limpiando…", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onPrimary)
                }
                scanner is SmartScannerStatus.Idle -> {
                    Button(
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Analizar ahora") }
                }
                else -> {
                    Button(
                        onClick = onSafeClean,
                        enabled = !busy && state.safeReclaimableBytes > 0,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Limpieza Segura", fontWeight = FontWeight.Bold) }
                    if (state.totalReclaimableBytes > state.safeReclaimableBytes) {
                        Text(
                            "Revisando las tarjetas podrías liberar hasta ${formatBytes(state.totalReclaimableBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionNote() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "  Fotos y videos protegidos: nunca se sugieren ni se borran desde aquí.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction, contentPadding = PaddingValues(0.dp)) { Text(action) }
        }
    }
}
