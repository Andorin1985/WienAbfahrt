package com.rwa.wienerlinien.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rwa.wienerlinien.data.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class StopRepository(private val context: Context) {

    private val gson = Gson()
    private val stopListType = object : TypeToken<List<Stop>>() {}.type

    // 5-minute call timeout per file – the OGD server is very slow (~2 KB/s)
    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    companion object {
        private const val BASE = "https://www.wienerlinien.at/ogd_realtime/doku/ogd"
        const val URL_STOPS  = "$BASE/wienerlinien-ogd-haltepunkte.csv"
        const val URL_LINES  = "$BASE/wienerlinien-ogd-linien.csv"
        const val URL_ROUTES = "$BASE/wienerlinien-ogd-fahrwegverlaeufe.csv"
    }

    // In-memory cache – cleared after a successful update
    @Volatile
    private var cachedStops: List<Stop>? = null

    private val updatedFile: File
        get() = File(context.filesDir, "haltestellen_mit_linien.json")

    /**
     * Searches stops by name (contains, case-insensitive) or by stopId prefix (if query is all digits).
     * Results are sorted: exact match first, starts-with second, contains last; alphabetically within each group.
     */
    suspend fun search(query: String): List<Stop> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        val stops = getStops() ?: return@withContext emptyList()
        if (q.all { it.isDigit() }) {
            stops.filter { it.stopId.startsWith(q) }.sortedBy { it.stopId }
        } else {
            stops
                .filter { it.name.contains(q, ignoreCase = true) }
                .sortedWith(compareBy(
                    {
                        when {
                            it.name.equals(q, ignoreCase = true) -> 0
                            it.name.startsWith(q, ignoreCase = true) -> 1
                            else -> 2
                        }
                    },
                    { it.name }
                ))
        }
    }

    suspend fun findNearest(lat: Double, lon: Double): Pair<Stop, Int>? =
        withContext(Dispatchers.Default) {
            val stops = getStops() ?: return@withContext null
            var nearest: Stop? = null
            var minDist = Double.MAX_VALUE
            for (stop in stops) {
                val d = haversine(lat, lon, stop.lat, stop.lon)
                if (d < minDist) {
                    minDist = d
                    nearest = stop
                }
            }
            nearest?.let { Pair(it, minDist.toInt()) }
        }

    suspend fun findNearby(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 500,
        maxResults: Int = 20
    ): List<Pair<Stop, Int>> = withContext(Dispatchers.Default) {
        val stops = getStops() ?: return@withContext emptyList()
        stops
            .mapNotNull { stop ->
                val d = haversine(lat, lon, stop.lat, stop.lon).toInt()
                if (d <= radiusMeters) Pair(stop, d) else null
            }
            .sortedBy { it.second }
            .take(maxResults)
    }

    suspend fun getAllStops(): List<Stop> = withContext(Dispatchers.Default) {
        (getStops() ?: emptyList()).sortedBy { it.name }
    }

    suspend fun getAllLines(): List<String> = withContext(Dispatchers.Default) {
        val stops = getStops() ?: return@withContext emptyList()
        stops.flatMap { it.lines }.distinct().sortedWith(compareBy { lineSortKey(it) })
    }

    private fun lineSortKey(name: String): String {
        val prefix = when {
            name.matches(Regex("U\\d")) -> "0$name"
            name == "WLB"              -> "1WLB"
            name.matches(Regex("[A-Z]")) || name.matches(Regex("\\d{1,2}")) -> "2$name".padEnd(5)
            name.startsWith("S")       -> "3$name"
            name.startsWith("N")       -> "5$name"
            else                       -> "4$name"
        }
        return prefix
    }

    data class Stats(val stopCount: Int, val lineCount: Int)

    suspend fun getStats(): Stats? = withContext(Dispatchers.Default) {
        val stops = getStops() ?: return@withContext null
        Stats(
            stopCount = stops.size,
            lineCount = stops.flatMap { it.lines }.distinct().size
        )
    }

    /**
     * Downloads the three OGD CSV files, rebuilds the stops JSON and saves it.
     * Returns true on success, false on any error.
     */
    suspend fun downloadAndUpdate(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("StopRepository", "downloadAndUpdate: start")
            val stopsCsv  = fetch(URL_STOPS)  ?: run { Log.e("StopRepository", "stopsCsv fetch returned null"); return@withContext false }
            Log.d("StopRepository", "downloadAndUpdate: stopsCsv ok (${stopsCsv.length} chars)")
            val linesCsv  = fetch(URL_LINES)  ?: run { Log.e("StopRepository", "linesCsv fetch returned null"); return@withContext false }
            Log.d("StopRepository", "downloadAndUpdate: linesCsv ok")
            val routesCsv = fetch(URL_ROUTES) ?: run { Log.e("StopRepository", "routesCsv fetch returned null"); return@withContext false }
            Log.d("StopRepository", "downloadAndUpdate: routesCsv ok")
            val stops = buildStopsWithLines(stopsCsv, linesCsv, routesCsv)
            Log.d("StopRepository", "downloadAndUpdate: built ${stops.size} stops")
            if (stops.isEmpty()) return@withContext false
            replaceStopsFile(gson.toJson(stops))
            true
        } catch (e: Exception) {
            Log.e("StopRepository", "downloadAndUpdate: unexpected exception", e)
            false
        }
    }

    /** Replaces the stops file with freshly downloaded data and clears the in-memory cache. */
    fun replaceStopsFile(json: String) {
        updatedFile.writeText(json)
        cachedStops = null
    }

    private fun fetch(url: String): String? = try {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        if (response.isSuccessful) {
            response.body?.string()
        } else {
            Log.e("StopRepository", "fetch failed: HTTP ${response.code} for $url")
            null
        }
    } catch (e: Exception) {
        Log.e("StopRepository", "fetch exception for $url", e)
        null
    }

    private fun buildStopsWithLines(stopsCsv: String, linesCsv: String, routesCsv: String): List<Stop> {
        data class RawStop(val stopId: String, val name: String, val lat: Double, val lon: Double)

        val stopsById = mutableMapOf<String, RawStop>()
        val stopsHeader = mutableMapOf<String, Int>()
        stopsCsv.lineSequence().forEachIndexed { i, line ->
            val cols = line.split(";")
            if (i == 0) { cols.forEachIndexed { idx, col -> stopsHeader[col.trim().trimStart('\uFEFF')] = idx }; return@forEachIndexed }
            val id   = cols.getOrNull(stopsHeader["StopID"]    ?: -1)?.trim() ?: return@forEachIndexed
            val name = cols.getOrNull(stopsHeader["StopText"]   ?: -1)?.trim() ?: return@forEachIndexed
            val lon  = cols.getOrNull(stopsHeader["Longitude"] ?: -1)?.trim()?.toDoubleOrNull() ?: 0.0
            val lat  = cols.getOrNull(stopsHeader["Latitude"]  ?: -1)?.trim()?.toDoubleOrNull() ?: 0.0
            if (lat != 0.0 && lon != 0.0 && id.isNotEmpty()) stopsById[id] = RawStop(id, name, lat, lon)
        }

        val lineNameById = mutableMapOf<String, String>()
        val linesHeader = mutableMapOf<String, Int>()
        linesCsv.lineSequence().forEachIndexed { i, line ->
            val cols = line.split(";")
            if (i == 0) { cols.forEachIndexed { idx, col -> linesHeader[col.trim().trimStart('\uFEFF')] = idx }; return@forEachIndexed }
            val id   = cols.getOrNull(linesHeader["LineID"] ?: -1)?.trim() ?: return@forEachIndexed
            val name = cols.getOrNull(linesHeader["LineText"] ?: -1)?.trim() ?: return@forEachIndexed
            if (id.isNotEmpty() && name.isNotEmpty()) lineNameById[id] = name
        }

        val stopLines = mutableMapOf<String, MutableSet<String>>()
        val routesHeader = mutableMapOf<String, Int>()
        routesCsv.lineSequence().forEachIndexed { i, line ->
            val cols = line.split(";")
            if (i == 0) { cols.forEachIndexed { idx, col -> routesHeader[col.trim().trimStart('\uFEFF')] = idx }; return@forEachIndexed }
            val lineId = cols.getOrNull(routesHeader["LineID"]  ?: -1)?.trim() ?: return@forEachIndexed
            val stopId = cols.getOrNull(routesHeader["StopID"]  ?: -1)?.trim() ?: return@forEachIndexed
            val lineName = lineNameById[lineId] ?: return@forEachIndexed
            stopLines.getOrPut(stopId) { mutableSetOf() }.add(lineName)
        }

        return stopsById.values.mapNotNull { raw ->
            val lines = stopLines[raw.stopId] ?: return@mapNotNull null
            Stop(raw.stopId, raw.name, raw.lat, raw.lon, lines.toList())
        }
    }

    private suspend fun getStops(): List<Stop>? = withContext(Dispatchers.IO) {
        cachedStops?.let { return@withContext it }
        val json = when {
            updatedFile.exists() -> updatedFile.readText()
            downloadAndUpdate() -> updatedFile.readText()
            else -> runCatching {
                context.assets.open("haltestellen_mit_linien.json").bufferedReader().readText()
            }.getOrNull() ?: return@withContext null
        }
        val stops: List<Stop> = gson.fromJson(json, stopListType)
        cachedStops = stops
        stops
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(dLambda / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
