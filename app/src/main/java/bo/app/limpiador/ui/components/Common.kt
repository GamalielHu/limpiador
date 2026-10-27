package bo.app.limpiador.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.data.model.FileItem
import bo.app.limpiador.ui.formatBytes
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

fun formatDate(ms: Long): String = if (ms <= 0) "—" else dateFormat.format(Date(ms))

/** Fila de archivo: casilla, miniatura (imágenes y videos) y datos. Tocar el texto abre el archivo. */
@Composable
fun FileRow(
    file: FileItem,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    badge: String? = null,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        Thumbnail(file)
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable { openFile(context, file) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                file.path.substringBeforeLast('/').ifEmpty { file.path },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatBytes(file.size)} · modificado ${formatDate(file.lastModified)}" +
                    (file.lastAccessed?.takeIf { it > file.lastModified }?.let { " · abierto ${formatDate(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Thumbnail(file: FileItem) {
    val shape = RoundedCornerShape(6.dp)
    val visual = file.category == FileCategory.IMAGES || file.category == FileCategory.VIDEOS
    Box(
        Modifier
            .size(48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (visual) {
            AsyncImage(
                model = file.contentUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Text(
                file.name.substringAfterLast('.', "?").take(4).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openFile(context: Context, file: FileItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.contentUri, file.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay app para abrir este archivo", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "Sin permiso para abrir este archivo", Toast.LENGTH_SHORT).show()
    }
}

/** Barra inferior con el total seleccionado y el botón de eliminar. */
@Composable
fun DeleteBar(count: Int, bytes: Long, enabled: Boolean, onDelete: () -> Unit) {
    androidx.compose.material3.Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("$count seleccionados", style = MaterialTheme.typography.bodyMedium)
                Text(formatBytes(bytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.Button(
                onClick = onDelete,
                enabled = enabled && count > 0,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Eliminar") }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(count: Int, bytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Eliminar $count archivos?") },
        text = { Text("Se liberarán ${formatBytes(bytes)}. Esta acción no se puede deshacer.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onDismiss(); onConfirm() }) { Text("Eliminar") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
