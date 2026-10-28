package bo.app.limpiador.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import bo.app.limpiador.data.model.CategoryUsage
import bo.app.limpiador.data.model.FileCategory
import bo.app.limpiador.data.model.FileItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FileDao {
    @Upsert
    suspend fun upsertAll(items: List<FileItem>)

    @Query("SELECT * FROM files WHERE uri IN (:uris)")
    suspend fun getByUris(uris: List<String>): List<FileItem>

    /** Solo los archivos cuyo tamaño se repite: los únicos que pueden ser duplicados. */
    @Query(
        """SELECT * FROM files WHERE size >= :minSize AND size IN
           (SELECT size FROM files WHERE size >= :minSize GROUP BY size HAVING COUNT(*) > 1)
           ORDER BY size DESC"""
    )
    suspend fun sizeCollisions(minSize: Long): List<FileItem>

    @Query("UPDATE files SET preHash = :preHash WHERE uri = :uri")
    suspend fun setPreHash(uri: String, preHash: String)

    @Query("UPDATE files SET checksum = :checksum WHERE uri = :uri")
    suspend fun setChecksum(uri: String, checksum: String)

    @Query(
        """SELECT * FROM files
           WHERE (CASE WHEN lastAccessed IS NOT NULL AND lastAccessed > lastModified
                       THEN lastAccessed ELSE lastModified END) < :cutoff
             AND (:allCategories OR category IN (:categories))
           ORDER BY size DESC LIMIT :limit"""
    )
    suspend fun staleFiles(
        cutoff: Long,
        allCategories: Boolean,
        categories: List<FileCategory>,
        limit: Int,
    ): List<FileItem>

    @Query(
        """SELECT * FROM files
           WHERE LOWER(name) LIKE '%.tmp' OR LOWER(name) LIKE '%.temp' OR LOWER(name) LIKE '%.log'
              OR LOWER(name) LIKE '%.bak' OR LOWER(name) LIKE '%.old' OR name LIKE '%~'
              OR LOWER(name) LIKE '%.part' OR LOWER(name) LIKE '%.crdownload'
              OR LOWER(path) LIKE '%/.thumbnails/%' OR LOWER(path) LIKE '%/cache/%'
              OR LOWER(path) LIKE '%/.trash%' OR category = 'APKS'
           ORDER BY size DESC LIMIT :limit"""
    )
    suspend fun residualFiles(limit: Int): List<FileItem>

    @Query("SELECT category, SUM(size) AS bytes, COUNT(*) AS count FROM files GROUP BY category ORDER BY bytes DESC")
    fun categoryUsage(): Flow<List<CategoryUsage>>

    @Query("SELECT COUNT(*) FROM files")
    suspend fun count(): Int

    @Query("DELETE FROM files WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    /** Purga lo que no apareció en el último escaneo de esa fuente. */
    @Query("DELETE FROM files WHERE scanStamp < :stamp AND uri LIKE :uriPrefix || '%'")
    suspend fun purgeOlderThan(stamp: Long, uriPrefix: String)

    /** Todo lo que no es foto ni video: el único universo que la limpieza inteligente evalúa. */
    @Query("SELECT * FROM files WHERE category NOT IN ('IMAGES', 'VIDEOS')")
    suspend fun nonVisualFiles(): List<FileItem>

    /** Purga del escaneo selectivo: solo toca entradas no visuales de MediaStore. */
    @Query(
        """DELETE FROM files WHERE scanStamp < :stamp AND uri LIKE 'content://media/%'
           AND category NOT IN ('IMAGES', 'VIDEOS')"""
    )
    suspend fun purgeNonVisualOlderThan(stamp: Long)
}
