package zw.royalcrest.connect

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import zw.royalcrest.connect.data.SavedServer
import zw.royalcrest.connect.data.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Renders the real screens against a running Royal Crest Connect server and
 * saves screenshots to app/build/screens/. Skipped unless RCA_TEST_SERVER
 * (e.g. http://127.0.0.1:8765/) and RCA_TEST_KEY are set, since it needs a
 * server with some chats and fee documents in it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// iPhone 15 Pro width; the height leaves room for a 59dp status bar on the
// website's phone mockups (393 x 852 overall).
@Config(qualifiers = "w393dp-h793dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val server = System.getenv("RCA_TEST_SERVER")
    private val key = System.getenv("RCA_TEST_KEY").orEmpty()
    private val out = File("build/screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun waitFor(text: String) = rule.waitUntil(20_000) {
        rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun reachable(): Boolean = try {
        (URL(server + "api/health").openConnection() as HttpURLConnection).run { connectTimeout = 2000; responseCode == 200 }
    } catch (_: Exception) { false }

    @Test
    fun chats() {
        start()

        waitFor("Mrs Moyo"); shot("1-chats")
        rule.onNodeWithText("Mrs Moyo").performClick()
        waitFor("fees invoice for Term 1"); shot("2-chat")
    }

    @Test
    fun groupChat() {
        start()
        waitFor("Form 1 Parents 2027"); rule.onNodeWithText("Form 1 Parents 2027").performClick()
        waitFor("Saturday 19 December"); shot("2b-group")
    }

    @Test
    fun broadcast() {
        start()
        waitFor("Broadcast"); rule.onNodeWithText("Broadcast").performClick()
        waitFor("Send broadcast"); shot("3-broadcast")
    }

    @Test
    fun fees() {
        start()
        waitFor("Fees"); rule.onNodeWithText("Fees").performClick()
        waitFor("Tariro Moyo"); shot("4-fees")
        rule.onNodeWithText("Balances").performClick()
        waitFor("paid "); shot("4b-balances")
    }

    @Test
    fun newFee() {
        start()
        waitFor("Fees"); rule.onNodeWithText("Fees").performClick()
        waitFor("Tariro Moyo")
        rule.onNodeWithText("New", useUnmergedTree = true).performClick()
        waitFor("Fee lines"); shot("6-new-invoice")
        rule.onNodeWithText("Receipt").performClick()
        waitFor("Amount received"); shot("7-new-receipt")
    }

    private fun start() {
        assumeTrue(server != null && reachable())
        runBlocking { Settings(ApplicationProvider.getApplicationContext()).save(SavedServer(server!!, key, "Mrs Muzunze")) }
        rule.activityRule.scenario.recreate()
    }

    @Test
    fun invoiceScreen() {
        start()
        waitFor("Fees"); rule.onNodeWithText("Fees").performClick()
        waitFor("Tariro Moyo"); rule.onNodeWithText("Tariro Moyo").performClick()
        waitFor("Balance due"); shot("5-invoice")
    }

    @Test
    fun setupScreen() {
        runBlocking { Settings(ApplicationProvider.getApplicationContext()).clear() }
        rule.activityRule.scenario.recreate()
        waitFor("Connect to the school server"); shot("0-setup")
    }
}
