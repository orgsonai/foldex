// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Zero to Ship

package com.zerotoship.foldex.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `先頭の v は無視される`() {
        assertEquals(0, AppVersion.compare("v1.2.2", "1.2.2"))
        assertEquals(0, AppVersion.compare("V1.2.2", "v1.2.2"))
    }

    @Test
    fun `桁数が違っても数値として同じなら等しい`() {
        assertEquals(0, AppVersion.compare("1.2", "1.2.0"))
        assertEquals(0, AppVersion.compare("1", "1.0.0"))
    }

    @Test
    fun `文字列比較ではなく数値として比べる`() {
        // 文字列のままだと "1.10.0" < "1.9.0" になってしまう。
        assertTrue(AppVersion.compare("1.10.0", "1.9.0") > 0)
        assertTrue(AppVersion.isNewerThan("1.10.0", "1.9.0"))
    }

    @Test
    fun `新しい方が正 古い方が負`() {
        assertTrue(AppVersion.compare("1.3.0", "1.2.2") > 0)
        assertTrue(AppVersion.compare("1.2.2", "1.3.0") < 0)
        assertTrue(AppVersion.compare("2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `接尾辞つきは同じ数値の正式版より古い`() {
        assertTrue(AppVersion.compare("1.3.0-alpha", "1.3.0") < 0)
        assertTrue(AppVersion.compare("1.3.0", "1.3.0-alpha") > 0)
        // 数値が上なら接尾辞つきでも新しい。
        assertTrue(AppVersion.compare("1.4.0-alpha", "1.3.0") > 0)
    }

    @Test
    fun `接尾辞どうしは文字列順で比べる`() {
        assertTrue(AppVersion.compare("1.3.0-beta", "1.3.0-alpha") > 0)
        assertEquals(0, AppVersion.compare("1.3.0-alpha", "1.3.0-alpha"))
    }

    @Test
    fun `同じバージョンなら更新なし`() {
        assertFalse(AppVersion.isNewerThan("1.2.2", "1.2.2"))
        assertFalse(AppVersion.isNewerThan("v1.2.2", "1.2.2"))
    }

    @Test
    fun `壊れた入力でも落ちず更新なし扱いになる`() {
        assertFalse(AppVersion.isNewerThan("", "1.2.2"))
        assertFalse(AppVersion.isNewerThan("なんだこれ", "1.2.2"))
        // 数字として読めない要素は 0 として扱う。
        assertEquals(0, AppVersion.compare("1.x.0", "1.0.0"))
    }

    @Test
    fun `実際に使うタグ形式で動く`() {
        // release yml は v<versionName> でタグを打つ。
        assertTrue(AppVersion.isNewerThan("v1.3.0", "1.2.2"))
        assertFalse(AppVersion.isNewerThan("v1.2.1", "1.2.2"))
    }
}
