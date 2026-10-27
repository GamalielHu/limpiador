package bo.app.limpiador.data.apps

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class AppInfo(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val versionName: String?,
    /** APK + splits + bibliotecas nativas. */
    val appBytes: Long,
    /** Datos del usuario sin contar la caché; null sin "Acceso de uso". */
    val dataBytes: Long?,
    val cacheBytes: Long?,
    /** Último uso en primer plano (ms); null si nunca o sin "Acceso de uso". */
    val lastUsed: Long?,
    val installedAt: Long,
    val updatedAt: Long,
    val launchable: Boolean,
) {
    val totalBytes: Long get() = appBytes + (dataBytes ?: 0) + (cacheBytes ?: 0)
}

/**
 * Tamaño por aplicación con StorageStatsManager (requiere "Acceso de uso"). Sin ese permiso
 * solo se puede medir el APK leyendo los archivos instalados.
 */
class InstalledAppsReader(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun load(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<AppInfo> = withContext(Dispatchers.IO) {
        val usage = hasUsageAccess()
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val user = Process.myUserHandle()
        val lastUsed = if (usage) lastUsedMap() else emptyMap()

        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0)
        val result = ArrayList<AppInfo>(apps.size)
        apps.forEachIndexed { i, ai ->
            coroutineContext.ensureActive()
            val isSystem = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val launchable = pm.getLaunchIntentForPackage(ai.packageName) != null
            // Componentes del sistema sin ícono de inicio no interesan al usuario.
            if (isSystem && !launchable) {
                onProgress(i + 1, apps.size)
                return@forEachIndexed
            }
            var appBytes: Long
            var dataBytes: Long? = null
            var cacheBytes: Long? = null
            val measured = if (usage) runCatching {
                stats.queryStatsForPackage(ai.storageUuid ?: StorageManager.UUID_DEFAULT, ai.packageName, user)
            }.getOrNull() else null
            if (measured != null) {
                appBytes = measured.appBytes
                cacheBytes = measured.cacheBytes
                dataBytes = (measured.dataBytes - measured.cacheBytes).coerceAtLeast(0)
            } else {
                appBytes = apkSize(ai)
            }
            @Suppress("DEPRECATION")
            val pi = runCatching { pm.getPackageInfo(ai.packageName, 0) }.getOrNull()
            result += AppInfo(
                packageName = ai.packageName,
                label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                isSystem = isSystem,
                versionName = pi?.versionName,
                appBytes = appBytes,
                dataBytes = dataBytes,
                cacheBytes = cacheBytes,
                lastUsed = lastUsed[ai.packageName]?.takeIf { it > 0 },
                installedAt = pi?.firstInstallTime ?: 0,
                updatedAt = pi?.lastUpdateTime ?: 0,
                launchable = launchable,
            )
            onProgress(i + 1, apps.size)
        }
        result.sortedByDescending { it.totalBytes }
    }

    private fun apkSize(ai: ApplicationInfo): Long {
        val files = buildList {
            ai.sourceDir?.let { add(it) }
            ai.splitSourceDirs?.let { addAll(it) }
        }
        return files.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
    }

    private fun lastUsedMap(): Map<String, Long> = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryAndAggregateUsageStats(now - TimeUnit.DAYS.toMillis(730), now)
            .mapValues { it.value.lastTimeUsed }
    }.getOrDefault(emptyMap())
}
