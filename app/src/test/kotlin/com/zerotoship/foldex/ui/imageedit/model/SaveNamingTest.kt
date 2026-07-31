// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.ui.imageedit.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SaveNamingTest {

    @Test
    fun `別名は元の名前に _edited を足す`() {
        assertEquals("photo_edited.jpg", SaveNaming.editedName("photo.jpg", ImageFormat.JPEG))
    }

    @Test
    fun `形式を変えたら拡張子も変わる`() {
        assertEquals("photo_edited.png", SaveNaming.editedName("photo.jpg", ImageFormat.PNG))
        assertEquals("photo_edited.webp", SaveNaming.editedName("photo.JPEG", ImageFormat.WEBP))
    }

    @Test
    fun `編集済みの画像をさらに編集しても _edited が重ならない`() {
        assertEquals("photo_edited.jpg", SaveNaming.editedName("photo_edited.jpg", ImageFormat.JPEG))
    }

    @Test
    fun `拡張子が無い名前でも扱える`() {
        assertEquals("photo_edited.jpg", SaveNaming.editedName("photo", ImageFormat.JPEG))
    }

    @Test
    fun `上書き用の名前は元の名前のまま拡張子だけ合わせる`() {
        assertEquals("photo.jpg", SaveNaming.sameNameWithFormat("photo.jpg", ImageFormat.JPEG))
        assertEquals("photo.png", SaveNaming.sameNameWithFormat("photo.jpg", ImageFormat.PNG))
    }

    @Test
    fun `名前が衝突したら番号を足す`() {
        val taken = setOf("photo_edited.jpg", "photo_edited2.jpg")
        assertEquals(
            "photo_edited3.jpg",
            SaveNaming.uniqueName("photo_edited.jpg") { it in taken },
        )
    }

    @Test
    fun `衝突しなければそのまま使う`() {
        assertEquals("photo_edited.jpg", SaveNaming.uniqueName("photo_edited.jpg") { false })
    }

    @Test
    fun `拡張子から形式を推測する`() {
        assertEquals(ImageFormat.PNG, ImageFormat.fromFileName("a.png"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromFileName("a.WEBP"))
        assertEquals(ImageFormat.JPEG, ImageFormat.fromFileName("a.jpg"))
        // 未知の拡張子は写真を想定して JPEG
        assertEquals(ImageFormat.JPEG, ImageFormat.fromFileName("a.bmp"))
    }
}
