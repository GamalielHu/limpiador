package bo.app.limpiador.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import bo.app.limpiador.data.model.FileItem

@Database(entities = [FileItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun files(): FileDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "indice.db")
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
