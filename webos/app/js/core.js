/* KijkTV voor LG webOS - kern: hulpfuncties, opslag, netwerk, focus/afstandsbediening. */
'use strict';

var KTV = window.KTV = {};

/* ---------------- Hulpfuncties ---------------- */

KTV.$ = function (sel, root) { return (root || document).querySelector(sel); };

KTV.el = function (tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
};

KTV.pad = function (n) { return n < 10 ? '0' + n : '' + n; };

KTV.time = function (ms) {
    var d = new Date(ms);
    return KTV.pad(d.getHours()) + ':' + KTV.pad(d.getMinutes());
};

KTV.range = function (p) { return KTV.time(p.start) + ' – ' + KTV.time(p.stop); };

var DAYS = ['zondag', 'maandag', 'dinsdag', 'woensdag', 'donderdag', 'vrijdag', 'zaterdag'];
var MONTHS = ['januari', 'februari', 'maart', 'april', 'mei', 'juni', 'juli', 'augustus', 'september', 'oktober', 'november', 'december'];

KTV.day = function (ms) {
    var d = new Date(ms);
    var t = new Date();
    var a = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    var b = new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
    var diff = Math.round((a - b) / 86400000);
    if (diff === 0) return 'Vandaag';
    if (diff === 1) return 'Morgen';
    if (diff === -1) return 'Gisteren';
    var s = DAYS[d.getDay()] + ' ' + d.getDate() + ' ' + MONTHS[d.getMonth()];
    return s.charAt(0).toUpperCase() + s.slice(1);
};

KTV.clock = function (ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    return h > 0 ? h + ':' + KTV.pad(m) + ':' + KTV.pad(sec) : m + ':' + KTV.pad(sec);
};

KTV.progress = function (p, now) {
    now = now || Date.now();
    var len = p.stop - p.start;
    if (len <= 0) return 0;
    return Math.max(0, Math.min(1, (now - p.start) / len));
};

/** Base64 met UTF-8 (zoals de gids van Xtream het levert). */
KTV.b64 = function (s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))).trim(); } catch (e) { return s; }
};

KTV.icon = function (name) {
    var paths = {
        play: 'M8,5v14l11,-7z',
        pause: 'M6,19h4V5H6v14zM14,5v14h4V5h-4z',
        next: 'M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z',
        replay: 'M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z',
        rewind: 'M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z',
        forward: 'M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z',
        search: 'M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z',
        settings: 'M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z',
        star: 'M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z',
        history: 'M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z',
        check: 'M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z',
        trash: 'M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z'
    };
    return '<svg class="ic" viewBox="0 0 24 24"><path d="' + (paths[name] || '') + '"/></svg>';
};

/* ---------------- Opslag (localStorage) ---------------- */

KTV.store = {
    get: function (k, def) {
        try {
            var v = localStorage.getItem('ktv.' + k);
            return v === null ? def : JSON.parse(v);
        } catch (e) { return def; }
    },
    set: function (k, v) {
        try { localStorage.setItem('ktv.' + k, JSON.stringify(v)); } catch (e) { /* vol */ }
    },
    remove: function (k) { try { localStorage.removeItem('ktv.' + k); } catch (e) { } }
};

/* ---------------- Netwerk via de tv-dienst ---------------- */

KTV.SERVICE = 'luna://nl.kijktv.app.service/';
var bridges = [];

/** Roept de achtergronddienst aan. Buiten de tv (testen) gebruikt hij window.KTV_MOCK of fetch(). */
KTV.call = function (method, params) {
    return new Promise(function (resolve, reject) {
        if (window.KTV_MOCK) {
            window.KTV_MOCK(method, params).then(resolve, reject);
            return;
        }
        if (typeof window.PalmServiceBridge === 'undefined') {
            // Gewone browser: alleen 'fetch' kan (als de server CORS toestaat).
            if (method !== 'fetch') { reject(new Error('Alleen beschikbaar op de tv')); return; }
            fetch(params.url).then(function (r) {
                return r.text().then(function (t) { resolve({ status: r.status, body: t }); });
            }, reject);
            return;
        }
        var bridge = new window.PalmServiceBridge();
        bridges.push(bridge);
        var done = false;
        var timer = setTimeout(function () {
            if (done) return;
            done = true;
            bridges.splice(bridges.indexOf(bridge), 1);
            reject(new Error('Time-out: de server reageert niet'));
        }, 120000);
        bridge.onservicecallback = function (msg) {
            if (done) return;
            done = true;
            clearTimeout(timer);
            bridges.splice(bridges.indexOf(bridge), 1);
            var res;
            try { res = JSON.parse(msg); } catch (e) { reject(new Error('Ongeldig antwoord')); return; }
            if (res.returnValue === false) reject(new Error(res.errorText || 'Er ging iets mis'));
            else resolve(res);
        };
        bridge.call(KTV.SERVICE + method, JSON.stringify(params || {}));
    });
};

KTV.getText = function (url) {
    return KTV.call('fetch', { url: url }).then(function (r) {
        if (r.status === 401 || r.status === 403) throw new Error('Gebruikersnaam of wachtwoord klopt niet');
        if (r.status < 200 || r.status >= 300) throw new Error('Server antwoordde met code ' + r.status);
        return r.body;
    });
};

KTV.getJson = function (url) {
    return KTV.getText(url).then(function (t) {
        try { return JSON.parse(t); } catch (e) { return null; }
    });
};

/* ---------------- Toetsen ---------------- */

KTV.KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
    BACK: 461, BACKSPACE: 8, ESC: 27,
    PLAY: 415, PAUSE: 19, PLAYPAUSE: 179, STOP: 413, FF: 417, RW: 412,
    CH_UP: 33, CH_DOWN: 34, INFO: 457
};

KTV.isBack = function (k) { return k === KTV.KEY.BACK || k === KTV.KEY.ESC || k === KTV.KEY.BACKSPACE; };

/* ---------------- Focus (navigeren met de pijltjes) ----------------
 * Elk element met class "f" kan focus krijgen. Een element kan eigen toetsafhandeling hebben
 * via el._key(e) (geeft true als het de toets zelf afhandelde), en el._act() voor OK.
 * Voor lange lijsten is de hele lijst één focus-element dat intern de selectie bijhoudt.
 */

KTV.focus = {
    cur: null,

    layer: function () {
        var dialogs = document.querySelectorAll('.layer.open');
        if (dialogs.length) return dialogs[dialogs.length - 1];
        return KTV.screens.top() ? KTV.screens.top().root : document.body;
    },

    candidates: function () {
        var layer = this.layer();
        var all = layer.querySelectorAll('.f');
        var out = [];
        for (var i = 0; i < all.length; i++) {
            var e = all[i];
            if (e.offsetParent === null && getComputedStyle(e).position !== 'fixed') continue;
            if (e.classList.contains('disabled')) continue;
            out.push(e);
        }
        return out;
    },

    set: function (e) {
        if (!e || e === this.cur) return;
        var old = this.cur;
        if (old) {
            old.classList.remove('focused');
            if (old._blur) old._blur();
        }
        this.cur = e;
        e.classList.add('focused');
        if (e._focus) e._focus();
        if (!e._noScroll) {
            try { e.scrollIntoView({ block: 'nearest', inline: 'nearest' }); } catch (x) { }
        }
    },

    /** Zoekt het dichtstbijzijnde element in een richting. */
    move: function (dir) {
        var cur = this.cur;
        var list = this.candidates();
        if (!cur || list.indexOf(cur) < 0) {
            if (list.length) this.set(list[0]);
            return true;
        }
        var r = cur.getBoundingClientRect();
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        var best = null, bestScore = Infinity;
        for (var i = 0; i < list.length; i++) {
            var e = list[i];
            if (e === cur) continue;
            var q = e.getBoundingClientRect();
            var qx = q.left + q.width / 2, qy = q.top + q.height / 2;
            var primary, secondary, overlap;
            if (dir === 'left' || dir === 'right') {
                primary = dir === 'right' ? q.left - r.right : r.left - q.right;
                if (dir === 'right' ? qx <= cx + 1 : qx >= cx - 1) continue;
                overlap = Math.min(r.bottom, q.bottom) - Math.max(r.top, q.top);
                secondary = overlap > 0 ? 0 : Math.abs(qy - cy);
            } else {
                primary = dir === 'down' ? q.top - r.bottom : r.top - q.bottom;
                if (dir === 'down' ? qy <= cy + 1 : qy >= cy - 1) continue;
                overlap = Math.min(r.right, q.right) - Math.max(r.left, q.left);
                secondary = overlap > 0 ? 0 : Math.abs(qx - cx);
            }
            var score = Math.max(0, primary) + secondary * 3;
            if (score < bestScore) { bestScore = score; best = e; }
        }
        if (best) { this.set(best); return true; }
        return false;
    },

    first: function (root) {
        var e = (root || this.layer()).querySelector('.f:not(.disabled)');
        if (e) this.set(e);
    }
};

/* ---------------- Schermen (stapel) ---------------- */

KTV.screens = {
    stack: [],
    top: function () { return this.stack[this.stack.length - 1]; },

    push: function (screen) {
        var prev = this.top();
        if (prev) {
            prev._lastFocus = KTV.focus.cur;
            prev.root.classList.add('hidden');
            if (prev.pause) prev.pause();
        }
        this.stack.push(screen);
        document.getElementById('app').appendChild(screen.root);
        KTV.focus.cur = null;
        if (screen.show) screen.show();
        if (!KTV.focus.cur) KTV.focus.first(screen.root);
    },

    pop: function () {
        var s = this.stack.pop();
        if (!s) return;
        if (s.destroy) s.destroy();
        if (s.root.parentNode) s.root.parentNode.removeChild(s.root);
        var t = this.top();
        if (t) {
            t.root.classList.remove('hidden');
            KTV.focus.cur = null;
            if (t._lastFocus && document.body.contains(t._lastFocus)) KTV.focus.set(t._lastFocus);
            if (t.resume) t.resume();
            if (!KTV.focus.cur || !document.body.contains(KTV.focus.cur)) KTV.focus.first(t.root);
        }
    },

    /** Vervangt het hele scherm (bijv. na inloggen). */
    reset: function (screen) {
        while (this.stack.length) {
            var s = this.stack.pop();
            if (s.destroy) s.destroy();
            if (s.root.parentNode) s.root.parentNode.removeChild(s.root);
        }
        this.push(screen);
    }
};

/* ---------------- Toetsen verdelen ---------------- */

document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    var active = document.activeElement;
    // Tekstvak in gebruik: alleen omhoog/omlaag/terug verlaten het vak.
    if (active && active.tagName === 'INPUT') {
        if (k === KTV.KEY.UP || k === KTV.KEY.DOWN || k === KTV.KEY.BACK || k === KTV.KEY.ESC) {
            active.blur();
            e.preventDefault();
            if (k === KTV.KEY.UP) KTV.focus.move('up');
            if (k === KTV.KEY.DOWN) KTV.focus.move('down');
            return;
        }
        if (k === KTV.KEY.ENTER) { active.blur(); e.preventDefault(); return; }
        return;
    }
    var cur = KTV.focus.cur;
    if (cur && cur._key && cur._key(e)) { e.preventDefault(); return; }
    var layers = document.querySelectorAll('.layer.open');
    var layer = layers.length ? layers[layers.length - 1] : null;
    var screen = KTV.screens.top();
    var handlerOwner = layer ? layer : screen;
    if (handlerOwner && handlerOwner.onKey && handlerOwner.onKey(e)) { e.preventDefault(); return; }
    switch (k) {
        case KTV.KEY.LEFT: KTV.focus.move('left'); break;
        case KTV.KEY.RIGHT: KTV.focus.move('right'); break;
        case KTV.KEY.UP: KTV.focus.move('up'); break;
        case KTV.KEY.DOWN: KTV.focus.move('down'); break;
        case KTV.KEY.ENTER:
            if (cur && cur.tagName === 'INPUT') { cur.focus(); break; }
            if (!cur) break;
            if (cur._long) {
                // Kort = OK, lang ingedrukt = tweede actie (bijv. favoriet). Uitgevoerd bij loslaten.
                if (!e.repeat && !enterDown) {
                    enterDown = { el: cur, fired: false };
                    enterTimer = setTimeout(function () {
                        if (enterDown && enterDown.el === KTV.focus.cur) { enterDown.fired = true; cur._long(); }
                    }, 650);
                }
            } else if (!e.repeat && cur._act) {
                cur._act();
            }
            break;
        default:
            if (KTV.isBack(k)) {
                if (layer && layer._close) layer._close();
                else if (screen && screen.back) screen.back();
                else if (KTV.screens.stack.length > 1) KTV.screens.pop();
                else KTV.exit();
            } else return;
    }
    e.preventDefault();
}, true);

var enterDown = null, enterTimer = null;
document.addEventListener('keyup', function (e) {
    if (e.keyCode !== KTV.KEY.ENTER || !enterDown) return;
    clearTimeout(enterTimer);
    var d = enterDown;
    enterDown = null;
    if (!d.fired && d.el === KTV.focus.cur && d.el._act) d.el._act();
}, true);

/* Magic Remote (aanwijzer): wijzen = focus, klikken = OK. */
document.addEventListener('mouseover', function (e) {
    var t = e.target;
    while (t && t !== document.body) {
        if (t.classList && t.classList.contains('f')) {
            KTV.focus.set(t);
            return;
        }
        t = t.parentNode;
    }
});

document.addEventListener('click', function (e) {
    var t = e.target;
    while (t && t !== document.body) {
        if (t.classList && t.classList.contains('f')) {
            if (t._pointer) return;   // lijsten handelen klikken zelf af
            KTV.focus.set(t);
            if (t.tagName === 'INPUT') { t.focus(); return; }
            if (t._act) t._act();
            return;
        }
        t = t.parentNode;
    }
});

KTV.exit = function () {
    KTV.dialog.choose('KijkTV afsluiten?', '', ['Afsluiten', 'Annuleren'], function (i) {
        if (i !== 0) return;
        if (window.PalmSystem && window.PalmSystem.platformBack) window.PalmSystem.platformBack();
        else window.close();
    });
};

/* ---------------- Melding en keuzevenster ---------------- */

KTV.toast = function (text) {
    var t = KTV.$('#toast');
    t.textContent = text;
    t.classList.add('show');
    clearTimeout(KTV._toastTimer);
    KTV._toastTimer = setTimeout(function () { t.classList.remove('show'); }, 2600);
};

KTV.dialog = {
    /** Keuzevenster met knoppen onder elkaar. cb(index) of cb(-1) bij terug. */
    choose: function (title, message, options, cb, selected) {
        var prevFocus = KTV.focus.cur;
        var layer = KTV.el('div', 'layer open');
        var box = KTV.el('div', 'dialog');
        box.appendChild(KTV.el('div', 'dialog-title', title));
        if (message) box.appendChild(KTV.el('div', 'dialog-msg', message));
        var buttons = [];
        options.forEach(function (o, i) {
            var b = KTV.el('div', 'dialog-opt f' + (selected === i ? ' selected' : ''), o);
            b._act = function () { close(i); };
            box.appendChild(b);
            buttons.push(b);
        });
        layer.appendChild(box);
        document.body.appendChild(layer);
        function close(i) {
            if (layer.parentNode) layer.parentNode.removeChild(layer);
            KTV.focus.cur = null;
            if (prevFocus && document.body.contains(prevFocus)) KTV.focus.set(prevFocus);
            else if (KTV.screens.top()) KTV.focus.first(KTV.screens.top().root);
            if (cb) cb(i);
        }
        layer._close = function () { close(-1); };
        KTV.focus.cur = null;
        KTV.focus.set(buttons[selected >= 0 ? selected : 0] || buttons[0]);
    }
};
