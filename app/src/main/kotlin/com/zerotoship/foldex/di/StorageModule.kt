package com.zerotoship.foldex.di

import android.content.Context
import com.zerotoship.foldex.core.model.StorageProvider
import com.zerotoship.foldex.storage.StorageProviderRouter
import com.zerotoship.foldex.storage.local.LocalStorageProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideLocalStorageProvider(@ApplicationContext context: Context): LocalStorageProvider =
        LocalStorageProvider(context)

    // sync モジュール (SyncWorker) など URI 種別を意識せず使いたい箇所向けの StorageProvider バインド。
    @Provides
    @Singleton
    fun provideStorageProvider(router: StorageProviderRouter): StorageProvider = router
}

// SmbStorageProvider と StorageProviderRouter は @Inject constructor のため Hilt が自動生成。
