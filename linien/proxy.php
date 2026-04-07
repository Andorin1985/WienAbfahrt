<?php
declare(strict_types=1);
header('Content-Type: application/json; charset=utf-8');

$stopId = isset($_GET['stopId']) ? (int)$_GET['stopId'] : 400;
$cacheDir = __DIR__ . '/cache';
$cacheFile = "{$cacheDir}/stop_{$stopId}.json";
$cacheTTL = 300; // 5 Minuten

if (!is_dir($cacheDir)) {
    mkdir($cacheDir, 0775, true);
}

/* ---------- 1) Cache prüfen ---------- */
if (file_exists($cacheFile) && (time() - filemtime($cacheFile) < $cacheTTL)) {
    $cached = file_get_contents($cacheFile);
    if ($cached !== false) {
        echo $cached;
        exit;
    }
}

/* ---------- 2) Live-Abruf ---------- */
$url = "https://www.wienerlinien.at/ogd_realtime/monitor?stopId={$stopId}";
$ch = curl_init($url);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_CONNECTTIMEOUT => 5,
    CURLOPT_TIMEOUT => 10,
    CURLOPT_FOLLOWLOCATION => true,
]);
$response = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
$error = curl_error($ch);
curl_close($ch);

/* ---------- 3) Netzwerkfehler ---------- */
if ($response === false || $httpCode !== 200) {
    if (file_exists($cacheFile)) {
        echo file_get_contents($cacheFile);
        exit;
    }
    http_response_code(503);
    echo json_encode(['error' => 'API nicht erreichbar', 'status' => $httpCode, 'curl' => $error]);
    exit;
}

/* ---------- 4) JSON prüfen ---------- */
$data = json_decode($response, true);
if ($data === null) {
    if (file_exists($cacheFile)) {
        echo file_get_contents($cacheFile);
        exit;
    }
    http_response_code(502);
    echo json_encode(['error' => 'Ungültiges JSON', 'raw' => substr($response, 0, 200)]);
    exit;
}

/* ---------- 5) Kein Fehler, aber keine Abfahrten ---------- */
if (
    isset($data['data']['monitors']) &&
    is_array($data['data']['monitors']) &&
    count($data['data']['monitors']) === 0
) {
    http_response_code(204); // No Content – kein Fehler, nur keine Daten
    $message = [
        'info' => 'Keine Abfahrten an dieser Haltestelle',
        'stopId' => $stopId,
        'serverTime' => $data['message']['serverTime'] ?? null,
    ];
    echo json_encode($message);
    exit;
}

/* ---------- 6) Echte Fehler prüfen ---------- */
if (
    empty($data['data']['monitors']) ||
    !isset($data['message']['value']) ||
    $data['message']['value'] !== 'OK'
) {
    if (file_exists($cacheFile)) {
        echo file_get_contents($cacheFile);
        exit;
    }
    http_response_code(204);
    echo json_encode(['error' => 'Keine aktuellen Abfahrten']);
    exit;
}

/* ---------- 7) Cache speichern & ausgeben ---------- */
file_put_contents($cacheFile, $response, LOCK_EX);
echo $response;
