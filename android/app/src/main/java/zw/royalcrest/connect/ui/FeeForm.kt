package zw.royalcrest.connect.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.royalcrest.connect.data.Catalogue
import zw.royalcrest.connect.data.Chat
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.FeeDoc
import zw.royalcrest.connect.data.FeeDocIn
import zw.royalcrest.connect.data.FeeLine
import zw.royalcrest.connect.data.friendly

class NewFeeVm(private val conn: Connection) : ViewModel() {
    val catalogue = MutableStateFlow<Catalogue?>(null)
    val chats = MutableStateFlow<List<Chat>>(emptyList())
    val openInvoices = MutableStateFlow<List<FeeDoc>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            try {
                catalogue.value = conn.api.catalogue()
                chats.value = conn.api.chats()
                openInvoices.value = conn.api.fees(kind = "invoice").filter { (it.balance ?: 0.0) > 0.0 }
            } catch (e: Exception) { error.value = e.friendly() }
        }
    }

    fun create(body: FeeDocIn, done: (String) -> Unit) = viewModelScope.launch {
        busy.value = true; error.value = null
        try { done(conn.api.createFee(body).id) } catch (e: Exception) { error.value = e.friendly() } finally { busy.value = false }
    }
}

private class LineInput(desc: String = "", amount: String = "") {
    var desc by mutableStateOf(desc)
    var amount by mutableStateOf(amount)
}

private fun String.toAmount(): Double? = replace(",", "").trim().toDoubleOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewFeeScreen(chatId: String?, invoiceId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val conn = LocalConnection.current
    val vm: NewFeeVm = viewModel(key = "newfee-$chatId-$invoiceId") { NewFeeVm(conn) }
    val catalogue by vm.catalogue.collectAsState()
    val chats by vm.chats.collectAsState()
    val invoices by vm.openInvoices.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    var receipt by remember { mutableStateOf(invoiceId != null) }
    var pupil by remember { mutableStateOf("") }
    var admission by remember { mutableStateOf("") }
    var cls by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(LineInput()) }
    var paid by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf<String?>(null) }
    var ref by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var linked by remember { mutableStateOf<FeeDoc?>(null) }
    var sendTo by remember { mutableStateOf(chatId) }
    var seeded by remember { mutableStateOf(false) }

    fun fillFrom(inv: FeeDoc) {
        linked = inv; pupil = inv.pupilName.orEmpty(); admission = inv.admissionNo.orEmpty()
        cls = inv.className.orEmpty(); term = inv.term.orEmpty(); parent = inv.parentName.orEmpty()
        amount = inv.balance?.let { money(it).replace(",", "") }.orEmpty()
        if (inv.chatId != null) sendTo = inv.chatId
    }
    LaunchedEffect(catalogue, invoices) {
        val cat = catalogue ?: return@LaunchedEffect
        if (seeded) return@LaunchedEffect
        seeded = true
        method = cat.paymentMethods?.firstOrNull()
        cat.feeItems?.firstOrNull()?.let { lines[0] = LineInput(it.name, it.amount?.toString().orEmpty()) }
        invoices.firstOrNull { it.id == invoiceId }?.let(::fillFrom)
    }

    val total = lines.sumOf { it.amount.toAmount() ?: 0.0 }
    val cur = catalogue?.currency ?: "USD"

    fun submit() {
        val body = if (!receipt) FeeDocIn(
            kind = "invoice", pupilName = pupil.trim(), admissionNo = admission, className = cls, parentName = parent,
            term = term, items = lines.filter { it.desc.isNotBlank() }.map { FeeLine(it.desc.trim(), it.amount.toAmount() ?: 0.0) },
            amountPaid = paid.toAmount() ?: 0.0, dueDate = due.trim().ifBlank { null }, paymentMethod = null,
            paymentRef = null, invoiceId = null, notes = notes, chatId = sendTo, createdBy = conn.displayName,
        ) else FeeDocIn(
            kind = "receipt", pupilName = pupil.trim(), admissionNo = admission, className = cls, parentName = parent,
            term = term, items = emptyList(), amountPaid = amount.toAmount() ?: 0.0, dueDate = null,
            paymentMethod = method, paymentRef = ref, invoiceId = linked?.id, notes = notes, chatId = sendTo,
            createdBy = conn.displayName,
        )
        vm.create(body, onCreated)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (receipt) "New receipt" else "New invoice") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White,
                navigationIconContentColor = Color.White))
    }) { pad ->
        if (catalogue == null && error == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Invoice", "Receipt").forEachIndexed { i, label ->
                    SegmentedButton((i == 1) == receipt, { receipt = i == 1 }, SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Gold, activeContentColor = Navy)) { Text(label) }
                }
            }
            if (receipt) {
                Picker("Paying invoice", linked?.let { "${it.number} · ${it.pupilName} · owes ${money(it.balance)}" } ?: "Not linked to an invoice",
                    listOf<Pair<String, () -> Unit>>("Not linked to an invoice" to { linked = null }) +
                        invoices.map { inv -> "${inv.number} · ${inv.pupilName} · owes ${money(inv.balance)}" to { fillFrom(inv) } })
            }
            Field("Pupil's full name", pupil) { pupil = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Field("Admission no.", admission) { admission = it } }
                Box(Modifier.weight(1f)) { Field("Class", cls) { cls = it } }
            }
            Quick(catalogue?.classes, cls) { cls = it }
            Field("Term", term) { term = it }
            Quick(catalogue?.terms, term) { term = it }
            Field("Parent / guardian", parent) { parent = it }

            if (!receipt) {
                Text("Fee lines", style = MaterialTheme.typography.titleSmall, color = Navy, modifier = Modifier.padding(top = 6.dp))
                lines.forEachIndexed { i, line ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(line.desc, { line.desc = it }, label = { Text("Description") }, singleLine = true,
                            modifier = Modifier.weight(1.6f))
                        OutlinedTextField(line.amount, { line.amount = it }, label = { Text(cur) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (lines.size > 1) lines.removeAt(i) else { line.desc = ""; line.amount = "" } }) {
                            Icon(Icons.Outlined.Close, "Remove line")
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    catalogue?.feeItems.orEmpty().filter { item -> lines.none { it.desc == item.name } }.forEach { item ->
                        AssistChip(onClick = {
                            val blank = lines.firstOrNull { it.desc.isBlank() }
                            if (blank != null) { blank.desc = item.name; if (item.amount != null) blank.amount = item.amount.toString() }
                            else lines.add(LineInput(item.name, item.amount?.toString().orEmpty()))
                        }, label = { Text("+ ${item.name}") })
                    }
                    AssistChip(onClick = { lines.add(LineInput()) }, label = { Text("+ Other") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { Field("Already paid", paid, KeyboardType.Decimal) { paid = it } }
                    Box(Modifier.weight(1f)) { Field("Due date (YYYY-MM-DD)", due) { due = it } }
                }
                Text("Total $cur ${money(total)}  ·  balance $cur ${money(total - (paid.toAmount() ?: 0.0))}",
                     style = MaterialTheme.typography.titleMedium, color = Navy)
            } else {
                Field("Amount received ($cur)", amount, KeyboardType.Decimal) { amount = it }
                Text("Paid by", style = MaterialTheme.typography.labelMedium, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    catalogue?.paymentMethods.orEmpty().forEach { m ->
                        FilterChip(method == m, { method = m }, label = { Text(m) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy))
                    }
                }
                Field("Reference (EcoCash, bank or slip no.)", ref) { ref = it }
            }

            Picker("Send the PDF on WhatsApp to", chats.firstOrNull { it.id == sendTo }?.name ?: "Don't send, just make the PDF",
                listOf<Pair<String, () -> Unit>>("Don't send, just make the PDF" to { sendTo = null }) +
                    chats.map { c -> c.name to { sendTo = c.id } })
            Field("Note on the document (optional)", notes) { notes = it }
            error?.let { Text(it, color = Bad) }
            Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy)) {
                if (busy) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = Navy, strokeWidth = 2.dp)
                else Text(if (receipt) "Create receipt" else "Create invoice")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth())

/** One-tap suggestions under a text field (classes, terms). */
@Composable
private fun Quick(values: List<String>?, current: String, onPick: (String) -> Unit) {
    if (values.isNullOrEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { v ->
            FilterChip(v == current, { onPick(v) }, label = { Text(v) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Gold, selectedLabelColor = Navy))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, shown: String, options: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(shown, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }) {
            options.forEach { (text, pick) -> DropdownMenuItem(text = { Text(text) }, onClick = { pick(); open = false }) }
        }
    }
}
