@file:OptIn(ExperimentalMaterial3Api::class)

package bo.app.limpiador.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.domain.usecase.DuplicateGroup
import bo.app.limpiador.ui.ScanState
import bo.app.limpiador.ui.components.ConfirmDeleteDialog
import bo.app.limpiador.ui.components.DeleteBar
import bo.app.limpiador.ui.components.FileRow
import bo.app.limpiador.ui.formatBytes

@Composable
fun DuplicateCleanerScreen(
    groups: List<DuplicateGroup>,
    selection: Set<String>,
    scanState: ScanState,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onKeepOldest: () -> Unit,
    onClearSelection: () -> Unit,
    onToggle: (DuplicateGroup, FileItem) -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val selectedBytes = remember(groups, selection) {
        groups.sumOf { g -> g.files.count { it.uri in selection } * g.size }
    }
    val busy = scanState is ScanState.Scanning || scanState is ScanState.Deleting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicados") },
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
            if (scanState is ScanState.Scanning) {
                Text(
                    "${scanState.phase}… ${scanState.processed}" + if (scanState.total > 0) " de ${scanState.total}" else "",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (scanState is ScanState.Scanning) "Buscando…" else "No se encontraron duplicados",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${groups.size} grupos", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatBytes(groups.sumOf { it.wastedBytes })} recuperables",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onKeepOldest) { Text("Conservar el más antiguo") }
            }
            if (selection.isNotEmpty()) {
                TextButton(onClick = onClearSelection, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Quitar selección")
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { group ->
                    item(key = "h-" + group.checksum) {
                        Text(
                            "${group.files.size} copias · ${formatBytes(group.size)} c/u",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(group.files, key = { group.checksum + it.uri }) { file ->
                        FileRow(
                            file = file,
                            checked = file.uri in selection,
                            onCheckedChange = { onToggle(group, file) },
                            badge = if (file.uri == group.oldest.uri) "Más antiguo" else null,
                        )
                    }
                    item(key = "d-" + group.checksum) { HorizontalDivider(Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(selection.size, selectedBytes, onDelete) { confirm = false }
    }
}
