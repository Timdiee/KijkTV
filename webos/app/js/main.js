/* KijkTV voor LG webOS - opstarten. */
'use strict';

(function () {
    function boot() {
        var a = KTV.account.get();
        KTV.screens.push(a ? KTV.Home() : KTV.Login());
        // Ontwikkelaarsmodus op de achtergrond verlengen (eens per week).
        setTimeout(function () { KTV.devmode.renewIfNeeded(false); }, 8000);
        // Verder kijken van je andere apparaten ophalen (zodra de zenders er zijn, ook favorieten).
        setTimeout(function () { KTV.syncNow(true); }, 3000);
        setInterval(function () { var t = KTV.screens.top(); if (t && t.syncRefresh) KTV.syncNow(false); }, 60000);
    }

    // Pauzeer het beeld als de app naar de achtergrond gaat (bijv. Home-knop).
    document.addEventListener('visibilitychange', function () {
        var top = KTV.screens.top();
        if (document.hidden && top && top.pause) top.pause();
        if (!document.hidden && top && top.resume && top.root.classList.contains('player')) top.resume();
        if (!document.hidden && top && top.syncRefresh) KTV.syncNow(false);
    });

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
    else boot();
})();
