// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncJobDao {

    /**
     * 並び順は手動 (sortOrder) だけで決める。
     *
     * 第 2 キーに updatedAt を使っていた頃は、[updateLastRun] が実行のたびに updatedAt を
     * 更新するため「同期が走ったジョブが勝手に先頭へ飛ぶ」状態だった (sortOrder が同値の
     * ジョブ同士で順位が入れ替わる)。作成後に変わらない createdAt をタイブレークに使い、
     * ユーザーが並べ替えない限り順番が動かないようにする。
     */
    @Query("SELECT * FROM sync_jobs ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<SyncJobEntity>>

    @Query("SELECT * FROM sync_jobs WHERE id = :id")
    suspend fun findById(id: String): SyncJobEntity?

    @Upsert
    suspend fun upsert(entity: SyncJobEntity)

    @Query("DELETE FROM sync_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * 実行結果の記録。updatedAt は触らない (updatedAt は「設定を更新した日時」であり、
     * 実行しただけで変えると並び順や差分判定に紛れ込むため。実行時刻は lastRunAt が持つ)。
     */
    @Query("UPDATE sync_jobs SET lastRunAt = :timestamp, lastRunResult = :result WHERE id = :id")
    suspend fun updateLastRun(id: String, timestamp: Long, result: String)

    @Query("UPDATE sync_jobs SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    /** 新規ジョブを末尾に置くための現在の最大 sortOrder (1 件も無ければ -1)。 */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sync_jobs")
    suspend fun maxSortOrder(): Int
}
