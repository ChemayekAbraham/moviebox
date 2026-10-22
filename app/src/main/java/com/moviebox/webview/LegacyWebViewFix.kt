package com.moviebox.webview

/**
 * Fallback for phones with an outdated Android System WebView.
 *
 * MovieBox's site is a Nuxt (Vue) app: the server HTML contains every movie card, but with
 * NO poster images, and the banner carousel only starts once the site's JavaScript runs.
 * That JavaScript uses modern syntax (`??`, `replaceAll`, `.at()`) which old WebViews can't
 * parse, so on those phones the page stays frozen: blank thumbnails and a banner stuck on
 * the first slide.
 *
 * This script only acts when the Vue app failed to start (no `__vue_app__` on `#__nuxt`).
 * It reads the cover URLs embedded in the page's `__NUXT_DATA__` payload and inserts the
 * missing <img> tags itself. It is written in plain ES5 on purpose so old WebViews can run it.
 * Note: no `$` characters may appear in the script (Kotlin string templates).
 */
object LegacyWebViewFix {

    /** Chrome/WebView major version the MovieBox site needs to work properly. */
    const val MIN_WEBVIEW_VERSION = 92

    const val SCRIPT = """
        javascript:(function() {
            if (window.__mbLegacyFix) return;
            window.__mbLegacyFix = true;

            function hydrated() {
                var root = document.getElementById('__nuxt');
                return !!(root && root.__vue_app__);
            }

            var cache = null;
            function readData() {
                if (cache) return cache;
                var el = document.getElementById('__NUXT_DATA__');
                if (!el) return null;
                var d;
                try { d = JSON.parse(el.textContent); } catch (e) { return null; }
                function v(i) { return typeof i === 'number' ? d[i] : i; }
                function urlOf(x) {
                    x = v(x);
                    if (x && typeof x === 'object' && x.url != null) x = v(x.url);
                    return (typeof x === 'string' && x.indexOf('http') === 0) ? x : null;
                }
                var covers = {}, banners = [];
                for (var i = 0; i < d.length; i++) {
                    var o = d[i];
                    if (!o || typeof o !== 'object' || Array.isArray(o) || o.detailPath == null) continue;
                    var path = v(o.detailPath);
                    if (typeof path !== 'string') continue;
                    if (o.image != null) {
                        var b = urlOf(o.image);
                        if (b) banners.push(b);
                    }
                    if (o.cover != null && !covers[path]) {
                        var c = urlOf(o.cover);
                        if (c) covers[path] = c;
                    }
                }
                cache = { covers: covers, banners: banners };
                return cache;
            }

            function addImg(box, src) {
                if (!box || !src || box.querySelector('img[data-mb-fix]')) return;
                var img = document.createElement('img');
                img.setAttribute('data-mb-fix', '1');
                img.alt = '';
                img.style.cssText = 'position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;z-index:1;';
                img.src = src;
                box.insertBefore(img, box.firstChild);
            }

            function fillPosters(data) {
                var cards = document.querySelectorAll('a.movie-card');
                for (var i = 0; i < cards.length; i++) {
                    var card = cards[i];
                    if (card.querySelector('img[src^="http"]:not([data-mb-fix])')) continue;
                    var m = (card.getAttribute('href') || '').match(/\/(?:moviesDetail|movies)\/([^\/?#]+)/);
                    var src = m && data.covers[m[1]];
                    var box = card.firstElementChild;
                    if (src && box && box.tagName === 'DIV') addImg(box, src);
                }
            }

            function fillBanner(slides, data) {
                var n = data.banners.length;
                if (!n || !slides.length) return;
                // Infinite carousels render a clone of the last slide first and of the first slide last
                var looped = slides.length === n + 2;
                if (!looped && slides.length !== n) return;
                for (var i = 0; i < slides.length; i++) {
                    var idx = looped ? (i - 1 + n) % n : i;
                    if (!slides[i].querySelector('img[src^="http"]:not([data-mb-fix])')) {
                        addImg(slides[i], data.banners[idx]);
                    }
                }
            }

            function run() {
                if (hydrated()) return true;
                var data = readData();
                if (!data) return false;
                fillPosters(data);
                fillBanner(document.querySelectorAll('.banner-box div.flex-shrink-0.select-none'), data);
                fillBanner(document.querySelectorAll('.banner-box .banner-media'), data);
                return false;
            }

            // Give the real site time to start; only step in if it never does.
            var tries = 0;
            function tick() {
                tries++;
                if (run() || tries > 5) return;
                setTimeout(tick, 2000);
            }
            setTimeout(tick, 4000);
        })();
    """
}
