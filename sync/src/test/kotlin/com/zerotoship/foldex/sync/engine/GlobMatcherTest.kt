package com.zerotoship.foldex.sync.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobMatcherTest {

    private fun matches(glob: String, path: String): Boolean =
        GlobMatcher.compileOrNull(glob)!!.matches(path)

    @Test
    fun `basename pattern matches at any depth`() {
        assertTrue(matches("*.jpg", "photo.jpg"))
        assertTrue(matches("*.jpg", "album/photo.jpg"))
        assertTrue(matches("*.jpg", "a/b/c/photo.jpg"))
        assertFalse(matches("*.jpg", "photo.jpeg"))
        assertFalse(matches("*.jpg", "photo.jpg.bak"))
    }

    @Test
    fun `single star does not cross separators`() {
        assertTrue(matches("docs/*.md", "docs/readme.md"))
        assertFalse(matches("docs/*.md", "docs/sub/readme.md"))
    }

    @Test
    fun `question mark matches exactly one non-separator char`() {
        assertTrue(matches("file?.txt", "file1.txt"))
        assertFalse(matches("file?.txt", "file12.txt"))
        assertFalse(matches("a?b", "a/b"))
    }

    @Test
    fun `double star crosses separators`() {
        assertTrue(matches("**/.git/**", ".git/config"))
        assertTrue(matches("**/.git/**", "project/.git/config"))
        assertTrue(matches("**/.git/**", "a/b/.git/hooks/pre-commit"))
        assertFalse(matches("**/.git/**", "project/.gitignore"))
    }

    @Test
    fun `leading double-star slash is optional prefix`() {
        assertTrue(matches("**/build", "build"))
        assertTrue(matches("**/build", "module/build"))
        assertFalse(matches("**/build", "module/build/output"))
    }

    @Test
    fun `anchored pattern with slash is rooted`() {
        assertTrue(matches("src/main", "src/main"))
        assertFalse(matches("src/main", "module/src/main"))
    }

    @Test
    fun `leading slash is stripped`() {
        assertTrue(matches("/src/main", "src/main"))
    }

    @Test
    fun `character classes`() {
        assertTrue(matches("img[0-9].png", "img3.png"))
        assertFalse(matches("img[0-9].png", "imgX.png"))
        assertTrue(matches("file[!0-9].txt", "fileA.txt"))
        assertFalse(matches("file[!0-9].txt", "file5.txt"))
    }

    @Test
    fun `regex metacharacters are treated literally`() {
        assertTrue(matches("report (final).pdf", "report (final).pdf"))
        assertTrue(matches("a.b+c", "a.b+c"))
        assertFalse(matches("a.b+c", "aXbc"))
    }

    @Test
    fun `trailing slash is ignored`() {
        assertTrue(matches("node_modules/", "node_modules"))
    }

    @Test
    fun `blank pattern compiles to null`() {
        assertNull(GlobMatcher.compileOrNull(""))
        assertNull(GlobMatcher.compileOrNull("   "))
        assertNull(GlobMatcher.compileOrNull("/"))
    }
}
