package com.rwa.wienerlinien.data.model

data class Stop(
    val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String> = emptyList()
)
