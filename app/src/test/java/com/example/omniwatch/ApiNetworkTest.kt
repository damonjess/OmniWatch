package com.example.omniwatch

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ApiNetworkTest {

    @Test
    fun testTflJamCamsApi() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.tfl.gov.uk")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(TrafficCameraApi::class.java)

        val cameras = kotlinx.coroutines.runBlocking { api.getLiveCameras() }
        val validFeeds = cameras.filter { place ->
            val img = place.additionalProperties?.firstOrNull { it.key == "imageUrl" }?.value
            !img.isNullOrBlank()
        }

        assertTrue("Expected live CCTV feeds with image URLs", validFeeds.isNotEmpty())
    }

    @Test
    fun testNationalHighwaysWebtrisApi() {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Ocp-Apim-Subscription-Key", BuildConfig.TRAFFIC_API_KEY)
                        .build()
                )
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://webtris.nationalhighways.co.uk")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(NationalHighwaysApi::class.java)

        val response = kotlinx.coroutines.runBlocking { api.getWebtrisSites() }
        val sites = response.sites.orEmpty()

        assertTrue("Expected Webtris sites from National Highways API", sites.isNotEmpty())
    }
}
