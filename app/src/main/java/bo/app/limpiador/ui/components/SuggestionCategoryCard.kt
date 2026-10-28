package bo.app.limpiador.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import bo.app.limpiador.domain.model.RiskLevel
import bo.app.limpiador.ui.formatBytes
import bo.app.limpiador.ui.state.CategoryUiState

private const val PREVIEW_LIMIT = 60

private val SafeBg = Color(0xFFD7F5E6)
private val SafeFg = Color(0xFF0E6B45)
private val ReviewBg = Color(0xFFFFEBC7)
private val ReviewFg = Color(0xFF8A5300)

@Composable
fun RiskChip(risk: RiskLevel) {
    val (bg, fg) = if (risk == RiskLevel.SAFE) SafeBg to SafeFg else ReviewBg to ReviewFg
    Text(
        risk.label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ScoreBadge(score: Int) {
    val color = when {
        score >= 80 -> SafeFg
        score >= 60 -> MaterialTheme.colorScheme.primary
        else -> ReviewFg
    }
    Text(
        "$score",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Tarjeta de una categoría de sugerencias: toggle, chip de riesgo, total liberable, casilla de lote
 * y desglose expandible de archivos con su puntuación.
 */
@Composable
fun SuggestionCategoryCard(
    state: CategoryUiState,
    busy: Boolean,
    onToggleEnabled: () -> Unit,
    onToggleExpanded: () -> Unit,
    onToggleFile: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onClean: () -> Unit,
) {
    val s = state.suggestion
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.category.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        RiskChip(s.risk)
                    }
                    Text(
                        s.category.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = { onToggleEnabled() }, enabled = !busy)
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatBytes(if (state.isSystemAction) s.systemReclaimableBytes else state.selectedBytes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (state.isSystemAction) "Lo vacía el sistema; las apps no pierden datos"
                        else "${state.selectedCount} de ${s.files.size} marcados · ${formatBytes(s.reclaimableBytes)} en total",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onClean,
                    enabled = !busy && state.enabled && (state.isSystemAction || state.selectedCount > 0),
                ) { Text("Limpiar") }
            }

            if (!state.isSystemAction) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpanded)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val all = s.files.isNotEmpty() && state.selected.size == s.files.size
                    Checkbox(checked = all, onCheckedChange = { onSelectAll(!all) }, enabled = !busy)
                    Text(
                        if (all) "Todos marcados" else "Marcar todos",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (state.expanded) "Ocultar archivos" else "Ver archivos",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        if (state.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = state.expanded && !state.isSystemAction) {
                Column {
                    HorizontalDivider()
                    s.files.take(PREVIEW_LIMIT).forEach { sf ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { onToggleFile(sf.item.uri) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = sf.item.uri in state.selected,
                                onCheckedChange = { onToggleFile(sf.item.uri) },
                                enabled = !busy,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sf.item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatBytes(sf.item.size)} · ${sf.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    sf.item.path.substringBeforeLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            ScoreBadge(sf.score)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (s.files.size > PREVIEW_LIMIT) {
                        Text(
                            "y ${s.files.size - PREVIEW_LIMIT} archivos más (se incluyen al marcar todos)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmallInfoRow(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
