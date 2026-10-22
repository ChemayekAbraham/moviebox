package com.moviebox.webview

import android.content.Context
import android.webkit.CookieManager

/**
 * Older versions planted a fake "VIP Lifetime" login (cookies + localStorage) on MovieBox sites.
 * That feature has been removed; this wipes whatever it left behind on users' phones.
 */
object LegacyVipCleanup {

    private const val PREF_KEY = "vip_cleanup_v1"
    private const val FAKE_TOKEN = "vip_token_dj_shirley_beats_lifetime"

    private val FAKE_COOKIES = listOf(
        "is_login", "is_vip", "vip", "vip_status", "vip_level",
        "userName", "nickname", "user_id", "token", "member_type"
    )

    /** Expires the fake cookies once per install. */
    fun clearCookiesOnce(ctx: Context, hosts: Collection<String>) {
        val prefs = ctx.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY, false)) return
        val cm = CookieManager.getInstance()
        hosts.forEach { host ->
            FAKE_COOKIES.forEach { name ->
                cm.setCookie("https://$host", "$name=; Domain=.$host; Path=/; Max-Age=0")
                cm.setCookie("https://$host", "$name=; Path=/; Max-Age=0")
            }
        }
        cm.flush()
        prefs.edit().putBoolean(PREF_KEY, true).apply()
    }

    /** Removes only storage entries written by the old fake-VIP script (safe to run on every page). */
    const val STORAGE_CLEANUP_JS = """
        (function() {
            if (window.__vipCleaned) return;
            window.__vipCleaned = true;
            var FAKE = '""" + FAKE_TOKEN + """';
            [localStorage, sessionStorage].forEach(function(store) {
                try {
                    for (var i = store.length - 1; i >= 0; i--) {
                        var k = store.key(i), v = store.getItem(k) || '';
                        if (v.indexOf(FAKE) !== -1 || v === 'DJ SHIRLEY BEATS') store.removeItem(k);
                    }
                    if (store.getItem('is_vip') === '1') store.removeItem('is_vip');
                    if (store.getItem('vip') === '1') store.removeItem('vip');
                } catch (e) {}
            });
        })();
    """
}
