package com.example.asombrate

import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

/** Mensaje para el usuario + detalle de debug. */
data class UserError(val userMessage: String, val debugDetail: String)

/**
 * Clasificador puro de excepciones de red en mensajes entendibles.
 * No depende de Android ni Context.
 */
object NetworkErrorClassifier {

    fun classify(t: Throwable): UserError {
        return when (t) {
            is SocketTimeoutException -> UserError(
                "Tiempo de espera agotado al consultar la ruta. Intenta de nuevo.",
                "Timeout: ${t.message}"
            )
            is UnknownHostException -> UserError(
                "Sin conexión a Internet o servicio no disponible.",
                "UnknownHost: ${t.message}"
            )
            is HttpException -> classifyHttp(t)
            is IOException -> UserError(
                "Problema de red. Revisa tu conexión e inténtalo de nuevo.",
                "IO: ${t.message}"
            )
            else -> UserError(
                "Ocurrió un error inesperado al calcular la ruta.",
                "${t::class.java.simpleName}: ${t.message}"
            )
        }
    }

    fun classifyHttp(e: HttpException): UserError {
        val code = e.code()
        val msg = when (code) {
            401, 403 -> "API key inválida o sin permisos para este servicio."
            404 -> "No se encontró información para esa ubicación."
            408 -> "El servidor tardó demasiado en responder."
            429 -> "Demasiadas solicitudes al servidor. Espera unos segundos e inténtalo de nuevo."
            in 500..599 -> "El servicio de rutas está teniendo problemas. Intenta más tarde."
            else -> "Error del servicio ($code)."
        }
        return UserError(msg, "HTTP $code: ${e.message()}")
    }

    /** ¿Vale la pena reintentar este error? */
    fun isTransient(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException -> true
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
