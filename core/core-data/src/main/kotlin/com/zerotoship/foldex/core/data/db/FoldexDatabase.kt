package com.zerotoship.foldex.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConnectionEntity::class,
        EncryptedCredentialEntity::class,
        ServerConfigEntity::class,
        ServerLogEntity::class,
        SyncJobEntity::class,
        SyncStateEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class FoldexDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun encryptedCredentialDao(): EncryptedCredentialDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun serverLogDao(): ServerLogDao
    abstract fun syncJobDao(): SyncJobDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        const val DATABASE_NAME = "foldex.db"
    }
}
