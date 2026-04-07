package com.rwa.wienerlinien.ui

data class UiDeparture(
    val minutes: Int,
    val time: String  // formatted as "HH:mm", empty if unavailable
)

data class UiLine(
    val name: String,
    val towards: String,
    val departures: List<UiDeparture>,
    val barrierFree: Boolean
)

data class UiTrafficInfo(
    val title: String,
    val description: String,
    val relatedLines: List<String>
)

sealed class DepartureState {
    object Idle : DepartureState()
    object Loading : DepartureState()

    data class Success(
        val stopName: String,
        val stopId: String,
        val lines: List<UiLine>,
        val trafficInfos: List<UiTrafficInfo>,
        val isFromCache: Boolean,
        val asOf: String
    ) : DepartureState()

    data class Error(
        val type: ErrorType,
        val args: Map<String, String> = emptyMap()
    ) : DepartureState()

    enum class ErrorType {
        API_LIMIT, SOURCE_DOWN, SOURCE_DOWN_GENERIC, NO_DEPARTURES
    }
}

sealed class StopUpdateState {
    object Idle : StopUpdateState()
    object Running : StopUpdateState()
    object Success : StopUpdateState()
    object Error : StopUpdateState()
}

sealed class GpsState {
    object Idle : GpsState()
    object Searching : GpsState()
    data class Found(val stopName: String, val distanceMeters: Int) : GpsState()
    data class Error(val type: GpsErrorType) : GpsState()
}

enum class GpsErrorType { NO_STOP_FOUND, LOAD_ERROR, UNAVAILABLE }

sealed class StoerungenState {
    object Loading : StoerungenState()
    data class Success(val disruptions: List<UiTrafficInfo>, val asOf: String) : StoerungenState()
    object Error : StoerungenState()
}
