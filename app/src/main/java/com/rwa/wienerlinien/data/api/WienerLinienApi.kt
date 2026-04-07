package com.rwa.wienerlinien.data.api

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class WienerLinienApi(private val client: OkHttpClient) {

    private val gson = Gson()

    companion object {
        private const val BASE = "https://www.wienerlinien.at/ogd_realtime"
    }

    /** Returns the parsed response and the HTTP status code (0 on network error). */
    suspend fun getMonitor(stopId: String): Pair<MonitorResponse?, Int> =
        withContext(Dispatchers.IO) {
            get("$BASE/monitor?stopId=$stopId", MonitorResponse::class.java)
        }

    suspend fun getTrafficInfo(): Pair<TrafficInfoResponse?, Int> =
        withContext(Dispatchers.IO) {
            get("$BASE/trafficInfoList?categoryId=2", TrafficInfoResponse::class.java)
        }

    private fun <T> get(url: String, clazz: Class<T>): Pair<T?, Int> {
        return try {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            val code = response.code
            val body = response.body?.string()
            val parsed = if (!body.isNullOrEmpty()) gson.fromJson(body, clazz) else null
            Pair(parsed, code)
        } catch (e: Exception) {
            Pair(null, 0)
        }
    }
}
