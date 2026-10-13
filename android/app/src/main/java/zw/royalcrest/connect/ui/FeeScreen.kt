package zw.royalcrest.connect.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.royalcrest.connect.data.Chat
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.FeeDoc
import zw.royalcrest.connect.data.SendDocIn
import zw.royalcrest.connect.data.friendly

class FeeVm(private val conn: Connection, private val id: String) : ViewModel() {
    val doc = MutableStateFlow<FeeDoc?>(null)
    val chats = MutableStateFlow<List<Chat>>(emptyList())
    val notice = MutableStateFlow<String?>(null)

    init {
        // Poll while the school PC is still making the PDF.
        viewModelScope.launch {
            while (true) {
                load()
                delay(if (doc.value?.status == "ready" || doc.value?.status == "error") 30_000 else 3_000)
            }
        }
    }

    private suspend fun load() {
        try { doc.value = conn.api.fee(id) } catch (e: Exception) { notice.value = e.friendly() }
    }

    fun loadChats() = viewModelScope.launch { try { chats.value = conn.api.chats() } catch (_: Exception) {} }

    fun send(chatId: String?) = viewModelScope.launch {
        try { conn.api.sendFee(id, SendDocIn(chatId, conn.displayName)); notice.value = "Queued for WhatsApp."; load() }
        catch (e: Exception) { notice.value = e.friendly() }
    }

    fun retry() = viewModelScope.launch {
        try { doc.value = conn.api.retryFee(id) } catch (e: Exception) { notice.value = e.friendly() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeeScreen(feeId: String, onBack: () -> Unit, onReceipt: (String) -> Unit) {
    val conn = LocalConnection.current
    val vm: FeeVm = viewModel(key = "fee-$feeId") { FeeVm(conn, feeId) }
    val doc by vm.doc.collectAsState()
    val chats by vm.chats.collectAsState()
    val notice by vm.notice.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickChat by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(doc?.number ?: "") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                doc?.pdfUrl?.let { url ->
                    IconButton(onClick = { scope.launch { sharePdf(context, conn, url) } }) { Icon(Icons.Outlined.Share, "Share PDF") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
    }) { pad ->
        val d = doc
        if (d == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (notice != null) Text(notice!!, color = Bad) else CircularProgressIndicator()
            }
            return@Scaffold
        }
        val cur = d.currency ?: "USD"
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (d.kind == "receipt") "Receipt" else "Invoice", style = MaterialTheme.typography.headlineSmall, color = Navy)
                StatusTag(d)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.pupilName ?: "", style = MaterialTheme.typography.titleMedium)
                    listOf("Admission no." to d.admissionNo, "Class" to d.className, "Term" to d.term,
                           "Parent / guardian" to d.parentName, "Sent to" to d.chatName,
                           "Due" to d.dueDate, "Paid by" to d.paymentMethod, "Reference" to d.paymentRef,
                           "For invoice" to d.invoiceNumber, "Issued" to shortTime(d.createdAt))
                        .filter { !it.second.isNullOrBlank() }
                        .forEach { (k, v) ->
                            Row { Text(k, color = Muted, modifier = Modifier.weight(1f)); Text(v!!, style = MaterialTheme.typography.labelLarge) }
                        }
                }
            }
            d.items.orEmpty().forEach { line ->
                Row { Text(line.description, Modifier.weight(1f)); Text(money(line.amount)) }
                HorizontalDivider(color = Line)
            }
            if (d.kind == "invoice") {
                Total("Total fees", "$cur ${money(d.total)}")
                Total("Paid so far", "$cur ${money(d.amountPaid)}")
                Total("Balance due", "$cur ${money(d.balance)}", strong = true)
            } else {
                Total("Amount received", "$cur ${money(d.amountPaid)}", strong = true)
                d.balance?.let { Total("Still owed on the invoice", "$cur ${money(it)}") }
            }
            when (d.status) {
                "ready" -> {
                    Button(onClick = { scope.launch { openPdf(context, conn, d.pdfUrl!!) } }, modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Navy)) { Text("Open PDF") }
                    OutlinedButton(onClick = { vm.loadChats(); pickChat = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text(if (d.chatId != null) "Send on WhatsApp again" else "Send on WhatsApp")
                    }
                }
                "error" -> {
                    Text("The PDF couldn't be made: ${d.errorMessage ?: "unknown error"}", color = Bad)
                    Button(onClick = { vm.retry() }) { Text("Try again") }
                }
                else -> Text("The school PC is making the PDF. This page updates by itself.", color = Muted)
            }
            if (d.kind == "invoice" && (d.balance ?: 0.0) > 0) {
                Button(onClick = { onReceipt(d.id) }, modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy)) { Text("Record a payment") }
            }
            notice?.let { Text(it, color = Good) }
        }
    }

    if (pickChat) AlertDialog(
        onDismissRequest = { pickChat = false },
        title = { Text("Send to") },
        text = {
            LazyColumn(Modifier.height(320.dp)) {
                doc?.chatId?.let { id ->
                    item { Text("${doc?.chatName ?: "Same chat"} (as before)", Modifier.fillMaxWidth()
                        .clickable { pickChat = false; vm.send(id) }.padding(vertical = 12.dp), color = Navy) }
                }
                items(chats, key = { it.id }) { c ->
                    Text(c.name, Modifier.fillMaxWidth().clickable { pickChat = false; vm.send(c.id) }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { pickChat = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Total(label: String, value: String, strong: Boolean = false) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(1f), style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
         color = if (strong) Navy else Muted)
    Text(value, style = if (strong) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
         color = if (strong) Navy else Ink)
}
