package com.rwa.wienerlinien.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rwa.wienerlinien.WienerLinienApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StoerungenViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = (application as WienerLinienApplication).departureRepository

    private val _state = MutableStateFlow<StoerungenState>(StoerungenState.Loading)
    val state: StateFlow<StoerungenState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    init {
        startPolling(showLoadingSpinner = true)
    }

    fun refresh() = startPolling(showLoadingSpinner = true)

    private fun startPolling(showLoadingSpinner: Boolean) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var first = showLoadingSpinner
            while (true) {
                if (first) {
                    _state.value = StoerungenState.Loading
                    first = false
                }
                val result = repo.getAllDisruptions()
                _state.value = if (result != null) {
                    StoerungenState.Success(disruptions = result.first, asOf = result.second)
                } else {
                    // Keep last successful data visible on transient errors
                    if (_state.value is StoerungenState.Success) _state.value
                    else StoerungenState.Error
                }
                delay(60_000L)
            }
        }
    }
}
