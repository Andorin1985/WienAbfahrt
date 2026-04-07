<?php
declare(strict_types=1);

/* ── i18n ─────────────────────────────────────────────────────────────────── */

$supported = ['de','en','es-419'];
$lang = $_GET['lang'] ?? null;
if (!$lang && isset($_COOKIE['lang']) && in_array($_COOKIE['lang'], $supported, true)) {
    $lang = $_COOKIE['lang'];
}
if (!$lang && isset($_SERVER['HTTP_ACCEPT_LANGUAGE'])) {
    $candidates = [];
    foreach (explode(',', $_SERVER['HTTP_ACCEPT_LANGUAGE']) as $part) {
        if (preg_match('/^\s*([a-zA-Z-]+)(;q=([0-9.]+))?/', $part, $m)) {
            $full = strtolower($m[1]);
            $base = substr($full, 0, 2);
            $q    = isset($m[3]) ? (float)$m[3] : 1.0;
            $candidates[$full] = max($candidates[$full] ?? 0, $q);
            $candidates[$base] = max($candidates[$base] ?? 0, $q - 0.001);
        }
    }
    arsort($candidates);
    foreach (array_keys($candidates) as $code) {
        if (in_array($code, $supported, true)) { $lang = $code; break; }
    }
}
if (!$lang) { $lang = 'de'; }
setcookie('lang', $lang, [
    'expires'  => time() + 60*60*24*90,
    'path'     => '/',
    'secure'   => !empty($_SERVER['HTTPS']),
    'httponly' => false,
    'samesite' => 'Lax',
]);

$translations = [];
foreach (['de','en','es-419', $lang] as $L) {
    $file = __DIR__ . "/i18n/$L.json";
    if (is_file($file)) {
        $json = json_decode(file_get_contents($file), true);
        if (is_array($json)) $translations = array_replace($translations, $json);
    }
}
function t(string $key, array $vars = []): string {
    global $translations;
    $s = $translations[$key] ?? $key;
    foreach ($vars as $k => $v) { $s = str_replace("{{$k}}", (string)$v, $s); }
    return $s;
}

/* ── HTTP / Cache helpers ─────────────────────────────────────────────────── */

function http_get_with_status(string $url, array $opts = []): array {
    $timeout = $opts['timeout'] ?? 3.0;
    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_USERAGENT      => 'Mozilla/5.0',
            CURLOPT_TIMEOUT        => $timeout,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $body   = curl_exec($ch);
        $status = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);
        return ['status' => $status, 'body' => $body === false ? '' : $body];
    }
    $ctx  = stream_context_create(['http' => ['header' => "User-Agent: Mozilla/5.0\r\n", 'timeout' => $timeout]]);
    $body = @file_get_contents($url, false, $ctx);
    $status = 0;
    if (!empty($http_response_header)) {
        foreach ($http_response_header as $h) {
            if (preg_match('#^HTTP/\S+\s+(\d{3})#', $h, $m)) { $status = (int)$m[1]; break; }
        }
    }
    return ['status' => $status, 'body' => $body === false ? '' : $body];
}

function json_or_array(string $raw): array {
    if ($raw === '') return [];
    $j = json_decode($raw, true);
    return is_array($j) ? $j : [];
}

function cache_path(string $key): string {
    $dir = __DIR__ . '/cache';
    if (!is_dir($dir)) @mkdir($dir, 0775, true);
    return $dir . '/' . preg_replace('/[^a-z0-9_-]+/i', '_', $key) . '.json';
}
function cache_write(string $key, array $payload): void {
    $payload['_cached_at'] = date('c');
    @file_put_contents(cache_path($key), json_encode($payload, JSON_UNESCAPED_UNICODE));
}
function cache_read_fresh(string $key, int $maxAgeSeconds): ?array {
    $path = cache_path($key);
    if (!is_file($path)) return null;
    $arr = json_or_array(@file_get_contents($path) ?: '');
    $ts  = isset($arr['_cached_at']) ? strtotime($arr['_cached_at']) : 0;
    return ($ts && (time() - $ts) <= $maxAgeSeconds) ? $arr : null;
}

/* ── Line helpers (mirrors Android lineColor / lineTypePriority) ──────────── */

function lineColor(string $name): string {
    $map = ['U1'=>'#E2001A','U2'=>'#9B27AF','U3'=>'#F47B20',
            'U4'=>'#1BA350','U5'=>'#46BCC6','U6'=>'#9E5A1B','WLB'=>'#1A7A3C'];
    if (isset($map[$name])) return $map[$name];
    if (preg_match('/A$/i', $name))    return '#1E63B0'; // bus (10A, 12A …)
    if (stripos($name, 'N') === 0)     return '#1E63B0'; // night bus
    if (stripos($name, 'S') === 0)     return '#003DA5'; // S-Bahn
    if (preg_match('/^[A-Z]$/', $name)) return '#E2001A'; // named tram (D, O …)
    if (preg_match('/^\d{1,2}$/', $name)) return '#E2001A'; // numbered tram
    return '#1E63B0';
}

function lineTypePriority(string $name): int {
    if (preg_match('/^U[1-6]$/i', $name))   return 0; // U-Bahn
    if (preg_match('/^[A-Z]$/', $name))      return 1; // named trams (D, O …)
    if (preg_match('/^\d{1,2}$/', $name))    return 2; // numbered trams
    if (stripos($name, 'S') === 0)           return 3; // S-Bahn
    if ($name === 'WLB')                     return 4;
    if (stripos($name, 'N') === 0)           return 5; // night buses
    return 6;
}

function relatedLines(array $info): array {
    // API may nest under 'attributes' or place at top level
    $raw = $info['attributes']['relatedLines'] ?? $info['relatedLines'] ?? null;
    if (is_null($raw)) return [];
    if (is_array($raw)) return array_values(array_filter($raw, 'is_string'));
    if (is_string($raw)) return array_values(array_filter(array_map('trim', explode(',', $raw))));
    return [];
}

/* ── Active tab ───────────────────────────────────────────────────────────── */

$validTabs  = ['abfahrt','favoriten','stoerungen','info'];
$activeTab  = in_array($_GET['tab'] ?? '', $validTabs, true) ? $_GET['tab'] : 'abfahrt';

/* ── Monitor fetch ────────────────────────────────────────────────────────── */

$stopId      = trim($_GET['stopId'] ?? '');
$monitors    = [];
$messageCode = null;
$is_stale    = false;
$res         = null;

if ($stopId) {
    $url = "https://www.wienerlinien.at/ogd_realtime/monitor?stopId=" . urlencode($stopId);
    $res = http_get_with_status($url, ['timeout' => 3.0]);

    if ($res['status'] === 200) {
        $dataArr     = json_or_array($res['body']);
        $monitors    = $dataArr['data']['monitors'] ?? [];
        $messageCode = $dataArr['message']['messageCode'] ?? null;
        if (!empty($monitors)) cache_write('monitor_' . $stopId, $dataArr);
    } else {
        $cached = cache_read_fresh('monitor_' . $stopId, 300);
        if ($cached) {
            $monitors    = $cached['data']['monitors'] ?? [];
            $messageCode = $cached['message']['messageCode'] ?? null;
            $is_stale    = true;
        }
    }
}

$hasDepartures = false;
foreach ($monitors as $mon) {
    foreach (($mon['lines'] ?? []) as $line) {
        if (!empty($line['departures']['departure'])) { $hasDepartures = true; break 2; }
    }
}

// Current stop name + line list (for star/favorites)
$currentStopName = null;
$currentLines    = [];
if (!empty($monitors)) {
    $currentStopName = $monitors[0]['locationStop']['properties']['title']
                    ?? $monitors[0]['locationStop']['properties']['name']
                    ?? null;
    foreach ($monitors as $mon) {
        foreach (($mon['lines'] ?? []) as $line) {
            $n = $line['name'] ?? '';
            if ($n && !in_array($n, $currentLines, true)) $currentLines[] = $n;
        }
    }
}

/* ── Traffic info fetch ───────────────────────────────────────────────────── */

$trafficInfos    = [];
$trafficFromCache = false;
$resT = http_get_with_status("https://www.wienerlinien.at/ogd_realtime/trafficInfoList", ['timeout' => 3.0]);

if ($resT['status'] === 200) {
    $tArr = json_or_array($resT['body']);
    $trafficInfos = array_values(array_filter(
        $tArr['data']['trafficInfos'] ?? [],
        fn($i) => (int)($i['refTrafficInfoCategoryId'] ?? 0) === 2
    ));
    if (!empty($trafficInfos)) cache_write('traffic', $tArr);
} else {
    $cachedT = cache_read_fresh('traffic', 600);
    if ($cachedT) {
        $trafficInfos = array_values(array_filter(
            $cachedT['data']['trafficInfos'] ?? [],
            fn($i) => (int)($i['refTrafficInfoCategoryId'] ?? 0) === 2
        ));
        $trafficFromCache = true;
    }
}

// Sorted disruptions for Störungen tab (U-Bahn first, then tram, then bus; then alphabetically)
$sortedDisruptions = $trafficInfos;
usort($sortedDisruptions, function (array $a, array $b): int {
    $linesA   = relatedLines($a);
    $linesB   = relatedLines($b);
    $prioA    = empty($linesA) ? PHP_INT_MAX : min(array_map('lineTypePriority', $linesA));
    $prioB    = empty($linesB) ? PHP_INT_MAX : min(array_map('lineTypePriority', $linesB));
    if ($prioA !== $prioB) return $prioA <=> $prioB;
    return ($a['title'] ?? '') <=> ($b['title'] ?? '');
});

/* ── JS strings + reload interval ────────────────────────────────────────── */

$JS_STRINGS = [
    'gps_not_supported'    => t('gps.notSupported'),
    'gps_permission_denied'=> t('gps.permissionDenied'),
    'gps_no_stop_found'    => t('gps.noStopFound'),
    'gps_load_error'       => t('gps.loadError'),
    'gps_distance_tpl'     => t('gps.distance', ['meters' => '{m}']),
    'pwa_ok'               => t('pwa.ok'),
    'pwa_err'              => t('pwa.err'),
    'haltestellenInfo_tpl' => t('ui.haltestellenInfo.stop', ['name' => '{name}', 'id' => '{id}']),
    'departures_in_tpl'    => t('departures.in', ['min' => '{min}']),
    'favoriten_empty'      => t('favoriten.empty'),
    'star_add'             => t('ui.star.add'),
    'star_remove'          => t('ui.star.remove'),
    'nav_abfahrt'          => t('nav.abfahrt'),
    'nav_favoriten'        => t('nav.favoriten'),
    'nav_stoerungen'       => t('nav.stoerungen'),
    'nav_info'             => t('nav.info'),
];

$reloadMs = match ($activeTab) {
    'abfahrt'    => ($stopId && (empty($monitors) || $is_stale)) ? 60000 : 30000,
    'stoerungen' => 60000,
    default      => 60000,
};

?>
<!DOCTYPE html>
<html lang="<?= htmlspecialchars($lang) ?>" data-bs-theme="dark">
<head>
    <meta charset="UTF-8">
    <title><?= htmlspecialchars(t('app.title')) ?></title>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap-icons@1.11.3/font/bootstrap-icons.min.css" rel="stylesheet">
    <link rel="manifest" href="manifest.json">
    <meta name="theme-color" content="#121212">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <link rel="alternate" hreflang="de"     href="?<?= http_build_query(array_merge($_GET, ['lang'=>'de'])) ?>">
    <link rel="alternate" hreflang="en"     href="?<?= http_build_query(array_merge($_GET, ['lang'=>'en'])) ?>">
    <link rel="alternate" hreflang="es-419" href="?<?= http_build_query(array_merge($_GET, ['lang'=>'es-419'])) ?>">
    <style>
        body {
            background: #121212;
            color: #e0e0e0;
            padding-bottom: calc(64px + env(safe-area-inset-bottom));
        }

        /* ── Top bar ── */
        .top-bar {
            position: sticky; top: 0; z-index: 100;
            background: #121212;
            border-bottom: 1px solid #2a2a2a;
            padding: 10px 16px;
            display: flex; align-items: center; justify-content: space-between;
        }
        .top-bar .title { font-size: .95rem; font-weight: 600; }
        .lang-btn {
            font-size: .75rem; padding: 2px 7px;
            color: #aaa; text-decoration: none;
            border: 1px solid #333; border-radius: 4px;
        }
        .lang-btn.active { color: #fff; border-color: #555; }
        .lang-btn + .lang-btn { margin-left: 4px; }

        /* ── Bottom nav ── */
        .bottom-nav {
            position: fixed; bottom: 0; left: 0; right: 0;
            height: calc(56px + env(safe-area-inset-bottom));
            padding-bottom: env(safe-area-inset-bottom);
            background: #1a1a1a; border-top: 1px solid #2a2a2a;
            display: flex; z-index: 200;
        }
        .bnav-item {
            flex: 1; display: flex; flex-direction: column;
            align-items: center; justify-content: center;
            color: #666; background: transparent; border: none;
            font-size: 10px; gap: 2px; cursor: pointer;
            transition: color .15s; padding: 0;
        }
        .bnav-item i { font-size: 22px; }
        .bnav-item.active { color: #4d8ef5; }

        /* ── Cards ── */
        .dep-card {
            background: #1e1e1e;
            border-left: 4px solid #4d8ef5;
            border-radius: 8px; padding: 12px 14px; margin-bottom: 10px;
        }
        .dep-card.has-disruption { border-left-color: #dc3545; }

        /* ── Disruption row ── */
        .disrupt-row {
            display: flex; padding: 10px 0;
            border-bottom: 1px solid #2a2a2a;
        }
        .disrupt-bar {
            width: 3px; border-radius: 2px;
            background: #dc3545; flex-shrink: 0; margin-right: 12px;
            align-self: stretch;
        }

        /* ── Line badge ── */
        .line-badge {
            display: inline-block; padding: 1px 7px; border-radius: 4px;
            font-size: 11px; font-weight: 700; color: #fff;
            margin: 2px 3px 2px 0; vertical-align: middle;
        }

        /* ── Autocomplete ── */
        .ac-wrap { position: relative; }
        .ac-dropdown {
            position: absolute; top: 100%; left: 0; right: 0;
            background: #1e1e1e; border: 1px solid #444;
            border-top: none; border-radius: 0 0 8px 8px;
            max-height: 220px; overflow-y: auto; z-index: 50;
            display: none;
        }
        .ac-item {
            padding: 8px 12px; cursor: pointer;
            border-bottom: 1px solid #2a2a2a; font-size: .875rem;
        }
        .ac-item:last-child { border-bottom: none; }
        .ac-item:hover, .ac-item.focused { background: #2a2a2a; }

        /* ── Favorites ── */
        .fav-item {
            display: flex; align-items: center; padding: 12px 0;
            border-bottom: 1px solid #2a2a2a; gap: 12px;
            cursor: pointer;
        }
        .fav-item:hover { background: #1a1a1a; border-radius: 6px; }

        /* ── Pulsing disruption text ── */
        .blink { animation: pulse 2.5s ease-in-out infinite; color: #ff6060; font-weight: 600; }
        @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.15} }
        @media (prefers-reduced-motion: reduce) { .blink { animation: none; } }

        input.form-control {
            background: #1e1e1e; color: #fff; border-color: #444;
            border-radius: 8px;
        }
        input.form-control:focus { background: #222; color: #fff; border-color: #555; box-shadow: none; }
        .btn-primary { border-radius: 8px; }
        .btn-outline-secondary { border-radius: 8px; border-color: #444; color: #bbb; }
        .btn-outline-secondary:hover { background: #2a2a2a; color: #fff; }
    </style>
</head>
<body>

<!-- ── Top bar ──────────────────────────────────────────────────────────────── -->
<div class="top-bar">
    <span class="title"><?= htmlspecialchars(t('ui.header')) ?></span>
    <div>
        <a class="lang-btn <?= $lang==='de'?'active':'' ?>" href="?<?= http_build_query(array_merge($_GET, ['lang'=>'de'])) ?>">DE</a>
        <a class="lang-btn <?= $lang==='en'?'active':'' ?>" href="?<?= http_build_query(array_merge($_GET, ['lang'=>'en'])) ?>">EN</a>
        <a class="lang-btn <?= $lang==='es-419'?'active':'' ?>" href="?<?= http_build_query(array_merge($_GET, ['lang'=>'es-419'])) ?>">ES</a>
    </div>
</div>

<!-- ── Tab: Abfahrt ─────────────────────────────────────────────────────────── -->
<div id="tab-abfahrt" class="tab-pane" style="display:none;">
    <div class="container py-3">

        <!-- GPS button -->
        <button onclick="findeNaechsteHaltestelle()" class="btn btn-outline-secondary w-100 mb-3">
            <i class="bi bi-geo-alt me-1"></i><?= htmlspecialchars(t('ui.findNearest')) ?>
        </button>

        <!-- Search with autocomplete -->
        <form method="get" id="stop-form" onsubmit="onFormSubmit()">
            <?php foreach ($_GET as $k => $v): ?>
                <?php if ($k !== 'stopId' && $k !== 'tab'): ?>
                    <input type="hidden" name="<?= htmlspecialchars($k) ?>" value="<?= htmlspecialchars((string)$v) ?>">
                <?php endif; ?>
            <?php endforeach; ?>
            <input type="hidden" name="tab" value="abfahrt">
            <div class="mb-3 ac-wrap">
                <label class="form-label small text-secondary"><?= htmlspecialchars(t('ui.stopId.label')) ?></label>
                <input type="text" class="form-control" id="stopId" name="stopId" autocomplete="off"
                       value="<?= htmlspecialchars($stopId) ?>"
                       placeholder="<?= htmlspecialchars(t('ui.stopId.ph')) ?>">
                <div class="ac-dropdown" id="ac-dropdown"></div>
            </div>
            <button type="submit" class="btn btn-primary w-100"><?= htmlspecialchars(t('ui.show')) ?></button>
        </form>

        <!-- Stop info + star -->
        <div id="haltestellenInfo" class="my-3 d-flex align-items-center gap-2" style="color:#4d8ef5;font-size:1rem;"></div>

        <!-- Alerts -->
        <?php if ($stopId && (int)$messageCode === 316): ?>
            <div class="alert alert-danger small py-2">⚠️ <?= htmlspecialchars(t('alert.apiLimit')) ?></div>
        <?php elseif ($is_stale): ?>
            <div class="alert alert-secondary small py-2">
                <?= htmlspecialchars(t('alert.showingCache')) ?>
            </div>
        <?php elseif ($stopId && isset($res['status']) && (int)$res['status'] === 200 && !$hasDepartures): ?>
            <div class="alert alert-secondary small py-2">
                <?= htmlspecialchars(t('alert.noDepartures', ['stopId' => $stopId])) ?>
            </div>
        <?php elseif ($stopId && empty($monitors) && !$is_stale && (!isset($res['status']) || (int)$res['status'] !== 200)): ?>
            <div class="alert alert-warning small py-2">
                <?= htmlspecialchars(t('alert.sourceDown', ['code' => isset($res['status']) && $res['status'] ? (int)$res['status'] : ''])) ?>
            </div>
        <?php endif; ?>

        <!-- Departure cards -->
        <?php foreach ($monitors as $monitor): ?>
            <?php foreach (($monitor['lines'] ?? []) as $line): ?>
                <?php
                $lineName = $line['name'] ?? '';
                $towards  = $line['towards'] ?? '';
                $color    = lineColor($lineName);
                $disruptTitle = null;
                foreach ($trafficInfos as $info) {
                    $rl = relatedLines($info);
                    if (in_array($lineName, $rl, true)) {
                        $disruptTitle = $info['title'] ?? t('traffic.disruptionDefault');
                        break;
                    }
                }
                ?>
                <div class="dep-card <?= $disruptTitle ? 'has-disruption' : '' ?>"
                     style="border-left-color:<?= $disruptTitle ? '#dc3545' : htmlspecialchars($color) ?>">
                    <div class="d-flex align-items-center gap-2 mb-1">
                        <span class="line-badge" style="background:<?= htmlspecialchars($color) ?>"><?= htmlspecialchars($lineName) ?></span>
                        <span class="fw-medium small"><?= htmlspecialchars(t('line.to')) ?> <?= htmlspecialchars($towards) ?></span>
                    </div>
                    <?php if ($disruptTitle): ?>
                        <div class="blink small mb-2">⚠ <?= htmlspecialchars($disruptTitle) ?></div>
                    <?php endif; ?>
                    <div>
                        <?php foreach (($line['departures']['departure'] ?? []) as $dep): ?>
                            <?php $mins = (string)($dep['departureTime']['countdown'] ?? '?'); ?>
                            <div class="small text-secondary"><?= t('departures.in', ['min' => $mins]) ?></div>
                        <?php endforeach; ?>
                    </div>
                </div>
            <?php endforeach; ?>
        <?php endforeach; ?>

        <?php if ($stopId): ?>
        <p class="text-secondary mt-3" style="font-size:.75rem;">
            <?= htmlspecialchars(t('footer.source')) ?> ·
            <?= htmlspecialchars(t('footer.disclaimer')) ?>
        </p>
        <?php endif; ?>
    </div>
</div>

<!-- ── Tab: Favoriten ───────────────────────────────────────────────────────── -->
<div id="tab-favoriten" class="tab-pane" style="display:none;">
    <div class="container py-3">
        <div id="fav-list"></div>
    </div>
</div>

<!-- ── Tab: Störungen ───────────────────────────────────────────────────────── -->
<div id="tab-stoerungen" class="tab-pane" style="display:none;">
    <div class="container py-3">
        <p class="text-secondary mb-3" style="font-size:.75rem;">
            <?= htmlspecialchars(t('alert.asOf', ['time' => date('H:i')])) ?>
            <?php if ($trafficFromCache): ?> · <?= htmlspecialchars(t('alert.showingCache')) ?><?php endif; ?>
        </p>

        <?php if (empty($trafficInfos) && $resT['status'] !== 200 && !$trafficFromCache): ?>
            <div class="disrupt-row">
                <div class="disrupt-bar" style="background:#aaa"></div>
                <div class="small text-secondary"><?= htmlspecialchars(t('stoerungen.error')) ?></div>
            </div>

        <?php elseif (empty($sortedDisruptions)): ?>
            <div class="text-center py-5 text-secondary">
                <i class="bi bi-check-circle" style="font-size:2rem;color:#1BA350"></i>
                <p class="mt-2 mb-0"><?= htmlspecialchars(t('stoerungen.none')) ?></p>
            </div>

        <?php else: ?>
            <?php foreach ($sortedDisruptions as $info): ?>
                <?php $rl = relatedLines($info); ?>
                <div class="disrupt-row">
                    <div class="disrupt-bar"></div>
                    <div class="flex-grow-1">
                        <div class="small fw-semibold mb-1">
                            <?= htmlspecialchars($info['title'] ?? t('traffic.disruptionDefault')) ?>
                        </div>
                        <?php if (!empty($info['description'])): ?>
                            <div class="small text-secondary mb-2"><?= htmlspecialchars($info['description']) ?></div>
                        <?php endif; ?>
                        <?php if (!empty($rl)): ?>
                            <div>
                                <?php foreach ($rl as $ln): ?>
                                    <span class="line-badge" style="background:<?= htmlspecialchars(lineColor($ln)) ?>"><?= htmlspecialchars($ln) ?></span>
                                <?php endforeach; ?>
                            </div>
                        <?php endif; ?>
                    </div>
                </div>
            <?php endforeach; ?>

            <p class="text-secondary mt-3" style="font-size:.75rem;">
                <?= htmlspecialchars(t('footer.source')) ?> · <?= htmlspecialchars(t('footer.disclaimer')) ?>
            </p>
        <?php endif; ?>
    </div>
</div>

<!-- ── Tab: Info ────────────────────────────────────────────────────────────── -->
<div id="tab-info" class="tab-pane" style="display:none;">
    <div class="container py-3">
        <div style="width:36px;height:4px;background:#4d8ef5;border-radius:2px;margin-bottom:16px;"></div>
        <h2 class="h5 mb-1"><?= htmlspecialchars(t('app.title')) ?></h2>
        <p class="text-secondary small mb-4"><?= htmlspecialchars(t('info.about')) ?></p>

        <hr style="border-color:#2a2a2a;">

        <p class="small fw-semibold text-secondary text-uppercase mb-2" style="letter-spacing:.05em;">
            <?= htmlspecialchars(t('footer.source')) ?>
        </p>
        <p class="small mb-1">
            <a href="<?= htmlspecialchars(t('footer.source.link')) ?>" target="_blank" rel="noopener" class="text-decoration-none" style="color:#4d8ef5">
                wienerlinien.at/open-data
            </a>
        </p>
        <p class="small text-secondary mb-4"><?= htmlspecialchars(t('footer.disclaimer')) ?></p>

        <hr style="border-color:#2a2a2a;">

        <p class="small text-secondary mb-2"><?= htmlspecialchars(t('footer.pwa')) ?></p>

        <hr style="border-color:#2a2a2a;">

        <p class="small text-secondary mt-3 mb-0"><?= htmlspecialchars(t('info.dev')) ?></p>
    </div>
</div>

<!-- ── Bottom Navigation ────────────────────────────────────────────────────── -->
<nav class="bottom-nav">
    <button class="bnav-item" id="nav-abfahrt"    onclick="switchTab('abfahrt')">
        <i class="bi bi-geo-alt-fill"></i>
        <span><?= htmlspecialchars(t('nav.abfahrt')) ?></span>
    </button>
    <button class="bnav-item" id="nav-favoriten"  onclick="switchTab('favoriten')">
        <i class="bi bi-star-fill"></i>
        <span><?= htmlspecialchars(t('nav.favoriten')) ?></span>
    </button>
    <button class="bnav-item" id="nav-stoerungen" onclick="switchTab('stoerungen')">
        <i class="bi bi-exclamation-triangle-fill"></i>
        <span><?= htmlspecialchars(t('nav.stoerungen')) ?></span>
    </button>
    <button class="bnav-item" id="nav-info"       onclick="switchTab('info')">
        <i class="bi bi-info-circle-fill"></i>
        <span><?= htmlspecialchars(t('nav.info')) ?></span>
    </button>
</nav>

<!-- ── JavaScript ───────────────────────────────────────────────────────────── -->
<script>
const I18N         = <?= json_encode($JS_STRINGS, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) ?>;
const CURRENT_STOP = <?= json_encode(['stopId' => $stopId, 'name' => $currentStopName ?? '', 'lines' => $currentLines], JSON_UNESCAPED_UNICODE) ?>;
const ACTIVE_TAB   = <?= json_encode($activeTab) ?>;

function esc(s) {
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function tpl(s, map) {
    return Object.entries(map).reduce((acc,[k,v]) => acc.replaceAll(`{${k}}`, v), s);
}

/* ── Tab switching ── */
function switchTab(name) {
    document.querySelectorAll('.tab-pane').forEach(el => el.style.display = 'none');
    document.getElementById('tab-' + name).style.display = 'block';
    document.querySelectorAll('.bnav-item').forEach(el => el.classList.remove('active'));
    document.getElementById('nav-' + name).classList.add('active');
    localStorage.setItem('wl_active_tab', name);
    const params = new URLSearchParams(window.location.search);
    params.set('tab', name);
    history.replaceState(null, '', '?' + params.toString());
    if (name === 'favoriten') renderFavorites();
}

/* ── Line badge color (JS mirror of PHP lineColor) ── */
function lineBadgeColor(name) {
    const map = {U1:'#E2001A',U2:'#9B27AF',U3:'#F47B20',U4:'#1BA350',U5:'#46BCC6',U6:'#9E5A1B',WLB:'#1A7A3C'};
    if (map[name]) return map[name];
    if (/A$/i.test(name))    return '#1E63B0';
    if (/^N/i.test(name))    return '#1E63B0';
    if (/^S/i.test(name))    return '#003DA5';
    if (/^[A-Z]$/.test(name)) return '#E2001A';
    if (/^\d{1,2}$/.test(name)) return '#E2001A';
    return '#1E63B0';
}

/* ── Favorites ── */
function getFavorites() {
    try { return JSON.parse(localStorage.getItem('wl_favorites') || '[]'); }
    catch { return []; }
}
function saveFavorites(list) { localStorage.setItem('wl_favorites', JSON.stringify(list)); }
function isFav(stopId) { return getFavorites().some(f => f.stopId === stopId); }

function toggleFav() {
    if (!CURRENT_STOP.stopId) return;
    const favs = getFavorites();
    if (isFav(CURRENT_STOP.stopId)) {
        saveFavorites(favs.filter(f => f.stopId !== CURRENT_STOP.stopId));
    } else {
        favs.unshift({ stopId: CURRENT_STOP.stopId, name: CURRENT_STOP.name, lines: CURRENT_STOP.lines });
        saveFavorites(favs);
    }
    updateStar();
    renderFavorites();
}

function removeFav(stopId) {
    saveFavorites(getFavorites().filter(f => f.stopId !== stopId));
    renderFavorites();
    if (CURRENT_STOP.stopId === stopId) updateStar();
}

function updateStar() {
    const btn = document.getElementById('star-btn');
    if (!btn) return;
    const on = isFav(CURRENT_STOP.stopId);
    btn.innerHTML = `<i class="bi ${on ? 'bi-star-fill' : 'bi-star'}"></i>`;
    btn.style.color = on ? '#f0c040' : '#888';
    btn.title = I18N[on ? 'star_remove' : 'star_add'];
}

function renderFavorites() {
    const list = document.getElementById('fav-list');
    if (!list) return;
    const favs = getFavorites();
    if (favs.length === 0) {
        list.innerHTML = `<p class="text-secondary text-center py-5" style="white-space:pre-line">${esc(I18N.favoriten_empty)}</p>`;
        return;
    }
    list.innerHTML = favs.map(f => `
        <div class="fav-item" onclick="goToStop(${JSON.stringify(f.stopId)})">
            <div class="flex-grow-1">
                <div class="fw-medium">${esc(f.name || f.stopId)}</div>
                <div class="small text-secondary">ID ${esc(f.stopId)}</div>
                ${(f.lines||[]).length > 0
                    ? '<div class="mt-1">' + (f.lines||[]).slice(0,8).map(l =>
                        `<span class="line-badge" style="background:${lineBadgeColor(l)}">${esc(l)}</span>`
                      ).join('') + '</div>'
                    : ''}
            </div>
            <button class="btn btn-sm" style="color:#dc3545;border:none;background:transparent"
                    onclick="event.stopPropagation();removeFav(${JSON.stringify(f.stopId)})"
                    title="Entfernen">
                <i class="bi bi-x-lg"></i>
            </button>
        </div>
    `).join('');
}

function goToStop(stopId) {
    const params = new URLSearchParams(window.location.search);
    params.set('stopId', stopId);
    params.set('tab', 'abfahrt');
    window.location.href = '?' + params.toString();
}

/* ── Autocomplete ── */
let acData = null;
const acInput    = document.getElementById('stopId');
const acDropdown = document.getElementById('ac-dropdown');
let acIndex = -1;

async function loadAcData() {
    if (!acData) {
        const res = await fetch('haltestellen_mit_linien.json');
        acData = await res.json();
    }
    return acData;
}

function filterAc(query, data) {
    const q = query.trim().toLowerCase();
    if (q.length < 2) return [];
    return data.filter(h =>
        h.name.toLowerCase().includes(q) || String(h.stopId).startsWith(q)
    ).slice(0, 8);
}

function renderAc(results) {
    if (results.length === 0) { acDropdown.style.display = 'none'; return; }
    acIndex = -1;
    acDropdown.innerHTML = results.map((h, i) => `
        <div class="ac-item" data-idx="${i}" data-stopid="${esc(h.stopId)}" data-name="${esc(h.name)}">
            <span class="fw-medium">${esc(h.name)}</span>
            <span class="text-secondary ms-2" style="font-size:.8em">ID ${esc(h.stopId)}</span>
            ${(h.lines||[]).length > 0
                ? ' &nbsp;' + (h.lines||[]).slice(0,5).map(l =>
                    `<span class="line-badge" style="background:${lineBadgeColor(l)}">${esc(l)}</span>`
                  ).join('')
                : ''}
        </div>
    `).join('');
    acDropdown.querySelectorAll('.ac-item').forEach(el => {
        el.addEventListener('mousedown', e => {
            e.preventDefault();
            selectAc(el.dataset.stopid, el.dataset.name);
        });
    });
    acDropdown.style.display = 'block';
}

function selectAc(stopId, name) {
    acInput.value = stopId;
    acDropdown.style.display = 'none';
    localStorage.setItem('wl_stop_id', stopId);
    document.getElementById('stop-form').submit();
}

function closeAc() { acDropdown.style.display = 'none'; }

acInput.addEventListener('input', async () => {
    try {
        const data    = await loadAcData();
        const results = filterAc(acInput.value, data);
        renderAc(results);
    } catch { closeAc(); }
});

acInput.addEventListener('keydown', e => {
    const items = acDropdown.querySelectorAll('.ac-item');
    if (!items.length) return;
    if (e.key === 'ArrowDown') {
        e.preventDefault();
        acIndex = Math.min(acIndex + 1, items.length - 1);
        items.forEach((el,i) => el.classList.toggle('focused', i === acIndex));
    } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        acIndex = Math.max(acIndex - 1, -1);
        items.forEach((el,i) => el.classList.toggle('focused', i === acIndex));
    } else if (e.key === 'Enter' && acIndex >= 0) {
        e.preventDefault();
        const el = items[acIndex];
        selectAc(el.dataset.stopid, el.dataset.name);
    } else if (e.key === 'Escape') {
        closeAc();
    }
});

acInput.addEventListener('blur', () => setTimeout(closeAc, 150));

/* ── Stop info + star button ── */
function renderStopInfo() {
    const el = document.getElementById('haltestellenInfo');
    if (!el || !CURRENT_STOP.stopId) return;
    const text = tpl(I18N.haltestellenInfo_tpl, {name: CURRENT_STOP.name || CURRENT_STOP.stopId, id: CURRENT_STOP.stopId});
    el.innerHTML = `<span>${esc(text)}</span>`
        + `<button id="star-btn" onclick="toggleFav()" style="background:transparent;border:none;padding:0 4px;cursor:pointer;font-size:1.2rem;" title="${esc(I18N.star_add)}">
               <i class="bi bi-star"></i>
           </button>`;
    updateStar();
}

/* ── GPS ── */
function getDist(lat1, lon1, lat2, lon2) {
    const R = 6371000, dLat = (lat2-lat1)*Math.PI/180, dLon = (lon2-lon1)*Math.PI/180;
    const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180)*Math.cos(lat2*Math.PI/180)*Math.sin(dLon/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

function findeNaechsteHaltestelle() {
    if (!navigator.geolocation) { alert(I18N.gps_not_supported); return; }
    navigator.geolocation.getCurrentPosition(async pos => {
        try {
            const data = await loadAcData(); // reuse already-loaded data
            let minDist = Infinity, best = null;
            for (const h of data) {
                const d = getDist(h.lat, h.lon, pos.coords.latitude, pos.coords.longitude);
                if (d < minDist) { minDist = d; best = h; }
            }
            if (best) {
                localStorage.setItem('wl_stop_id', best.stopId);
                const distText = tpl(I18N.gps_distance_tpl, {m: String(Math.round(minDist))});
                localStorage.setItem('gps_info_extra', distText);
                const params = new URLSearchParams(window.location.search);
                params.set('stopId', best.stopId);
                params.set('tab', 'abfahrt');
                window.location.href = '?' + params.toString();
            } else {
                alert(I18N.gps_no_stop_found);
            }
        } catch { alert(I18N.gps_load_error); }
    }, () => alert(I18N.gps_permission_denied));
}

function onFormSubmit() {
    localStorage.setItem('wl_stop_id', document.getElementById('stopId').value);
    localStorage.removeItem('gps_info_extra');
}

/* ── Init ── */
window.addEventListener('DOMContentLoaded', () => {
    // Determine active tab: PHP-set > localStorage
    const savedTab = localStorage.getItem('wl_active_tab');
    const tab = (ACTIVE_TAB !== 'abfahrt') ? ACTIVE_TAB : (savedTab || 'abfahrt');
    switchTab(tab);

    // Auto-load saved stop (only on Abfahrt tab when no stopId in URL)
    if (tab === 'abfahrt' && !CURRENT_STOP.stopId) {
        const saved = localStorage.getItem('wl_stop_id');
        if (saved) {
            document.getElementById('stopId').value = saved;
            document.getElementById('stop-form').submit();
            return;
        }
    }

    // Render stop info with GPS extra
    renderStopInfo();
    if (CURRENT_STOP.stopId) {
        const gpsExtra = localStorage.getItem('gps_info_extra');
        if (gpsExtra && localStorage.getItem('wl_stop_id') === CURRENT_STOP.stopId) {
            const infoEl = document.getElementById('haltestellenInfo');
            const span = infoEl && infoEl.querySelector('span');
            if (span) span.textContent += ' – ' + gpsExtra;
        }
    }
});

// Auto-reload
setTimeout(() => location.reload(), <?= (int)$reloadMs ?>);
</script>
<script>
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('sw.js')
      .then(() => console.log(I18N.pwa_ok))
      .catch(err => console.warn(I18N.pwa_err, err));
}
</script>
</body>
</html>
