package bo.app.limpiador.data.model

import android.net.Uri
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Metadatos de un archivo indexado. La clave es la Uri (MediaStore o SAF), así el mismo
 * archivo encontrado por las dos vías no se duplica en el índice.
 *
 * [preHash] y [checksum] se guardan como caché: solo son válidos mientras [size] y
 * [lastModified] no cambien (el escáner los borra si cambian).
 */
@Entity(
    tableName = "files",
    indices = [Index("size"), Index("lastModified"), Index("category"), Index("checksum")],
)
data class FileItem(
    @PrimaryKey val uri: String,
    val id: Long,
    val path: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    /** Milisegundos epoch. */
    val lastModified: Long,
    /** Milisegundos epoch; null si el sistema no lo informa (sin acceso por ruta o noatime). */
    val lastAccessed: Long?,
    /** SHA-256 completo (hex), calculado solo cuando hubo colisión de tamaño y pre-hash. */
    val checksum: String?,
    /** SHA-256 de tamaño + primeros 64 KB + últimos 64 KB. */
    @ColumnInfo(defaultValue = "NULL") val preHash: String? = null,
    val category: FileCategory = FileCategory.fromMime(mimeType, name),
    /** Marca del escaneo que lo vio por última vez; sirve para purgar los que ya no existen. */
    val scanStamp: Long = 0,
) {
    val contentUri: Uri get() = Uri.parse(uri)

    /** Momento de último uso conocido: el más reciente entre modificación y acceso. */
    val lastUsed: Long get() = maxOf(lastModified, lastAccessed ?: 0L)
}

enum class FileCategory(val label: String) {
    IMAGES("Imágenes"),
    VIDEOS("Videos"),
    AUDIO("Audio"),
    DOCUMENTS("Documentos"),
    APKS("Instaladores APK"),
    ARCHIVES("Comprimidos"),
    OTHER("Otros");

    companion object {
        private val docExt = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "odt", "ods", "csv", "rtf")
        private val zipExt = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")

        fun fromMime(mime: String?, name: String): FileCategory {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime?.startsWith("image/") == true -> IMAGES
                mime?.startsWith("video/") == true -> VIDEOS
                mime?.startsWith("audio/") == true -> AUDIO
                ext == "apk" || mime == "application/vnd.android.package-archive" -> APKS
                ext in zipExt -> ARCHIVES
                ext in docExt || mime?.startsWith("text/") == true || mime == "application/pdf" -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

/** Suma de bytes y cantidad por categoría (resultado de consulta agregada). */
data class CategoryUsage(val category: FileCategory, val bytes: Long, val count: Int)
