package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateManagerInstallOutcomeTest {
    private lateinit var settingsDir: File
    private lateinit var manager: UpdateManager
    private var dismissedBefore: String? = null
    private var installOutcome = InstallOutcome(succeeded = false)
    private var installedPath: String? = null

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
            val downloadPath = "C:/updates/BOSS-9.5.9.dmg"
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
            assertNotEquals("9.5.10", UpdateSettings.lastDismissedVersion)
        }

    @Test
    fun `generic install failure remains visible without dismissing the version`() =
        runBlocking {
            val update = update("9.5.9")
            val downloadPath = "C:/updates/BOSS-9.5.9.dmg"
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
                val newerInfo = update("9.5.10")
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
                            // A later artifact can be staged while this operation is in flight;
                            // cleanup must remain bound to the path this installation claimed.
                            manager.stageDownloadedUpdate(newerInfo, newer.absolutePath)
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
