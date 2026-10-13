package zw.royalcrest.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import zw.royalcrest.connect.data.Settings
import zw.royalcrest.connect.ui.AppRoot
import zw.royalcrest.connect.ui.RoyalCrestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(applicationContext)
        setContent { RoyalCrestTheme { AppRoot(settings) } }
    }
}
