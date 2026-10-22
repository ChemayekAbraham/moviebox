package com.moviebox.webview

/**
 * Makes the MovieBox (ArtPlayer) controls auto-hide during playback on touchscreens.
 *
 * ArtPlayer only hides its controls when the "mouse" is not over the player:
 *  - `isHover` is updated from document `mousemove` and is true while the pointer is over the
 *    control bar, which blocks the 3-second auto-hide.
 *  - The `art-hover` class (added on `mouseenter`) forces the control bar visible via CSS.
 * In a WebView a tap generates emulated mouse events but never a `mouseleave`, so after tapping
 * play/pause the pointer is considered "parked" on the controls forever and they never hide.
 *
 * After each touch on the player we tell ArtPlayer the pointer has left, so its normal
 * auto-hide timer works. Note: no `$` characters may appear in the script (Kotlin templates).
 */
object PlayerAutoHideFix {

    const val SCRIPT = """
        javascript:(function() {
            if (window.__mbPlayerAutoHide) return;
            window.__mbPlayerAutoHide = true;

            var timer = null;
            function releasePointer() {
                var players = document.querySelectorAll('.art-video-player');
                if (!players.length) return;
                // Event path is [document, window] -> ArtPlayer sets isHover = false
                document.dispatchEvent(new MouseEvent('mousemove', { bubbles: true }));
                for (var i = 0; i < players.length; i++) {
                    players[i].dispatchEvent(new MouseEvent('mouseleave'));
                }
            }

            // Emulated mouse events fire right after touchend, so release a moment later
            document.addEventListener('touchend', function(e) {
                var t = e.target;
                if (!t || !t.closest || !t.closest('.art-video-player')) return;
                clearTimeout(timer);
                timer = setTimeout(releasePointer, 600);
            }, true);
        })();
    """
}
