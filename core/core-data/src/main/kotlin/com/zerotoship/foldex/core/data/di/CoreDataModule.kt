package com.zerotoship.foldex.core.data.di

import android.content.Context
import androidx.room.Room
import com.zerotoship.foldex.core.data.db.ConnectionDao
import com.zerotoship.foldex.core.data.db.EncryptedCredentialDao
import com.zerotoship.foldex.core.data.db.FoldexDatabase
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
        Room.databaseBuilder(context, FoldexDatabase::class.java, FoldexDatabase.DATABASE_NAME).build()

    @Provides
    fun provideConnectionDao(database: FoldexDatabase): ConnectionDao = database.connectionDao()

    @Provides
    fun provideEncryptedCredentialDao(database: FoldexDatabase): EncryptedCredentialDao =
        database.encryptedCredentialDao()

    @Provides
    @Singleton
    fun provideCredentialCipher(): CredentialCipher = CredentialCipher()
}
