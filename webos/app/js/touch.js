/* KijkTV - bediening met je vinger (iPhone). Op de LG-tv doet dit bestand niets.
 * - Het tv-scherm (1080 hoog) wordt passend gemaakt voor de telefoon.
 * - Tikken = OK, vegen = scrollen, lang drukken = OK ingedrukt houden.
 * - Een terugknop linksboven vervangt de terugtoets van de afstandsbediening.
 * - In de speler: tik = OK, vegen omhoog/omlaag = andere zender, links/rechts = spoelen.
 */
'use strict';

(function () {
    var touch = KTV.isIOS || window.KTV_TOUCH === true;
    if (!touch) return;
    KTV.touch = true;
    var root = document.documentElement;
    root.classList.add('touch');

    /* ---- Passend maken: hoogte 1080, breedte zoveel als de telefoon breed is ---- */
    var meta = document.querySelector('meta[name=viewport]');
    if (meta) meta.setAttribute('content', 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover');
    var scale = 1;
    function fit() {
        var h = window.innerHeight || 390, w = window.innerWidth || 844;
        scale = h / 1080;
        var lw = Math.round(w / scale);
        root.style.width = lw + 'px';
        root.style.height = '1080px';
        document.body.style.width = lw + 'px';
        document.body.style.height = '1080px';
        root.style.zoom = scale;
    }
    fit();
    window.addEventListener('resize', function () { setTimeout(fit, 50); });

    /* ---- Toetsen nadoen ---- */
    function key(code, type) {
        var ev = document.createEvent('Event');
        ev.initEvent(type, true, true);
        Object.defineProperty(ev, 'keyCode', { value: code });
        Object.defineProperty(ev, 'repeat', { value: false });
        document.dispatchEvent(ev);
    }
    function press(code) { key(code, 'keydown'); key(code, 'keyup'); }
    KTV.pressKey = press;

    /* ---- Terugknop ---- */
    var back = document.createElement('div');
    back.className = 'touch-back';
    back.innerHTML = '<svg viewBox="0 0 24 24"><path d="M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z"/></svg>';
    back.addEventListener('click', function (e) {
        e.stopPropagation();
        var a = document.activeElement;
        if (a && a.tagName === 'INPUT') a.blur();
        // In de speler is "terug" op de afstandsbediening "vorige zender"; met de vinger betekent het: stoppen.
        if (topIsPlayer()) { KTV.screens.pop(); return; }
        press(KTV.KEY.ESC);
    });
    function ready(fn) { if (document.body) fn(); else document.addEventListener('DOMContentLoaded', fn); }
    ready(function () { document.body.appendChild(back); });
    setInterval(function () {
        var st = KTV.screens && KTV.screens.stack;
        var can = (st && st.length > 1) || !!document.querySelector('.layer.open');
        root.classList.toggle('can-back', !!can);
    }, 250);

    function topIsPlayer() {
        var t = KTV.screens && KTV.screens.top();
        return !!(t && t.root.classList.contains('player')) && !document.querySelector('.layer.open');
    }

    /* ---- Vegen en lang drukken ---- */
    var drag = null, suppressUntil = 0;

    function findUp(n, test) {
        while (n && n !== document.body && n !== document) { if (test(n)) return n; n = n.parentNode; }
        return null;
    }
    function isF(n) { return n.classList && n.classList.contains('f'); }
    function fire(target, type) {
        var ev = document.createEvent('MouseEvents');
        ev.initMouseEvent(type, true, true, window, 0, 0, 0, 0, 0, false, false, false, false, 0, null);
        target.dispatchEvent(ev);
    }

    document.addEventListener('touchstart', function (e) {
        if (e.touches.length > 1) { drag = null; return; }
        var t = e.touches[0];
        var target = e.target;
        var d = drag = {
            x: t.clientX, y: t.clientY, moved: false, axis: null, target: target,
            fEl: findUp(target, isF),
            comp: findUp(target, function (n) { return !!n._comp; }),
            vscroll: findUp(target, function (n) {
                return n.classList && (n.classList.contains('home-rows') || n.classList.contains('search-rows') ||
                    n.classList.contains('dialog') || n.classList.contains('scrolly'));
            }),
            player: topIsPlayer() && !findUp(target, function (n) { return n === back; })
        };
        if (d.comp) d.startScroll = d.comp._comp.scroll || 0;
        if (d.comp) d.startOffset = d.comp._comp.offset || 0;
        if (d.vscroll) d.startTop = d.vscroll.scrollTop;

        if (d.player && !d.fEl) {
            // Speler: lang drukken = OK ingedrukt houden (pauze bij live tv).
            d.longTimer = setTimeout(function () {
                if (drag !== d || d.moved) return;
                d.longFired = true;
                key(KTV.KEY.ENTER, 'keydown');
                setTimeout(function () { key(KTV.KEY.ENTER, 'keyup'); }, 750);
            }, 450);
        } else if (d.fEl && d.fEl._long) {
            d.longTimer = setTimeout(function () {
                if (drag !== d || d.moved) return;
                d.longFired = true;
                fire(target, 'mouseover');   // focus
                fire(target, 'mousemove');   // juiste regel/kaart in een lijst selecteren
                d.fEl._long();
            }, 550);
        }
    }, { passive: true });

    document.addEventListener('touchmove', function (e) {
        var d = drag;
        if (!d || e.touches.length > 1) return;
        var t = e.touches[0];
        var dx = t.clientX - d.x, dy = t.clientY - d.y;
        if (!d.moved) {
            if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
            d.moved = true;
            d.axis = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y';
            clearTimeout(d.longTimer);
        }
        if (d.player) { e.preventDefault(); return; }
        var lx = dx / scale, ly = dy / scale;
        var c = d.comp && d.comp._comp;
        var handled = false;
        if (c && d.axis === 'y' && typeof c.render === 'function' && (c instanceof KTV.VList || c instanceof KTV.VGrid)) {
            var h = c.root.clientHeight || 600;
            var total = c instanceof KTV.VList ? c.items.length * c.rowH : Math.ceil(c.items.length / c.cols) * c.cellH;
            c.scroll = Math.max(0, Math.min(d.startScroll - ly, Math.max(0, total - h)));
            c.render();
            handled = true;
        } else if (c && d.axis === 'x' && c instanceof KTV.HRow) {
            var maxO = Math.max(0, c.track.scrollWidth - c.viewport.clientWidth);
            c.offset = Math.max(0, Math.min(d.startOffset - lx, maxO));
            c.track.style.transform = 'translateX(' + (-c.offset) + 'px)';
            handled = true;
        } else if (d.vscroll && d.axis === 'y') {
            d.vscroll.scrollTop = d.startTop - ly;
            handled = true;
        }
        if (handled) e.preventDefault();
    }, { passive: false });

    document.addEventListener('touchend', function (e) {
        var d = drag;
        drag = null;
        if (!d) return;
        clearTimeout(d.longTimer);
        if (d.longFired || d.moved) suppressUntil = Date.now() + 450;
        if (!d.player || d.longFired) return;
        var t = e.changedTouches[0];
        var dx = t.clientX - d.x, dy = t.clientY - d.y;
        if (d.moved) {
            // Speler: vegen = pijltjes.
            // Omhoog vegen = volgende zender (zoals bij video-apps op de telefoon).
            if (d.axis === 'y') press(dy < 0 ? KTV.KEY.DOWN : KTV.KEY.UP);
            else press(dx < 0 ? KTV.KEY.LEFT : KTV.KEY.RIGHT);
            e.preventDefault();
        } else if (!d.fEl) {
            // Tikken op het beeld = OK.
            press(KTV.KEY.ENTER);
            suppressUntil = Date.now() + 450;
            e.preventDefault();
        }
    }, { passive: false });

    // Na vegen of lang drukken geen "tik" meer laten volgen.
    document.addEventListener('click', function (e) {
        if (Date.now() < suppressUntil && e.target !== back && !findUp(e.target, function (n) { return n === back; })) {
            e.stopPropagation();
            e.preventDefault();
        }
    }, true);
})();
