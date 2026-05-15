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
    fun provideDatabase(@ApplicationContext context: Context): FoldexDatabase {
        // P6 ではスキーマに server_configs / server_logs を追加する。
        // 現状 0.x なのでスキーマ変更時は破壊的マイグレーションで十分
        // (ユーザデータの永続保証は P8 から)。
        fun build() = Room.databaseBuilder(context, FoldexDatabase::class.java, FoldexDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

        val db = build()
        // Room.databaseBuilder().build() は遅延オープンで、最初の DAO 呼び出しまで
        // マイグレーション失敗 (`A migration from N to M was required but not found`) が
        // 顕在化しない。fallbackToDestructiveMigration(dropAllTables = true) を入れていても
        // 一部端末 / インクリメンタルビルド汚染で発火しないケースがあり、起動直後にクラッシュする。
        // ここで openHelper.writableDatabase を叩いて強制オープンし、失敗したら DB ファイルを
        // 物理削除して作り直す (UI が触る前にリカバリするため、データ消失は起動失敗より望ましい)。
        return runCatching { db.openHelper.writableDatabase; db }.getOrElse {
            runCatching { db.close() }
            context.deleteDatabase(FoldexDatabase.DATABASE_NAME)
            build()
        }
    }

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
