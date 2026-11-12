package io.github.nebur692.meshclient.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the Windows app's FilesPathsTests. / Los mismos casos que FilesPathsTests de la app de Windows. */
class RemotePathsTest {
    @Test fun `a name joins its folder`() {
        for ((folder, name, expected) in listOf(
            Triple("", "C:", "C:/"), Triple("", "C:\\", "C:/"), Triple("C:/", "Users", "C:/Users"), Triple("C:/Users", "Ana", "C:/Users/Ana"),
            Triple("/", "etc", "/etc"), Triple("/etc", "ssh", "/etc/ssh"), Triple("/etc/", "ssh", "/etc/ssh"), Triple("/tmp/", "x", "/tmp/x"),
        )) assertEquals("$folder + $name", expected, RemotePaths.join(folder, name))
    }

    @Test fun `the parent of a folder, up to the drives or the root`() {
        for ((path, expected) in listOf(
            "C:/Users/Ana" to "C:/Users", "C:/Users" to "C:/", "C:/" to "", "/etc/ssh" to "/etc", "/etc" to "/", "/" to "/",
        )) assertEquals(path, expected, RemotePaths.parent(path))
    }
}
