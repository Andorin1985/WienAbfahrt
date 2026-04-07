package com.rwa.wienerlinien.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rwa.wienerlinien.R

enum class AppTab { Abfahrt, Favoriten, Stoerungen, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: DepartureViewModel = viewModel()) {
    var selectedTab    by remember { mutableStateOf(AppTab.Abfahrt) }
    var showSettings   by remember { mutableStateOf(false) }

    val departureState by vm.departureState.collectAsState()
    val isFavorite     by vm.isFavorite.collectAsState()
    val keepScreenOn   by vm.keepScreenOn.collectAsState()

    // Apply keep-screen-on flag whenever the setting changes
    val activity = LocalContext.current as? Activity
    SideEffect {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            AppTab.Abfahrt    -> stringResource(R.string.ui_header)
                            AppTab.Favoriten  -> stringResource(R.string.nav_favoriten)
                            AppTab.Stoerungen -> stringResource(R.string.nav_stoerungen)
                            AppTab.Info       -> stringResource(R.string.nav_info)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    // Star — only on Abfahrt tab when a stop is loaded
                    if (selectedTab == AppTab.Abfahrt && departureState is DepartureState.Success) {
                        IconButton(onClick = vm::toggleFavorite) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                                contentDescription = null,
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // Settings
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.nav_einstellungen),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == AppTab.Abfahrt,
                    onClick  = { selectedTab = AppTab.Abfahrt },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.Abfahrt) Icons.Filled.LocationOn
                            else Icons.Outlined.LocationOn,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.nav_abfahrt)) }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.Favoriten,
                    onClick  = { selectedTab = AppTab.Favoriten },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.Favoriten) Icons.Filled.Star
                            else Icons.Outlined.Star,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.nav_favoriten)) }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.Stoerungen,
                    onClick  = { selectedTab = AppTab.Stoerungen },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.Stoerungen) Icons.Filled.Warning
                            else Icons.Outlined.Warning,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.nav_stoerungen)) }
                )
                NavigationBarItem(
                    selected = selectedTab == AppTab.Info,
                    onClick  = { selectedTab = AppTab.Info },
                    icon = {
                        Icon(
                            if (selectedTab == AppTab.Info) Icons.Filled.Info
                            else Icons.Outlined.Info,
                            contentDescription = null
                        )
                    },
                    label = { Text(stringResource(R.string.nav_info)) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                AppTab.Abfahrt    -> DepartureScreen(vm = vm)
                AppTab.Favoriten  -> FavoritenScreen(
                    vm = vm,
                    onStopSelected = { selectedTab = AppTab.Abfahrt }
                )
                AppTab.Stoerungen -> StoerungenScreen()
                AppTab.Info       -> InfoScreen()
            }
        }
    }

    // Settings bottom sheet
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            containerColor   = MaterialTheme.colorScheme.surface
        ) {
            SettingsContent(vm = vm, onDismiss = { showSettings = false })
        }
    }
}

@Composable
private fun SettingsContent(vm: DepartureViewModel, onDismiss: () -> Unit) {
    val keepScreenOn    by vm.keepScreenOn.collectAsState()
    val stopUpdateState by vm.stopUpdateState.collectAsState()
    val canUpdate       by vm.canManualUpdate.collectAsState()
    val themeMode       by vm.themeMode.collectAsState()
    val gpsDeclined     by vm.gpsDeclined.collectAsState()

    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = stringResource(R.string.nav_einstellungen),
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_keep_screen_on),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_keep_screen_on_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = keepScreenOn,
                onCheckedChange = vm::setKeepScreenOn,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_gps),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.settings_gps_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Switch(
                checked = !gpsDeclined,
                onCheckedChange = { vm.setGpsDeclined(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_theme),
                style = MaterialTheme.typography.bodyMedium
            )
            val themeModes = listOf("system", "light", "dark")
            val themeLabels = listOf(
                stringResource(R.string.settings_theme_system),
                stringResource(R.string.settings_theme_light),
                stringResource(R.string.settings_theme_dark)
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeModes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { vm.setThemeMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, themeModes.size)
                    ) {
                        Text(themeLabels[index], style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.settings_update_stops),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = when (stopUpdateState) {
                    is StopUpdateState.Running -> stringResource(R.string.stops_update_running)
                    is StopUpdateState.Success -> stringResource(R.string.stops_update_done)
                    is StopUpdateState.Error   -> stringResource(R.string.stops_update_error)
                    else -> if (!canUpdate) stringResource(R.string.settings_update_cooldown)
                            else stringResource(R.string.settings_update_stops_desc)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when (stopUpdateState) {
                    is StopUpdateState.Error -> MaterialTheme.colorScheme.error
                    is StopUpdateState.Success -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            OutlinedButton(
                onClick = vm::triggerStopUpdate,
                enabled = canUpdate && stopUpdateState !is StopUpdateState.Running,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (stopUpdateState is StopUpdateState.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_update_stops))
            }
        }
    }
}
