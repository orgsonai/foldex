package com.zerotoship.foldex.core.data.di

import android.content.Context
import androidx.room.Room
import com.zerotoship.foldex.core.data.db.ConnectionDao
import com.zerotoship.foldex.core.data.db.EncryptedCredentialDao
import com.zerotoship.foldex.core.data.db.FoldexDatabase
import com.zerotoship.foldex.core.data.db.ServerConfigDao
import com.zerotoship.foldex.core.data.db.ServerLogDao
import com.zerotoship.foldex.core.data.db.SyncJobDao
import com.zerotoship.foldex.core.data.db.SyncStateDao
import com.zerotoship.foldex.core.data.security.CredentialCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FoldexDatabase =
        Room.databaseBuilder(context, FoldexDatabase::class.java, FoldexDatabase.DATABASE_NAME)
            // P6 ではスキーマに server_configs / server_logs を追加する。
            // 現状 0.x なのでスキーマ変更時は破壊的マイグレーションで十分
            // (ユーザデータの永続保証は P8 から)。
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideConnectionDao(database: FoldexDatabase): ConnectionDao = database.connectionDao()

    @Provides
    fun provideEncryptedCredentialDao(database: FoldexDatabase): EncryptedCredentialDao =
        database.encryptedCredentialDao()

    @Provides
    fun provideServerConfigDao(database: FoldexDatabase): ServerConfigDao =
        database.serverConfigDao()

    @Provides
    fun provideServerLogDao(database: FoldexDatabase): ServerLogDao = database.serverLogDao()

    @Provides
    fun provideSyncJobDao(database: FoldexDatabase): SyncJobDao = database.syncJobDao()

    @Provides
    fun provideSyncStateDao(database: FoldexDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    @Singleton
    fun provideCredentialCipher(): CredentialCipher = CredentialCipher()
}
