/*
 * KijkTV voor LG webOS - verder kijken op al je apparaten.
 * Zelfde regels als de Android-versie (app/src/main/java/nl/kijktv/Sync.kt); houd die gelijk.
 * Het IPTV-wachtwoord gaat nooit naar de database: in links wordt het {U}/{P}.
 */
'use strict';

(function () {
    var BASE = 'https://kijktv-26626-default-rtdb.europe-west1.firebasedatabase.app/kijktv/';
    var PULL_EVERY = 20000, PUSH_DELAY = 10000;
    var store = KTV.store;
    var pending = {}, latest = {}, lastPullAt = 0, pulling = false, again = null;

    /* ---- SHA-256 (klein, zonder afhankelijkheden) ---- */
    function sha256(ascii) {
        function rr(v, n) { return (v >>> n) | (v << (32 - n)); }
        var K = [], H = [], primes = 0, cand = 2;
        while (primes < 64) {
            var isP = true;
            for (var f = 2; f * f <= cand; f++) if (cand % f === 0) { isP = false; break; }
            if (isP) {
                if (primes < 8) H[primes] = (Math.pow(cand, 1 / 2) * 4294967296) | 0;
                K[primes++] = (Math.pow(cand, 1 / 3) * 4294967296) | 0;
            }
            cand++;
        }
        var s = unescape(encodeURIComponent(ascii)); // UTF-8
        var bitLen = s.length * 8;
        s += '\x80';
        while (s.length % 64 !== 56) s += '\x00';
        var words = [];
        for (var i = 0; i < s.length; i++) words[i >> 2] |= s.charCodeAt(i) << ((3 - i % 4) * 8);
        words.push((bitLen / 4294967296) | 0);
        words.push(bitLen | 0);
        var hash = H.slice(0);
        for (var j = 0; j < words.length; j += 16) {
            var w = words.slice(j, j + 16), old = hash.slice(0);
            for (var t = 0; t < 64; t++) {
                if (t >= 16) {
                    var w15 = w[t - 15], w2 = w[t - 2];
                    w[t] = (w[t - 16] + (rr(w15, 7) ^ rr(w15, 18) ^ (w15 >>> 3)) + w[t - 7] + (rr(w2, 17) ^ rr(w2, 19) ^ (w2 >>> 10))) | 0;
                }
                var a = hash[0], e = hash[4];
                var t1 = hash[7] + (rr(e, 6) ^ rr(e, 11) ^ rr(e, 25)) + ((e & hash[5]) ^ (~e & hash[6])) + K[t] + (w[t] | 0);
                var t2 = (rr(a, 2) ^ rr(a, 13) ^ rr(a, 22)) + ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));
                hash = [(t1 + t2) | 0].concat(hash);
                hash[4] = (hash[4] + t1) | 0;
                hash.length = 8;
            }
            for (var k = 0; k < 8; k++) hash[k] = (hash[k] + old[k]) | 0;
        }
        var out = '';
        for (var m = 0; m < 8; m++) out += ('00000000' + (hash[m] >>> 0).toString(16)).slice(-8);
        return out;
    }

    function hostPort(server) { return String(server).trim().toLowerCase().replace(/^[a-z]+:\/\//, '').replace(/\/+$/, ''); }

    // Zoals Java's URLEncoder (+ spatie als %20), zodat de sleutel op elk apparaat hetzelfde is.
    function encPath(s) {
        return encodeURIComponent(s).replace(/[!'()~]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); });
    }

    function keyFor(a) {
        if (!a || a.type !== 'xtream' || !a.user) return null;
        return sha256('kijktv1|' + hostPort(a.server) + '|' + a.user + '|' + a.pass);
    }
    function itemId(key) { return String(key).replace(/[^A-Za-z0-9_-]/g, '_'); }
    function syncable(kind) { return kind === 'movie' || kind === 'episode' || kind === 'catchup'; }

    function hideLogin(a, url) {
        var u = String(url || '');
        if (u.toLowerCase().indexOf(a.server.toLowerCase()) === 0) u = '{S}' + u.slice(a.server.length);
        [[encodeURIComponent(a.user), encodeURIComponent(a.pass)], [encPath(a.user), encPath(a.pass)]].forEach(function (p) {
            u = u.split('/' + p[0] + '/' + p[1] + '/').join('/{U}/{P}/');
        });
        if (a.pass.length >= 3 && (u.indexOf(a.pass) >= 0 || u.indexOf(encodeURIComponent(a.pass)) >= 0)) return '';
        return u;
    }
    function showLogin(a, url) {
        return url.split('{S}').join(a.server).split('/{U}/{P}/').join('/' + encodeURIComponent(a.user) + '/' + encodeURIComponent(a.pass) + '/');
    }

    function toRemote(a, w) {
        return { key: w.key, kind: w.kind, title: w.title || '', sub: w.sub || '', img: w.img || '', url: hideLogin(a, w.url),
            pos: w.pos || 0, dur: w.dur || 0, at: w.at || 0, sid: w.sid || 0 };
    }
    function fromRemote(a, o) {
        if (!o || !o.key || !syncable(o.kind) || !o.url) return null;
        return { key: o.key, kind: o.kind, title: o.title || '', sub: o.sub || '', img: o.img || null, url: showLogin(a, o.url),
            pos: +o.pos || 0, dur: +o.dur || 0, at: +o.at || 0, sid: +o.sid || 0 };
    }

    function request(method, path, body) {
        return KTV.call('fetch', { url: BASE + path + '.json', method: method, body: body === undefined ? undefined : JSON.stringify(body), timeout: 15000 })
            .then(function (r) {
                if (r.status < 200 || r.status >= 300) throw new Error('code ' + r.status);
                return r.body;
            });
    }
    function quiet(p) { if (p && p.catch) p.catch(function () { }); }

    /* ---- Lokale opslag zonder terugkoppeling ---- */
    function localList() { return store.get('history', []); }
    function applyRemote(w) {
        var l = localList().filter(function (x) { return x.key !== w.key; });
        l.push(w);
        l.sort(function (x, y) { return y.at - x.at; });
        if (l.length > 300) l.length = 300;
        store.set('history', l);
    }
    function removeLocal(key) { store.set('history', localList().filter(function (x) { return x.key !== key; })); }

    function favPayload(t) {
        if (!KTV.data.channels.length) return null;
        var set = {};
        KTV.fav.all().forEach(function (u) { set[u] = 1; });
        var ids = [];
        KTV.data.channels.forEach(function (c) { if (set[c.url] && c.sid && ids.indexOf(c.sid) < 0) ids.push(c.sid); });
        return { t: t, ids: ids };
    }

    KTV.sync = {
        sha256: sha256,
        keyFor: keyFor,

        changed: function (w) {
            if (!w || !syncable(w.kind)) return;
            var a = KTV.account.get(), key = keyFor(a);
            if (!key) return;
            var id = itemId(w.key);
            latest[id] = toRemote(a, w);
            if (pending[id]) return;
            pending[id] = setTimeout(function () { send(key, id); }, PUSH_DELAY);
        },
        flush: function () {
            var a = KTV.account.get(), key = keyFor(a);
            Object.keys(pending).forEach(function (id) { clearTimeout(pending[id]); if (key) send(key, id); });
        },
        removed: function (w) {
            if (!w || !syncable(w.kind)) return;
            var key = keyFor(KTV.account.get());
            if (!key) return;
            var id = itemId(w.key);
            clearTimeout(pending[id]); delete pending[id]; delete latest[id];
            quiet(request('DELETE', key + '/h/' + id));
        },
        cleared: function () {
            var key = keyFor(KTV.account.get());
            if (!key) return;
            Object.keys(pending).forEach(function (id) { clearTimeout(pending[id]); });
            pending = {}; latest = {};
            quiet(request('DELETE', key + '/h'));
        },
        favChanged: function () {
            var key = keyFor(KTV.account.get());
            if (!key) return;
            var t = Date.now();
            store.set('sync.favT', t);
            var p = favPayload(t);
            if (p) quiet(request('PUT', key + '/f', p));
        },

        /** Stand van de andere apparaten ophalen en samenvoegen. done(changed) */
        pull: function (force, done) {
            var a = KTV.account.get(), key = keyFor(a);
            var now = Date.now();
            if (!key) return;
            if (pulling) { if (force) again = done || true; return; }
            if (!force && now - lastPullAt < PULL_EVERY) return;
            pulling = true;
            lastPullAt = now;
            var changed = false, ok = true;
            function put(path, body) { return request('PUT', path, body).catch(function () { ok = false; }); }
            request('GET', key).then(function (text) {
                var root = null;
                try { root = JSON.parse(text); } catch (e) { root = null; }
                root = root || {};
                var lastSync = store.get('sync.last.' + key, 0);
                var sent = store.get('sync.sent', {});
                var syncStart = Date.now();
                var jobs = [];

                var remote = {};
                Object.keys(root.h || {}).forEach(function (id) {
                    var w = fromRemote(a, root.h[id]);
                    if (w) remote[w.key] = w;
                });
                var local = {};
                localList().forEach(function (w) { if (syncable(w.kind)) local[w.key] = w; });
                Object.keys(remote).forEach(function (k) {
                    var l = local[k], r = remote[k];
                    if (!l || r.at > (l.at || 0)) { applyRemote(r); changed = true; }
                });
                Object.keys(local).forEach(function (k) {
                    var l = local[k], r = remote[k];
                    if (!r) {
                        if ((l.at || 0) <= lastSync || (l.at || 0) <= (sent[k] || 0)) { removeLocal(k); changed = true; }
                        else jobs.push(put(key + '/h/' + itemId(k), toRemote(a, l)));
                    } else if ((l.at || 0) > r.at) jobs.push(put(key + '/h/' + itemId(k), toRemote(a, l)));
                });

                if (KTV.data.channels.length) {
                    var favT = store.get('sync.favT', 0), f = root.f, rt = f ? +f.t || 0 : 0;
                    if (f && rt > favT) {
                        var ids = {};
                        (f.ids || []).forEach(function (x) { ids[x] = 1; });
                        var known = {};
                        KTV.data.channels.forEach(function (c) { known[c.url] = 1; });
                        var urls = KTV.data.channels.filter(function (c) { return c.sid && ids[c.sid]; }).map(function (c) { return c.url; });
                        KTV.fav.all().forEach(function (u) { if (!known[u] && urls.indexOf(u) < 0) urls.push(u); });
                        store.set('fav', urls);
                        store.set('sync.favT', rt);
                        changed = true;
                    } else if (favT > rt) {
                        var p = favPayload(favT);
                        if (p) jobs.push(put(key + '/f', p));
                    }
                }
                return Promise.all(jobs).then(function () {
                    if (ok) { store.set('sync.last.' + key, syncStart); var m = store.get('sync.sent', {}); Object.keys(m).forEach(function (k) { if (m[k] <= syncStart) delete m[k]; }); store.set('sync.sent', m); }
                });
            }).catch(function () { /* geen internet: later opnieuw */ }).then(function () {
                pulling = false;
                if (done) done(changed);
                if (again) { var d = again; again = null; KTV.sync.pull(true, typeof d === 'function' ? d : null); }
            });
        }
    };

    function send(key, id) {
        delete pending[id];
        var body = latest[id];
        delete latest[id];
        if (body) quiet(request('PUT', key + '/h/' + id, body).then(function () { markSent(body.key, body.at); }));
    }
    /** Onthoud tot welke stand een item al op de server stond (voor het herkennen van verwijderen). */
    function markSent(k, at) {
        var m = store.get('sync.sent', {});
        if (!(m[k] >= at)) { m[k] = at; store.set('sync.sent', m); }
    }

    /** Na ophalen: het scherm dat open staat bijwerken (niet tijdens het kijken). */
    KTV.syncNow = function (force) {
        KTV.sync.pull(force, function (changed) {
            var t = KTV.screens.top();
            if (changed && t && t.syncRefresh) t.syncRefresh();
        });
    };
})();
