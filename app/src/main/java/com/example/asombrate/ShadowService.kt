package com.example.asombrate

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface OrsApiService {
    @POST("v2/directions/driving-car")
    @Headers("Accept: application/json")
    suspend fun getDirections(
        @Header("Authorization") apiKey: String,
        @Body request: RouteRequest
    ): DirectionsResponse

    @GET("geocode/search")
    suspend fun geocode(
        @Query("text") text: String,
        @Query("api_key") apiKey: String,
        @Query("size") size: Int = 5
    ): GeocodeResponse

    @GET("geocode/reverse")
    suspend fun reverseGeocode(
        @Query("point.lat") lat: Double,
        @Query("point.lon") lon: Double,
        @Query("api_key") apiKey: String,
        @Query("size") size: Int = 1
    ): GeocodeResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://api.openrouteservice.org/"

    // Fase 2: cliente HTTP con timeouts explícitos y conexión más tolerante.
    // Los reintentos por 429/5xx/timeout se aplican a nivel suspend en el ViewModel
    // (retryingCall) para que respeten el ciclo de vida de las corrutinas.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
