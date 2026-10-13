package zw.royalcrest.connect.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import zw.royalcrest.connect.data.Balance
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.FeeDoc
import zw.royalcrest.connect.data.friendly

class FeesVm(private val conn: Connection) : ViewModel() {
    val docs = MutableStateFlow<List<FeeDoc>?>(null)
    val balances = MutableStateFlow<List<Balance>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)
    var query = ""

    init { viewModelScope.launch { while (true) { load(); delay(8_000) } } }

    suspend fun load() {
        try {
            docs.value = conn.api.fees(q = query.ifBlank { null })
            balances.value = conn.api.balances().filter { (it.balance ?: 0.0) > 0.0 }
            error.value = null
        } catch (e: Exception) { error.value = e.friendly() }
    }

    fun refresh() = viewModelScope.launch { refreshing.value = true; load(); refreshing.value = false }
}

private val TABS = listOf("Invoices", "Receipts", "Balances")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeesScreen(onNew: () -> Unit, onOpen: (String) -> Unit) {
    val conn = LocalConnection.current
    val vm: FeesVm = viewModel(key = "fees-" + conn.baseUrl) { FeesVm(conn) }
    val docs by vm.docs.collectAsState()
    val balances by vm.balances.collectAsState()
    val error by vm.error.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { delay(300); vm.query = query; vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Fees") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White))
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = Gold, contentColor = Navy,
                icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)) {
                TABS.forEachIndexed { i, label ->
                    SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, TABS.size),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = Gold, activeContentColor = Navy)) { Text(label) }
                }
            }
            if (tab < 2) OutlinedTextField(query, { query = it }, placeholder = { Text("Pupil, parent or admission no.") },
                singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            error?.let { Text(it, color = Bad, modifier = Modifier.padding(16.dp, 4.dp)) }
            PullToRefreshBox(refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                if (tab == 2) {
                    if (balances.isEmpty()) EmptyState("No balances owing", "Every invoice is paid in full.")
                    else LazyColumn(Modifier.fillMaxSize()) {
                        items(balances, key = { (it.admissionNo ?: "") + it.pupilName }) { b ->
                            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(b.pupilName ?: "", style = MaterialTheme.typography.titleMedium)
                                    Text(listOfNotNull(b.className, b.admissionNo).joinToString(" · ") +
                                         " · paid ${money(b.paid)} of ${money(b.invoiced)}",
                                         style = MaterialTheme.typography.bodySmall, color = Muted)
                                }
                                Text("${b.currency} ${money(b.balance)}", style = MaterialTheme.typography.titleMedium, color = Bad)
                            }
                            HorizontalDivider(color = Line)
                        }
                    }
                } else {
                    val kind = if (tab == 0) "invoice" else "receipt"
                    val shown = docs?.filter { it.kind == kind }
                    when {
                        shown == null -> {}
                        shown.isEmpty() -> EmptyState(if (tab == 0) "No invoices yet" else "No receipts yet",
                            "Tap New to make one. The school PC turns it into a PDF and can send it to the parent on WhatsApp.")
                        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                            items(shown, key = { it.id }) { d -> FeeRow(d) { onOpen(d.id) }; HorizontalDivider(color = Line) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeeRow(d: FeeDoc, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.pupilName ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1,
                     overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                StatusTag(d)
            }
            Text(listOfNotNull(d.number, d.term, d.className).joinToString(" · "),
                 style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${d.currency} ${money(if (d.kind == "receipt") d.amountPaid else d.total)}", style = MaterialTheme.typography.titleSmall)
            if (d.kind == "invoice" && (d.balance ?: 0.0) > 0) Text("owes ${money(d.balance)}", style = MaterialTheme.typography.bodySmall, color = Bad)
            else if (d.kind == "invoice") Text("paid", style = MaterialTheme.typography.bodySmall, color = Good)
        }
    }
}

@Composable
fun StatusTag(d: FeeDoc) = when (d.status) {
    "ready" -> Tag("PDF ready", bg = Color(0xFFE3F1E8), fg = Good)
    "error" -> Tag("PDF failed", bg = Color(0xFFF8E1DE), fg = Bad)
    else -> Tag("Making PDF…", bg = Color(0xFFFFF1C2))
}
