// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.sync.engine

import com.zerotoship.foldex.core.model.SyncFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {

    @Test
    fun `empty filter accepts everything`() {
        val filter = Filter(SyncFilter.EMPTY)
        assertTrue(filter.isEmpty)
        assertTrue(filter.accepts("any/path/file.txt"))
        assertTrue(filter.accepts("photo.jpg", size = 999_999_999L))
    }

    @Test
    fun `empty path is rejected`() {
        assertFalse(Filter(SyncFilter.EMPTY).accepts(""))
        assertFalse(Filter(SyncFilter.EMPTY).accepts("   "))
    }

    @Test
    fun `include-only keeps matching and drops the rest`() {
        val filter = Filter(SyncFilter(includePatterns = listOf("*.jpg", "*.png")))
        assertTrue(filter.accepts("album/a.jpg"))
        assertTrue(filter.accepts("b.png"))
        assertFalse(filter.accepts("notes.txt"))
        assertFalse(filter.accepts("readme.md"))
    }

    @Test
    fun `exclude removes matching even when included`() {
        val filter = Filter(
            SyncFilter(
                includePatterns = listOf("**/*"),
                excludePatterns = listOf("**/.git/**", "*.tmp"),
            ),
        )
        assertTrue(filter.accepts("src/Main.kt"))
        assertFalse(filter.accepts("project/.git/config"))
        assertFalse(filter.accepts("cache/build.tmp"))
    }

    @Test
    fun `exclude takes precedence over include`() {
        val filter = Filter(
            SyncFilter(
                includePatterns = listOf("*.log"),
                excludePatterns = listOf("debug.log"),
            ),
        )
        assertTrue(filter.accepts("app.log"))
        assertFalse(filter.accepts("logs/debug.log"))
    }

    @Test
    fun `max file size is enforced only when size is known`() {
        val filter = Filter(SyncFilter(maxFileSize = 1_000L))
        assertTrue(filter.accepts("small.bin", size = 1_000L))
        assertFalse(filter.accepts("big.bin", size = 1_001L))
        // サイズ不明 (ディレクトリ等) はサイズ判定をスキップして通す
        assertTrue(filter.accepts("unknown.bin", size = null))
    }

    @Test
    fun `leading slash on the path is tolerated`() {
        val filter = Filter(SyncFilter(includePatterns = listOf("docs/*.md")))
        assertTrue(filter.accepts("/docs/readme.md"))
    }
}
