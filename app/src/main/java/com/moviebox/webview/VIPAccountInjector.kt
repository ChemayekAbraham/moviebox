package com.moviebox.webview

import android.webkit.CookieManager

object VIPAccountInjector {

    const val USER_NAME = "DJ SHIRLEY BEATS"
    const val USER_ID = "88888888"

    fun injectCookies(domain: String) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val cookies = listOf(
            "is_login=1",
            "is_vip=1",
            "vip=1",
            "vip_status=1",
            "vip_level=9",
            "userName=DJ%20SHIRLEY%20BEATS",
            "nickname=DJ%20SHIRLEY%20BEATS",
            "user_id=$USER_ID",
            "token=vip_token_dj_shirley_beats_lifetime",
            "member_type=VIP_LIFETIME"
        )
        for (cookie in cookies) {
            cookieManager.setCookie("https://$domain", "$cookie; Domain=.$domain; Path=/; Max-Age=315360000")
        }
        cookieManager.flush()
    }

    const val INJECTION_SCRIPT = """
        javascript:(function() {
            var userName = "DJ SHIRLEY BEATS";
            var userId = "88888888";
            var userObj = {
                id: userId,
                userId: userId,
                user_id: userId,
                userName: userName,
                name: userName,
                nickName: userName,
                nickname: userName,
                avatar: "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=200&q=80",
                isVip: true,
                is_vip: 1,
                vip: 1,
                vipLevel: 9,
                vip_level: 9,
                vipStatus: 1,
                vip_status: 1,
                memberType: "LIFETIME_VIP",
                member_type: "LIFETIME_VIP",
                isPremium: true,
                premium: true,
                vipExpireTime: "3000-01-30 23:59:59",
                vip_expire_time: 32506185600,
                token: "vip_token_dj_shirley_beats_lifetime",
                isLogin: true,
                is_login: true,
                status: 1
            };

            var userStr = JSON.stringify(userObj);
            var storageKeys = [
                'userInfo', 'user_info', 'user', 'currentUser', 'account',
                'loginUser', 'profile', 'auth_user', 'moviebox_user',
                'login_state', 'auth'
            ];

            storageKeys.forEach(function(k) {
                try {
                    localStorage.setItem(k, userStr);
                    sessionStorage.setItem(k, userStr);
                } catch(e) {}
            });

            try {
                localStorage.setItem('is_vip', '1');
                localStorage.setItem('vip', '1');
                localStorage.setItem('isLogin', 'true');
                localStorage.setItem('token', 'vip_token_dj_shirley_beats_lifetime');
                localStorage.setItem('userName', userName);
                localStorage.setItem('nickname', userName);
            } catch(e) {}

            function updateLoginBadges() {
                var loginElements = document.querySelectorAll('a, button, span, div, p');
                loginElements.forEach(function(el) {
                    var txt = (el.innerText || '').trim();
                    if (txt === 'Log in' || txt === 'Login' || txt === 'Sign in' || txt === 'Sign In') {
                        el.innerHTML = '<span style="color:#FBBF24; font-weight:800; display:inline-flex; align-items:center; gap:4px; font-size:12px;">👑 ' + userName + ' <span style="background:#E50914; color:#fff; font-size:9px; padding:1px 5px; border-radius:3px; margin-left:2px;">VIP</span></span>';
                        el.style.pointerEvents = 'none';
                    }
                });

                // Suppress login dialogs and popups asking user to log in
                var modals = document.querySelectorAll('[class*="login-dialog"], [class*="login_dialog"], [class*="login-modal"], [class*="auth-modal"], [id*="login-modal"], [class*="login-wrap"]');
                modals.forEach(function(m) {
                    m.style.display = 'none';
                });
            }

            updateLoginBadges();
            setInterval(updateLoginBadges, 1000);
        })();
    """
}
