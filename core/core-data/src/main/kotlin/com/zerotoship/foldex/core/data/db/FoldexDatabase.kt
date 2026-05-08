package com.zerotoship.foldex.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ConnectionEntity::class, EncryptedCredentialEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FoldexDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun encryptedCredentialDao(): EncryptedCredentialDao

    companion object {
        const val DATABASE_NAME = "foldex.db"
    }
}
