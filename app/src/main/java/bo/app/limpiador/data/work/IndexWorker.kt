package bo.app.limpiador.data.work

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bo.app.limpiador.data.db.AppDatabase
import bo.app.limpiador.data.scanner.FileHasher
import bo.app.limpiador.data.scanner.StorageScanner
import bo.app.limpiador.domain.usecase.FindDuplicatesUseCase
import java.util.concurrent.TimeUnit

/**
 * Refresca el índice y precalcula los hashes mientras el teléfono carga, para que el análisis
 * manual encuentre la caché lista y termine en segundos.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).files()
        return runCatching {
            StorageScanner(applicationContext, dao).scanMediaStore().collect { p ->
                setProgress(workDataOf("processed" to p.processed, "total" to p.total))
            }
            FindDuplicatesUseCase(dao, FileHasher(applicationContext.contentResolver))()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val NAME = "indice-periodico"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) setRequiresDeviceIdle(true) }
                .build()
            val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
