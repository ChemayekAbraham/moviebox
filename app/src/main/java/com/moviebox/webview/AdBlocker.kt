package com.moviebox.webview

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

object AdBlocker {

    private val AD_KEYWORDS = listOf(
        "1xbet",
        "1x-bet",
        "1x001",
        "1x002",
        "1x003",
        "linebet",
        "paripesa",
        "waje.bet",
        "bet9ja",
        "betking",
        "sportybet",
        "betway",
        "parimatch",
        "melbet",
        "show-sb.com",
        "sportsnow.top",
        "/games",
        "/live/list",
        "popads",
        "popcash",
        "adsterra",
        "propellerads",
        "monetag",
        "exoclick",
        "juicyads",
        "vungle",
        "mintegral",
        "ironsrc",
        "applovin",
        "adnxs",
        "doubleclick",
        "googleads",
        "googlesyndication",
        "adservice",
        "mgid",
        "outbrain",
        "taboola",
        "adcolony",
        "inmobi",
        "adtrue",
        "adkeeper",
        "clickadu",
        "onclick",
        "trafficjunky",
        "trafficstars",
        "hilltopads",
        "a-ads",
        "richads",
        "cootlogix",
        "pwxcd=", // CloudFront ad-redirect script parameter
        "adfox",
        // Pop-under / redirect networks commonly used on streaming players
        "highperformanceformat",
        "profitablecpm",
        "profitablegatecpm",
        "effectivegatecpm",
        "effectiveratecpm",
        "magsrv",
        "realsrv",
        "pemsrv",
        "tsyndicate",
        "adsco.re",
        "admaven",
        "ad-maven",
        "bidvertiser",
        "revcontent",
        "galaksion",
        "clickaine",
        "zeydoo",
        "pushance",
        "onclckmn",
        "acscdn",
        "oclasrv",
        "popmyads",
        "adskeeper",
        "betwinner",
        "22bet",
        "mostbet",
        "stake.com",
        "tagivi",
        "createlouisville",
        "googletagmanager",
        "google-analytics"
    )

    /** Hosts allowed to serve scripts, frames and API calls. Everything else is treated as ads. */
    private val TRUSTED_RESOURCE_HOSTS = HostPolicy.MOVIEBOX_HOSTS + listOf(
        "hakunaymatata.com",   // MovieBox video CDN
        "alicdn.com",
        "aliyuncs.com",
        "googleapis.com",
        "gstatic.com",
        "cloudfront.net",
        "trasre.com",
        "bytefuntv.com",
        "ytimg.com",
        "youtube.com",
        "youtube-nocookie.com",
        "googlevideo.com",
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "netflix.com",
        "plex.tv"
    )

    private val MEDIA_EXTENSIONS = listOf(
        ".mp4", ".m3u8", ".ts", ".mkv", ".webm", ".m4s", ".mpd", ".vtt", ".srt", ".ass",
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif", ".woff", ".woff2", ".ttf", ".css"
    )

    /**
     * True for scripts / iframes / XHR from domains we don't trust. Media (video, subtitles,
     * images, fonts) is always allowed so movies from any CDN keep playing.
     */
    fun isUntrustedThirdParty(request: android.webkit.WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (!HostPolicy.isWebUrl(url)) return false
        val host = HostPolicy.hostOf(url)
        if (host.isEmpty()) return false
        if (TRUSTED_RESOURCE_HOSTS.any { host == it || host.endsWith(".$it") }) return false

        val headers = request.requestHeaders ?: emptyMap()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value?.lowercase(Locale.ROOT) ?: ""
        val hasRange = headers.keys.any { it.equals("Range", true) }
        val path = (request.url.path ?: "").lowercase(Locale.ROOT)

        val isMedia = hasRange ||
                accept.startsWith("image/") || accept.startsWith("video/") || accept.startsWith("audio/") ||
                accept.startsWith("text/css") ||
                MEDIA_EXTENSIONS.any { path.endsWith(it) }
        return !isMedia
    }

    private val UPDATE_KEYWORDS = listOf(
        ".apk",
        "/app/update",
        "/version/check",
        "/upgrade",
        "app-update",
        "packageurl",
        "check_update",
        "miniapp_deeplink"
    )

    fun isAdOrUpdate(url: String): Boolean {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val host = HostPolicy.hostOf(url)

        // Always whitelist official movie players and streaming platforms (checked by real host)
        if (HostPolicy.isMovieBoxHost(url) ||
            host == "netflix.com" || host.endsWith(".netflix.com") ||
            host == "plex.tv" || host.endsWith(".plex.tv")) {
            // Still block betting sections and APK auto-updates even on MovieBox servers
            val path = (try { android.net.Uri.parse(url).path } catch (e: Exception) { null })
                ?.lowercase(Locale.ROOT) ?: ""
            return path.startsWith("/games") ||
                    path.startsWith("/live/list") ||
                    UPDATE_KEYWORDS.any { lowerUrl.contains(it) }
        }

        // Block external APK updates and version checks
        for (updateKey in UPDATE_KEYWORDS) {
            if (lowerUrl.contains(updateKey)) {
                return true
            }
        }

        for (keyword in AD_KEYWORDS) {
            if (lowerUrl.contains(keyword)) {
                return true
            }
        }
        return false
    }

    fun isAd(url: String): Boolean = isAdOrUpdate(url)

    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    const val AD_CLEANING_JS = """
        javascript:(function() {
            var css = `
                iframe[src*="1xbet"], iframe[src*="waje"], iframe[src*="sportsnow"],
                div[id*="ad_"], div[class*="ad_"], div[class*="ad-"], div[class*="ads-"],
                div[class*="banner"], div[class*="popup"],
                a[href*="1xbet"], a[href*="waje.bet"], a[href*="sportsnow.top"],
                a[href*="/games"], a[href*="/live/list"],
                .adsbygoogle, .popunder, #pop-ad, .floating-ad,
                div[id^="google_ads"], div[id*="container-banner"],
                .live-card-box, .live-card-list, .live-card-item-box,
                div[class*="bottom-[70px]"], div[class*="from-[#1cb7ff]"],
                div[class*="download-app"], div[class*="app-download"],
                div[class*="guide-download"], div[class*="guide-top"], div[class*="down-app"],
                div[class*="update-dialog"], div[id*="update_modal"],
                section[class*="fixed"][class*="bottom-0"][class*="md:hidden"],
                footer, .pc-footer, .pc-get-app,
                .slide-nav-content ~ *, .slide-nav-content .line.second,
                .slide-nav .get-app, .slide-nav a.email,
                .h5-social-share, .pc-social-share, .btn-group .copy-btn {
                    display: none !important;
                    visibility: hidden !important;
                    width: 0 !important;
                    height: 0 !important;
                    pointer-events: none !important;
                }
                .btn-group .main-btn:not(.copy-btn) {
                    flex: 1 1 100% !important;
                    width: 100% !important;
                    max-width: 100% !important;
                }
            `;
            var style = document.createElement('style');
            style.type = 'text/css';
            style.appendChild(document.createTextNode(css));
            var parent = document.head || document.documentElement || document.body;
            if (parent) {
                parent.appendChild(style);
            }

            var badKeywords = ['/games', '/live/list', '1xbet', '1x-bet', 'waje.bet', 'sportsnow.top', 'show-sb.com'];
            function isBad(s) {
                if (!s) return false;
                var str = String(s).toLowerCase();
                return badKeywords.some(function(k) { return str.indexOf(k) !== -1; });
            }

            // Block SPA routing to betting/games
            var origPush = history.pushState;
            history.pushState = function(state, title, url) {
                if (isBad(url)) return;
                return origPush.apply(this, arguments);
            };
            var origReplace = history.replaceState;
            history.replaceState = function(state, title, url) {
                if (isBad(url)) return;
                return origReplace.apply(this, arguments);
            };

            // Intercept link clicks to gambling before navigation
            document.addEventListener('click', function(e) {
                var target = e.target;
                while (target && target !== document) {
                    if (target.tagName === 'A') {
                        var h = target.getAttribute('href') || target.href || '';
                        if (isBad(h)) {
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            return false;
                        }
                    }
                    target = target.parentElement;
                }
            }, true);

            function cleanDOM() {
                // 1. Remove Games, Live, Sport Live, and APK download links from any menu, tab, or navigation
                document.querySelectorAll('li, a, [class*="nav-item"], [class*="tab-item"]').forEach(function(el) {
                    if (el.id === '__nuxt' || el.tagName === 'BODY' || el.tagName === 'HTML') return;
                    var txt = (el.innerText || el.textContent || '').trim().toLowerCase();
                    var href = '';
                    var a = el.querySelector('a') || (el.tagName === 'A' ? el : null);
                    if (a) href = a.getAttribute('href') || '';
                    if (txt === 'games' || txt === 'live' || txt === 'sport live' || txt === 'moviebox app' || txt === 'old moviebox' ||
                        txt === 'video downloader' || txt === 'always find us' ||
                        href === '/games' || href.indexOf('/games') !== -1 || href.indexOf('/live') !== -1 || (href.indexOf('download') !== -1 && txt.indexOf('video') === -1)) {
                        el.style.setProperty('display', 'none', 'important');
                        try { el.remove(); } catch(e) {}
                    }
                });

                // 2. Remove Live sports betting cards section completely
                document.querySelectorAll('.nav-list > *, .pc-nav-list > *').forEach(function(el) {
                    var t = (el.textContent || '').trim().toLowerCase();
                    if (t === 'video downloader') el.style.setProperty('display', 'none', 'important');
                });
                document.querySelectorAll('.live-card-box, .live-card-list, .live-card-item-box').forEach(function(el) {
                    var p = el.closest('.comp-box') || el;
                    p.style.setProperty('display', 'none', 'important');
                    try { p.remove(); } catch(e) {}
                });

                // 3. Remove floating mobile "Get the App" download banner
                document.querySelectorAll('div[class*="bottom-[70px]"], div[class*="from-[#1cb7ff]"]').forEach(function(el) {
                    var p = el.closest('.mobile-only') || el;
                    p.style.setProperty('display', 'none', 'important');
                    try { p.remove(); } catch(e) {}
                });

                // 4. Safe fallback for any element containing "Get the App"
                document.querySelectorAll('span, p, div, button').forEach(function(el) {
                    if (el.children.length === 0) {
                        var txt = (el.textContent || '');
                        if (txt.includes('Get the App to Watch') || txt.includes('Download Free')) {
                            var banner = el.closest('div[class*="bottom-"]') || el.closest('.mobile-only') || el.parentElement;
                            if (banner && banner.id !== '__nuxt' && banner.tagName !== 'BODY') {
                                banner.style.setProperty('display', 'none', 'important');
                                try { banner.remove(); } catch(e) {}
                            }
                        }
                    }
                });

                // 5. Remove any elements with ad links
                var adLinks = document.querySelectorAll('a[href*="/games"], a[href*="1xbet"], a[href*="waje.bet"], a[href*="sportsnow.top"]');
                adLinks.forEach(function(el) {
                    el.style.setProperty('display', 'none', 'important');
                    try { el.remove(); } catch(e) {}
                });
            }

            cleanDOM();
            setInterval(cleanDOM, 300);

            // Auto-recover from dynamic import chunk fetch errors
            window.addEventListener('error', function(e) {
                if (e.message && e.message.includes('dynamically imported module')) {
                    var btns = document.querySelectorAll('button, a, span');
                    for (var i = 0; i < btns.length; i++) {
                        if (btns[i].textContent && btns[i].textContent.includes('clear errors')) {
                            btns[i].click();
                            return;
                        }
                    }
                    setTimeout(function() { location.reload(); }, 500);
                }
            });

            // Neutralize unwanted ad popups & new windows
            window.open = function() { return null; };

            // Strip target=_blank so ad links can't spawn pop-unders
            document.addEventListener('click', function(e) {
                var t = e.target;
                while (t && t !== document) {
                    if (t.tagName === 'A' && t.target === '_blank') { t.removeAttribute('target'); }
                    t = t.parentElement;
                }
            }, true);
        })();
    """
}
