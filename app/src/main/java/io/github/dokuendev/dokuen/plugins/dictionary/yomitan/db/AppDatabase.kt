package io.github.dokuendev.dokuen.plugins.dictionary.yomitan.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DictionaryEntity::class,
        TermEntity::class,
        TermMetaEntity::class,
        KanjiEntity::class,
        KanjiMetaEntity::class,
        TagMetaEntity::class,
        MediaEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun dictionaryDao(): DictionaryDao
    abstract fun termDao(): TermDao
    abstract fun termMetaDao(): TermMetaDao
    abstract fun kanjiDao(): KanjiDao
    abstract fun kanjiMetaDao(): KanjiMetaDao
    abstract fun tagMetaDao(): TagMetaDao
    abstract fun mediaDao(): MediaDao
    abstract fun dictionaryDeletionDao(): DictionaryDeletionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yomitan_dictionary_db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
