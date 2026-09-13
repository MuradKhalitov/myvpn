package com.myvpn.android.data

import java.math.BigDecimal
import java.net.URI
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

object DecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("Decimal", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): BigDecimal =
        (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content.toBigDecimal()
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
}

@Serializable data class TariffResponse(
    val id: String, val code: String, val name: String, val description: String? = null,
    val durationDays: Int, @Serializable(with = DecimalSerializer::class) val price: BigDecimal, val currency: String
)
@Serializable data class CreateCheckoutRequest(val tariffCode: String)
@Serializable data class CheckoutResponse(
    val paymentOrderId: String, val tariffName: String,
    @Serializable(with = DecimalSerializer::class) val amount: BigDecimal,
    val currency: String, val durationDays: Int, val status: String,
    val confirmationUrl: String? = null, val expiresAt: String? = null
)
@Serializable data class PaymentStatusResponse(
    val outcome: String, val paymentOrderId: String? = null, val paymentStatus: String? = null,
    val activationStatus: String? = null, val paidAt: String? = null,
    val nextCheckAt: String? = null, val expiresAt: String? = null
)

interface PaymentSource {
    suspend fun tariffs(): List<TariffResponse>
    suspend fun checkout(code: String): CheckoutResponse
    suspend fun current(): PaymentStatusResponse
}

class PaymentRepository(private val api: MyVpnApi, private val auth: PhoneAuthRepository) : PaymentSource {
    override suspend fun tariffs() = authorized { api.tariffs(it) }
    override suspend fun checkout(code: String) = authorized { api.checkout(it, CreateCheckoutRequest(code)) }
    override suspend fun current() = authorized { api.currentPayment(it) }

    private suspend fun <T> authorized(request: suspend (String) -> T): T {
        val token = auth.accessToken()
        return try { request("Bearer $token") } catch (failure: HttpException) {
            if (failure.code() != 401 && failure.code() != 403) throw failure
            request("Bearer ${auth.refreshAfterUnauthorized(token)}")
        }
    }
}

internal fun validCheckoutUrl(value: String?): Boolean = value != null && runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)
