<?php
$csvUrl = "https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-haltepunkte.csv";
$jsonPath = __DIR__ . "/haltestellen.json";

// Datei laden
$csvRaw = @file_get_contents($csvUrl);
if (!$csvRaw) {
    die("❌ Fehler beim Laden der CSV-Datei.\n");
}

$lines = array_filter(array_map('trim', explode("\n", $csvRaw)));

// Header überspringen
array_shift($lines);

$haltestellen = [];

foreach ($lines as $line) {
    $fields = str_getcsv($line, ";");
    if (count($fields) < 7) continue;

    $stopId = $fields[0];
    $stopText = $fields[2];
    $lon = (float)$fields[5];  // Longitude
    $lat = (float)$fields[6];  // Latitude

    // Nur speichern, wenn Koordinaten sinnvoll sind
    if ($lat === 0.0 || $lon === 0.0) continue;

    $haltestellen[] = [
        'stopId' => $stopId,
        'name'   => $stopText,
        'lat'    => $lat,
        'lon'    => $lon,
    ];
}

file_put_contents($jsonPath, json_encode($haltestellen, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
echo "✅ Fertig: " . count($haltestellen) . " Haltestellen gespeichert in: $jsonPath\n";
