package ai.rever.boss.search

/**
 * Holds the one Spotlight file index retained by a window between dialog sessions.
 *
 * File contents are derived solely from a project path, unlike a dialog's query, results, category,
 * and selection. Retaining one path-index pair keeps reopening Spotlight warm without retaining an
 * unbounded collection of projects. Replacing the project clears the old index before installing
 * the replacement so a window cannot accidentally expose files from its previous project.
 */
internal class SpotlightFileIndexOwner(
    private val createIndexer: () -> FileIndexer = { FileIndexer() },
) {
    private var projectPath: String? = null
    private var fileIndexer: FileIndexer? = null

    fun indexerFor(nextProjectPath: String): FileIndexer {
        fileIndexer?.takeIf { projectPath == nextProjectPath }?.let { return it }

        fileIndexer?.clearIndex()
        return createIndexer().also {
            projectPath = nextProjectPath
            fileIndexer = it
        }
    }
}
