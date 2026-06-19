// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EncryptedCredentialDao {

    @Query("SELECT * FROM encrypted_credentials WHERE id = :id")
    suspend fun findById(id: String): EncryptedCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EncryptedCredentialEntity)

    @Query("DELETE FROM encrypted_credentials WHERE id = :id")
    suspend fun deleteById(id: String)
}
