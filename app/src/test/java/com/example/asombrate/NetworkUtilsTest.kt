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
import java.net.ConnectException
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
    fun `unknown host del placeholder indica configuracion faltante`() {
        val t = UnknownHostException("your-backend.example.com")
        val err = NetworkErrorClassifier.classify(t)
        assertEquals(R.string.error_backend_base_url_missing, err.userMessage.resId)
    }

    @Test
    fun `connect exception muestra backend no disponible`() {
        val t = ConnectException("Failed to connect to /10.0.2.2:8081")
        val err = NetworkErrorClassifier.classify(t)
        assertEquals(R.string.error_backend_unreachable, err.userMessage.resId)
        assertEquals(ServiceMode.TEMP_UNAVAILABLE, err.serviceMode)
    }

    @Test
    fun `429 con BLOCK se trata como cuota agotada`() {
        val e = httpException(429)
        assertTrue(NetworkErrorClassifier.isTransient(e))
        val err = NetworkErrorClassifier.classifyHttp(e, "BLOCK")
        assertEquals(R.string.error_quota_exceeded, err.userMessage.resId)
        assertEquals(ServiceMode.BLOCK, err.serviceMode)
    }

    @Test
    fun `429 con header NORMAL mantiene rate limit generico`() {
        val e = httpException(429)
        val err = NetworkErrorClassifier.classifyHttp(e, "NORMAL")
        assertEquals(R.string.error_rate_limit, err.userMessage.resId)
        assertEquals(ServiceMode.NORMAL, err.serviceMode)
    }

    @Test
    fun `503 con header DEGRADED muestra mensaje degradado amigable`() {
        val e = httpException(503)
        val err = NetworkErrorClassifier.classifyHttp(e, "DEGRADED")
        assertEquals(R.string.error_degraded_mode, err.userMessage.resId)
        assertEquals(ServiceMode.DEGRADED, err.serviceMode)
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

class UsageStateHeaderParserTest {

    @Test
    fun `parsea NORMAL`() {
        assertEquals(ServiceMode.NORMAL, parseUsageStateHeader("NORMAL"))
    }

    @Test
    fun `parsea HIGH_USAGE`() {
        assertEquals(ServiceMode.HIGH_USAGE, parseUsageStateHeader("HIGH_USAGE"))
    }

    @Test
    fun `parsea DEGRADED`() {
        assertEquals(ServiceMode.DEGRADED, parseUsageStateHeader("DEGRADED"))
    }

    @Test
    fun `parsea BLOCK`() {
        assertEquals(ServiceMode.BLOCK, parseUsageStateHeader("BLOCK"))
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

class ShadowUtilsPolylineTest {

    @Test
    fun `decodePolyline decodifica una geometria real larga`() {
        val geometry = "akfuBl__}QAb@@V~@pCn@Sf@CtAGLFLE\\?lEh@`C`@b@CTGPG`Bo@LER@bAh@H?HE`@_A\\]HM^Wz@e@`AMf@Fl@DdA@NClAWZG`@IzAK^Er@\\V?DIPa@Lm@@c@KaAo@}D_@mBMUS{AYaC[cCOmA@q@BUJKhAO\\IHGLKHUPqAJa@p@m@`@I^E|AMZGd@]T_@f@i@f@e@vCyCXYl@aAp@eBZe@dAu@r@YHCrAc@nA@VFXBX?\\GZM\\YTa@Je@@[A]G[MYMSa@[_@Mc@Ek@]{@}@i@u@Ye@w@}A[]cAyAw@wAoB{Cc@c@iA}BmIsP_AkB}BsEq@kAg@m@q@g@]Ss@[[I[Gw@G[Cq@@aCPeCVsCTm@@u@Aq@Ci@IkAW}GsBoFeBcA_@[QiAw@{@cAm@{@gA}AiByC{CoFmByCiAeBiEmGy@sAoEoK_CwFoAsDcAiCc@cAsAaC{GoKwHcMiD}Fm@aA_A_BaBcEmAoDa@s@[_@k@g@kDiBu@_@u@_@]U{@kA[k@s@yA_@m@c@k@u@y@MSw@_BmAoCc@o@k@i@w@a@e@QaHcB_ASo@Es@?sABw@CYE_@Qu@m@c@e@Y_@OYKWI[G_A?cAJqB?]Cc@OcA}E}OYkAIu@J_E?i@Cc@Ga@iAuEq@sFM}@[iCM{AB{@TwDDqA@iAAy@EqAMyASkBeDkMSaAgA_G{A}Ge@uBMo@Qu@uA_GKg@e@oBUq@aA{CaByD_@aAKWO[q@sA{@}A}BsDyG}LsD{Gm@gAsC_F_ByC}EiJaAmBiA_Ci@oA_@{@O[Sa@Wo@s@}AkAkCQ]w@kBcBwDu@aBS_@_@u@Wg@GSkA_DoB{D{@kBaAkB}C}Gc@cAMe@Ww@wAkEo@aBGOkCqGeAkCe@mA]y@_A{BiAaCYo@c@cBgBcGeA_EMy@Iq@s@}G[}Ba@gDEy@@[BOGEeF}BqCoA_@CsAUSG_Bw@}@k@a@m@Wq@y@eG}@oFCU{@gGmBkN_AkHcAsHy@_GGy@KkAm@sEGi@MmCKa@Sa@q@kBOk@k@qEe@qDw@aFKs@c@sCa@sCSyAS_BS{A_@oCk@iESaBOaBBaCPeHFyBNsF?M\\iJTcDB]B_@h@qHBYV{DF}@RiDdAmPb@oHN}B_FY}D[OAgCQoDWuCU_BOiBOiDYgD[aCS]CK@WvD"

        val points = ShadowUtils.decodePolyline(geometry)

        assertTrue(points.size > 100)
        assertEquals(19.3711, points.first().lat, 0.001)
        assertEquals(-99.2871, points.first().lng, 0.001)
        assertEquals(19.43282, points.last().lat, 0.01)
        assertEquals(-99.1373, points.last().lng, 0.01)
    }
}
