package zw.royalcrest.connect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zw.royalcrest.connect.data.Connection
import java.util.Locale

/** The signed-in server connection, for every screen below the setup screen. */
val LocalConnection = compositionLocalOf<Connection> { error("not connected") }

val AUDIENCES = listOf("parent" to "Parents", "group" to "Groups", "staff" to "Staff",
                       "enquiry" to "Enquiries", "other" to "Other")

fun audienceLabel(a: String?): String? = when (a) {
    "parent" -> "Parent"; "group" -> "Group"; "staff" -> "Staff"
    "enquiry" -> "Enquiry"; "other" -> "Other"; else -> null
}

fun money(v: Double?): String = String.format(Locale.US, "%,.2f", v ?: 0.0)

/** "2026-10-13T17:52:00.000000+02:00" → "13 Oct, 17:52" (already in local time). */
fun shortTime(iso: String?): String {
    if (iso == null || iso.length < 16) return ""
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val m = iso.substring(5, 7).toIntOrNull() ?: return iso
    return "${iso.substring(8, 10).trimStart('0')} ${months[m - 1]}, ${iso.substring(11, 16)}"
}

@Composable
fun Tag(text: String, bg: Color = MaterialTheme.colorScheme.primaryContainer, fg: Color = Navy) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = fg,
         modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Navy, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center)
            if (action != null) Button(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(action) }
        }
    }
}
