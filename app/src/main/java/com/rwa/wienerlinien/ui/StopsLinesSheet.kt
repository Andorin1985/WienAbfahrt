package com.rwa.wienerlinien.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rwa.wienerlinien.R
import com.rwa.wienerlinien.data.model.Stop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopsListSheet(onDismiss: () -> Unit, onNavigateToStop: ((String) -> Unit)? = null) {
    val vm: StopIdViewModel = viewModel()
    val query   by vm.stopsQuery.collectAsState()
    val stops   by vm.filteredStops.collectAsState()

    LaunchedEffect(Unit) { vm.loadAllStops() }

    ModalBottomSheet(
        onDismissRequest = { vm.onStopsQueryChanged(""); onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sheet_stops_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = vm::onStopsQueryChanged,
                placeholder = { Text(stringResource(R.string.sheet_stops_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
            if (stops.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = stringResource(R.string.sheet_stops_count, stops.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(stops, key = { it.stopId }) { stop ->
                        StopRow(stop, onClick = if (onNavigateToStop != null) {
                            { onNavigateToStop(stop.stopId); onDismiss() }
                        } else null)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinesListSheet(onDismiss: () -> Unit) {
    val vm: StopIdViewModel = viewModel()
    val query by vm.linesQuery.collectAsState()
    val lines by vm.filteredLines.collectAsState()

    LaunchedEffect(Unit) { vm.loadAllLines() }

    ModalBottomSheet(
        onDismissRequest = { vm.onLinesQueryChanged(""); onDismiss() },
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sheet_lines_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = vm::onLinesQueryChanged,
                placeholder = { Text(stringResource(R.string.sheet_lines_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = stringResource(R.string.sheet_lines_count, lines.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(lines, key = { it }) { line ->
                        LineRow(line)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StopRow(stop: Stop, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "ID: ${stop.stopId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (stop.lines.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                stop.lines.take(4).forEach { line ->
                    Box(
                        modifier = Modifier
                            .background(lineColor(line), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (stop.lines.size > 4) {
                    Text(
                        text = "+${stop.lines.size - 4}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LineRow(line: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .background(lineColor(line), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = line,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = lineTypeName(line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun lineTypeName(name: String): String = when {
    name.matches(Regex("U\\d"))              -> "U-Bahn"
    name == "WLB"                            -> "Wiener Lokalbahn"
    name.startsWith("S")                     -> "S-Bahn"
    name.startsWith("N")                     -> "Nachtbus"
    name.matches(Regex("[A-Z]"))             -> "Straßenbahn"
    name.matches(Regex("\\d{1,2}"))         -> "Straßenbahn"
    else                                     -> "Bus"
}
