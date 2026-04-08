package com.example.asombrate

import okhttp3.MediaType
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TtlCacheTest {

    @Test
    fun `put y get devuelven valor hasta que expira`() {
        var now = 0L
        val cache = TtlCache<String, String>(ttlMillis = 1000L, clock = { now })
        cache.put("a", "1")
        now = 500L
        assertEquals("1", cache.get("a"))
        now = 2000L
        assertNull(cache.get("a"))
    }

    @Test
    fun `LRU evicta el mas antiguo al superar maxEntries`() {
        val cache = TtlCache<String, String>(ttlMillis = 60_000L, maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `get refresca orden LRU`() {
        val cache = TtlCache<String, String>(ttlMillis = 60_000L, maxEntries = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        // tocar "a" => "b" pasa a ser el más antiguo
        cache.get("a")
        cache.put("c", "3")
        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}

class NetworkErrorClassifierTest {

    private fun httpException(code: Int): HttpException {
        val body = ResponseBody.create(MediaType.parse("application/json"), "")
        return HttpException(Response.error<Any>(code, body))
    }

    @Test
    fun `timeout es transitorio y mensaje amable`() {
        val t = SocketTimeoutException("boom")
        val err = NetworkErrorClassifier.classify(t)
        assertTrue(NetworkErrorClassifier.isTransient(t))
        assertEquals(R.string.error_timeout, err.userMessage.resId)
    }

    @Test
    fun `unknown host NO es transitorio`() {
        val t = UnknownHostException("no dns")
        assertFalse(NetworkErrorClassifier.isTransient(t))
        val err = NetworkErrorClassifier.classify(t)
        assertEquals(R.string.error_no_connection, err.userMessage.resId)
    }

    @Test
    fun `429 es transitorio y mensaje menciona rate limit`() {
        val e = httpException(429)
        assertTrue(NetworkErrorClassifier.isTransient(e))
        assertEquals(R.string.error_rate_limit, NetworkErrorClassifier.classify(e).userMessage.resId)
    }

    @Test
    fun `500 es transitorio`() {
        val e = httpException(503)
        assertTrue(NetworkErrorClassifier.isTransient(e))
    }

    @Test
    fun `401 NO es transitorio`() {
        val e = httpException(401)
        assertFalse(NetworkErrorClassifier.isTransient(e))
        assertEquals(R.string.error_auth_api_key, NetworkErrorClassifier.classify(e).userMessage.resId)
    }
}

class MapMoveThresholdTest {

    @Test
    fun `movimiento nulo inicial siempre dispara`() {
        assertTrue(MapMoveThreshold.shouldTrigger(null, LatLng(19.0, -99.0)))
    }

    @Test
    fun `micromovimiento bajo umbral no dispara`() {
        val prev = LatLng(19.4326, -99.1332)
        // +0.00001 lat ~ 1m
        val curr = LatLng(19.43261, -99.13321)
        assertFalse(MapMoveThreshold.shouldTrigger(prev, curr))
    }

    @Test
    fun `movimiento mayor a 25 metros dispara`() {
        val prev = LatLng(19.4326, -99.1332)
        // +0.001 lat ~ 111m
        val curr = LatLng(19.4336, -99.1332)
        assertTrue(MapMoveThreshold.shouldTrigger(prev, curr))
    }
}
