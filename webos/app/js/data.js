/* KijkTV voor LG webOS - gegevens: account, zenders, gids, films/series, favorieten, geschiedenis. */
'use strict';

(function () {
    var store = KTV.store;

    /* ---------------- Account ---------------- */

    function isImg(u) { return !!u && /^(https?:|data:image)/i.test(String(u)); }

    function withScheme(s) { return s.indexOf('://') >= 0 ? s : 'http://' + s; }

    function cleanServer(s) {
        s = withScheme(s.trim());
        var m = s.match(/^(https?:\/\/[^\/?#]+)/i);
        return m ? m[1] : s.replace(/\/+$/, '');
    }

    function queryParam(url, key) {
        var q = url.split('?')[1] || '';
        var parts = q.split('&');
        for (var i = 0; i < parts.length; i++) {
            var kv = parts[i].split('=');
            if (kv[0].toLowerCase() === key) {
                try { return decodeURIComponent((kv[1] || '').replace(/\+/g, ' ')); } catch (e) { return kv[1]; }
            }
        }
        return '';
    }

    KTV.account = {
        get: function () { return store.get('account', null); },
        set: function (a) { if (a) store.set('account', a); else store.remove('account'); },

        /** Maakt een account van wat iemand invult; herkent ook get.php-links met inloggegevens. */
        fromInput: function (server, user, pass, m3u) {
            server = (server || '').trim(); user = (user || '').trim(); pass = (pass || '').trim(); m3u = (m3u || '').trim();
            if (server && user && pass) return { type: 'xtream', server: cleanServer(server), user: user, pass: pass };
            var link = m3u || (server && !user && !pass ? server : '');
            if (!link) return null;
            link = withScheme(link);
            var u = queryParam(link, 'username'), p = queryParam(link, 'password');
            if (u && p) return { type: 'xtream', server: cleanServer(link), user: u, pass: p };
            return { type: 'm3u', url: link };
        }
    };

    function apiUrl(a, action) {
        var u = a.server + '/player_api.php?username=' + encodeURIComponent(a.user) + '&password=' + encodeURIComponent(a.pass);
        if (action) u += '&action=' + action;
        return u;
    }
    function enc(s) { return encodeURIComponent(s); }

    /* ---------------- Zenders ---------------- */

    var D = KTV.data = {
        channels: [],
        groups: [],          // [{name, list}]
        info: '',            // "Geldig tot ..."
        tz: null,            // tijdzone van de server (voor terugkijken)
        ext: 'm3u8',
        movies: null, movieCats: [],
        series: null, seriesCats: [],
        version: 0
    };

    function setChannels(list) {
        D.channels = list;
        var map = {}, order = [];
        list.forEach(function (c) {
            if (!map[c.group]) { map[c.group] = []; order.push(c.group); }
            map[c.group].push(c);
        });
        D.groups = order.map(function (g) { return { name: g, list: map[g] }; });
        D.version++;
    }

    function xtreamLogin(a) {
        return KTV.getJson(apiUrl(a, null)).then(function (obj) {
            if (!obj) throw new Error('Dit adres lijkt geen IPTV-server. Controleer de server-link.');
            var ui = obj.user_info;
            if (!ui || parseInt(ui.auth, 10) !== 1) throw new Error('Inloggen mislukt: gebruikersnaam of wachtwoord klopt niet.');
            var status = String(ui.status || '');
            if (status && status.toLowerCase() !== 'active') throw new Error('Je abonnement is niet actief (status: ' + status + ').');
            var exp = parseInt(ui.exp_date, 10);
            D.info = exp > 0 ? 'Geldig tot ' + new Date(exp * 1000).toLocaleDateString('nl-NL', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
            D.maxConnections = parseInt(ui.max_connections, 10) || 0;
            D.tz = (obj.server_info && obj.server_info.timezone) || null;
            var formats = (ui.allowed_output_formats || []).map(function (f) { return String(f).toLowerCase(); });
            // De ingebouwde speler van LG speelt HLS (m3u8) het soepelst af.
            D.ext = formats.length === 0 || formats.indexOf('m3u8') >= 0 ? 'm3u8' : 'ts';
        }, function (e) {
            var m = (e && e.message) || '';
            if (m.indexOf('wachtwoord') >= 0) throw e;
            throw new Error('De server is niet bereikbaar. Klopt de server-link (inclusief :poort)?\n' + m);
        });
    }

    function xtreamChannels(a) {
        return KTV.call('xtreamList', { server: a.server, user: a.user, pass: a.pass, kind: 'live' }).then(function (r) {
            var cats = {}, order = [];
            r.cats.forEach(function (c) { cats[c[0]] = c[1]; order.push(c[0]); });
            var byCat = {};
            r.items.forEach(function (it) {
                var cat = it[4];
                var ch = {
                    name: it[0], sid: it[1], logo: isImg(it[2]) ? it[2] : null,
                    epgId: it[3] || null, group: cats[cat] || 'Overig', archive: it[5] || 0
                };
                ch.url = a.server + '/live/' + enc(a.user) + '/' + enc(a.pass) + '/' + ch.sid + '.' + D.ext;
                (byCat[cat] = byCat[cat] || []).push(ch);
            });
            var all = [];
            order.forEach(function (c) { if (byCat[c]) { all = all.concat(byCat[c]); delete byCat[c]; } });
            Object.keys(byCat).forEach(function (c) { all = all.concat(byCat[c]); });
            return all;
        });
    }

    var attrRe = /([A-Za-z0-9_-]+)="([^"]*)"/g;

    function parseM3u(text) {
        var out = [], name = null, group = '', logo = null, epgId = null;
        var lines = text.split(/\r?\n/);
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i].trim();
            if (!line) continue;
            if (/^#EXTINF/i.test(line)) {
                var inQ = false, comma = -1;
                for (var j = 0; j < line.length; j++) {
                    var c = line[j];
                    if (c === '"') inQ = !inQ;
                    else if (c === ',' && !inQ) { comma = j; break; }
                }
                var head = comma >= 0 ? line.slice(0, comma) : line;
                var attrs = {}, m;
                attrRe.lastIndex = 0;
                while ((m = attrRe.exec(head))) attrs[m[1].toLowerCase()] = m[2];
                name = (comma >= 0 ? line.slice(comma + 1).trim() : '') || attrs['tvg-name'] || 'Zender';
                group = (attrs['group-title'] || '').trim();
                logo = attrs['tvg-logo'] || null;
                epgId = attrs['tvg-id'] || null;
            } else if (/^#EXTGRP:/i.test(line)) {
                if (!group) group = line.slice(8).trim();
            } else if (line[0] !== '#') {
                out.push({ name: name || 'Zender', url: line, group: group || 'Overig', logo: logo, epgId: epgId, archive: 0 });
                name = null; group = ''; logo = null; epgId = null;
            }
        }
        return out;
    }

    /** Logt in en laadt de zenders. */
    D.load = function (a) {
        var p;
        if (a.type === 'xtream') {
            p = xtreamLogin(a).then(function () { return xtreamChannels(a); });
        } else {
            D.info = ''; D.tz = null;
            p = KTV.getText(a.url).then(parseM3u, function (e) {
                throw new Error('Kon de playlist niet downloaden. Klopt de link?\n' + ((e && e.message) || ''));
            });
        }
        return p.then(function (list) {
            if (!list.length) throw new Error('Geen zenders gevonden bij deze aanbieder.');
            setChannels(list);
            return list;
        });
    };

    /** Past het gekozen streamtype toe op live-links van Xtream. */
    D.liveUrl = function (ch) {
        var f = KTV.settings.get('format');
        if (ch.url.indexOf('/live/') < 0) return ch.url;
        if (f === 'hls') return ch.url.replace(/\.ts$/, '.m3u8');
        if (f === 'ts') return ch.url.replace(/\.m3u8$/, '.ts');
        return ch.url;
    };

    D.altUrl = function (url) {
        if (url.indexOf('/live/') < 0) return null;
        if (/\.m3u8$/.test(url)) return url.replace(/\.m3u8$/, '.ts');
        if (/\.ts$/.test(url)) return url.replace(/\.ts$/, '.m3u8');
        return null;
    };

    /* ---------------- Programmagids ---------------- */

    var epgCache = {};   // sid -> {at, list}
    var queue = [], busy = 0, listeners = [], notifyTimer = null;

    KTV.epg = {
        list: function (ch) {
            var e = ch && ch.sid ? epgCache[ch.sid] : null;
            return e ? e.list : [];
        },
        now: function (ch, t) {
            t = t || Date.now();
            var l = this.list(ch);
            for (var i = 0; i < l.length; i++) if (l[i].start <= t && l[i].stop > t) return l[i];
            return null;
        },
        next: function (ch, t) {
            t = t || Date.now();
            var l = this.list(ch);
            for (var i = 0; i < l.length; i++) if (l[i].start > t) return l[i];
            return null;
        },
        /** Vraagt de gids voor een zender op (achter elkaar, max. 3 tegelijk). */
        request: function (ch) {
            var a = KTV.account.get();
            if (!a || a.type !== 'xtream' || !ch || !ch.sid) return;
            var e = epgCache[ch.sid];
            if (e && (e.loading || Date.now() - e.at < 30 * 60000)) return;
            epgCache[ch.sid] = { at: Date.now(), list: e ? e.list : [], loading: true };
            queue.push({ a: a, ch: ch });
            pump();
        },
        onUpdate: function (fn) { listeners.push(fn); },
        offUpdate: function (fn) { var i = listeners.indexOf(fn); if (i >= 0) listeners.splice(i, 1); },
        clear: function () { epgCache = {}; queue = []; }
    };

    function pump() {
        while (busy < 3 && queue.length) {
            var job = queue.shift();
            busy++;
            fetchEpg(job.a, job.ch).then(done, done);
        }
        function done() { busy--; pump(); }
    }

    function fetchEpg(a, ch) {
        return KTV.getJson(apiUrl(a, 'get_simple_data_table&stream_id=' + ch.sid)).then(function (obj) {
            var arr = (obj && obj.epg_listings) || [];
            var now = Date.now();
            var list = [];
            arr.forEach(function (o) {
                var s = parseInt(o.start_timestamp, 10) * 1000, e = parseInt(o.stop_timestamp, 10) * 1000;
                if (!s || !e || e < now - 8 * 86400000 || s > now + 3 * 86400000) return;
                list.push({ start: s, stop: e, title: KTV.b64(o.title), desc: KTV.b64(o.description).slice(0, 500),
                    archived: String(o.has_archive) === '1' });
            });
            list.sort(function (x, y) { return x.start - y.start; });
            epgCache[ch.sid] = { at: Date.now(), list: list };
            notify();
        }, function () {
            epgCache[ch.sid] = { at: Date.now(), list: [] };
        });
    }

    function notify() {
        if (notifyTimer) return;
        notifyTimer = setTimeout(function () {
            notifyTimer = null;
            listeners.slice().forEach(function (fn) { try { fn(); } catch (e) { } });
        }, 300);
    }

    /* ---------------- Terugkijken ---------------- */

    KTV.catchup = {
        can: function (ch, p, now) {
            now = now || Date.now();
            var a = KTV.account.get();
            if (!a || a.type !== 'xtream' || !ch || !ch.sid || !p) return false;
            if (p.start > now) return false;
            if (p.archived) return true;
            if (!ch.archive) return false;
            return now - p.start < ch.archive * 86400000;
        },
        url: function (ch, p) {
            var a = KTV.account.get();
            if (!a) return null;
            var minutes = Math.max(1, Math.round((p.stop - p.start) / 60000)) + 5;
            return a.server + '/timeshift/' + enc(a.user) + '/' + enc(a.pass) + '/' + minutes + '/' + tzStamp(p.start, D.tz) + '/' + ch.sid + '.ts';
        },
        // Andere schrijfwijzen van dezelfde link; aanbieders verschillen hierin.
        variants: function (url) {
            var m = /^(.*)\/timeshift\/([^\/]+)\/([^\/]+)\/(\d+)\/([^\/]+)\/(\d+)\.(ts|m3u8)$/.exec(url || '');
            if (!m) return [url];
            var b = m[1], u = m[2], pw = m[3], min = m[4], st = m[5], sid = m[6], sm = Math.min(+min, 120);
            var all = [url,
                b + '/timeshift/' + u + '/' + pw + '/' + min + '/' + st + '/' + sid + '.m3u8',
                b + '/streaming/timeshift.php?username=' + u + '&password=' + pw + '&stream=' + sid + '&start=' + st + '&duration=' + min,
                b + '/timeshift/' + u + '/' + pw + '/' + sm + '/' + st + '/' + sid + '.ts',
                b + '/streaming/timeshift.php?username=' + u + '&password=' + pw + '&stream=' + sid + '&start=' + st + '&duration=' + sm];
            var out = [];
            all.forEach(function (x) { if (out.indexOf(x) < 0) out.push(x); });
            return out;
        }
    };

    function tzStamp(ms, tz) {
        try {
            var f = new Intl.DateTimeFormat('en-GB', {
                timeZone: tz || undefined, year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', hour12: false
            });
            var o = {};
            f.formatToParts(new Date(ms)).forEach(function (x) { o[x.type] = x.value; });
            return o.year + '-' + o.month + '-' + o.day + ':' + (o.hour === '24' ? '00' : o.hour) + '-' + o.minute;
        } catch (e) {
            var d = new Date(ms);
            return d.getFullYear() + '-' + KTV.pad(d.getMonth() + 1) + '-' + KTV.pad(d.getDate()) + ':' + KTV.pad(d.getHours()) + '-' + KTV.pad(d.getMinutes());
        }
    }

    /* ---------------- Films en series ---------------- */

    function catMap(r) { var m = {}; r.cats.forEach(function (c) { m[c[0]] = c[1]; }); return m; }

    D.loadMovies = function () {
        if (D.movies) return Promise.resolve(D.movies);
        var a = KTV.account.get();
        if (!a || a.type !== 'xtream') return Promise.resolve([]);
        return KTV.call('xtreamList', { server: a.server, user: a.user, pass: a.pass, kind: 'vod' }).then(function (r) {
            var cats = catMap(r);
            D.movies = r.items.map(function (it) {
                return { id: it[0 + 1], name: it[0], poster: isImg(it[2]) ? it[2] : null,
                    category: cats[it[3]] || 'Overig', ext: it[4] || 'mp4', rating: it[5], added: it[6] || 0 };
            });
            var seen = {};
            D.movieCats = [];
            D.movies.forEach(function (m) { if (!seen[m.category]) { seen[m.category] = 1; D.movieCats.push(m.category); } });
            return D.movies;
        });
    };

    D.loadSeries = function () {
        if (D.series) return Promise.resolve(D.series);
        var a = KTV.account.get();
        if (!a || a.type !== 'xtream') return Promise.resolve([]);
        return KTV.call('xtreamList', { server: a.server, user: a.user, pass: a.pass, kind: 'series' }).then(function (r) {
            var cats = catMap(r);
            D.series = r.items.map(function (it) {
                return { id: it[1], name: it[0], cover: isImg(it[2]) ? it[2] : null,
                    category: cats[it[3]] || 'Overig', plot: it[4] || '', rating: it[5], added: it[6] || 0 };
            });
            var seen = {};
            D.seriesCats = [];
            D.series.forEach(function (s) { if (!seen[s.category]) { seen[s.category] = 1; D.seriesCats.push(s.category); } });
            return D.series;
        });
    };

    D.seriesInfo = function (s) {
        var a = KTV.account.get();
        return KTV.getJson(apiUrl(a, 'get_series_info&series_id=' + s.id)).then(function (obj) {
            obj = obj || {};
            var plot = (obj.info && obj.info.plot) || s.plot || '';
            var seasons = {};
            function add(arr, key) {
                (arr || []).forEach(function (e, i) {
                    if (!e || !e.id) return;
                    var season = parseInt(e.season, 10) || key;
                    var info = e.info || {};
                    (seasons[season] = seasons[season] || []).push({
                        id: String(e.id), season: season, number: parseInt(e.episode_num, 10) || i + 1,
                        title: e.title || ('Aflevering ' + (i + 1)), ext: e.container_extension || 'mp4',
                        plot: info.plot || '', duration: parseInt(info.duration_secs, 10) || 0,
                        image: isImg(info.movie_image) ? info.movie_image : null
                    });
                });
            }
            var eps = obj.episodes;
            if (Array.isArray(eps)) eps.forEach(function (arr, i) { add(arr, i + 1); });
            else if (eps && typeof eps === 'object') Object.keys(eps).forEach(function (k) { add(eps[k], parseInt(k, 10) || 1); });
            var nums = Object.keys(seasons).map(Number).sort(function (x, y) { return x - y; });
            nums.forEach(function (n) { seasons[n].sort(function (x, y) { return x.number - y.number; }); });
            return { plot: plot, seasons: nums.map(function (n) { return { number: n, episodes: seasons[n] }; }) };
        });
    };

    D.movieUrl = function (m) {
        var a = KTV.account.get();
        return a.server + '/movie/' + enc(a.user) + '/' + enc(a.pass) + '/' + m.id + '.' + m.ext;
    };
    D.episodeUrl = function (e) {
        var a = KTV.account.get();
        return a.server + '/series/' + enc(a.user) + '/' + enc(a.pass) + '/' + e.id + '.' + e.ext;
    };
    D.episodeLabel = function (e) { return 'S' + e.season + ' A' + e.number; };

    D.movieDetails = function (m) {
        var a = KTV.account.get();
        return KTV.getJson(apiUrl(a, 'get_vod_info&vod_id=' + m.id)).then(function (o) {
            var info = (o && o.info) || {};
            return { plot: info.plot || info.description || '', year: info.releasedate || info.year || '',
                duration: info.duration || '', genre: info.genre || '', backdrop: (info.backdrop_path && info.backdrop_path[0]) || null };
        }, function () { return { plot: '' }; });
    };

    /* ---------------- Instellingen ---------------- */

    KTV.settings = {
        get: function (k) { var s = store.get('settings', {}); return s[k] || (k === 'format' ? 'auto' : ''); },
        set: function (k, v) { var s = store.get('settings', {}); s[k] = v; store.set('settings', s); }
    };

    /* ---------------- Ontwikkelaarsmodus automatisch verlengen ---------------- */

    var WEEK = 7 * 86400000;

    KTV.devmode = {
        onTv: function () { return typeof window.PalmServiceBridge !== 'undefined'; },
        last: function () { return store.get('devRenew', 0); },
        /** Verlengt als het langer dan een week geleden is (of altijd bij force). */
        renewIfNeeded: function (force) {
            if (!this.onTv()) return Promise.resolve(false);
            if (!force && Date.now() - this.last() < WEEK) return Promise.resolve(false);
            var top = KTV.screens.top();
            // Niet onderbreken terwijl iemand kijkt; dan probeert hij het later opnieuw.
            if (!force && top && top.root.classList.contains('player')) return Promise.resolve(false);
            KTV.toast('Ontwikkelaarsmodus wordt automatisch verlengd…');
            return KTV.call('renewDevMode', {}).then(function (r) {
                if (r && r.launched) {
                    store.set('devRenew', Date.now());
                    return true;
                }
                return false;
            }, function () { return false; });
        },
        label: function () {
            var t = this.last();
            if (!t) return 'nog niet';
            var days = Math.floor((Date.now() - t) / 86400000);
            return days === 0 ? 'vandaag' : days === 1 ? 'gisteren' : days + ' dagen geleden';
        }
    };

    /* ---------------- Favorieten ---------------- */

    KTV.fav = {
        all: function () { return store.get('fav', []); },
        has: function (url) { return this.all().indexOf(url) >= 0; },
        toggle: function (url) {
            var l = this.all(), i = l.indexOf(url);
            if (i >= 0) l.splice(i, 1); else l.push(url);
            store.set('fav', l);
            if (KTV.sync) KTV.sync.favChanged();
            return i < 0;
        },
        channels: function () {
            var set = {};
            this.all().forEach(function (u) { set[u] = 1; });
            return D.channels.filter(function (c) { return set[c.url]; });
        }
    };

    /* ---------------- Geschiedenis ---------------- */

    KTV.history = {
        all: function () {
            return store.get('history', []).sort(function (a, b) { return b.at - a.at; });
        },
        get: function (key) {
            var l = store.get('history', []);
            for (var i = 0; i < l.length; i++) if (l[i].key === key) return l[i];
            return null;
        },
        record: function (item) {
            var l = store.get('history', []);
            for (var i = 0; i < l.length; i++) {
                if (l[i].key === item.key) {
                    if (!item.pos) item.pos = l[i].pos;
                    if (!item.dur) item.dur = l[i].dur;
                    l.splice(i, 1);
                    break;
                }
            }
            var copy = {};
            Object.keys(item).forEach(function (k) { if (k !== 'plot') copy[k] = item[k]; });
            copy.at = Date.now();
            l.unshift(copy);
            if (l.length > 300) l.length = 300;
            store.set('history', l);
            if (KTV.sync) KTV.sync.changed(copy);
        },
        update: function (key, pos, dur) {
            var l = store.get('history', []);
            for (var i = 0; i < l.length; i++) {
                if (l[i].key === key) {
                    l[i].pos = pos;
                    if (dur > 0) l[i].dur = dur;
                    l[i].at = Date.now();
                    store.set('history', l);
                    if (KTV.sync) KTV.sync.changed(l[i]);
                    return;
                }
            }
        },
        remove: function (key) {
            var w = this.get(key);
            store.set('history', store.get('history', []).filter(function (x) { return x.key !== key; }));
            if (w && KTV.sync) KTV.sync.removed(w);
        },
        clear: function () { store.set('history', []); if (KTV.sync) KTV.sync.cleared(); },
        isVideo: function (w) { return w.kind === 'movie' || w.kind === 'episode' || w.kind === 'catchup'; },
        finished: function (w) { return w.dur > 0 && w.pos >= w.dur * 0.95; },
        resumable: function (w) { return this.isVideo(w) && w.pos > 60000 && !this.finished(w); },
        progress: function (w) { return w.dur > 0 ? Math.min(1, w.pos / w.dur) : 0; },
        resumeList: function () { var self = this; return this.all().filter(function (w) { return self.resumable(w); }); }
    };
})();
