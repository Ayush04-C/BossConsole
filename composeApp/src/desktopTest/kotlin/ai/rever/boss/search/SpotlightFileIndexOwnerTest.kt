package ai.rever.boss.search

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SpotlightFileIndexOwnerTest {
    @Test
    fun `same-project reopen reuses its completed index while project replacement evicts it`() =
        runBlocking {
            val owner =
                SpotlightFileIndexOwner {
                    FileIndexer { projectPath ->
                        listOf(
                            IndexedFile(
                                name = "file.kt",
                                path = "$projectPath/file.kt",
                                relativePath = "file.kt",
                            ),
                        )
                    }
                }

            val firstProject = owner.indexerFor("/projects/first")
            firstProject.indexProject("/projects/first")

            assertSame(firstProject, owner.indexerFor("/projects/first"))
            assertEquals(listOf("/projects/first/file.kt"), firstProject.indexedFiles.value.map { it.path })

            val replacement = owner.indexerFor("/projects/replacement")

            assertNotSame(firstProject, replacement)
            assertEquals(emptyList(), firstProject.indexedFiles.value)
            replacement.indexProject("/projects/replacement")
            assertEquals(listOf("/projects/replacement/file.kt"), replacement.indexedFiles.value.map { it.path })
        }
}
