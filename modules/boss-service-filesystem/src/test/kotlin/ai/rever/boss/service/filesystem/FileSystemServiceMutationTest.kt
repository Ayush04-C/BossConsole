package ai.rever.boss.service.filesystem

import ai.rever.boss.ipc.proto.services.CreateFileRequest
import ai.rever.boss.ipc.proto.services.DeleteFileRequest
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemServiceMutationTest {
    private val service = FileSystemServiceImpl()
    private val testDirectory =
        File.createTempFile("filesystem-service-mutation-", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }

    @AfterTest
    fun cleanUp() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun `create regular file succeeds`() {
        val file = testDirectory.resolve("new-file.txt")

        createFile(file)

        assertTrue(file.isFile)
    }

    @Test
    fun `create existing regular file is refused`() {
        val file = testDirectory.resolve("existing-file.txt").apply { createNewFile() }

        val error = assertFailsWith<StatusException> { createFile(file) }

        assertEquals(Status.Code.ALREADY_EXISTS, error.status.code)
        assertEquals("File already exists: ${file.absolutePath}", error.status.description)
        assertTrue(file.isFile)
    }

    @Test
    fun `create file at directory path is refused`() {
        val directory = testDirectory.resolve("occupied-by-directory").apply { mkdir() }

        val error = assertFailsWith<StatusException> { createFile(directory) }

        assertEquals(Status.Code.ALREADY_EXISTS, error.status.code)
        assertEquals("File already exists: ${directory.absolutePath}", error.status.description)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun `create with a missing parent preserves the I O exception`() {
        val file = testDirectory.resolve("missing-parent/file.txt")

        assertFailsWith<IOException> { createFile(file) }

        assertFalse(file.exists())
    }

    @Test
    fun `create with a regular-file parent preserves the I O exception`() {
        val parent = testDirectory.resolve("regular-file-parent").apply { createNewFile() }
        val file = parent.resolve("child.txt")

        assertFailsWith<IOException> { createFile(file) }

        assertFalse(file.exists())
    }

    @Test
    fun `delete regular file succeeds`() {
        val file = testDirectory.resolve("delete-me.txt").apply { createNewFile() }

        deleteFile(file, recursive = false)

        assertFalse(file.exists())
    }

    @Test
    fun `delete empty directory succeeds`() {
        val directory = testDirectory.resolve("empty-directory").apply { mkdir() }

        deleteFile(directory, recursive = false)

        assertFalse(directory.exists())
    }

    @Test
    fun `delete non-empty directory without recursion is refused`() {
        val directory = testDirectory.resolve("non-empty-directory").apply { mkdir() }
        val child = directory.resolve("child.txt").apply { createNewFile() }

        val error = assertFailsWith<StatusException> { deleteFile(directory, recursive = false) }

        assertEquals(Status.Code.FAILED_PRECONDITION, error.status.code)
        assertEquals(
            "Cannot delete non-empty directory without recursive=true: ${directory.absolutePath}",
            error.status.description,
        )
        assertTrue(directory.isDirectory)
        assertTrue(child.isFile)
    }

    @Test
    fun `delete missing target remains successful`() {
        val missingFile = testDirectory.resolve("missing.txt")

        deleteFile(missingFile, recursive = false)

        assertFalse(missingFile.exists())
    }

    @Test
    fun `recursive delete of a missing target remains successful`() {
        val missingFile = testDirectory.resolve("missing-recursively.txt")

        deleteFile(missingFile, recursive = true)

        assertFalse(missingFile.exists())
    }

    @Test
    fun `recursive delete succeeds`() {
        val directory = testDirectory.resolve("recursive-directory").apply { mkdir() }
        val nestedDirectory = directory.resolve("nested").apply { mkdir() }
        nestedDirectory.resolve("child.txt").createNewFile()

        deleteFile(directory, recursive = true)

        assertFalse(directory.exists())
    }

    private fun createFile(file: File) {
        runBlocking {
            service.createFile(
                CreateFileRequest
                    .newBuilder()
                    .setPath(file.absolutePath)
                    .setIsDirectory(false)
                    .build(),
            )
        }
    }

    private fun deleteFile(
        file: File,
        recursive: Boolean,
    ) {
        runBlocking {
            service.deleteFile(
                DeleteFileRequest
                    .newBuilder()
                    .setPath(file.absolutePath)
                    .setRecursive(recursive)
                    .build(),
            )
        }
    }
}
