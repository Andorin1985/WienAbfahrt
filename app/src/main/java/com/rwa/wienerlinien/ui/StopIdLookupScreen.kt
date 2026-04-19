package com.rwa.wienerlinien.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.rwa.wienerlinien.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopIdLookupSheet(onDismiss: () -> Unit) {
    val vm: StopIdViewModel = viewModel()
    val query        by vm.query.collectAsState()
    val results      by vm.results.collectAsState()
    val mode         by vm.mode.collectAsState()
    val isGpsLoading by vm.isGpsLoading.collectAsState()

    val context         = LocalContext.current
    val keyboard        = LocalSoftwareKeyboardController.current
    val snackbarState   = remember { SnackbarHostState() }
    val scope           = rememberCoroutineScope()
    val fusedLocation   = remember { LocationServices.getFusedLocationProviderClient(context) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) triggerNearbyLookup(fusedLocation, vm)
    }

    fun onGpsClicked() {
        keyboard?.hide()
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ->
                triggerNearbyLookup(fusedLocation, vm)
            else -> locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun onCopy(stopId: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("stop_id", stopId))
        scope.launch {
            snackbarState.showSnackbar(
                context.getString(R.string.stop_id_copied, stopId),
                duration = SnackbarDuration.Short
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            vm.clearResults()
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbarState) }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.stop_id_lookup_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::onQueryChanged,
                        placeholder = { Text(stringResource(R.string.stop_id_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedIconButton(
                        onClick = ::onGpsClicked,
                        modifier = Modifier.size(56.dp)
                    ) {
                        if (isGpsLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                Icons.Outlined.MyLocation,
                                contentDescription = stringResource(R.string.stop_id_gps_button),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (mode is StopIdMode.Nearby && !isGpsLoading && results.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.stop_id_nearby_header),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }

                    if (!isGpsLoading && query.length >= 2 || mode is StopIdMode.Nearby) {
                        if (results.isEmpty() && !isGpsLoading) {
                            item {
                                Text(
                                    text = stringResource(R.string.stop_id_no_results),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    }

                    items(results) { (stop, distance) ->
                        StopIdRow(
                            name     = stop.name,
                            stopId   = stop.stopId,
                            lines    = stop.lines,
                            distance = distance,
                            onClick  = { onCopy(stop.stopId) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StopIdRow(
    name: String,
    stopId: String,
    lines: List<String>,
    distance: Int?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (distance != null) {
                    Text(
                        text = stringResource(R.string.stop_id_distance_m, distance),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = "ID: $stopId",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (lines.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    lines.take(8).forEach { line ->
                        Box(
                            modifier = Modifier
                                .background(lineColor(line), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun triggerNearbyLookup(
    fusedLocation: com.google.android.gms.location.FusedLocationProviderClient,
    vm: StopIdViewModel
) {
    try {
        val cts = CancellationTokenSource()
        fusedLocation.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location != null) vm.findNearby(location.latitude, location.longitude)
                else vm.clearResults()
            }
            .addOnFailureListener { vm.clearResults() }
    } catch (e: SecurityException) {
        vm.clearResults()
    }
}
