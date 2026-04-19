package com.rwa.wienerlinien.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rwa.wienerlinien.WienerLinienApplication
import com.rwa.wienerlinien.data.model.Favorite
import com.rwa.wienerlinien.data.model.Stop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class DepartureViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as WienerLinienApplication
    private val departureRepo = app.departureRepository
    private val stopRepo = app.stopRepository

    private val _stopIdInput = MutableStateFlow("")
    val stopIdInput: StateFlow<String> = _stopIdInput.asStateFlow()

    private val _departureState = MutableStateFlow<DepartureState>(DepartureState.Idle)
    val departureState: StateFlow<DepartureState> = _departureState.asStateFlow()

    private val _gpsState = MutableStateFlow<GpsState>(GpsState.Idle)
    val gpsState: StateFlow<GpsState> = _gpsState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Stop>>(emptyList())
    val searchResults: StateFlow<List<Stop>> = _searchResults.asStateFlow()

    private val _favorites = MutableStateFlow<List<Favorite>>(emptyList())
    val favorites: StateFlow<List<Favorite>> = _favorites.asStateFlow()

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _themeMode = MutableStateFlow("system")
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _gpsDeclined = MutableStateFlow(false)
    val gpsDeclined: StateFlow<Boolean> = _gpsDeclined.asStateFlow()

    private val _stopStats = MutableStateFlow<com.rwa.wienerlinien.data.repository.StopRepository.Stats?>(null)
    val stopStats: StateFlow<com.rwa.wienerlinien.data.repository.StopRepository.Stats?> = _stopStats.asStateFlow()

    private val _stopUpdateState = MutableStateFlow<StopUpdateState>(StopUpdateState.Idle)
    val stopUpdateState: StateFlow<StopUpdateState> = _stopUpdateState.asStateFlow()

    private val _canManualUpdate = MutableStateFlow(true)
    val canManualUpdate: StateFlow<Boolean> = _canManualUpdate.asStateFlow()

    private var refreshJob: Job? = null
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            loadFavorites()
            _keepScreenOn.value = departureRepo.getKeepScreenOn()
            _themeMode.value = departureRepo.getThemeMode()
            _gpsDeclined.value = departureRepo.getGpsDeclined()
            _stopStats.value = stopRepo.getStats()
            val last = departureRepo.getLastManualUpdate()
            _canManualUpdate.value = System.currentTimeMillis() - last >= 24 * 60 * 60 * 1000L
            val saved = departureRepo.getSavedStopId()
            if (saved.isNotEmpty()) {
                _stopIdInput.value = saved
                startPolling(saved)
            }
        }
    }

    // ── Input & search ───────────────────────────────────────────────────────

    fun onStopIdChanged(value: String) {
        _stopIdInput.value = value
        searchJob?.cancel()
        if (value.length < 2) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(500) // debounce
            _searchResults.value = stopRepo.search(value)
        }
    }

    fun onSearchResultSelected(stop: Stop) {
        _stopIdInput.value = stop.stopId
        _searchResults.value = emptyList()
        _gpsState.value = GpsState.Idle
        viewModelScope.launch { departureRepo.saveStopId(stop.stopId) }
        startPolling(stop.stopId)
    }

    fun onShowClicked() {
        val id = _stopIdInput.value.trim()
        if (id.isEmpty()) return
        _searchResults.value = emptyList()
        _gpsState.value = GpsState.Idle
        viewModelScope.launch { departureRepo.saveStopId(id) }
        startPolling(id)
    }

    // ── GPS ──────────────────────────────────────────────────────────────────

    fun findNearestStop(lat: Double, lon: Double) {
        _gpsState.value = GpsState.Searching
        viewModelScope.launch {
            val result = stopRepo.findNearest(lat, lon)
            if (result == null) {
                _gpsState.value = GpsState.Error(GpsErrorType.NO_STOP_FOUND)
                return@launch
            }
            val (stop, distanceMeters) = result
            _stopIdInput.value = stop.stopId
            _searchResults.value = emptyList()
            departureRepo.saveStopId(stop.stopId)
            _gpsState.value = GpsState.Found(stop.name, distanceMeters)
            startPolling(stop.stopId)
        }
    }

    fun triggerStopUpdate() {
        if (!_canManualUpdate.value || _stopUpdateState.value is StopUpdateState.Running) return
        _stopUpdateState.value = StopUpdateState.Running
        _canManualUpdate.value = false
        viewModelScope.launch {
            // 15 min safety timeout – the OGD server is very slow (~2 KB/s per file)
            val success = withTimeoutOrNull(15 * 60_000L) {
                stopRepo.downloadAndUpdate()
            }
            if (success == true) {
                departureRepo.setLastManualUpdate(System.currentTimeMillis())
                _stopUpdateState.value = StopUpdateState.Success
            } else {
                _stopUpdateState.value = StopUpdateState.Error
                _canManualUpdate.value = true
            }
        }
    }

    fun onGpsPermissionDenied() {
        _gpsDeclined.value = true
        viewModelScope.launch { departureRepo.setGpsDeclined(true) }
    }

    fun setGpsDeclined(value: Boolean) {
        _gpsDeclined.value = value
        viewModelScope.launch { departureRepo.setGpsDeclined(value) }
    }

    fun onLocationUnavailable() {
        _gpsState.value = GpsState.Error(GpsErrorType.UNAVAILABLE)
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    fun toggleFavorite() {
        val state = _departureState.value as? DepartureState.Success ?: return
        viewModelScope.launch {
            if (_isFavorite.value) {
                departureRepo.removeFavorite(state.stopId)
            } else {
                departureRepo.addFavorite(Favorite(state.stopId, state.stopName))
            }
            _isFavorite.value = !_isFavorite.value
            loadFavorites()
        }
    }

    fun removeFavorite(stopId: String) {
        viewModelScope.launch {
            departureRepo.removeFavorite(stopId)
            loadFavorites()
            if ((_departureState.value as? DepartureState.Success)?.stopId == stopId) {
                _isFavorite.value = false
            }
        }
    }

    fun setKeepScreenOn(value: Boolean) {
        _keepScreenOn.value = value
        viewModelScope.launch { departureRepo.setKeepScreenOn(value) }
    }

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        viewModelScope.launch { departureRepo.setThemeMode(mode) }
    }

    fun navigateToStop(stopId: String) {
        _stopIdInput.value = stopId
        _searchResults.value = emptyList()
        _gpsState.value = GpsState.Idle
        viewModelScope.launch { departureRepo.saveStopId(stopId) }
        startPolling(stopId)
    }

    fun onFavoriteClicked(favorite: Favorite) {
        _stopIdInput.value = favorite.stopId
        _searchResults.value = emptyList()
        _gpsState.value = GpsState.Idle
        viewModelScope.launch { departureRepo.saveStopId(favorite.stopId) }
        startPolling(favorite.stopId)
    }

    private suspend fun loadFavorites() {
        _favorites.value = departureRepo.getFavorites()
    }

    // ── Polling ──────────────────────────────────────────────────────────────

    private fun startPolling(stopId: String) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var firstIteration = true
            while (true) {
                // Always show spinner on first load; silent refresh when data is already visible
                if (firstIteration || _departureState.value !is DepartureState.Success) {
                    _departureState.value = DepartureState.Loading
                }
                firstIteration = false
                val state = departureRepo.getDepartures(stopId)
                _departureState.value = state
                // Update favorite star
                _isFavorite.value = departureRepo.isFavorite(stopId)
                val interval = if (state is DepartureState.Success && !state.isFromCache) 30_000L else 60_000L
                delay(interval)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        searchJob?.cancel()
    }
}
