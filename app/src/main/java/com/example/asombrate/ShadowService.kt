package com.example.asombrate

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

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
        @Query("size") size: Int = 1
    ): GeocodeResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://api.openrouteservice.org/"

    val instance: OrsApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OrsApiService::class.java)
    }
}
