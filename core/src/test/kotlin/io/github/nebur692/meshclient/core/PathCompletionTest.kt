package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Completing a remote path as it is typed (the same cases as the Windows app). / Completar una ruta mientras se escribe. */
class PathCompletionTest {
    @Test fun `the folder to list and what is typed of the next name`() {
        val cases = listOf(
            Triple("C", true, "" to "C"), Triple("C:/", true, "C:/" to ""), Triple("C:\\Us", true, "C:/" to "Us"),
            Triple("C:/Users/ad", true, "C:/Users" to "ad"), Triple("/", false, "/" to ""), Triple("/et", false, "/" to "et"),
            Triple("/etc/ss", false, "/etc" to "ss"), Triple("/etc/", false, "/etc" to ""), Triple("ho", false, "/" to "ho"),
            Triple("//us", false, "/" to "us"), Triple("C:\\\\Users\\\\a", true, "C:/Users" to "a"),
        )
        for ((typed, windows, expected) in cases) assertEquals(typed, expected, PathCompletion.split(typed, windows))
    }

    @Test fun `folders and drives that start with it, in order, and no files`() {
        fun e(n: String, k: RemoteEntryKind) = RemoteEntry(n, k, 0, null, null)
        val entries = listOf(e("Users", RemoteEntryKind.Folder), e("usr.txt", RemoteEntryKind.File), e("updates", RemoteEntryKind.Folder), e("Windows", RemoteEntryKind.Folder))
        assertEquals(listOf("updates", "Users"), PathCompletion.matches(entries, "u"))
        assertEquals(listOf("C:"), PathCompletion.matches(listOf(e("C:", RemoteEntryKind.Drive), e("D:", RemoteEntryKind.Drive)), "c"))
        assertEquals(emptyList<String>(), PathCompletion.matches(entries, "x"))
    }

    @Test fun `taking a name leaves the path ready for the next level, and typed paths are kept as the app keeps them`() {
        assertEquals("C:/", PathCompletion.accept("", "C:"))
        assertEquals("C:/Users/", PathCompletion.accept("C:/", "Users"))
        assertEquals("/etc/", PathCompletion.accept("/", "etc"))
        assertEquals("/etc/ssh/", PathCompletion.accept("/etc", "ssh"))
        assertEquals("C:/Users", PathCompletion.normalise("C:\\Users\\"))
        assertEquals("C:/", PathCompletion.normalise("C:/"))
        assertEquals("/", PathCompletion.normalise("/"))
    }
}
