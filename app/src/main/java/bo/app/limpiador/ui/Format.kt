package bo.app.limpiador.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(Locale.getDefault(), if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}
