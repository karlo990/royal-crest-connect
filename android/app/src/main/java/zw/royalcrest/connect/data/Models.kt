package zw.royalcrest.connect.data

import com.google.gson.annotations.SerializedName

// Shapes of the Royal Crest Connect server's JSON (server/). Gson
// fills these by reflection and ignores Kotlin defaults, so anything the
// server may leave out is nullable.

data class Health(
    val status: String?,
    val service: String?,
    @SerializedName("key_required") val keyRequired: Boolean?,
)

data class Chat(
    val id: String,
    val name: String,
    @SerializedName("profile_pic_url") val profilePicUrl: String?,
    @SerializedName("wa_name") val waName: String?,
    val audience: String?,
    @SerializedName("last_text") val lastText: String?,
    @SerializedName("last_at") val lastAt: String?,
    @SerializedName("last_direction") val lastDirection: String?,
    @SerializedName("is_unsaved") val isUnsaved: Boolean?,
)

data class PollOption(val id: String, val text: String, val votes: Int?)

data class Message(
    val id: String,
    @SerializedName("chat_id") val chatId: String,
    val sender: String?,
    val kind: String?,
    val text: String?,
    @SerializedName("poll_id") val pollId: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("media_url") val mediaUrl: String?,
    val direction: String?,
    @SerializedName("wa_status") val waStatus: String?,
    val options: List<PollOption>?,
)

data class SendMessageIn(val sender: String, val text: String)

data class AudienceIn(val audience: String?)

data class BroadcastIn(
    val sender: String,
    val text: String,
    @SerializedName("chat_ids") val chatIds: List<String> = emptyList(),
    val audiences: List<String> = emptyList(),
)

data class BroadcastOut(
    @SerializedName("sent_to") val sentTo: Int?,
    @SerializedName("skipped_duplicate") val skippedDuplicate: Int?,
)

data class PollIn(
    val sender: String,
    val question: String,
    val options: List<String>,
    @SerializedName("send_to_whatsapp") val sendToWhatsapp: Boolean = true,
)

data class BroadcastPollIn(
    val sender: String,
    val question: String,
    val options: List<String>,
    @SerializedName("chat_ids") val chatIds: List<String> = emptyList(),
    val audiences: List<String> = emptyList(),
)

data class Poll(
    val id: String,
    @SerializedName("chat_id") val chatId: String,
    val question: String,
    @SerializedName("chat_name") val chatName: String?,
    @SerializedName("created_at") val createdAt: String?,
    val options: List<PollOption>?,
    val votes: Int?,
)

data class FeeItem(val name: String, val amount: Double?)

data class Catalogue(
    val name: String?,
    val currency: String?,
    val terms: List<String>?,
    val classes: List<String>?,
    @SerializedName("fee_items") val feeItems: List<FeeItem>?,
    @SerializedName("payment_methods") val paymentMethods: List<String>?,
)

data class FeeLine(val description: String, val amount: Double)

data class FeeDocIn(
    val kind: String,
    @SerializedName("pupil_name") val pupilName: String,
    @SerializedName("admission_no") val admissionNo: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("parent_name") val parentName: String?,
    val term: String?,
    val items: List<FeeLine>,
    @SerializedName("amount_paid") val amountPaid: Double,
    @SerializedName("due_date") val dueDate: String?,
    @SerializedName("payment_method") val paymentMethod: String?,
    @SerializedName("payment_ref") val paymentRef: String?,
    @SerializedName("invoice_id") val invoiceId: String?,
    val notes: String?,
    @SerializedName("chat_id") val chatId: String?,
    @SerializedName("created_by") val createdBy: String,
)

data class FeeDoc(
    val id: String,
    val kind: String,
    val number: String,
    @SerializedName("chat_id") val chatId: String?,
    @SerializedName("chat_name") val chatName: String?,
    @SerializedName("pupil_name") val pupilName: String?,
    @SerializedName("admission_no") val admissionNo: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("parent_name") val parentName: String?,
    val term: String?,
    val items: List<FeeLine>?,
    val total: Double?,
    @SerializedName("amount_paid") val amountPaid: Double?,
    val balance: Double?,
    val currency: String?,
    @SerializedName("due_date") val dueDate: String?,
    @SerializedName("payment_method") val paymentMethod: String?,
    @SerializedName("payment_ref") val paymentRef: String?,
    @SerializedName("invoice_number") val invoiceNumber: String?,
    val status: String?,
    @SerializedName("pdf_url") val pdfUrl: String?,
    @SerializedName("error_message") val errorMessage: String?,
    @SerializedName("created_at") val createdAt: String?,
)

data class SendDocIn(@SerializedName("chat_id") val chatId: String?, val sender: String)

data class Balance(
    @SerializedName("pupil_name") val pupilName: String?,
    @SerializedName("admission_no") val admissionNo: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("chat_id") val chatId: String?,
    val currency: String?,
    val invoiced: Double?,
    val paid: Double?,
    val balance: Double?,
)
