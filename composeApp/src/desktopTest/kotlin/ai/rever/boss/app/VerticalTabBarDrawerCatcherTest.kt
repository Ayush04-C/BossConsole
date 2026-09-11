package ai.rever.boss.app

import ai.rever.boss.components.window_panel.components.main_window_panels.VerticalTabBarDrawer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The chevron drawer's click-catcher must leave the retained rail interactive.
 *
 * SplitView composes the rail (WindowBarRow) BEFORE the drawer, so the drawer's dismissal
 * catcher - a fillMaxSize Box - sits on top of the rail. Pressing a retained quick action must
 * fire the action, not dismiss the drawer, and the rail must keep its hover (its Swing labels
 * are driven by it). Pressing the part of the panel the drawer does not cover still dismisses.
 *
 * The test composes the lightweight drawer path: in a test `OverlayConfig.heavyweightCorner` is
 * never set, so `overlayCornerIsHeavyweight()` is false and the drawer is ordinary layout, where
 * the catcher's hit region is what is under test.
 */
class VerticalTabBarDrawerCatcherTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the retained rail stays interactive under the chevron drawer's click-catcher`() {
        var dismissals = 0
        rule.setContent {
            Box(modifier = Modifier.size(PANEL_SIZE)) {
                // Composed first, exactly as SplitView composes WindowBarRow before RevealedBar.
                Box(
                    modifier =
                        Modifier
                            .width(RAIL_WIDTH)
                            .fillMaxHeight()
                            .testTag(RAIL_TAG),
                )
                VerticalTabBarDrawer(
                    visible = true,
                    hoverSource = remember { MutableInteractionSource() },
                    hoverEnabled = false,
                    width = PANEL_SIZE,
                    railWidth = RAIL_WIDTH,
                    panelRegion = IntRect(0, 0, 300, 400),
                    onDismissOutside = { dismissals++ },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(DRAWER_WIDTH, DRAWER_HEIGHT)
                                .testTag(DRAWER_TAG),
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(RAIL_TAG).performClick()
        assertEquals(
            0,
            dismissals,
            "pressing the retained rail must not count as an outside click",
        )

        rule.onNodeWithTag(DRAWER_TAG).performClick()
        assertEquals(1, dismissals, "pressing the rest of the panel still dismisses the drawer")
    }

    private companion object {
        val RAIL_WIDTH = 36.dp
        val PANEL_SIZE = 220.dp
        val DRAWER_WIDTH = 100.dp
        val DRAWER_HEIGHT = 200.dp
        const val RAIL_TAG = "retained-rail"
        const val DRAWER_TAG = "drawer-content"
    }
}
