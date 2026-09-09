package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManagerInstallOutcomeTest {
    private lateinit var settingsDir: File
    private lateinit var manager: UpdateManager
    private var dismissedBefore: String? = null
    private var installOutcome = InstallOutcome(succeeded = false)
    private var installedPath: String? = null
    private var duringInstall: () -> Unit = {}

    @BeforeTest
    fun setUp() {
        settingsDir = createTempDirectory("update-settings-").toFile()
        UpdateSettingsFiles.settingsFileOverride = File(settingsDir, "update-settings.json")
        dismissedBefore = UpdateSettings.lastDismissedVersion
        UpdateSettings.lastDismissedVersion = null
        manager =
            UpdateManager(
                UpdateInstallOperation { downloadPath ->
                    installedPath = downloadPath
                    duringInstall()
                    installOutcome
                },
            )
    }

    @AfterTest
    fun tearDown() {
        manager.shutdown()
        UpdateSettings.lastDismissedVersion = dismissedBefore
        UpdateSettingsFiles.settingsFileOverride = null
        settingsDir.deleteRecursively()
    }

    @Test
    fun `unsupported OS refusal dismisses only that version and keeps the error`() =
        runBlocking {
            val update = update("9.5.9")
            val message = "This update requires macOS 13.0 or later"
            val downloadPath = "/updates/BOSS-9.5.9.dmg"
            installOutcome =
                InstallOutcome(
                    succeeded = false,
                    errorMessage = message,
                    failureReason = InstallFailureReason.UnsupportedOs,
                )
            manager.stageDownloadedUpdate(update, downloadPath)

            assertFalse(manager.installUpdate(downloadPath))

            assertEquals(downloadPath, installedPath)
            assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            assertTrue(File(settingsDir, "update-settings.json").readText().contains("9.5.9"))
            val state = assertIs<UpdateState.Error>(manager.updateState.value)
            assertEquals(message, state.message)
        }

    @Test
    fun `generic install failure remains visible without dismissing the version`() =
        runBlocking {
            val update = update("9.5.9")
            val downloadPath = "/updates/BOSS-9.5.9.dmg"
            installOutcome = InstallOutcome(succeeded = false, errorMessage = "Could not mount update")
            manager.stageDownloadedUpdate(update, downloadPath)

            assertFalse(manager.installUpdate(downloadPath))

            assertNull(UpdateSettings.lastDismissedVersion)
            assertEquals(downloadPath, installedPath)
            assertEquals("Could not mount update", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    @Test
    fun `automatic checks suppress a refused version while forced checks and newer releases remain eligible`() =
        runBlocking {
            val refused = update("9.5.9")
            var available = refused
            installOutcome =
                InstallOutcome(
                    succeeded = false,
                    errorMessage = "This update requires macOS 13.0 or later",
                    failureReason = InstallFailureReason.UnsupportedOs,
                )
            manager.shutdown()
            manager =
                UpdateManager(
                    UpdateInstallOperation { installOutcome },
                    checkOperation = { available },
                )
            manager.stageDownloadedUpdate(refused, "/tmp/BOSS-9.5.9.dmg")

            assertFalse(manager.installUpdate("/tmp/BOSS-9.5.9.dmg"))
            assertEquals(UpdateResult.NoUpdateAvailable, manager.checkForUpdates())
            assertIs<UpdateResult.UpdateAvailable>(manager.checkForUpdates(force = true))

            available = update("9.5.10")
            val newer = assertIs<UpdateResult.UpdateAvailable>(manager.checkForUpdates())
            assertEquals("9.5.10", newer.updateInfo.latestVersion.toString())
        }

    @Test
    fun `unsupported OS cleanup removes only the artifact claimed by the refused install`() =
        runBlocking {
            val staging = createRestrictedDir(defaultStagingDir())
            val refused = File(staging, "BOSS-refused-${System.nanoTime()}.dmg").apply { writeText("refused") }
            val newer = File(staging, "BOSS-newer-${System.nanoTime()}.dmg").apply { writeText("newer") }
            val unrelated = File(staging, "unrelated-${System.nanoTime()}.dmg").apply { writeText("keep") }
            try {
                val refusedInfo = update("9.5.9")
                installOutcome =
                    InstallOutcome(
                        succeeded = false,
                        errorMessage = "This update requires macOS 13.0 or later",
                        failureReason = InstallFailureReason.UnsupportedOs,
                    )
                manager.shutdown()
                manager =
                    UpdateManager(
                        UpdateInstallOperation {
                            installOutcome
                        },
                    )
                manager.stageDownloadedUpdate(refusedInfo, refused.absolutePath)

                assertFalse(manager.installUpdate(refused.absolutePath))
                assertFalse(refused.exists())
                assertTrue(newer.exists(), "cleanup must not delete a newer staged artifact")
                assertTrue(unrelated.exists(), "cleanup must not delete an unrelated staging file")
            } finally {
                refused.delete()
                newer.delete()
                unrelated.delete()
            }
        }

    @Test
    fun `refusal remembers the claimed version even when another download finishes`() =
        runBlocking {
            installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
            manager.stageDownloadedUpdate(update("9.5.9"), "old.dmg")
            duringInstall = { manager.stageDownloadedUpdate(update("9.5.10"), "new.dmg") }

            assertFalse(manager.installUpdate("old.dmg"))

            assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            assertEquals("new.dmg", assertIs<UpdateState.ReadyToInstall>(manager.updateState.value).downloadPath)
        }

    @Test
    fun `stale install action cannot claim a different staged artifact`() =
        runBlocking {
            manager.stageDownloadedUpdate(update("9.5.10"), "new.dmg")

            assertFalse(manager.installUpdate("old.dmg"))

            assertNull(installedPath)
            assertNull(UpdateSettings.lastDismissedVersion)
            assertEquals("new.dmg", assertIs<UpdateState.ReadyToInstall>(manager.updateState.value).downloadPath)
        }

    @Test
    fun `refused downgrade preserves the newer release dismissal`() =
        runBlocking {
            UpdateSettings.lastDismissedVersion = "9.5.9"
            installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
            manager.stageDownloadedUpdate(update("9.5.7"), "downgrade.dmg")

            assertFalse(manager.installUpdate("downgrade.dmg"))

            assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            assertEquals("Unsupported macOS", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    @Test
    fun `cancellation during persistence keeps the installer refusal visible`() =
        runBlocking {
            installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
            manager.stageDownloadedUpdate(update("9.5.9"), "update.dmg")
            val installJob = launch(start = CoroutineStart.LAZY) { manager.installUpdate("update.dmg") }
            duringInstall = { installJob.cancel() }

            installJob.start()
            installJob.join()

            assertTrue(installJob.isCancelled)
            assertEquals("Unsupported macOS", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    @Test
    fun `cleanup failure preserves the OS refusal and does not remove a directory tree`() =
        runBlocking {
            val staging = createRestrictedDir(defaultStagingDir())
            val artifact = createTempDirectory(staging.toPath(), "refused-directory-").toFile()
            val child = File(artifact, "keep.txt").apply { writeText("keep") }
            try {
                installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
                manager.stageDownloadedUpdate(update("9.5.9"), artifact.absolutePath)

                assertFalse(manager.installUpdate(artifact.absolutePath))

                assertTrue(child.exists())
                assertEquals("Unsupported macOS", assertIs<UpdateState.Error>(manager.updateState.value).message)
                assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            } finally {
                artifact.deleteRecursively()
            }
        }

    @Test
    fun `refused artifact outside staging remains untouched`() =
        runBlocking {
            val unrelated = File(settingsDir, "unrelated.dmg").apply { writeText("keep") }
            installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
            manager.stageDownloadedUpdate(update("9.5.9"), unrelated.absolutePath)

            assertFalse(manager.installUpdate(unrelated.absolutePath))

            assertEquals("keep", unrelated.readText())
            assertEquals("Unsupported macOS", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    @Test
    fun `refused downgrade artifact is cleaned without replacing a newer dismissal`() =
        runBlocking {
            val staging = createRestrictedDir(defaultStagingDir())
            val artifact = File.createTempFile("refused-downgrade-", ".dmg", staging).apply { writeText("refused") }
            try {
                UpdateSettings.lastDismissedVersion = "9.5.9"
                installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
                manager.stageDownloadedUpdate(update("9.5.7"), artifact.absolutePath)

                assertFalse(manager.installUpdate(artifact.absolutePath))

                assertFalse(artifact.exists())
                assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            } finally {
                artifact.delete()
            }
        }

    @Test
    fun `cleanup cannot delete a newly staged artifact that reused the refused path`() =
        runBlocking {
            val staging = createRestrictedDir(defaultStagingDir())
            val artifact = File.createTempFile("refused-reused-", ".dmg", staging).apply { writeText("old") }
            try {
                installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
                manager.stageDownloadedUpdate(update("9.5.9"), artifact.absolutePath)
                duringInstall = {
                    artifact.writeText("new")
                    manager.stageDownloadedUpdate(update("9.5.10"), artifact.absolutePath)
                }

                assertFalse(manager.installUpdate(artifact.absolutePath))

                assertEquals("new", artifact.readText())
                assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
                val ready = assertIs<UpdateState.ReadyToInstall>(manager.updateState.value)
                assertEquals(artifact.absolutePath, ready.downloadPath)
                assertEquals("9.5.10", ready.updateInfo?.latestVersion.toString())
            } finally {
                artifact.delete()
            }
        }

    @Test
    fun `refused intermediate release preserves suppression of the latest release`() =
        runBlocking {
            UpdateSettings.lastDismissedVersion = "9.5.11"
            installOutcome = InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
            manager.stageDownloadedUpdate(update("9.5.9"), File(settingsDir, "intermediate.dmg").absolutePath)

            assertFalse(manager.installUpdate(File(settingsDir, "intermediate.dmg").absolutePath))

            assertEquals("9.5.11", UpdateSettings.lastDismissedVersion)
            assertEquals("Unsupported macOS", assertIs<UpdateState.Error>(manager.updateState.value).message)
        }

    @Test
    fun `a download cannot replace an artifact until the refused installation releases it`() =
        runBlocking {
            val staging = createRestrictedDir(defaultStagingDir())
            val artifact = File.createTempFile("refused-serialized-", ".dmg", staging).apply { writeText("old") }
            val installing = CompletableDeferred<Unit>()
            val releaseInstall = CompletableDeferred<Unit>()
            var downloaded = false
            manager.shutdown()
            manager =
                UpdateManager(
                    UpdateInstallOperation {
                        installing.complete(Unit)
                        releaseInstall.await()
                        InstallOutcome(false, "Unsupported macOS", InstallFailureReason.UnsupportedOs)
                    },
                    checkOperation = { update("9.5.10") },
                    downloadOperation = { _, _ ->
                        downloaded = true
                        assertFalse(
                            artifact.exists(),
                            "refused file is cleaned before a new download can reuse its path",
                        )
                        artifact.writeText("new")
                        artifact.absolutePath
                    },
                )
            try {
                manager.stageDownloadedUpdate(update("9.5.9"), artifact.absolutePath)
                val installation =
                    async(start = CoroutineStart.UNDISPATCHED) { manager.installUpdate(artifact.absolutePath) }
                installing.await()
                val download = async(start = CoroutineStart.UNDISPATCHED) { manager.downloadUpdate(update("9.5.10")) }
                assertFalse(downloaded)
                assertEquals("old", artifact.readText())
                // A discard pressed during install must not queue deletion of the next download.
                manager.discardDownload()
                releaseInstall.complete(Unit)

                assertFalse(installation.await())
                assertIs<UpdateResult.UpdateAvailable>(download.await())

                assertEquals("new", artifact.readText())
                assertEquals(
                    artifact.absolutePath,
                    assertIs<UpdateState.ReadyToInstall>(manager.updateState.value).downloadPath,
                )
                assertEquals("9.5.9", UpdateSettings.lastDismissedVersion)
            } finally {
                releaseInstall.complete(Unit)
                artifact.delete()
            }
        }

    private fun update(version: String): UpdateInfo {
        val latest = Version.parse(version)!!
        return UpdateInfo(
            available = true,
            currentVersion = Version.parse("9.5.8")!!,
            latestVersion = latest,
            releaseNotes = "",
        )
    }
}
