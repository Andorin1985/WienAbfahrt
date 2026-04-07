package com.rwa.wienerlinien.data.api

import com.google.gson.annotations.SerializedName

// ── Monitor (departures) ─────────────────────────────────────────────────────

data class MonitorResponse(
    val data: MonitorData?,
    val message: ApiMessage?
)

data class MonitorData(
    val monitors: List<Monitor>?
)

data class Monitor(
    val locationStop: LocationStop?,
    val lines: List<MonitorLine>?
)

data class LocationStop(
    val properties: StopProperties?
)

data class StopProperties(
    val name: String?,
    val title: String?,
    val municipality: String?
)

data class MonitorLine(
    val name: String?,
    val towards: String?,
    val barrierFree: Boolean?,
    val departures: Departures?
)

data class Departures(
    val departure: List<DepartureItem>?
)

data class DepartureItem(
    val departureTime: DepartureTime?
)

data class DepartureTime(
    val timePlanned: String?,
    val timeReal: String?,
    val countdown: Int?
)

data class ApiMessage(
    val value: String?,
    val messageCode: Int?,
    val serverTime: String?
)

// ── Traffic disruptions ──────────────────────────────────────────────────────

data class TrafficInfoResponse(
    val data: TrafficInfoData?
)

data class TrafficInfoData(
    val trafficInfos: List<TrafficInfoItem>?
)

data class TrafficInfoItem(
    val refTrafficInfoCategoryId: Int?,
    val name: String?,
    val title: String?,
    val description: String?,
    val attributes: TrafficAttributes?
)

data class TrafficAttributes(
    // The API returns relatedLines as either a String or List<String>
    // We handle both via a custom deserializer in the repository
    val relatedLines: Any?
)
