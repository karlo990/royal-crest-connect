package zw.royalcrest.connect.data

import com.google.gson.JsonParser
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One function per server endpoint the app uses (server/app/routers). */
interface Api {
    @GET("api/health") suspend fun health(): Health
    @GET("api/auth/check") suspend fun authCheck(): Map<String, Any>

    @GET("api/chats") suspend fun chats(): List<Chat>
    @PATCH("api/chats/{id}/audience") suspend fun setAudience(@Path("id") id: String, @Body body: AudienceIn): Map<String, Any?>
    @GET("api/chats/{id}/messages") suspend fun messages(@Path("id") id: String): List<Message>
    /** Staff replies go on the priority queue so they never wait behind a broadcast. */
    @POST("api/reply/{id}") suspend fun reply(@Path("id") id: String, @Body body: SendMessageIn): Message

    @Multipart
    @POST("api/chats/{id}/images")
    suspend fun sendImage(
        @Path("id") id: String,
        @Part("sender") sender: RequestBody,
        @Part("caption") caption: RequestBody,
        @Part file: MultipartBody.Part,
    ): Message

    @POST("api/chats/{id}/polls") suspend fun createPoll(@Path("id") id: String, @Body body: PollIn): Message
    @POST("api/broadcast") suspend fun broadcast(@Body body: BroadcastIn): BroadcastOut
    @POST("api/broadcast/poll") suspend fun broadcastPoll(@Body body: BroadcastPollIn): BroadcastOut
    @GET("api/polls") suspend fun polls(@Query("limit") limit: Int = 50): List<Poll>

    @GET("api/fees/catalogue") suspend fun catalogue(): Catalogue
    @GET("api/fees") suspend fun fees(@Query("kind") kind: String? = null, @Query("q") q: String? = null,
                                      @Query("chat_id") chatId: String? = null): List<FeeDoc>
    @GET("api/fees/balances") suspend fun balances(): List<Balance>
    @POST("api/fees") suspend fun createFee(@Body body: FeeDocIn): FeeDoc
    @GET("api/fees/{id}") suspend fun fee(@Path("id") id: String): FeeDoc
    @POST("api/fees/{id}/send") suspend fun sendFee(@Path("id") id: String, @Body body: SendDocIn): Message
    @POST("api/fees/{id}/retry") suspend fun retryFee(@Path("id") id: String): FeeDoc
}

/** The connection to one server: its address, the API key on every request,
 * and the same HTTP client for images and PDF downloads. */
class Connection(val baseUrl: String, apiKey: String, val displayName: String) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)          // a sleeping Hugging Face Space takes a while
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (apiKey.isBlank()) req else req.newBuilder().header("Authorization", "Bearer $apiKey").build())
        }
        .build()

    val api: Api = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(http)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(Api::class.java)

    /** "/static/…" paths from the server, as full URLs. */
    fun absolute(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }
}

/** A sentence a person can act on, from any failure of an API call. */
fun Throwable.friendly(): String = when (this) {
    is HttpException -> {
        val detail = try {
            val body = response()?.errorBody()?.string().orEmpty()
            val el = JsonParser.parseString(body).asJsonObject.get("detail")
            when {
                el == null -> null
                el.isJsonPrimitive -> el.asString
                el.isJsonArray && el.asJsonArray.size() > 0 -> el.asJsonArray[0].asJsonObject.get("msg")?.asString
                else -> null
            }
        } catch (_: Exception) { null }
        when (code()) {
            401 -> "The access key is wrong. Check it under Settings."
            else -> detail ?: "The server said no (${code()})."
        }
    }
    is IOException -> "Can't reach the server. Check the internet connection and try again."
    else -> message ?: "Something went wrong."
}
