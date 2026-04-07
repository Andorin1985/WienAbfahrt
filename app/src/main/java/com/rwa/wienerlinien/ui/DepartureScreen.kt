package com.rwa.wienerlinien.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.rwa.wienerlinien.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepartureScreen(vm: DepartureViewModel = viewModel()) {
    val stopIdInput    by vm.stopIdInput.collectAsState()
    val departureState by vm.departureState.collectAsState()
    val gpsState       by vm.gpsState.collectAsState()
    val searchResults  by vm.searchResults.collectAsState()
    val gpsDeclined    by vm.gpsDeclined.collectAsState()

    val context        = LocalContext.current
    val keyboard       = LocalSoftwareKeyboardController.current
    val fusedLocation  = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Location permission
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) getLastLocation(fusedLocation, vm)
        else vm.onGpsPermissionDenied()
    }

    fun onFindNearest() {
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ->
                getLastLocation(fusedLocation, vm)
            else -> locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── GPS + Search ───────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!gpsDeclined) {
                        OutlinedButton(
                            onClick = ::onFindNearest,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.ui_find_nearest))
                        }

                        when (val g = gpsState) {
                            is GpsState.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(R.string.gps_searching),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is GpsState.Found -> Text(
                                text = "${g.stopName}  ·  ${stringResource(R.string.gps_distance, g.distanceMeters)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            is GpsState.Error -> Text(
                                text = when (g.type) {
                                    GpsErrorType.NO_STOP_FOUND -> stringResource(R.string.gps_no_stop_found)
                                    GpsErrorType.LOAD_ERROR    -> stringResource(R.string.gps_load_error)
                                    GpsErrorType.UNAVAILABLE   -> stringResource(R.string.gps_unavailable)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            else -> {}
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ExposedDropdownMenuBox(
                            expanded = searchResults.isNotEmpty(),
                            onExpandedChange = {},
                            modifier = Modifier.weight(1f)
                        ) {
                            OutlinedTextField(
                                value = stopIdInput,
                                onValueChange = vm::onStopIdChanged,
                                label = { Text(stringResource(R.string.ui_stop_id_label)) },
                                placeholder = { Text(stringResource(R.string.ui_stop_id_placeholder)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Go
                                ),
                                keyboardActions = KeyboardActions(onGo = {
                                    keyboard?.hide()
                                    vm.onShowClicked()
                                }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryEditable),
                                shape = RoundedCornerShape(8.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = searchResults.isNotEmpty(),
                                onDismissRequest = { vm.onStopIdChanged("") }
                            ) {
                                searchResults.forEach { stop ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(stop.name, fontWeight = FontWeight.Medium)
                                                if (stop.lines.isNotEmpty()) {
                                                    Text(
                                                        stop.lines.take(6).joinToString(" · "),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        },
                                        trailingIcon = {
                                            Text(
                                                "ID ${stop.stopId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        onClick = {
                                            keyboard?.hide()
                                            vm.onSearchResultSelected(stop)
                                        }
                                    )
                                }
                            }
                        }
                        if (departureState is DepartureState.Loading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    keyboard?.hide()
                                    vm.onShowClicked()
                                },
                                modifier = Modifier.fillMaxHeight(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.ui_show))
                            }
                        }
                    }
                }
            }

            // ── Content ────────────────────────────────────────────────────────
            when (val state = departureState) {
                is DepartureState.Idle -> {}

                is DepartureState.Loading -> item {
                    Text(
                        text = stringResource(R.string.ui_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                is DepartureState.Error -> item { ErrorBanner(state) }

                is DepartureState.Success -> {
                    // Cache notice
                    if (state.isFromCache) {
                        item {
                            InfoBanner(
                                text = "${stringResource(R.string.alert_showing_cache)}\n" +
                                       stringResource(R.string.alert_as_of, state.asOf),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    // Stop header
                    item {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 4.dp)
                        ) {
                            Text(
                                text = state.stopName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "ID ${state.stopId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Disruptions
                    items(state.trafficInfos) { info -> DisruptionRow(info) }
                    if (state.trafficInfos.isNotEmpty()) {
                        item { Spacer(Modifier.height(4.dp)) }
                    }
                    // Lines
                    items(state.lines) { line -> LineRow(line) }
                }
            }

            // ── Footer ─────────────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(top = 24.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.footer_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.footer_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun getLastLocation(
    fusedLocation: com.google.android.gms.location.FusedLocationProviderClient,
    vm: DepartureViewModel
) {
    try {
        val cts = CancellationTokenSource()
        fusedLocation.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) vm.findNearestStop(location.latitude, location.longitude)
                else vm.onLocationUnavailable()
            }
            .addOnFailureListener { vm.onLocationUnavailable() }
    } catch (e: SecurityException) {
        vm.onGpsPermissionDenied()
    }
}

// ── Sub-composables ─────────────────────────────────────────────────────────────

@Composable
private fun LineRow(line: UiLine) {
    Column {
        // Header: badge + direction
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val badgeColor = lineColor(line.name)
            Box(
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = line.towards,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (line.barrierFree) {
                Text(
                    text = stringResource(R.string.barrier_free),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // All departure times – large, stacked vertically
        Column(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp)
                .padding(bottom = 20.dp)
        ) {
            val lineCol = lineColor(line.name)
            line.departures.forEach { dep -> DepartureItem(dep, lineCol) }
        }
    }
}

@Composable
private fun DepartureItem(departure: UiDeparture, color: Color) {
    val isCritical = departure.minutes == 0

    val infiniteTransition = rememberInfiniteTransition(label = "depart")
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .then(if (isCritical) Modifier.alpha(blinkAlpha) else Modifier),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = if (isCritical) "<1" else "${departure.minutes}",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isCritical) color else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "min",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        if (departure.time.isNotEmpty()) {
            Text(
                text = departure.time,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun DisruptionRow(info: UiTrafficInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                info.relatedLines.forEach { line ->
                    Box(
                        modifier = Modifier
                            .background(lineColor(line), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Text(
                    text = info.title.ifEmpty { stringResource(R.string.traffic_disruption_default) },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            if (info.description.isNotEmpty()) {
                Text(
                    text = info.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(state: DepartureState.Error) {
    val text = when (state.type) {
        DepartureState.ErrorType.API_LIMIT          -> stringResource(R.string.alert_api_limit)
        DepartureState.ErrorType.SOURCE_DOWN        ->
            stringResource(R.string.alert_source_down, state.args["code"] ?: "?")
        DepartureState.ErrorType.SOURCE_DOWN_GENERIC ->
            stringResource(R.string.alert_source_down_generic)
        DepartureState.ErrorType.NO_DEPARTURES      ->
            stringResource(R.string.alert_no_departures, state.args["stopId"] ?: "")
    }
    InfoBanner(text, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun InfoBanner(text: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp)
        )
    }
}

// ── Line color logic ──────────────────────────────────────────────────────────

internal fun lineColor(name: String): Color {
    // U-Bahn – official Wiener Linien brand colors
    when (name) {
        "U1" -> return Color(0xFFE2001A) // red
        "U2" -> return Color(0xFF9B27AF) // purple
        "U3" -> return Color(0xFFF47B20) // orange
        "U4" -> return Color(0xFF1BA350) // green
        "U5" -> return Color(0xFF46BCC6) // turquoise
        "U6" -> return Color(0xFF9E5A1B) // brown
    }
    return when {
        name.endsWith("A", ignoreCase = true) -> Color(0xFF1E63B0) // bus (10A, 12A …)
        name.startsWith("N")                  -> Color(0xFF1E63B0) // night bus
        name.startsWith("S")                  -> Color(0xFF003DA5) // S-Bahn
        name == "WLB"                         -> Color(0xFF1A7A3C) // Wiener Lokalbahn
        name.matches(Regex("[A-Z]"))           -> Color(0xFFE2001A) // named trams (D, O)
        name.matches(Regex("\\d{1,2}"))       -> Color(0xFFE2001A) // trams (1 – 99)
        else                                  -> Color(0xFF1E63B0) // buses / other
    }
}
