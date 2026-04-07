package com.rwa.wienerlinien.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rwa.wienerlinien.data.model.Favorite
import com.rwa.wienerlinien.data.api.MonitorResponse
import com.rwa.wienerlinien.data.api.TrafficInfoItem
import com.rwa.wienerlinien.data.api.WienerLinienApi
import com.rwa.wienerlinien.ui.DepartureState
import com.rwa.wienerlinien.ui.UiDeparture
import com.rwa.wienerlinien.ui.UiLine
import com.rwa.wienerlinien.ui.UiTrafficInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val KEY_STOP_ID              = stringPreferencesKey("last_stop_id")
private val KEY_FAVORITES            = stringPreferencesKey("favorites")
private val KEY_KEEP_SCREEN_ON       = booleanPreferencesKey("keep_screen_on")
private val KEY_LAST_MANUAL_UPDATE   = androidx.datastore.preferences.core.longPreferencesKey("last_manual_update")
private val KEY_THEME_MODE           = stringPreferencesKey("theme_mode")
private val KEY_GPS_DECLINED         = booleanPreferencesKey("gps_declined")

class DepartureRepository(
    private val context: Context,
    private val api: WienerLinienApi,
    private val dataStore: DataStore<Preferences>
) {
    private val gson = Gson()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val favoriteListType = object : TypeToken<List<Favorite>>() {}.type

    // ── Persistence ──────────────────────────────────────────────────────────

    suspend fun getSavedStopId(): String =
        dataStore.data.map { it[KEY_STOP_ID] ?: "" }.first()

    suspend fun saveStopId(stopId: String) {
        dataStore.edit { it[KEY_STOP_ID] = stopId }
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    suspend fun getFavorites(): List<Favorite> {
        val json = dataStore.data.map { it[KEY_FAVORITES] ?: "[]" }.first()
        return try { gson.fromJson(json, favoriteListType) } catch (e: Exception) { emptyList() }
    }

    suspend fun addFavorite(favorite: Favorite) {
        val list = getFavorites().toMutableList()
        if (list.none { it.stopId == favorite.stopId }) list.add(0, favorite)
        dataStore.edit { it[KEY_FAVORITES] = gson.toJson(list) }
    }

    suspend fun removeFavorite(stopId: String) {
        val list = getFavorites().filter { it.stopId != stopId }
        dataStore.edit { it[KEY_FAVORITES] = gson.toJson(list) }
    }

    suspend fun isFavorite(stopId: String): Boolean =
        getFavorites().any { it.stopId == stopId }

    suspend fun getKeepScreenOn(): Boolean =
        dataStore.data.map { it[KEY_KEEP_SCREEN_ON] ?: true }.first()

    suspend fun setKeepScreenOn(value: Boolean) {
        dataStore.edit { it[KEY_KEEP_SCREEN_ON] = value }
    }

    suspend fun getThemeMode(): String =
        dataStore.data.map { it[KEY_THEME_MODE] ?: "system" }.first()

    suspend fun setThemeMode(mode: String) {
        dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun getGpsDeclined(): Boolean =
        dataStore.data.map { it[KEY_GPS_DECLINED] ?: false }.first()

    suspend fun setGpsDeclined(value: Boolean) {
        dataStore.edit { it[KEY_GPS_DECLINED] = value }
    }

    suspend fun getLastManualUpdate(): Long =
        dataStore.data.map { it[KEY_LAST_MANUAL_UPDATE] ?: 0L }.first()

    suspend fun setLastManualUpdate(ts: Long) {
        dataStore.edit { it[KEY_LAST_MANUAL_UPDATE] = ts }
    }

    // ── Departures ───────────────────────────────────────────────────────────

    suspend fun getDepartures(stopId: String): DepartureState {
        val (monitor, monitorCode) = api.getMonitor(stopId)
        val (traffic, _) = api.getTrafficInfo()   // second request; rate limiter handles the gap

        return when {
            monitorCode == 200 && monitor != null -> {
                cacheWrite(stopId, monitor)
                mapSuccess(stopId, monitor, traffic?.data?.trafficInfos, fromCache = false)
            }

            monitorCode in 500..599 -> {
                val cached = cacheRead(stopId)
                if (cached != null) {
                    mapSuccess(stopId, cached.first, traffic?.data?.trafficInfos, fromCache = true, asOf = cached.second)
                } else {
                    DepartureState.Error(DepartureState.ErrorType.SOURCE_DOWN, mapOf("code" to monitorCode.toString()))
                }
            }

            monitorCode == 0 -> {
                // Network failure – serve stale cache if available
                val cached = cacheRead(stopId)
                if (cached != null) {
                    mapSuccess(stopId, cached.first, traffic?.data?.trafficInfos, fromCache = true, asOf = cached.second)
                } else {
                    DepartureState.Error(DepartureState.ErrorType.SOURCE_DOWN_GENERIC)
                }
            }

            else -> DepartureState.Error(DepartureState.ErrorType.API_LIMIT)
        }
    }

    // ── All disruptions (unfiltered by stop) ─────────────────────────────────

    /** Returns (list, asOf) on success, null on network/API error. */
    suspend fun getAllDisruptions(): Pair<List<UiTrafficInfo>, String>? {
        val (traffic, code) = api.getTrafficInfo()
        if (code == 0 || traffic == null) return null
        val list = traffic.data?.trafficInfos
            ?.filter { it.refTrafficInfoCategoryId == 2 }
            ?.map { info ->
                UiTrafficInfo(
                    title = info.title ?: "",
                    description = info.description ?: "",
                    relatedLines = relatedLines(info)
                )
            }
            ?.sortedWith(compareBy({ linePriority(it.relatedLines) }, { it.title }))
            ?: emptyList()
        return Pair(list, java.time.LocalTime.now().format(timeFmt))
    }

    /**
     * Sort key for a disruption: the highest-priority (lowest number) line type
     * among all affected lines. U-Bahn first, then trams, then buses/other.
     */
    private fun linePriority(lines: List<String>): Int =
        lines.minOfOrNull { lineTypePriority(it) } ?: Int.MAX_VALUE

    private fun lineTypePriority(name: String): Int = when {
        name.matches(Regex("U[1-6]", RegexOption.IGNORE_CASE))  -> 0  // U-Bahn
        name.matches(Regex("[A-Z]"))                             -> 1  // named trams (D, O …)
        name.matches(Regex("\\d{1,2}"))                         -> 2  // numbered trams
        name.startsWith("S", ignoreCase = true)                 -> 3  // S-Bahn
        name == "WLB"                                           -> 4  // Wiener Lokalbahn
        name.startsWith("N", ignoreCase = true)                 -> 5  // night buses
        else                                                     -> 6  // buses / other
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private fun mapSuccess(
        stopId: String,
        monitor: MonitorResponse,
        trafficInfos: List<TrafficInfoItem>?,
        fromCache: Boolean,
        asOf: String = java.time.LocalTime.now().format(timeFmt)
    ): DepartureState {
        val monitors = monitor.data?.monitors ?: emptyList()
        if (monitors.isEmpty()) return DepartureState.Error(DepartureState.ErrorType.NO_DEPARTURES, mapOf("stopId" to stopId))

        val stopName = monitors.firstOrNull()?.locationStop?.properties?.title ?: stopId

        val uiLines = monitors.flatMap { m ->
            m.lines?.map { line ->
                val departures = line.departures?.departure
                    ?.mapNotNull { dep ->
                        val minutes = dep.departureTime?.countdown ?: return@mapNotNull null
                        UiDeparture(minutes = minutes, time = formatTime(dep.departureTime.timeReal, dep.departureTime.timePlanned))
                    }
                    ?.take(6)
                    ?: emptyList()
                UiLine(
                    name = line.name ?: "?",
                    towards = line.towards ?: "",
                    departures = departures,
                    barrierFree = line.barrierFree ?: false
                )
            } ?: emptyList()
        }

        if (uiLines.isEmpty()) return DepartureState.Error(DepartureState.ErrorType.NO_DEPARTURES, mapOf("stopId" to stopId))

        // Only real disruptions (refTrafficInfoCategoryId == 2), no elevators (1) or notices (3)
        val lineNames = uiLines.map { it.name.uppercase() }.toSet()
        val uiDisruptions = trafficInfos
            ?.filter { info -> info.refTrafficInfoCategoryId == 2 }
            ?.filter { info ->
                val related = relatedLines(info)
                related.isNotEmpty() && related.any { it.uppercase() in lineNames }
            }
            ?.map { info ->
                UiTrafficInfo(
                    title = info.title ?: "",
                    description = info.description ?: "",
                    relatedLines = relatedLines(info)
                )
            } ?: emptyList()

        return DepartureState.Success(
            stopName = stopName,
            stopId = stopId,
            lines = uiLines,
            trafficInfos = uiDisruptions,
            isFromCache = fromCache,
            asOf = asOf
        )
    }

    private fun formatTime(timeReal: String?, timePlanned: String?): String {
        val raw = timeReal?.takeIf { it.isNotEmpty() } ?: timePlanned?.takeIf { it.isNotEmpty() } ?: return ""
        return try {
            // Try ISO 8601 with colon offset (+02:00); if that fails, insert colon for RFC 822 (+0200)
            val odt = try {
                OffsetDateTime.parse(raw)
            } catch (e: Exception) {
                OffsetDateTime.parse(raw.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2"))
            }
            odt.atZoneSameInstant(ZoneId.systemDefault()).format(timeFmt)
        } catch (e: Exception) { "" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun relatedLines(info: TrafficInfoItem): List<String> {
        val raw = info.attributes?.relatedLines
        if (raw != null) {
            val fromAttr = when (raw) {
                is List<*> -> raw.filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }
                is String  -> raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                else       -> emptyList()
            }
            if (fromAttr.isNotEmpty()) return fromAttr
        }
        // Fallback: API doesn't populate relatedLines, but titles follow "LINENAME: Beschreibung"
        val colonIdx = info.title?.indexOf(':') ?: -1
        if (colonIdx > 0) {
            val candidate = info.title!!.substring(0, colonIdx).trim()
            if (candidate.isNotEmpty()) return listOf(candidate)
        }
        return emptyList()
    }

    // ── File cache (same logic as PHP: store JSON + timestamp) ───────────────

    private fun cacheFile(stopId: String) = File(context.cacheDir, "monitor_$stopId.json")

    private data class CacheEntry(val response: MonitorResponse, val time: String)

    private fun cacheWrite(stopId: String, response: MonitorResponse) {
        val entry = mapOf(
            "ts" to System.currentTimeMillis(),
            "asOf" to java.time.LocalTime.now().format(timeFmt),
            "data" to response
        )
        cacheFile(stopId).writeText(gson.toJson(entry))
    }

    /** Returns (response, asOf-string) if cache is ≤ 5 minutes old, else null. */
    private fun cacheRead(stopId: String): Pair<MonitorResponse, String>? {
        val file = cacheFile(stopId)
        if (!file.exists()) return null
        return try {
            val raw = gson.fromJson(file.readText(), Map::class.java)
            val ts = (raw["ts"] as? Double)?.toLong() ?: return null
            if (System.currentTimeMillis() - ts > 5 * 60 * 1000) return null
            val asOf = raw["asOf"] as? String ?: java.time.Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(timeFmt)
            val dataJson = gson.toJson(raw["data"])
            val response = gson.fromJson(dataJson, MonitorResponse::class.java)
            Pair(response, asOf)
        } catch (e: Exception) {
            null
        }
    }
}
