package zw.royalcrest.connect.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import zw.royalcrest.connect.data.AudienceIn
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.Message
import zw.royalcrest.connect.data.PollIn
import zw.royalcrest.connect.data.SendMessageIn
import zw.royalcrest.connect.data.friendly

class ChatVm(private val conn: Connection, private val chatId: String) : ViewModel() {
    private val _messages = MutableStateFlow<List<Message>?>(null)
    val messages = _messages.asStateFlow()
    private val _audience = MutableStateFlow<String?>(null)
    val audience = _audience.asStateFlow()
    val notices = MutableStateFlow<String?>(null)
    val sending = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            try { _audience.value = conn.api.chats().firstOrNull { it.id == chatId }?.audience } catch (_: Exception) {}
        }
        viewModelScope.launch { while (true) { load(); delay(5_000) } }
    }

    private suspend fun load() {
        try { _messages.value = conn.api.messages(chatId) } catch (e: Exception) {
            if (_messages.value == null) notices.value = e.friendly()
        }
    }

    private fun act(block: suspend () -> Unit) = viewModelScope.launch {
        sending.value = true
        try { block(); load() } catch (e: Exception) { notices.value = e.friendly() } finally { sending.value = false }
    }

    fun send(text: String, onSent: () -> Unit) = act {
        conn.api.reply(chatId, SendMessageIn(conn.displayName, text.trim()))
        onSent()
    }

    fun sendImage(context: Context, uri: Uri, caption: String) = act {
        val (bytes, mime) = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            (cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read that photo.")) to
                (cr.getType(uri) ?: "image/jpeg")
        }
        val ext = when (mime) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val text = "text/plain".toMediaType()
        conn.api.sendImage(chatId, conn.displayName.toRequestBody(text), caption.toRequestBody(text),
            MultipartBody.Part.createFormData("file", "photo.$ext", bytes.toRequestBody(mime.toMediaType())))
        notices.value = "Photo queued for WhatsApp."
    }

    fun poll(question: String, options: List<String>, toWhatsapp: Boolean) = act {
        conn.api.createPoll(chatId, PollIn(conn.displayName, question.trim(), options, toWhatsapp))
        notices.value = if (toWhatsapp) "Poll sent. Replies with a number are counted." else "Poll saved in the app."
    }

    fun setAudience(a: String?) = act {
        conn.api.setAudience(chatId, AudienceIn(a))
        _audience.value = a
        notices.value = "Saved as ${audienceLabel(a) ?: "not set"}."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: String, chatName: String, onBack: () -> Unit, onNewFee: (String) -> Unit) {
    val conn = LocalConnection.current
    val vm: ChatVm = viewModel(key = "chat-$chatId") { ChatVm(conn, chatId) }
    val messages by vm.messages.collectAsState()
    val audience by vm.audience.collectAsState()
    val notice by vm.notices.collectAsState()
    val sending by vm.sending.collectAsState()
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    val images = rememberImageLoader(conn)
    var draft by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var audienceMenu by remember { mutableStateOf(false) }
    var pollOpen by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { vm.sendImage(context, uri, draft); draft = "" }
    }

    LaunchedEffect(notice) { notice?.let { snack.showSnackbar(it); vm.notices.value = null } }
    LaunchedEffect(messages?.size) { messages?.let { if (it.isNotEmpty()) list.scrollToItem(it.size - 1) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(chatName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        audienceLabel(audience)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Gold) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { onNewFee(chatId) }) { Icon(Icons.Outlined.ReceiptLong, "Invoice or receipt") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Send a poll") }, leadingIcon = { Icon(Icons.Outlined.Poll, null) },
                                onClick = { menu = false; pollOpen = true })
                            DropdownMenuItem(text = { Text("This chat is…") }, onClick = { menu = false; audienceMenu = true })
                        }
                        DropdownMenu(audienceMenu, { audienceMenu = false }) {
                            AUDIENCES.forEach { (value, _) ->
                                DropdownMenuItem(text = { Text(audienceLabel(value)!!) },
                                    onClick = { audienceMenu = false; vm.setAudience(value) })
                            }
                            DropdownMenuItem(text = { Text("Not set") }, onClick = { audienceMenu = false; vm.setAudience(null) })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Panel).imePadding()) {
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages.orEmpty(), key = { it.id }) { m -> Bubble(m, conn, images) }
            }
            HorizontalDivider(color = Line)
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Outlined.Image, "Send a photo", tint = Navy)
                    }
                    OutlinedTextField(draft, { draft = it }, placeholder = { Text("Reply on WhatsApp") },
                        modifier = Modifier.weight(1f).heightIn(max = 160.dp))
                    Spacer(Modifier.padding(4.dp))
                    FilledIconButton(
                        onClick = { vm.send(draft) { draft = "" } }, enabled = draft.isNotBlank() && !sending,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Navy, contentColor = Gold),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }

    if (pollOpen) PollDialog(onDismiss = { pollOpen = false }) { q, opts, wa -> pollOpen = false; vm.poll(q, opts, wa) }
}

private val STATUS = mapOf("sent" to "✓ delivered", "error" to "⚠ not sent", "pending" to "sending…",
                           "urgent" to "sending…", "documents" to "sending…")

@Composable
private fun Bubble(m: Message, conn: Connection, images: coil.ImageLoader) {
    val out = m.direction == "out"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (out) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (out) Navy else Color.White, contentColor = if (out) Color.White else Ink,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!out && !m.sender.isNullOrBlank()) Text(m.sender, style = MaterialTheme.typography.labelMedium, color = NavySoft)
                when {
                    m.kind == "image" && m.mediaUrl != null -> AsyncImage(conn.absolute(m.mediaUrl), m.text, images,
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(bottom = 4.dp), contentScale = ContentScale.Fit)
                    m.kind == "document" && m.mediaUrl != null -> TextButton(onClick = {
                        scope.launch { openPdf(context, conn, m.mediaUrl) }
                    }) {
                        Icon(Icons.Outlined.Description, null, tint = if (out) Gold else Navy)
                        Text("  Open PDF", color = if (out) Gold else Navy)
                    }
                }
                if (m.kind == "poll" && m.options != null) {
                    Text(m.text?.lineSequence()?.firstOrNull()?.removePrefix("📊")?.trim(' ', '*') ?: "", style = MaterialTheme.typography.titleSmall)
                    m.options.forEachIndexed { i, o -> Text("${i + 1}. ${o.text} — ${o.votes ?: 0}", style = MaterialTheme.typography.bodyMedium) }
                } else if (!m.text.isNullOrBlank()) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                }
                Text(shortTime(m.createdAt) + (if (out) "  " + (STATUS[m.waStatus] ?: "") else ""),
                     style = MaterialTheme.typography.labelSmall,
                     color = if (out) (if (m.waStatus == "error") Gold else Panel) else Muted,
                     modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

/** Question + 2 to 9 options; WhatsApp or app-only. Shared with the Broadcast tab. */
@Composable
fun PollDialog(showWhatsappSwitch: Boolean = true, onDismiss: () -> Unit, onSend: (String, List<String>, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf(true) }
    val opts = options.lines().map { it.trim() }.filter { it.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send a poll") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(question, { question = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(options, { options = it }, label = { Text("Options, one per line") },
                    minLines = 3, modifier = Modifier.fillMaxWidth())
                Text("Parents answer by replying with the number. ${opts.size} option(s).",
                     style = MaterialTheme.typography.bodySmall, color = Muted)
                if (showWhatsappSwitch) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Send on WhatsApp", Modifier.weight(1f)); Switch(whatsapp, { whatsapp = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(question, opts, whatsapp) },
                enabled = question.isNotBlank() && opts.size in 2..9) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
