package com.zerotoship.foldex.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConnectionEntity::class,
        EncryptedCredentialEntity::class,
        ServerConfigEntity::class,
        ServerLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class FoldexDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun encryptedCredentialDao(): EncryptedCredentialDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun serverLogDao(): ServerLogDao

    companion object {
        const val DATABASE_NAME = "foldex.db"
    }
}
