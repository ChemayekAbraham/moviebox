package com.moviebox.webview

/**
 * Adds the in-app download experience to MovieBox's player page:
 *  - One red "Download" button under the player (the site's own "Download · APP" button is removed).
 *  - Tapping it opens a sheet where you tick any number of episodes (any season) and pick quality.
 *  - Each ticked episode is resolved through MovieBox's play API to a direct MP4 and handed to
 *    Android's DownloadManager via the [BRIDGE_NAME] JavaScript bridge.
 */
object VideoDownloadInjector {

    const val BRIDGE_NAME = "MovieBoxNative"

    const val SCRIPT = """
        javascript:(function() {
            if (window.__mbDownloadHooked) return;
            window.__mbDownloadHooked = true;

            var MB = { detail: null, quality: 0, season: 1, selected: {}, streams: {}, sizes: {}, pending: {} };
            var NATIVE = window.MovieBoxNative;

            /* ---------------- data ---------------- */
            function detailPathFromUrl() {
                var m = location.pathname.match(/\/(?:movies|moviesDetail)\/([^\/?#]+)/);
                return m ? m[1] : null;
            }
            function loadDetail() {
                var path = detailPathFromUrl();
                if (!path) return Promise.reject('no-detail');
                if (MB.detail && MB.detail.path === path) return Promise.resolve(MB.detail);
                return fetch('https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=' + encodeURIComponent(path), { credentials: 'include' })
                    .then(function(r) { return r.json(); })
                    .then(function(j) {
                        var d = j.data || {}, s = d.subject || {}, res = d.resource || {};
                        var seasons = (res.seasons || []).filter(function(x) { return x.maxEp > 0; });
                        var qualities = {};
                        (res.seasons || []).forEach(function(x) {
                            (x.resolutions || []).forEach(function(r) { qualities[r.resolution] = true; });
                        });
                        MB.streams = {}; MB.sizes = {}; MB.pending = {};
                        MB.detail = {
                            path: path,
                            subjectId: s.subjectId,
                            title: s.title || document.title,
                            isSeries: s.subjectType === 2 && seasons.length > 0,
                            seasons: seasons,
                            qualities: Object.keys(qualities).map(Number).sort(function(a, b) { return a - b; })
                        };
                        if (!MB.detail.qualities.length) MB.detail.qualities = [360, 480, 1080];
                        return MB.detail;
                    });
            }
            function getStreams(se, ep) {
                var d = MB.detail;
                var url = location.origin + '/wefeed-h5api-bff/subject/play?subjectId=' + d.subjectId +
                    '&se=' + se + '&ep=' + ep + '&detailPath=' + encodeURIComponent(d.path);
                return fetch(url, { credentials: 'include' })
                    .then(function(r) { return r.json(); })
                    .then(function(j) { return (j.data && j.data.streams) || []; });
            }
            function pickStream(streams, wanted) {
                var list = streams.filter(function(s) { return s.url; })
                    .sort(function(a, b) { return parseInt(b.resolutions) - parseInt(a.resolutions); });
                for (var i = 0; i < list.length; i++) {
                    if (parseInt(list[i].resolutions) <= wanted) return list[i];
                }
                return list[list.length - 1];
            }
            function currentPlayerQuality() {
                var vals = document.querySelectorAll('.art-selector-value');
                for (var i = 0; i < vals.length; i++) {
                    var t = (vals[i].innerText || '').trim();
                    if (/^\d{3,4}P$/i.test(t)) return parseInt(t);
                }
                return 0;
            }
            function pad(n) { return (n < 10 ? '0' : '') + n; }
            function fmtSize(b) {
                b = parseInt(b) || 0;
                if (b >= 1073741824) return (b / 1073741824).toFixed(2).replace(/\.?0+$/, '') + ' GB';
                if (b >= 1048576) return Math.round(b / 1048576) + ' MB';
                return Math.max(1, Math.round(b / 1024)) + ' KB';
            }
            function shortSize(b) {
                return b >= 1073741824 ? (b / 1073741824).toFixed(1) + 'G' : Math.round(b / 1048576) + 'M';
            }

            /* ---------------- size estimation (data cost) ---------------- */
            function streamsFor(se, ep) {
                var k = se + '-' + ep;
                if (!MB.streams[k]) {
                    MB.streams[k] = getStreams(se, ep).catch(function() { delete MB.streams[k]; return []; });
                }
                return MB.streams[k];
            }
            var probeCbs = {}, probeSeq = 0;
            window.__mbSizeCb = function(token, bytes) {
                var cb = probeCbs[token]; delete probeCbs[token];
                if (cb) cb(bytes);
            };
            function probeSize(url) {
                return new Promise(function(resolve) {
                    if (!NATIVE || !NATIVE.probeSize) { resolve(0); return; }
                    var t = 'p' + (++probeSeq);
                    probeCbs[t] = function(b) { resolve(b > 0 ? b : 0); };
                    try { NATIVE.probeSize(t, url, location.href); } catch (e) { delete probeCbs[t]; resolve(0); }
                });
            }
            function streamSize(s) {
                if (!s) return Promise.resolve(0);
                var b = parseInt(s.size) || 0;
                return b > 0 ? Promise.resolve(b) : (s.url ? probeSize(s.url) : Promise.resolve(0));
            }
            function sizeKey(se, ep, q) { return se + '-' + ep + '-' + q; }
            /* Fetch the size of one episode/movie at a quality; re-renders the sheet when known. */
            function ensureSize(se, ep, q) {
                var k = sizeKey(se, ep, q);
                if (k in MB.sizes || MB.pending[k]) return;
                MB.pending[k] = true;
                streamsFor(se, ep).then(function(streams) {
                    var s = pickStream(streams, q);
                    if (!s && !MB.detail.isSeries) {
                        var v = document.querySelector('video');
                        var src = v ? (v.currentSrc || v.src) : '';
                        if (src && src.indexOf('blob:') !== 0) s = { url: src };
                    }
                    return streamSize(s);
                }).catch(function() { return 0; }).then(function(b) {
                    delete MB.pending[k];
                    MB.sizes[k] = b || 0;
                    renderSheet();
                });
            }
            function selectedJobs() {
                var d = MB.detail;
                return d.isSeries
                    ? Object.keys(MB.selected).map(function(k) { var p = k.split('-'); return { se: +p[0], ep: +p[1] }; })
                        .sort(function(a, b) { return a.se - b.se || a.ep - b.ep; })
                    : [{ se: 0, ep: 0 }];
            }
            function freeSpace() {
                try { return (NATIVE && NATIVE.freeSpace) ? Number(NATIVE.freeSpace()) || 0 : 0; } catch (e) { return 0; }
            }
            /* Total data for the current selection at the current quality. */
            function estimate() {
                var jobs = selectedJobs(), r = { count: jobs.length, total: 0, known: 0, unknown: 0, waiting: 0 };
                jobs.forEach(function(j) {
                    var k = sizeKey(j.se, j.ep, MB.quality);
                    if (k in MB.sizes) {
                        if (MB.sizes[k] > 0) { r.total += MB.sizes[k]; r.known++; } else r.unknown++;
                    } else {
                        r.waiting++;
                        ensureSize(j.se, j.ep, MB.quality);
                    }
                });
                return r;
            }
            function dataBox(est) {
                if (!est.count) return '';
                var free = freeSpace(), main, sub = [], cls = '';
                if (est.waiting && !est.known) {
                    main = '<span class="mbdl-spin"></span>Calculating\u2026';
                } else if (!est.known) {
                    main = 'Unknown';
                } else {
                    main = (est.waiting || est.unknown ? '\u2248 ' : '') + fmtSize(est.total) + (est.waiting || est.unknown ? '+' : '');
                }
                sub.push(est.count + ' ' + (MB.detail.isSeries ? 'episode' + (est.count > 1 ? 's' : '') : 'movie') + ' \u00b7 ' + MB.quality + 'P');
                if (est.waiting && est.known) sub.push('checking ' + est.waiting + ' more\u2026');
                if (est.unknown) sub.push(est.unknown + ' size unknown');
                if (free) sub.push(fmtSize(free) + ' free');
                var warn = '';
                if (free && est.total > free) {
                    cls = ' warn';
                    warn = '<div class="mbdl-data-warn">\u26a0 Not enough storage for this download</div>';
                }
                return '<div class="mbdl-data' + cls + '"><div class="mbdl-data-l"><span>\ud83d\udcf6 Data needed</span><b>' + main + '</b></div>' +
                    '<div class="mbdl-data-s">' + sub.join(' \u00b7 ') + '</div>' + warn + '</div>';
            }

            /* ---------------- styles ---------------- */
            function ensureStyle() {
                if (document.getElementById('mbdl-style')) return;
                var css =
                    '.mbdl-backdrop{position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,.6);display:flex;align-items:flex-end;opacity:0;transition:opacity .2s}' +
                    '.mbdl-backdrop.show{opacity:1}' +
                    '.mbdl-sheet{width:100%;max-height:82vh;overflow-y:auto;background:#181A24;border-radius:20px 20px 0 0;padding:10px 16px 18px;box-sizing:border-box;transform:translateY(100%);transition:transform .25s ease;font-family:inherit;color:#fff}' +
                    '.mbdl-backdrop.show .mbdl-sheet{transform:translateY(0)}' +
                    '.mbdl-handle{width:40px;height:4px;border-radius:2px;background:#3a3d4d;margin:0 auto 12px}' +
                    '.mbdl-head{display:flex;justify-content:space-between;align-items:flex-start;margin-bottom:6px}' +
                    '.mbdl-title{font-size:18px;font-weight:800}' +
                    '.mbdl-sub{font-size:12px;color:#9CA3AF;margin-top:2px}' +
                    '.mbdl-x{font-size:20px;color:#9CA3AF;padding:2px 6px;cursor:pointer}' +
                    '.mbdl-label{font-size:12px;color:#9CA3AF;margin:14px 0 8px;text-transform:uppercase;letter-spacing:.5px;font-weight:700}' +
                    '.mbdl-chips{display:flex;gap:8px;flex-wrap:wrap}' +
                    '.mbdl-chip{padding:7px 14px;border-radius:999px;background:#222533;color:#d1d5db;font-size:13px;font-weight:700;cursor:pointer;border:1px solid transparent}' +
                    '.mbdl-chip.on{background:rgba(229,9,20,.15);color:#fff;border-color:#E50914}' +
                    '.mbdl-row{display:flex;justify-content:space-between;align-items:center;margin:16px 0 8px}' +
                    '.mbdl-row span:first-child{font-size:12px;color:#9CA3AF;text-transform:uppercase;letter-spacing:.5px;font-weight:700}' +
                    '.mbdl-all{font-size:13px;color:#FF5A5F;font-weight:700;cursor:pointer}' +
                    '.mbdl-grid{display:grid;grid-template-columns:repeat(5,1fr);gap:8px}' +
                    '.mbdl-ep{position:relative;height:46px;border-radius:10px;background:#222533;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:14px;color:#d1d5db;cursor:pointer;border:1.5px solid transparent;transition:all .15s}' +
                    '.mbdl-ep .mbdl-tick{position:absolute;top:-6px;right:-6px;width:18px;height:18px;border-radius:50%;background:#E50914;color:#fff;font-size:11px;display:none;align-items:center;justify-content:center;box-shadow:0 2px 6px rgba(0,0,0,.4)}' +
                    '.mbdl-ep.on{border-color:#E50914;background:rgba(229,9,20,.15);color:#fff}' +
                    '.mbdl-ep.on .mbdl-tick{display:flex}' +
                    '.mbdl-go{margin-top:18px;padding:14px;border-radius:12px;text-align:center;font-weight:800;font-size:15px;color:#fff;background:linear-gradient(90deg,#E50914,#FF5A5F);box-shadow:0 6px 18px rgba(229,9,20,.35);cursor:pointer}' +
                    '.mbdl-go.off{opacity:.4;pointer-events:none}' +
                    '.mbdl-note{font-size:11px;color:#6B7280;text-align:center;margin-top:8px}' +
                    '.mbdl-chip small{display:block;font-size:10px;font-weight:600;color:#9CA3AF;text-align:center;margin-top:1px}' +
                    '.mbdl-chip.on small{color:#FFB4B6}' +
                    '.mbdl-ep{flex-direction:column;line-height:1.1}' +
                    '.mbdl-ep small{font-size:9px;font-weight:600;color:#FFB4B6;margin-top:2px}' +
                    '.mbdl-data{margin-top:16px;padding:12px 14px;border-radius:12px;background:linear-gradient(135deg,rgba(255,90,95,.10),rgba(34,37,51,.9));border:1px solid rgba(255,90,95,.25)}' +
                    '.mbdl-data.warn{border-color:#F59E0B;background:linear-gradient(135deg,rgba(245,158,11,.12),rgba(34,37,51,.9))}' +
                    '.mbdl-data-l{display:flex;justify-content:space-between;align-items:center}' +
                    '.mbdl-data-l span{font-size:13px;color:#d1d5db;font-weight:700}' +
                    '.mbdl-data-l b{font-size:20px;font-weight:900;color:#fff;display:flex;align-items:center;gap:8px}' +
                    '.mbdl-data-s{font-size:11px;color:#9CA3AF;margin-top:4px}' +
                    '.mbdl-data-warn{font-size:12px;color:#FBBF24;font-weight:700;margin-top:6px}' +
                    '.mbdl-spin{width:14px;height:14px;border:2px solid rgba(255,255,255,.25);border-top-color:#FF5A5F;border-radius:50%;display:inline-block;animation:mbdlspin .8s linear infinite}' +
                    '@keyframes mbdlspin{to{transform:rotate(360deg)}}' +
                    '#mb-native-dl{margin:12px;display:flex;align-items:center;justify-content:center;gap:10px;padding:13px 16px;border-radius:12px;font-weight:800;font-size:15px;color:#fff;cursor:pointer;background:linear-gradient(90deg,#E50914,#FF5A5F);box-shadow:0 6px 18px rgba(229,9,20,.35)}' +
                    '#mb-native-dl .q{background:rgba(0,0,0,.25);padding:2px 8px;border-radius:8px;font-size:11px}' +
                    '#mb-native-dl.mbdl-detail{margin:12px 0;width:100%;box-sizing:border-box}' +
                    '#mb-native-dl.mbdl-float{position:fixed;left:16px;right:16px;bottom:16px;margin:0;z-index:2147483000;animation:mbdlup .3s ease}' +
                    '#mb-native-dl:active{transform:scale(.98)}' +
                    '@keyframes mbdlup{from{transform:translateY(120%);opacity:0}to{transform:none;opacity:1}}';
                var st = document.createElement('style');
                st.id = 'mbdl-style';
                st.textContent = css;
                (document.head || document.documentElement).appendChild(st);
            }

            /* ---------------- sheet ---------------- */
            function closeSheet() {
                var b = document.getElementById('mbdl-backdrop');
                if (!b) return;
                b.id = '';
                b.classList.remove('show');
                setTimeout(function() { if (b.parentNode) b.parentNode.removeChild(b); }, 250);
            }

            function selectedCount() { return Object.keys(MB.selected).length; }

            function renderSheet() {
                var d = MB.detail, sheet = document.querySelector('#mbdl-backdrop .mbdl-sheet');
                if (!sheet) return;
                var html = '<div class="mbdl-handle"></div>' +
                    '<div class="mbdl-head"><div><div class="mbdl-title">\u2b07 Download</div>' +
                    '<div class="mbdl-sub">' + d.title + '</div></div><div class="mbdl-x" data-a="close">\u2715</div></div>';

                html += '<div class="mbdl-label">Quality</div><div class="mbdl-chips">';
                d.qualities.forEach(function(q) {
                    var chipSize = '';
                    if (!d.isSeries) {
                        var sk = sizeKey(0, 0, q);
                        if (sk in MB.sizes) { if (MB.sizes[sk] > 0) chipSize = '<small>' + fmtSize(MB.sizes[sk]) + '</small>'; }
                        else { ensureSize(0, 0, q); chipSize = '<small>\u2026</small>'; }
                    }
                    html += '<div class="mbdl-chip' + (q === MB.quality ? ' on' : '') + '" data-a="q" data-v="' + q + '">' + q + 'P' + chipSize + '</div>';
                });
                html += '</div>';

                var est = estimate();
                var costTxt = (est.known && !est.waiting) ? ' \u00b7 ' + (est.unknown ? '\u2248 ' : '') + fmtSize(est.total) : '';

                if (d.isSeries) {
                    if (d.seasons.length > 1) {
                        html += '<div class="mbdl-label">Season</div><div class="mbdl-chips">';
                        d.seasons.forEach(function(s) {
                            html += '<div class="mbdl-chip' + (s.se === MB.season ? ' on' : '') + '" data-a="s" data-v="' + s.se + '">Season ' + s.se + '</div>';
                        });
                        html += '</div>';
                    }
                    var season = d.seasons.filter(function(s) { return s.se === MB.season; })[0] || d.seasons[0];
                    var allOn = true;
                    for (var e = 1; e <= season.maxEp; e++) if (!MB.selected[season.se + '-' + e]) allOn = false;
                    html += '<div class="mbdl-row"><span>Episodes (' + season.maxEp + ')</span>' +
                        '<span class="mbdl-all" data-a="all">' + (allOn ? 'Clear all' : 'Select all') + '</span></div><div class="mbdl-grid">';
                    for (var ep = 1; ep <= season.maxEp; ep++) {
                        var key = season.se + '-' + ep;
                        var epSize = MB.sizes[sizeKey(season.se, ep, MB.quality)];
                        html += '<div class="mbdl-ep' + (MB.selected[key] ? ' on' : '') + '" data-a="ep" data-v="' + key + '">' +
                            ep + (MB.selected[key] && epSize > 0 ? '<small>' + shortSize(epSize) + '</small>' : '') +
                            '<span class="mbdl-tick">\u2713</span></div>';
                    }
                    html += '</div>';
                    var n = selectedCount();
                    html += dataBox(est);
                    html += '<div class="mbdl-go' + (n ? '' : ' off') + '" data-a="go">' +
                        (n ? 'Download ' + n + ' episode' + (n > 1 ? 's' : '') + costTxt : 'Tick the episodes you want') + '</div>';
                    html += '<div class="mbdl-note">You can tick episodes from different seasons</div>';
                } else {
                    html += dataBox(est);
                    html += '<div class="mbdl-go" data-a="go">Download movie' + costTxt + '</div>';
                }
                var scroll = sheet.scrollTop;
                sheet.innerHTML = html;
                sheet.scrollTop = scroll;
            }

            function openSheet() {
                if (document.getElementById('mbdl-backdrop')) return;
                ensureStyle();
                MB.selected = {};
                var b = document.createElement('div');
                b.id = 'mbdl-backdrop';
                b.className = 'mbdl-backdrop';
                b.innerHTML = '<div class="mbdl-sheet"><div class="mbdl-handle"></div>' +
                    '<div style="padding:36px 0;text-align:center;color:#9CA3AF;font-size:14px;">Loading episodes\u2026</div></div>';
                document.body.appendChild(b);
                b.addEventListener('click', onSheetClick, true);
                requestAnimationFrame(function() { b.classList.add('show'); });

                loadDetail().then(function(d) {
                    if (!MB.quality || d.qualities.indexOf(MB.quality) === -1) {
                        var cur = currentPlayerQuality();
                        MB.quality = d.qualities.indexOf(cur) !== -1 ? cur :
                            (d.qualities.indexOf(480) !== -1 ? 480 : d.qualities[d.qualities.length - 1]);
                    }
                    if (d.isSeries) {
                        var p = new URLSearchParams(location.search);
                        var se = parseInt(p.get('detailSe') || p.get('se')) || d.seasons[0].se;
                        if (!d.seasons.some(function(s) { return s.se === se; })) se = d.seasons[0].se;
                        MB.season = se;
                    }
                    renderSheet();
                }).catch(function() {
                    closeSheet();
                    NATIVE.notify('Could not load episodes. Try again in a moment.');
                });
            }

            function onSheetClick(e) {
                e.stopPropagation();
                if (e.target.classList && e.target.classList.contains('mbdl-backdrop')) { closeSheet(); return; }
                var t = e.target.closest('[data-a]');
                if (!t) return;
                var a = t.getAttribute('data-a'), v = t.getAttribute('data-v');
                var d = MB.detail;
                if (a === 'close') { closeSheet(); return; }
                if (a === 'q') MB.quality = parseInt(v);
                if (a === 's') MB.season = parseInt(v);
                if (a === 'ep') { if (MB.selected[v]) delete MB.selected[v]; else MB.selected[v] = true; }
                if (a === 'all') {
                    var season = d.seasons.filter(function(s) { return s.se === MB.season; })[0];
                    var allOn = true, ep;
                    for (ep = 1; ep <= season.maxEp; ep++) if (!MB.selected[season.se + '-' + ep]) allOn = false;
                    for (ep = 1; ep <= season.maxEp; ep++) {
                        var k = season.se + '-' + ep;
                        if (allOn) delete MB.selected[k]; else MB.selected[k] = true;
                    }
                }
                if (a === 'go') { startDownloads(); return; }
                renderSheet();
            }

            /* ---------------- downloading ---------------- */
            function startDownloads() {
                var d = MB.detail, q = MB.quality;
                var jobs = selectedJobs();
                closeSheet();
                NATIVE.notify('Preparing ' + jobs.length + ' download' + (jobs.length > 1 ? 's' : '') + '\u2026');

                var ok = 0, failed = 0;
                function next(i) {
                    if (i >= jobs.length) {
                        MB.selected = {};
                        NATIVE.notify(failed
                            ? '\u2705 ' + ok + ' added, ' + failed + ' unavailable \u2014 see the Me tab'
                            : '\u2705 ' + ok + ' download' + (ok > 1 ? 's' : '') + ' added \u2014 see the Me tab');
                        return;
                    }
                    var j = jobs[i];
                    getStreams(j.se, j.ep).then(function(streams) {
                        var s = pickStream(streams, q);
                        if (!s && !d.isSeries) {
                            var v = document.querySelector('video');
                            var src = v ? (v.currentSrc || v.src) : '';
                            if (src && src.indexOf('blob:') !== 0) s = { url: src, resolutions: currentPlayerQuality() || q };
                        }
                        if (!s) { failed++; return; }
                        var name = d.title + (d.isSeries ? ' S' + pad(j.se) + 'E' + pad(j.ep) : '');
                        NATIVE.downloadQuiet(s.url, name, parseInt(s.resolutions) + 'P', location.href);
                        ok++;
                    }).catch(function() { failed++; })
                      .then(function() { setTimeout(function() { next(i + 1); }, 250); });
                }
                next(0);
            }

            /* ---------------- page integration ---------------- */
            /* Most specific label first, so "Watch Online" wins over a stray "Play" */
            var WATCH_RES = [/^watch online$/i, /^(watch now|play now|continue watching|resume)$/i, /^(watch|play)$/i];
            function findWatchButton() {
                var els = document.querySelectorAll('button,a,div,span,p');
                for (var r = 0; r < WATCH_RES.length; r++) {
                    for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        if (el.children.length > 3 || el.closest('#mb-native-dl,#mbdl-backdrop')) continue;
                        if (!WATCH_RES[r].test((el.innerText || '').trim())) continue;
                        if (!el.offsetWidth || !el.offsetHeight) continue;
                        /* climb to the actual clickable element */
                        var c = el;
                        for (var k = 0; k < 4 && c && c !== document.body; k++) {
                            if (c.tagName === 'BUTTON' || c.tagName === 'A' || getComputedStyle(c).cursor === 'pointer') return c;
                            c = c.parentElement;
                        }
                        return el;
                    }
                }
                return null;
            }
            function placeButton(btn) {
                var player = document.querySelector('.artplayer-app');
                if (player) {
                    var anchor = player.closest('[class*="h-[203px]"]') || player;
                    anchor.insertAdjacentElement('afterend', btn);
                    btn.className = '';
                    return true;
                }
                var watch = findWatchButton();
                if (watch) {
                    /* if Watch Online sits in a horizontal row, go below the whole row */
                    var p = watch.parentElement, target = watch;
                    if (p && p.children.length > 1) {
                        var cs = getComputedStyle(p);
                        if (cs.display.indexOf('flex') !== -1 && cs.flexDirection.indexOf('row') === 0) target = p;
                    }
                    target.insertAdjacentElement('afterend', btn);
                    btn.className = 'mbdl-detail';
                    return true;
                }
                return false;
            }
            var floatTimer = 0;
            function ensureButton() {
                var path = detailPathFromUrl();
                var btn = document.getElementById('mb-native-dl');
                if (!path) { if (btn) btn.remove(); return; }
                if (btn && (btn.getAttribute('data-path') !== path || !document.body.contains(btn))) { btn.remove(); btn = null; }
                var hasPlayer = !!document.querySelector('.artplayer-app');
                /* upgrade a floating button once the real anchor appears */
                if (btn && btn.classList.contains('mbdl-float') && (hasPlayer || findWatchButton())) { btn.remove(); btn = null; }
                /* moved from details page to player page: re-anchor under the player */
                if (btn && hasPlayer && btn.classList.contains('mbdl-detail')) { btn.remove(); btn = null; }
                if (!btn) {
                    ensureStyle();
                    btn = document.createElement('div');
                    btn.id = 'mb-native-dl';
                    btn.setAttribute('data-path', path);
                    btn.innerHTML = '<span style="font-size:18px;">\u2b07</span><span class="t">Download</span><span class="q"></span>';
                    btn.addEventListener('click', function(e) {
                        e.preventDefault(); e.stopPropagation(); openSheet();
                    }, true);
                    if (!placeButton(btn)) {
                        /* page still rendering: wait a little, then fall back to a floating button */
                        if (!floatTimer) floatTimer = Date.now();
                        if (Date.now() - floatTimer < 3000) return;
                        btn.className = 'mbdl-float';
                        document.body.appendChild(btn);
                    }
                    floatTimer = 0;
                    loadDetail().then(function(d) {
                        btn.querySelector('.t').textContent = d.isSeries ? 'Download Episodes' : 'Download for Offline';
                    }).catch(function() {});
                }
                var q = btn.querySelector('.q'), cur = currentPlayerQuality();
                q.textContent = cur ? cur + 'P' : '';
                q.style.display = cur ? '' : 'none';
            }

            /* Remove the site's own "Download · APP" button from the Refresh / Prev / Next row */
            function removeSiteDownload() {
                document.querySelectorAll('span,div,i,em').forEach(function(el) {
                    if (el.children.length || (el.textContent || '').trim() !== 'APP') return;
                    var item = el;
                    for (var i = 0; i < 6 && item && item.parentElement; i++) {
                        var parentTxt = item.parentElement.innerText || '';
                        if (/Download/.test(item.innerText || '') && /Refresh|Next|Prev/.test(parentTxt)) {
                            item.style.setProperty('display', 'none', 'important');
                            return;
                        }
                        item = item.parentElement;
                    }
                });
            }

            setInterval(function() { ensureButton(); removeSiteDownload(); }, 800);

            /* Any leftover site download button opens our sheet instead of the APK / ad downloader */
            function isDownloadTrigger(el) {
                for (var i = 0; el && i < 6; i++, el = el.parentElement) {
                    if (el.id === 'mb-native-dl' || el.id === 'mbdl-backdrop') return false;
                    var txt = (el.innerText || '').trim();
                    var href = (el.getAttribute && el.getAttribute('href')) || '';
                    if (txt === 'Download This Video' || txt === 'Video Downloader' ||
                        txt === 'Download' || href.indexOf('downloader') !== -1) {
                        return true;
                    }
                }
                return false;
            }
            document.addEventListener('click', function(e) {
                if (!isDownloadTrigger(e.target)) return;
                e.preventDefault();
                e.stopPropagation();
                e.stopImmediatePropagation();
                if (detailPathFromUrl()) openSheet();
                return false;
            }, true);
        })();
    """
}
