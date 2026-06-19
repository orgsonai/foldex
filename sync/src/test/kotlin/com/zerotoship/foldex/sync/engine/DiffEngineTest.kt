// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.core.model.SyncState
import com.zerotoship.foldex.sync.model.ConflictResolution
import com.zerotoship.foldex.sync.model.SkipReason
import com.zerotoship.foldex.sync.model.SyncAction
import com.zerotoship.foldex.sync.model.SyncEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffEngineTest {

    private val engine = DiffEngine(now = { 0L })

    private fun e(path: String, size: Long = 10L, mtime: Long = 100L) = SyncEntry(path, size, mtime)

    private fun state(
        path: String,
        localSize: Long? = null,
        localMtime: Long? = null,
        remoteSize: Long? = null,
        remoteMtime: Long? = null,
    ) = SyncState("job", path, localSize, localMtime, remoteSize, remoteMtime, lastSyncedAt = 0L)

    private fun diff(
        direction: SyncDirection = SyncDirection.TO_REMOTE,
        policy: ConflictPolicy = ConflictPolicy.NEWER_WINS,
        deleteEnabled: Boolean = false,
        local: Map<String, SyncEntry> = emptyMap(),
        remote: Map<String, SyncEntry> = emptyMap(),
        previous: Map<String, SyncState> = emptyMap(),
    ) = engine.computeActions(DiffEngine.Input(direction, policy, deleteEnabled, local, remote, previous))

    @Test
    fun `initial sync uploads everything local`() {
        val actions = diff(local = mapOf("a.txt" to e("a.txt"), "dir/b.txt" to e("dir/b.txt")))
        assertEquals(
            listOf(SyncAction.Upload("a.txt", 10L, 100L), SyncAction.Upload("dir/b.txt", 10L, 100L)),
            actions,
        )
    }

    @Test
    fun `unchanged file since last sync is skipped`() {
        val actions = diff(
            local = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            remote = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            previous = mapOf("a.txt" to state("a.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.Skip("a.txt", SkipReason.UNCHANGED)), actions)
    }

    @Test
    fun `local change is uploaded`() {
        val actions = diff(
            local = mapOf("a.txt" to e("a.txt", 20L, 200L)),
            remote = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            previous = mapOf("a.txt" to state("a.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.Upload("a.txt", 20L, 200L)), actions)
    }

    @Test
    fun `identical content on both sides without state adopts as in-sync`() {
        val actions = diff(
            local = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            remote = mapOf("a.txt" to e("a.txt", 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.Skip("a.txt", SkipReason.UNCHANGED)), actions)
    }

    @Test
    fun `differing content on both sides without state is a conflict`() {
        val actions = diff(
            local = mapOf("a.txt" to e("a.txt", 20L, 300L)),
            remote = mapOf("a.txt" to e("a.txt", 10L, 100L)),
        )
        assertEquals(1, actions.size)
        val a = actions.single()
        assertTrue(a is SyncAction.Conflict)
        a as SyncAction.Conflict
        assertEquals(ConflictResolution.TakeLocal, a.resolution) // local is newer
    }

    @Test
    fun `remote drift with local unchanged is a conflict and skipped under remote-wins`() {
        val actions = diff(
            policy = ConflictPolicy.REMOTE_WINS,
            local = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            remote = mapOf("a.txt" to e("a.txt", 99L, 999L)),
            previous = mapOf("a.txt" to state("a.txt", 10L, 100L, 10L, 100L)),
        )
        // TO_REMOTE で REMOTE_WINS は逆向き転送になるためスキップ
        assertEquals(listOf(SyncAction.Skip("a.txt", SkipReason.CONFLICT_SKIPPED)), actions)
    }

    @Test
    fun `remote-only file is skipped on upload sync`() {
        val actions = diff(remote = mapOf("only-remote.txt" to e("only-remote.txt")))
        assertEquals(listOf(SyncAction.Skip("only-remote.txt", SkipReason.REMOTE_ONLY)), actions)
    }

    @Test
    fun `local deletion deletes remote when delete enabled`() {
        val actions = diff(
            deleteEnabled = true,
            remote = mapOf("gone.txt" to e("gone.txt")),
            previous = mapOf("gone.txt" to state("gone.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.DeleteRemote("gone.txt")), actions)
    }

    @Test
    fun `local deletion is skipped when delete disabled`() {
        val actions = diff(
            deleteEnabled = false,
            remote = mapOf("gone.txt" to e("gone.txt")),
            previous = mapOf("gone.txt" to state("gone.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.Skip("gone.txt", SkipReason.DELETE_DISABLED)), actions)
    }

    @Test
    fun `remote-deleted file is re-uploaded`() {
        val actions = diff(
            local = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            previous = mapOf("a.txt" to state("a.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(listOf(SyncAction.Upload("a.txt", 10L, 100L)), actions)
    }

    @Test
    fun `download direction mirrors the logic`() {
        val actions = diff(
            direction = SyncDirection.TO_LOCAL,
            deleteEnabled = true,
            local = mapOf("stale.txt" to e("stale.txt")),
            remote = mapOf("new.txt" to e("new.txt")),
            previous = mapOf("stale.txt" to state("stale.txt", 10L, 100L, 10L, 100L)),
        )
        assertEquals(
            listOf(
                SyncAction.Download("new.txt", 10L, 100L),
                SyncAction.DeleteLocal("stale.txt"),
            ),
            actions,
        )
    }

    @Test
    fun `bidirectional with both sides matching snapshot is skipped even if cross-storage mtimes differ`() {
        // SMB 等はアップロード時にリモート側 mtime を付け直すため local.mtime != remote.mtime に
        // なりうる。両側ともスナップショットと一致していれば「同期済み」で再転送してはいけない。
        val actions = diff(
            direction = SyncDirection.BIDIRECTIONAL,
            local = mapOf("a.txt" to e("a.txt", 10L, 100L)),
            remote = mapOf("a.txt" to e("a.txt", 10L, 555L)), // mtime だけ食い違う
            previous = mapOf("a.txt" to state("a.txt", 10L, 100L, 10L, 555L)),
        )
        assertEquals(listOf(SyncAction.Skip("a.txt", SkipReason.UNCHANGED)), actions)
    }

    @Test
    fun `keep both produces a conflict action with a rename plan`() {
        val actions = diff(
            policy = ConflictPolicy.KEEP_BOTH,
            local = mapOf("a.txt" to e("a.txt", 20L, 50L)),  // local older, but KEEP_BOTH ignores mtime
            remote = mapOf("a.txt" to e("a.txt", 10L, 200L)),
            previous = mapOf("a.txt" to state("a.txt", 5L, 5L, 5L, 5L)),
        )
        val a = actions.single()
        assertTrue(a is SyncAction.Conflict)
        a as SyncAction.Conflict
        assertTrue(a.resolution is ConflictResolution.KeepBoth)
    }
}
