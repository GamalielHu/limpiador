@file:OptIn(ExperimentalMaterial3Api::class)

package bo.app.limpiador.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.ui.ScanState
import bo.app.limpiador.ui.StaleFilter
import bo.app.limpiador.ui.components.ConfirmDeleteDialog
import bo.app.limpiador.ui.components.DeleteBar
import bo.app.limpiador.ui.components.FileRow
import bo.app.limpiador.ui.formatBytes

private val dayOptions = listOf(0 to "Todos", 30 to "30 días", 90 to "90 días", 180 to "6 meses", 365 to "1 año")

@Composable
fun StaleFilesScreen(
    files: List<FileItem>,
    selection: Set<String>,
    filter: StaleFilter,
    loading: Boolean,
    scanState: ScanState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onFilterChange: (StaleFilter) -> Unit,
    onToggle: (FileItem) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val selectedBytes = remember(files, selection) { files.filter { it.uri in selection }.sumOf { it.size } }
    val busy = scanState is ScanState.Scanning || scanState is ScanState.Deleting
    val title = when {
        filter.residual -> "Residuales"
        filter.days == 0 && filter.categories.size == 1 -> filter.categories.first().label
        filter.days == 0 -> "Archivos más grandes"
        else -> "Archivos inactivos"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
                },
            )
        },
        bottomBar = { DeleteBar(selection.size, selectedBytes, !busy) { confirm = true } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter.residual,
                    onClick = { onFilterChange(filter.copy(residual = !filter.residual)) },
                    label = { Text("Residuales") },
                )
                dayOptions.forEach { (days, label) ->
                    FilterChip(
                        selected = !filter.residual && filter.days == days,
                        onClick = { onFilterChange(filter.copy(days = days, residual = false)) },
                        label = { Text(label) },
                    )
                }
            }
            if (!filter.residual) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = filter.categories.isEmpty(),
                        onClick = { onFilterChange(filter.copy(categories = emptySet())) },
                        label = { Text("Todas") },
                    )
                    FileCategory.entries.forEach { c ->
                        val on = c in filter.categories
                        FilterChip(
                            selected = on,
                            onClick = {
                                onFilterChange(filter.copy(categories = if (on) filter.categories - c else filter.categories + c))
                            },
                            label = { Text(c.label) },
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val all = files.isNotEmpty() && selection.size == files.size
                Checkbox(checked = all, onCheckedChange = { onSelectAll(!all) }, enabled = files.isNotEmpty())
                Text(
                    "${files.size} archivos · ${formatBytes(files.sumOf { it.size })}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                files.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nada que mostrar con este filtro", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(files, key = { it.uri }) { f ->
                        FileRow(file = f, checked = f.uri in selection, onCheckedChange = { onToggle(f) })
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(selection.size, selectedBytes, onDelete) { confirm = false }
    }
}
