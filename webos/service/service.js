/*
 * KijkTV netwerkdienst voor LG webOS.
 * Een LG-app mag (door CORS) niet rechtstreeks met de IPTV-server praten; deze dienst draait
 * op de tv zelf (Node.js) en haalt de gegevens op namens de app.
 * Geschreven voor Node 12 (webOS 23): geen ?. of ?? gebruiken.
 */
'use strict';

var http = require('http');
var https = require('https');
var zlib = require('zlib');
var urlLib = require('url');

var USER_AGENT = 'KijkTV/1.0 (webOS; LG TV)';
var MAX_BYTES = 40 * 1024 * 1024;

function get(url, timeoutMs, redirects) {
    redirects = redirects || 0;
    return new Promise(function (resolve, reject) {
        var u;
        try { u = urlLib.parse(url); } catch (e) { reject(new Error('Ongeldige link')); return; }
        var lib = u.protocol === 'https:' ? https : http;
        var req = lib.request({
            protocol: u.protocol,
            hostname: u.hostname,
            port: u.port,
            path: u.path,
            method: 'GET',
            headers: { 'User-Agent': USER_AGENT, 'Accept-Encoding': 'gzip, deflate', 'Accept': '*/*' },
            rejectUnauthorized: false
        }, function (res) {
            var status = res.statusCode;
            if (status >= 300 && status < 400 && res.headers.location && redirects < 5) {
                res.resume();
                var next = urlLib.resolve(url, res.headers.location);
                get(next, timeoutMs, redirects + 1).then(resolve, reject);
                return;
            }
            var stream = res;
            var enc = (res.headers['content-encoding'] || '').toLowerCase();
            if (enc === 'gzip') stream = res.pipe(zlib.createGunzip());
            else if (enc === 'deflate') stream = res.pipe(zlib.createInflate());
            var chunks = [];
            var size = 0;
            stream.on('data', function (c) {
                size += c.length;
                if (size > MAX_BYTES) { req.abort(); reject(new Error('Antwoord te groot')); return; }
                chunks.push(c);
            });
            stream.on('end', function () {
                var buf = Buffer.concat(chunks);
                // .gz-bestanden zonder content-encoding
                if (buf.length > 2 && buf[0] === 0x1f && buf[1] === 0x8b) {
                    try { buf = zlib.gunzipSync(buf); } catch (e) { /* laat zo */ }
                }
                resolve({ status: status, body: buf.toString('utf8') });
            });
            stream.on('error', function (e) { reject(e); });
        });
        req.on('error', function (e) { reject(e); });
        req.setTimeout(timeoutMs || 30000, function () { req.abort(); reject(new Error('Time-out: de server reageert niet')); });
        req.end();
    });
}

function str(o, k) {
    var v = o ? o[k] : null;
    if (v === null || v === undefined) return '';
    v = String(v);
    return v === 'null' ? '' : v.trim();
}

function apiUrl(p, action) {
    var u = p.server + '/player_api.php?username=' + encodeURIComponent(p.user) + '&password=' + encodeURIComponent(p.pass);
    if (action) u += '&action=' + action;
    return u;
}

function getJson(url) {
    return get(url, 60000).then(function (r) {
        if (r.status === 401 || r.status === 403) throw new Error('Gebruikersnaam of wachtwoord klopt niet');
        if (r.status < 200 || r.status >= 300) throw new Error('Server antwoordde met code ' + r.status);
        try { return JSON.parse(r.body); } catch (e) { return null; }
    });
}

function cats(p, action) {
    return getJson(apiUrl(p, action)).then(function (arr) {
        var out = [];
        if (Array.isArray(arr)) {
            arr.forEach(function (c) { out.push([str(c, 'category_id'), str(c, 'category_name') || 'Overig']); });
        }
        return out;
    });
}

/** Compacte lijsten, zodat de app niet megabytes aan JSON hoeft te verwerken. */
function xtreamList(p) {
    var kind = p.kind;
    var catAction = kind === 'live' ? 'get_live_categories' : kind === 'vod' ? 'get_vod_categories' : 'get_series_categories';
    var listAction = kind === 'live' ? 'get_live_streams' : kind === 'vod' ? 'get_vod_streams' : 'get_series';
    return cats(p, catAction).then(function (c) {
        return getJson(apiUrl(p, listAction)).then(function (arr) {
            var items = [];
            if (Array.isArray(arr)) {
                arr.forEach(function (o) {
                    if (kind === 'live') {
                        var id = parseInt(o.stream_id, 10);
                        if (isNaN(id)) return;
                        var archive = parseInt(o.tv_archive, 10) === 1 ? Math.max(1, parseInt(o.tv_archive_duration, 10) || 1) : 0;
                        items.push([str(o, 'name') || ('Zender ' + id), id, str(o, 'stream_icon'), str(o, 'epg_channel_id'), str(o, 'category_id'), archive]);
                    } else if (kind === 'vod') {
                        var vid = parseInt(o.stream_id, 10);
                        if (isNaN(vid)) return;
                        items.push([str(o, 'name') || ('Film ' + vid), vid, str(o, 'stream_icon'), str(o, 'category_id'),
                            str(o, 'container_extension') || 'mp4', str(o, 'rating'), parseInt(o.added, 10) || 0]);
                    } else {
                        var sid = parseInt(o.series_id, 10);
                        if (isNaN(sid)) return;
                        items.push([str(o, 'name') || ('Serie ' + sid), sid, str(o, 'cover'), str(o, 'category_id'),
                            str(o, 'plot').slice(0, 400), str(o, 'rating'), parseInt(o.last_modified, 10) || 0]);
                    }
                });
            }
            return { cats: c, items: items };
        });
    });
}

var handlers = {
    fetch: function (p) {
        if (!p.url) return Promise.reject(new Error('Geen link opgegeven'));
        return get(p.url, p.timeout || 45000).then(function (r) { return { status: r.status, body: r.body }; });
    },
    xtreamList: xtreamList,
    ping: function () { return Promise.resolve({ ok: true, node: process.version }); }
};

function run(method, payload) {
    var h = handlers[method];
    if (!h) return Promise.reject(new Error('Onbekende opdracht'));
    return h(payload || {});
}

// Op de tv: registreren bij de luna-bus. Buiten de tv (tests): alleen exporteren.
var Service = null;
try { Service = require('webos-service'); } catch (e) { Service = null; }

if (Service) {
    var service = new Service('nl.kijktv.app.service');
    Object.keys(handlers).forEach(function (name) {
        service.register(name, function (message) {
            run(name, message.payload).then(function (data) {
                data.returnValue = true;
                message.respond(data);
            }, function (err) {
                message.respond({ returnValue: false, errorText: (err && err.message) || 'Onbekende fout' });
            });
        });
    });
} else {
    module.exports = { run: run, get: get };
}
