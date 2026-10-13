package zw.royalcrest.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import zw.royalcrest.connect.data.Chat
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.friendly

class ChatsVm(private val conn: Connection) : ViewModel() {
    private val _chats = MutableStateFlow<List<Chat>?>(null)
    val chats = _chats.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    init {
        viewModelScope.launch { while (true) { load(); delay(10_000) } }
    }

    suspend fun load() {
        try { _chats.value = conn.api.chats(); _error.value = null }
        catch (e: Exception) { _error.value = e.friendly() }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }
}

/** One image loader per connection, so pictures carry the access key. */
@Composable
fun rememberImageLoader(conn: Connection): ImageLoader {
    val context = LocalContext.current
    return remember(conn) { ImageLoader.Builder(context).okHttpClient(conn.http).crossfade(true).build() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(onOpenChat: (String, String) -> Unit, onSettings: () -> Unit) {
    val conn = LocalConnection.current
    val vm: ChatsVm = viewModel(key = conn.baseUrl) { ChatsVm(conn) }
    val chats by vm.chats.collectAsState()
    val error by vm.error.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    val images = rememberImageLoader(conn)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chats") },
            actions = { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White,
                                                       actionIconContentColor = Color.White),
        )
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search chats") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == null, { filter = null }, label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy))
            AUDIENCES.forEach { (value, label) ->
                FilterChip(filter == value, { filter = if (filter == value) null else value }, label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy))
            }
        }
        error?.let { Text(it, color = Bad, modifier = Modifier.padding(16.dp, 8.dp)) }
        val list = chats
        PullToRefreshBox(refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
            when {
                list == null -> Box(Modifier.fillMaxSize())
                list.isEmpty() -> EmptyState("No chats yet",
                    "Chats appear here once the WhatsApp bridge on the school PC is running.")
                else -> {
                    val shown = list.filter {
                        (filter == null || it.audience == filter) &&
                            (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true))
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.id }) { chat ->
                            ChatRow(chat, images, conn) { onOpenChat(chat.id, chat.name) }
                            HorizontalDivider(color = Line, modifier = Modifier.padding(start = 76.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, images: ImageLoader, conn: Connection, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(chat.name, conn.absolute(chat.profilePicUrl), images)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(chat.name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                audienceLabel(chat.audience)?.let {
                    Tag(it, bg = if (chat.audience == "enquiry") Color(0xFFFFF1C2) else Panel)
                }
            }
            Text((if (chat.lastDirection == "out") "You: " else "") + (chat.lastText ?: "").replace("*", "").replace('\n', ' '),
                 style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(shortTime(chat.lastAt).substringAfter(", "), style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
fun Avatar(name: String, url: String?, images: ImageLoader, size: Int = 48) {
    val initials = name.split(' ', '+').filter { it.isNotBlank() && it[0].isLetter() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "#" }
    Box(Modifier.size(size.dp).clip(CircleShape).background(NavySoft), contentAlignment = Alignment.Center) {
        Text(initials, color = Gold, style = MaterialTheme.typography.titleSmall)
        if (url != null) AsyncImage(url, null, images, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
