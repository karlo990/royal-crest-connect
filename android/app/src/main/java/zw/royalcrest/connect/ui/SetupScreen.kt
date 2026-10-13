package zw.royalcrest.connect.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import zw.royalcrest.connect.R
import zw.royalcrest.connect.data.Connection
import zw.royalcrest.connect.data.SavedServer
import zw.royalcrest.connect.data.Settings
import zw.royalcrest.connect.data.friendly

/** Server address + access key + your name. Checks both before saving. */
@Composable
fun SetupScreen(initial: SavedServer?, onConnected: (SavedServer) -> Unit) {
    var url by remember { mutableStateOf(initial?.url?.removeSuffix("/").orEmpty()) }
    var key by remember { mutableStateOf(initial?.key.orEmpty()) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        error = null
        val base = Settings.normalize(url).getOrElse { error = it.message; return }
        if (name.isBlank()) { error = "Type your name. It shows on the replies you send, so colleagues know who answered."; return }
        busy = true
        scope.launch {
            try {
                val conn = Connection(base, key.trim(), name.trim())
                val health = conn.api.health()
                if (health.service != "royal-crest-connect") error("That address isn't a Royal Crest Connect server.")
                conn.api.authCheck()                          // 401 here = wrong key
                onConnected(SavedServer(base, key.trim(), name.trim()))
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Navy).statusBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.crest), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text("Royal Crest Connect", style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color.White)
        Text("SCIENCE · INNOVATION · SKILL", style = MaterialTheme.typography.labelMedium, color = Gold)
        Spacer(Modifier.height(24.dp))
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Connect to the school server", style = MaterialTheme.typography.titleLarge, color = Navy)
                Text("The address and access key come from whoever set up the server (see the setup guide on the website).",
                     style = MaterialTheme.typography.bodyMedium, color = Muted)
                OutlinedTextField(url, { url = it }, label = { Text("Server address") },
                    placeholder = { Text("royalcrest.hf.space") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                OutlinedTextField(key, { key = it }, label = { Text("Access key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next))
                OutlinedTextField(name, { name = it }, label = { Text("Your name") },
                    placeholder = { Text("e.g. Mrs Muzunze") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodyMedium) }
                Button(
                    onClick = ::connect, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Navy),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Navy, strokeWidth = 2.dp)
                    else Text("Connect")
                }
            }
        }
    }
}
