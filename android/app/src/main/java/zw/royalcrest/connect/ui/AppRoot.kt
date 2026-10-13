package zw.royalcrest.connect.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.SavedServer
import zw.royalcrest.connect.data.Settings

private sealed interface Boot {
    data object Loading : Boot
    data object Setup : Boot
    data class Ready(val conn: Connection) : Boot
}

/** Setup screen until a server is saved, then the three tabs. */
@Composable
fun AppRoot(settings: Settings) {
    var boot by remember { mutableStateOf<Boot>(Boot.Loading) }
    var saved by remember { mutableStateOf<SavedServer?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val s = settings.load()
        saved = s
        boot = if (s == null) Boot.Setup else Boot.Ready(Connection(s.url, s.key, s.name.ifBlank { "Royal Crest" }))
    }

    when (val b = boot) {
        Boot.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Boot.Setup -> SetupScreen(initial = saved) { s ->
            scope.launch {
                settings.save(s)
                saved = s
                boot = Boot.Ready(Connection(s.url, s.key, s.name.ifBlank { "Royal Crest" }))
            }
        }
        is Boot.Ready -> CompositionLocalProvider(LocalConnection provides b.conn) {
            MainNav(onSettings = { boot = Boot.Setup })
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val CHAT = "chat/{id}/{name}"
    const val NEW_FEE = "fee/new?chat={chat}&invoice={invoice}"
    const val FEE = "fee/{id}"
    fun chat(id: String, name: String) = "chat/${Uri.encode(id)}/${Uri.encode(name)}"
    fun newFee(chat: String? = null, invoice: String? = null) =
        "fee/new?chat=${Uri.encode(chat.orEmpty())}&invoice=${Uri.encode(invoice.orEmpty())}"
    fun fee(id: String) = "fee/${Uri.encode(id)}"
}

@Composable
private fun MainNav(onSettings: () -> Unit) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeTabs(
                onOpenChat = { id, name -> nav.navigate(Routes.chat(id, name)) },
                onNewFee = { nav.navigate(Routes.newFee()) },
                onOpenFee = { nav.navigate(Routes.fee(it)) },
                onSettings = onSettings,
            )
        }
        composable(Routes.CHAT, listOf(navArgument("id") { type = NavType.StringType },
                                       navArgument("name") { type = NavType.StringType })) { e ->
            ChatScreen(
                chatId = e.arguments?.getString("id").orEmpty(),
                chatName = e.arguments?.getString("name").orEmpty(),
                onBack = { nav.popBackStack() },
                onNewFee = { nav.navigate(Routes.newFee(chat = it)) },
            )
        }
        composable(Routes.NEW_FEE, listOf(
            navArgument("chat") { type = NavType.StringType; defaultValue = "" },
            navArgument("invoice") { type = NavType.StringType; defaultValue = "" },
        )) { e ->
            NewFeeScreen(
                chatId = e.arguments?.getString("chat")?.ifBlank { null },
                invoiceId = e.arguments?.getString("invoice")?.ifBlank { null },
                onBack = { nav.popBackStack() },
                onCreated = { id -> nav.popBackStack(); nav.navigate(Routes.fee(id)) },
            )
        }
        composable(Routes.FEE, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            FeeScreen(
                feeId = e.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onReceipt = { invoiceId -> nav.navigate(Routes.newFee(invoice = invoiceId)) },
            )
        }
    }
}

@Composable
private fun HomeTabs(
    onOpenChat: (String, String) -> Unit,
    onNewFee: () -> Unit,
    onOpenFee: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val items = listOf(
        Triple("Chats", Icons.AutoMirrored.Outlined.Chat, 0),
        Triple("Broadcast", Icons.Outlined.Campaign, 1),
        Triple("Fees", Icons.Outlined.ReceiptLong, 2),
    )
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Navy) {
                items.forEach { (label, icon, i) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Navy, selectedTextColor = Gold, indicatorColor = Gold,
                            unselectedIconColor = Panel, unselectedTextColor = Panel,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatsScreen(onOpenChat = onOpenChat, onSettings = onSettings)
                1 -> BroadcastScreen()
                else -> FeesScreen(onNew = onNewFee, onOpen = onOpenFee)
            }
        }
    }
}
