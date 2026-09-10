package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.PluginType
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.sandbox.PluginSandboxManager
import ai.rever.boss.plugin.sandbox.PluginSandboxManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginSandboxUnloadAwaitTest {
    @Test
    fun `uninstall waits for sandbox teardown before reporting reload can proceed`() =
        runBlocking {
            val real = PluginSandboxManagerImpl()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val sandboxManager =
                object : PluginSandboxManager by real {
                    override suspend fun removeSandbox(pluginId: String) {
                        started.complete(Unit)
                        release.await()
                        real.removeSandbox(pluginId)
                    }
                }
            val manager =
                DynamicPluginManager(
                    PanelRegistry(),
                    TabRegistry(),
                    sandboxManager,
                    createSandboxedContext = { _, _ -> error("No plugin is loaded in this fixture") },
                )
            val id = "com.example.await-removal"
            val info =
                DynamicPluginInfo(
                    manifest =
                        PluginManifest(
                            pluginId = id,
                            displayName = "Await removal",
                            version = "1.0.0",
                            apiVersion = "1.0",
                            mainClass = "example.Plugin",
                            type = PluginType.PANEL,
                        ),
                    jarPath = "/unused.jar",
                    state = PluginState.ERROR,
                    loadedAt = 0L,
                    enabled = false,
                )
            // Reproduce the existing unload-with-state-only path without loading a real plugin JAR.
            manager.javaClass
                .getDeclaredMethod("updatePluginState", String::class.java, DynamicPluginInfo::class.java)
                .apply {
                    isAccessible = true
                    invoke(manager, id, info)
                }
            val uninstall = async(start = CoroutineStart.UNDISPATCHED) { manager.uninstallPlugin(id, force = true) }
            try {
                withTimeout(5_000) { started.await() }
                assertFalse(uninstall.isCompleted, "reload must not overtake its sandbox removal")
                release.complete(Unit)
                assertTrue(withTimeout(5_000) { uninstall.await() }.isSuccess)
                assertFalse(manager.isInstalled(id))
            } finally {
                release.complete(Unit)
                uninstall.join()
                manager.disposeWindow()
                real.dispose()
            }
        }
}
