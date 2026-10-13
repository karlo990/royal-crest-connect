package zw.royalcrest.connect.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.net.URI

private val Context.store by preferencesDataStore(name = "rca_settings")

data class SavedServer(val url: String, val key: String, val name: String)

/** Server address, access key and the staff member's name, kept on the phone. */
class Settings(private val context: Context) {
    private val urlKey = stringPreferencesKey("server_url")
    private val apiKey = stringPreferencesKey("api_key")
    private val nameKey = stringPreferencesKey("display_name")

    suspend fun load(): SavedServer? {
        val p = context.store.data.first()
        val url = p[urlKey] ?: return null
        return SavedServer(url, p[apiKey].orEmpty(), p[nameKey].orEmpty())
    }

    suspend fun save(s: SavedServer) {
        context.store.edit {
            it[urlKey] = s.url
            it[apiKey] = s.key
            it[nameKey] = s.name
        }
    }

    suspend fun clear() { context.store.edit { it.clear() } }

    companion object {
        /** "royalcrest.hf.space" → "https://royalcrest.hf.space/". Plain
         * http:// is only allowed for an address on the local network. */
        fun normalize(raw: String): Result<String> {
            var url = raw.trim()
            if (url.isEmpty()) return Result.failure(IllegalArgumentException("Type the server address."))
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = (if (isLocal(url.substringBefore('/').substringBefore(':'))) "http://" else "https://") + url
            }
            val host = try { URI(url).host } catch (_: Exception) { null }
                ?: return Result.failure(IllegalArgumentException("That doesn't look like an address."))
            if (url.startsWith("http://") && !isLocal(host)) {
                return Result.failure(IllegalArgumentException("Use https:// for a server on the internet."))
            }
            return Result.success(if (url.endsWith("/")) url else "$url/")
        }

        private fun isLocal(host: String): Boolean {
            if (host == "localhost" || host.endsWith(".local")) return true
            val p = host.split('.').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
        }
    }
}
