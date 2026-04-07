<?php
// CSV-Quellen
$urlHaltepunkte = "https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-haltepunkte.csv";
$urlFahrweg     = "https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-fahrwegverlaeufe.csv";
$urlLinien      = "https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-linien.csv";

$csvHaltepunkte = @file_get_contents($urlHaltepunkte);
$csvFahrweg     = @file_get_contents($urlFahrweg);
$csvLinien      = @file_get_contents($urlLinien);

if (!$csvHaltepunkte || !$csvFahrweg || !$csvLinien) {
    die("❌ Fehler beim Laden der CSV-Dateien.\n");
}

// 1. Mapping LineID → BEZEICHNUNG (z. B. "13A", "U2")
$linienIdToName = [];
foreach (array_slice(explode("\n", $csvLinien), 1) as $line) {
    $fields = str_getcsv($line, ";");
    if (count($fields) >= 2) {
        $linienIdToName[trim($fields[0])] = trim($fields[1]);
    }
}

// 2. Haltepunkte einlesen
$haltestellen = [];
foreach (array_slice(explode("\n", $csvHaltepunkte), 1) as $line) {
    $fields = str_getcsv($line, ";");
    if (count($fields) < 7) continue;
    $halt_id = trim($fields[0]);
    $name    = trim($fields[2]);
    $lon     = (float)str_replace(",", ".", $fields[5]);
    $lat     = (float)str_replace(",", ".", $fields[6]);
    if ($lat == 0.0 || $lon == 0.0) continue;
    $haltestellen[$halt_id] = [
        'stopId' => $halt_id,
        'name'   => $name,
        'lat'    => $lat,
        'lon'    => $lon,
        'lines'  => []
    ];
}

// 3. Fahrwegverläufe: StopID (Feld 3), LineID (Feld 0)
foreach (array_slice(explode("\n", $csvFahrweg), 1) as $line) {
    $fields = str_getcsv($line, ";");
    if (count($fields) < 4) continue;
    $lineId = trim($fields[0]);
    $stopId = trim($fields[3]);
    if (!$stopId || !$lineId) continue;
    if (!isset($haltestellen[$stopId])) continue;
    $linienname = $linienIdToName[$lineId] ?? null;
    if ($linienname && !in_array($linienname, $haltestellen[$stopId]['lines'])) {
        $haltestellen[$stopId]['lines'][] = $linienname;
    }
}

// 4. Nur Haltestellen mit mindestens einer Linie
$final = array_values(array_filter($haltestellen, fn($h) => !empty($h['lines'])));

// Ergebnis speichern
file_put_contents(__DIR__ . "/haltestellen_mit_linien.json", json_encode($final, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE));
echo "✅ Fertig. " . count($final) . " Haltestellen mit Linien gespeichert.\n";
