package zw.royalcrest.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.royalcrest.connect.data.BroadcastIn
import zw.royalcrest.connect.data.BroadcastPollIn
import zw.royalcrest.connect.data.Chat
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.Poll
import zw.royalcrest.connect.data.friendly

class BroadcastVm(private val conn: Connection) : ViewModel() {
    val chats = MutableStateFlow<List<Chat>>(emptyList())
    val polls = MutableStateFlow<List<Poll>>(emptyList())
    val busy = MutableStateFlow(false)
    val result = MutableStateFlow<Pair<String, Boolean>?>(null)

    init { reload() }

    fun reload() = viewModelScope.launch {
        try { chats.value = conn.api.chats(); polls.value = conn.api.polls() }
        catch (e: Exception) { result.value = e.friendly() to false }
    }

    fun send(text: String, audiences: List<String>, done: () -> Unit) = viewModelScope.launch {
        busy.value = true
        try {
            val r = conn.api.broadcast(BroadcastIn(conn.displayName, text.trim(), audiences = audiences))
            val skipped = r.skippedDuplicate ?: 0
            result.value = "Queued for ${r.sentTo ?: 0} chat(s)" +
                (if (skipped > 0) "; $skipped already had it." else ". It goes out a few seconds apart.") to true
            done()
        } catch (e: Exception) { result.value = e.friendly() to false } finally { busy.value = false }
    }

    fun poll(question: String, options: List<String>, audiences: List<String>) = viewModelScope.launch {
        busy.value = true
        try {
            val r = conn.api.broadcastPoll(BroadcastPollIn(conn.displayName, question.trim(), options, audiences = audiences))
            result.value = "Poll sent to ${r.sentTo ?: 0} chat(s)." to true
            reload()
        } catch (e: Exception) { result.value = e.friendly() to false } finally { busy.value = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BroadcastScreen() {
    val conn = LocalConnection.current
    val vm: BroadcastVm = viewModel(key = "bc-" + conn.baseUrl) { BroadcastVm(conn) }
    val chats by vm.chats.collectAsState()
    val polls by vm.polls.collectAsState()
    val busy by vm.busy.collectAsState()
    val result by vm.result.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(listOf("parent")) }
    var confirm by remember { mutableStateOf(false) }
    var pollOpen by remember { mutableStateOf(false) }
    val count = chats.count { it.audience in picked }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Broadcast") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Send to", style = MaterialTheme.typography.titleSmall, color = Navy)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AUDIENCES.forEach { (value, label) ->
                                FilterChip(value in picked, { picked = if (value in picked) picked - value else picked + value },
                                    label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy))
                            }
                        }
                        Text("$count chat(s). Tag chats as parent, group or staff from inside each chat.",
                             style = MaterialTheme.typography.bodySmall, color = Muted)
                        OutlinedTextField(text, { text = it }, label = { Text("Message") }, minLines = 4,
                            modifier = Modifier.fillMaxWidth())
                        Text("Nobody gets the same text twice in 24 hours. For every parent at once, send it to the parents' WhatsApp groups.",
                             style = MaterialTheme.typography.bodySmall, color = Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { confirm = true }, enabled = text.isNotBlank() && picked.isNotEmpty() && !busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy),
                                modifier = Modifier.weight(1f).height(48.dp)) { Text("Send broadcast") }
                            OutlinedButton(onClick = { pollOpen = true }, enabled = picked.isNotEmpty() && !busy,
                                modifier = Modifier.height(48.dp)) { Text("Poll") }
                        }
                        result?.let { (msg, ok) -> Text(msg, color = if (ok) Good else Bad, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            if (polls.isNotEmpty()) item { Text("Poll results", style = MaterialTheme.typography.titleLarge, color = Navy) }
            items(polls, key = { it.id }) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.question, style = MaterialTheme.typography.titleSmall)
                        Text("${p.chatName ?: ""} · ${shortTime(p.createdAt)} · ${p.votes ?: 0} vote(s)",
                             style = MaterialTheme.typography.bodySmall, color = Muted)
                        val total = (p.votes ?: 0).coerceAtLeast(1)
                        p.options.orEmpty().forEachIndexed { i, o ->
                            Text("${i + 1}. ${o.text}", style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(progress = { (o.votes ?: 0) / total.toFloat() },
                                modifier = Modifier.fillMaxWidth(), color = Navy, trackColor = Panel,
                                gapSize = 0.dp, drawStopIndicator = {})
                        }
                    }
                }
            }
        }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Send to $count chat(s)?") },
        text = { Text("It goes out on WhatsApp from the school number.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.send(text, picked) { text = "" } }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
    if (pollOpen) PollDialog(showWhatsappSwitch = false, onDismiss = { pollOpen = false }) { q, opts, _ ->
        pollOpen = false; vm.poll(q, opts, picked)
    }
}
