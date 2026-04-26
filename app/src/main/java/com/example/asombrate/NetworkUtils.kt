package com.example.asombrate

import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.net.ConnectException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.math.pow

/** Cache en memoria con TTL por entrada, thread-safe básico. */
class TtlCache<K, V>(
    private val ttlMillis: Long,
    private val maxEntries: Int = 64,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = LinkedHashMap<K, Entry<V>>()

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (entry.expiresAt <= clock()) {
            map.remove(key)
            return null
        }
        // refresca LRU
        map.remove(key)
        map[key] = entry
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        if (map.size >= maxEntries && !map.containsKey(key)) {
            val first = map.entries.iterator()
            if (first.hasNext()) {
                first.next()
                first.remove()
            }
        }
        map[key] = Entry(value, clock() + ttlMillis)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size
}

/** Mensaje para el usuario + detalle de debug + estado de servicio. */
data class UserError(
    val userMessage: UiText,
    val debugDetail: String,
    val serviceMode: ServiceMode = ServiceMode.NORMAL
)

fun parseUsageStateHeader(raw: String?): ServiceMode? {
    return when (raw?.trim()?.uppercase()) {
        "NORMAL" -> ServiceMode.NORMAL
        "HIGH_USAGE" -> ServiceMode.HIGH_USAGE
        "DEGRADED" -> ServiceMode.DEGRADED
        "BLOCK" -> ServiceMode.BLOCK
        else -> null
    }
}

/**
 * Clasificador puro de excepciones de red en mensajes entendibles.
 * No depende de Android ni Context.
 */
object NetworkErrorClassifier {

    private val placeholderBackendHosts = listOf(
        "your-backend.example.com",
        "tu-backend-publico.com"
    )

    private fun isBackendBaseUrlMisconfigured(error: UnknownHostException): Boolean {
        val configuredBackend = BuildConfig.BACKEND_BASE_URL.trim()
        val message = error.message.orEmpty()
        if (placeholderBackendHosts.any { message.contains(it, ignoreCase = true) }) {
            return true
        }
        return message.isBlank() && placeholderBackendHosts.any {
            configuredBackend.contains(it, ignoreCase = true)
        }
    }

    private fun isBackendUnreachable(error: IOException): Boolean {
        val msg = error.message.orEmpty()
        val backend = BuildConfig.BACKEND_BASE_URL.trim()
        return error is ConnectException ||
            msg.contains("failed to connect", ignoreCase = true) ||
            msg.contains("connection refused", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) && backend.contains("10.0.2.2")
    }

    fun classify(t: Throwable, usageStateHeader: String? = null): UserError {
        return when (t) {
            is SocketTimeoutException -> UserError(
                UiText(R.string.error_timeout),
                "Timeout: ${t.message}",
                ServiceMode.TEMP_UNAVAILABLE
            )
            is SSLException -> UserError(
                UiText(R.string.error_secure_connection),
                "SSL: ${t.message}",
                ServiceMode.TEMP_UNAVAILABLE
            )
            is UnknownHostException -> UserError(
                if (isBackendBaseUrlMisconfigured(t)) {
                    UiText(R.string.error_backend_base_url_missing)
                } else {
                    UiText(R.string.error_no_connection)
                },
                "UnknownHost: ${t.message} [backend=${BuildConfig.BACKEND_BASE_URL}]",
                ServiceMode.TEMP_UNAVAILABLE
            )
            is HttpException -> classifyHttp(
                e = t,
                usageStateHeader = usageStateHeader ?: t.response()?.headers()?.get("X-Usage-State")
            )
            is IOException -> UserError(
                if (isBackendUnreachable(t)) {
                    UiText(R.string.error_backend_unreachable)
                } else {
                    UiText(R.string.error_network_generic)
                },
                "IO: ${t.message}",
                ServiceMode.TEMP_UNAVAILABLE
            )
            else -> UserError(
                UiText(R.string.error_unexpected),
                "${t::class.java.simpleName}: ${t.message}",
                ServiceMode.NORMAL
            )
        }
    }

    fun classifyHttp(e: HttpException, usageStateHeader: String? = null): UserError {
        val code = e.code()
        val headerMode = parseUsageStateHeader(usageStateHeader)

        if (headerMode == ServiceMode.BLOCK) {
            return UserError(
                UiText(R.string.error_quota_exceeded),
                "HTTP $code [X-Usage-State=BLOCK]: ${e.message()}",
                ServiceMode.BLOCK
            )
        }

        if (headerMode == ServiceMode.DEGRADED) {
            return UserError(
                UiText(R.string.error_degraded_mode),
                "HTTP $code [X-Usage-State=DEGRADED]: ${e.message()}",
                ServiceMode.DEGRADED
            )
        }

        if (headerMode == ServiceMode.NORMAL && code == 429) {
            return UserError(
                UiText(R.string.error_rate_limit),
                "HTTP $code [X-Usage-State=NORMAL]: ${e.message()}",
                ServiceMode.NORMAL
            )
        }

        val (msg, defaultMode) = when (code) {
            401, 403 -> UiText(R.string.error_auth_api_key) to ServiceMode.NORMAL
            404 -> UiText(R.string.error_not_found) to ServiceMode.NORMAL
            408 -> UiText(R.string.error_http_timeout) to ServiceMode.TEMP_UNAVAILABLE
            429 -> UiText(R.string.error_quota_exceeded) to ServiceMode.BLOCK
            in 500..599 -> UiText(R.string.error_server_5xx) to ServiceMode.TEMP_UNAVAILABLE
            else -> UiText(R.string.error_service_generic, listOf(code)) to ServiceMode.NORMAL
        }

        val mode = headerMode ?: defaultMode
        return UserError(msg, "HTTP $code: ${e.message()}", mode)
    }

    /** ¿Vale la pena reintentar este error? */
    fun isTransient(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException -> true
        is SSLException -> false
        is UnknownHostException -> false
        is HttpException -> {
            val c = t.code()
            c == 408 || c == 429 || c in 500..599
        }
        is IOException -> true
        else -> false
    }
}

/**
 * Reintentos acotados con backoff exponencial para operaciones suspendidas.
 * Solo reintenta si NetworkErrorClassifier.isTransient(throwable) == true.
 */
suspend fun <T> retryingCall(
    maxAttempts: Int = 3,
    initialBackoffMillis: Long = 400L,
    maxBackoffMillis: Long = 2000L,
    block: suspend () -> T
): T {
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt < maxAttempts) {
        try {
            return block()
        } catch (t: Throwable) {
            lastError = t
            if (!NetworkErrorClassifier.isTransient(t) || attempt == maxAttempts - 1) {
                throw t
            }
            val delayMs =
                min(maxBackoffMillis, (initialBackoffMillis * 2.0.pow(attempt.toDouble())).toLong())
            delay(delayMs)
            attempt++
        }
    }
    throw lastError ?: IllegalStateException("retryingCall: sin intentos")
}

/**
 * Umbral de distancia para decidir si un movimiento del mapa justifica una
 * llamada de reverse geocode nueva. Evita consumo innecesario de ORS.
 */
object MapMoveThreshold {
    const val MIN_METERS = 25.0

    /** true si el nuevo punto está lo suficientemente lejos del anterior. */
    fun shouldTrigger(previous: LatLng?, current: LatLng): Boolean {
        if (previous == null) return true
        val d = ShadowUtils.distanceMeters(
            Location(previous.lat, previous.lng),
            Location(current.lat, current.lng)
        )
        return d >= MIN_METERS
    }
}
