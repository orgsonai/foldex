package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.ConflictPolicy
import com.zerotoship.foldex.core.model.SyncDirection
import com.zerotoship.foldex.sync.model.ConflictResolution
import com.zerotoship.foldex.sync.model.ConflictSide
import com.zerotoship.foldex.sync.model.SyncEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictResolverTest {

    private fun entry(path: String, mtime: Long) = SyncEntry(path, size = 10L, mtimeSeconds = mtime)

    @Test
    fun `newer wins picks the side with the later mtime`() {
        val local = entry("a.txt", mtime = 200)
        val remote = entry("a.txt", mtime = 100)
        assertEquals(
            ConflictResolution.TakeLocal,
            ConflictResolver.resolve("a.txt", local, remote, ConflictPolicy.NEWER_WINS, SyncDirection.TO_REMOTE),
        )
        assertEquals(
            ConflictResolution.TakeRemote,
            ConflictResolver.resolve("a.txt", entry("a.txt", 100), entry("a.txt", 300), ConflictPolicy.NEWER_WINS, SyncDirection.TO_REMOTE),
        )
    }

    @Test
    fun `newer wins tie breaks toward the sync source`() {
        val same = 100L
        assertEquals(
            ConflictResolution.TakeLocal,
            ConflictResolver.resolve("a", entry("a", same), entry("a", same), ConflictPolicy.NEWER_WINS, SyncDirection.TO_REMOTE),
        )
        assertEquals(
            ConflictResolution.TakeRemote,
            ConflictResolver.resolve("a", entry("a", same), entry("a", same), ConflictPolicy.NEWER_WINS, SyncDirection.TO_LOCAL),
        )
    }

    @Test
    fun `local wins and remote wins are direction-independent`() {
        val l = entry("a", 1)
        val r = entry("a", 2)
        assertEquals(ConflictResolution.TakeLocal, ConflictResolver.resolve("a", l, r, ConflictPolicy.LOCAL_WINS, SyncDirection.TO_LOCAL))
        assertEquals(ConflictResolution.TakeRemote, ConflictResolver.resolve("a", l, r, ConflictPolicy.REMOTE_WINS, SyncDirection.TO_REMOTE))
    }

    @Test
    fun `skip policy returns Skip`() {
        assertEquals(
            ConflictResolution.Skip,
            ConflictResolver.resolve("a", entry("a", 1), entry("a", 2), ConflictPolicy.SKIP, SyncDirection.TO_REMOTE),
        )
    }

    @Test
    fun `keep both renames the destination side`() {
        val toRemote = ConflictResolver.resolve("dir/a.txt", entry("dir/a.txt", 1), entry("dir/a.txt", 2), ConflictPolicy.KEEP_BOTH, SyncDirection.TO_REMOTE)
        require(toRemote is ConflictResolution.KeepBoth)
        assertEquals(ConflictSide.REMOTE, toRemote.renameSide)
        assertTrue(toRemote.renamedPath.startsWith("dir/a (conflict "))
        assertTrue(toRemote.renamedPath.endsWith(").txt"))

        val toLocal = ConflictResolver.resolve("a.txt", entry("a.txt", 1), entry("a.txt", 2), ConflictPolicy.KEEP_BOTH, SyncDirection.TO_LOCAL)
        require(toLocal is ConflictResolution.KeepBoth)
        assertEquals(ConflictSide.LOCAL, toLocal.renameSide)
    }

    @Test
    fun `conflict renamed path keeps directory and extension`() {
        assertEquals("dir/sub/photo (conflict X).jpg", conflictRenamedPath("dir/sub/photo.jpg", "(conflict X)"))
        assertEquals("notes (conflict X)", conflictRenamedPath("notes", "(conflict X)"))
        assertEquals("archive.tar (conflict X).gz", conflictRenamedPath("archive.tar.gz", "(conflict X)"))
        // 先頭ドット (拡張子なし扱い)
        assertEquals(".gitignore (conflict X)", conflictRenamedPath(".gitignore", "(conflict X)"))
    }
}
