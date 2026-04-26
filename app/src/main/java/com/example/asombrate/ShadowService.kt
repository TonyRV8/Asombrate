package com.example.asombrate

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import java.net.URI
import java.util.concurrent.TimeUnit

interface OrsApiService {
    @POST("directions")
    @Headers("Accept: application/json")
    suspend fun getDirections(
        @Body request: RouteRequest
    ): Response<DirectionsResponse>

    @POST("geocode")
    suspend fun geocode(
        @Body request: GeocodeSearchRequest
    ): GeocodeResponse

    @POST("reverse-geocode")
    suspend fun reverseGeocode(
        @Body request: ReverseGeocodeRequest
    ): GeocodeResponse
}

object RetrofitClient {
    private const val DEFAULT_DEBUG_BACKEND_URL = "http://10.0.2.2:8081/"
    private val placeholderHosts = listOf(
        "your-backend.example.com",
        "tu-backend-publico.com"
    )

    private fun isPlaceholderHost(raw: String): Boolean {
        return placeholderHosts.any { raw.contains(it, ignoreCase = true) }
    }

    private fun requireValidBaseUrl(raw: String): String {
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        val parsed = try {
            URI(normalized)
        } catch (_: Exception) {
            null
        } ?: throw IllegalStateException("BACKEND_BASE_URL invalida: $normalized")

        val host = parsed.host?.lowercase().orEmpty()
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val isLocalHost = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"

        check(host.isNotBlank()) { "BACKEND_BASE_URL invalida: host faltante." }
        check(scheme == "http" || scheme == "https") { "BACKEND_BASE_URL debe usar http o https." }

        if (!BuildConfig.DEBUG) {
            check(scheme == "https") { "Release requiere backend HTTPS." }
            check(!isLocalHost) { "Release no puede usar backend local." }
            check(!isPlaceholderHost(normalized)) { "Release no puede usar backend placeholder." }
        }

        return normalized
    }

    private fun resolveBaseUrl(): String {
        val configured = BuildConfig.BACKEND_BASE_URL.trim()
        val looksLikePlaceholder = isPlaceholderHost(configured)

        val raw = if (BuildConfig.DEBUG && (configured.isBlank() || looksLikePlaceholder)) {
            DEFAULT_DEBUG_BACKEND_URL
        } else {
            configured
        }

        return requireValidBaseUrl(raw)
    }

    private val BASE_URL: String by lazy {
        resolveBaseUrl()
    }

    // Fase 2: cliente HTTP con timeouts explícitos y conexión más tolerante.
    // Los reintentos por 429/5xx/timeout se aplican a nivel suspend en el ViewModel
    // (retryingCall) para que respeten el ciclo de vida de las corrutinas.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("Accept", "application/json")
                        .header(
                            "User-Agent",
                            "Asombrate-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})"
                        )
                        .header("X-Asombrate-App-Version", BuildConfig.VERSION_NAME)
                        .build()
                    chain.proceed(request)
                }
            )
            .build()
    }

    val instance: OrsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OrsApiService::class.java)
    }
}
