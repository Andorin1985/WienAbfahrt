package com.rwa.wienerlinien.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rwa.wienerlinien.WienerLinienApplication
import com.rwa.wienerlinien.data.model.Stop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class StopIdMode {
    object Text : StopIdMode()
    object Nearby : StopIdMode()
}

class StopIdViewModel(application: Application) : AndroidViewModel(application) {

    private val stopRepo = (application as WienerLinienApplication).stopRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<Pair<Stop, Int?>>>(emptyList())
    val results: StateFlow<List<Pair<Stop, Int?>>> = _results.asStateFlow()

    private val _mode = MutableStateFlow<StopIdMode>(StopIdMode.Text)
    val mode: StateFlow<StopIdMode> = _mode.asStateFlow()

    private val _isGpsLoading = MutableStateFlow(false)
    val isGpsLoading: StateFlow<Boolean> = _isGpsLoading.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(value: String) {
        _query.value = value
        _mode.value = StopIdMode.Text
        searchJob?.cancel()
        if (value.length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(500)
            _results.value = stopRepo.search(value).map { Pair(it, null) }
        }
    }

    fun findNearby(lat: Double, lon: Double) {
        searchJob?.cancel()
        _isGpsLoading.value = true
        _mode.value = StopIdMode.Nearby
        _query.value = ""
        viewModelScope.launch {
            val nearby = stopRepo.findNearby(lat, lon)
            _results.value = nearby
            _isGpsLoading.value = false
        }
    }

    fun clearResults() {
        searchJob?.cancel()
        _query.value = ""
        _results.value = emptyList()
        _mode.value = StopIdMode.Text
        _isGpsLoading.value = false
    }

    // ── Stops list sheet ──────────────────────────────────────────────────────

    private val _allStops = MutableStateFlow<List<Stop>>(emptyList())
    private val _stopsQuery = MutableStateFlow("")
    val stopsQuery: StateFlow<String> = _stopsQuery.asStateFlow()

    val filteredStops: StateFlow<List<Stop>> = combine(_allStops, _stopsQuery) { stops, q ->
        if (q.isBlank()) stops
        else stops.filter {
            it.name.contains(q, ignoreCase = true) || it.stopId.startsWith(q)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadAllStops() {
        if (_allStops.value.isNotEmpty()) return
        viewModelScope.launch { _allStops.value = stopRepo.getAllStops() }
    }

    fun onStopsQueryChanged(value: String) { _stopsQuery.value = value }

    // ── Lines list sheet ──────────────────────────────────────────────────────

    private val _allLines = MutableStateFlow<List<String>>(emptyList())
    private val _linesQuery = MutableStateFlow("")
    val linesQuery: StateFlow<String> = _linesQuery.asStateFlow()

    val filteredLines: StateFlow<List<String>> = combine(_allLines, _linesQuery) { lines, q ->
        if (q.isBlank()) lines
        else lines.filter { it.contains(q, ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun loadAllLines() {
        if (_allLines.value.isNotEmpty()) return
        viewModelScope.launch { _allLines.value = stopRepo.getAllLines() }
    }

    fun onLinesQueryChanged(value: String) { _linesQuery.value = value }

    override fun onCleared() {
        super.onCleared()
        searchJob?.cancel()
    }
}
