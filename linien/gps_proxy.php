<?php
if (!isset($_GET['lat']) || !isset($_GET['lon'])) {
    http_response_code(400);
    echo json_encode(['error' => 'Missing lat/lon']);
    exit;
}

$lat = urlencode($_GET['lat']);
$lon = urlencode($_GET['lon']);

$url = "https://www.wienerlinien.at/ogd_realtime/stop?lat=$lat&lon=$lon";
header('Content-Type: application/json');
echo file_get_contents($url);
