/* KijkTV voor LG webOS - tv-gids (raster), films, filmdetails en series. */
'use strict';

(function () {
    var el = KTV.el, D = KTV.data, K = KTV.KEY;

    function screen(cls) { return { root: el('div', 'screen ' + cls) }; }

    /* ======================= Tv-gids als raster ======================= */

    var STEP = 30 * 60000, WINDOW = 2 * 3600000;

    KTV.Guide = function (list, startIndex, title) {
        var s = screen('guide');
        var r = s.root;
        var head = el('div', 'page-head');
        head.appendChild(el('div', 'page-title', 'Tv-gids'));
        head.appendChild(el('div', 'page-sub', title || ''));
        head.appendChild(el('div', 'spacer'));
        var clock = el('div', 'nav-clock', KTV.time(Date.now()));
        head.appendChild(clock);
        r.appendChild(head);

        var detail = el('div', 'guide-detail');
        var dTitle = el('div', 'gd-title'), dMeta = el('div', 'gd-meta'), dDesc = el('div', 'gd-desc');
        detail.appendChild(dTitle); detail.appendChild(dMeta); detail.appendChild(dDesc);
        r.appendChild(detail);

        var ruler = el('div', 'guide-ruler');
        var dayLabel = el('div', 'guide-day');
        var times = el('div', 'guide-times');
        ruler.appendChild(dayLabel); ruler.appendChild(times);
        r.appendChild(ruler);

        var grid = el('div', 'guide-grid f');
        var rowsEl = el('div', 'guide-rows');
        var nowLine = el('div', 'guide-now');
        grid.appendChild(rowsEl); grid.appendChild(nowLine);
        r.appendChild(grid);
        grid._noScroll = true;

        var CH_W = 300, ROW_H = 76;
        var now0 = Date.now();
        var maxArchive = 0;
        list.forEach(function (c) { if (c.archive > maxArchive) maxArchive = c.archive; });
        maxArchive = Math.min(7, maxArchive);
        var minStart = floor(now0 - (maxArchive ? maxArchive * 86400000 : 2 * 3600000));
        var maxEnd = now0 + 3 * 86400000;
        var ws = floor(now0) - STEP;
        var row = Math.max(0, Math.min(startIndex || 0, list.length - 1));
        var top = Math.max(0, row - 2);
        var focusTime = now0;
        var cellSel = null;   // {start, stop, p}

        function floor(t) { return t - (t % STEP); }
        function trackW() { return (grid.clientWidth || 1840) - CH_W; }
        function px(t) { return (t - ws) / WINDOW * trackW(); }
        function visibleRows() { return Math.max(1, Math.floor((grid.clientHeight || 600) / ROW_H)); }

        function cellsFor(ch) {
            var we = ws + WINDOW;
            var progs = KTV.epg.list(ch).filter(function (p) { return p.stop > ws && p.start < we; });
            if (!progs.length) return [{ start: ws, stop: we, p: null }];
            return progs.map(function (p) { return { start: Math.max(p.start, ws), stop: Math.min(p.stop, we), p: p }; });
        }

        function pick(cells, t) {
            for (var i = 0; i < cells.length; i++) if (t >= cells[i].start && t < cells[i].stop) return cells[i];
            var best = cells[0], bd = Infinity;
            cells.forEach(function (c) { var d = Math.min(Math.abs(c.start - t), Math.abs(c.stop - t)); if (d < bd) { bd = d; best = c; } });
            return best;
        }

        function renderRuler() {
            times.innerHTML = '';
            for (var t = ws; t < ws + WINDOW; t += STEP) {
                var l = el('div', 'guide-tick', KTV.time(t));
                l.style.left = px(t) + 'px';
                times.appendChild(l);
            }
            dayLabel.textContent = KTV.day(ws + STEP);
            var n = Date.now();
            if (n >= ws && n < ws + WINDOW) { nowLine.style.display = 'block'; nowLine.style.left = (CH_W + px(n)) + 'px'; }
            else nowLine.style.display = 'none';
        }

        function render() {
            var vis = visibleRows();
            if (row < top) top = row;
            if (row >= top + vis) top = row - vis + 1;
            top = Math.max(0, Math.min(top, Math.max(0, list.length - vis)));
            rowsEl.innerHTML = '';
            var now = Date.now();
            var focused = grid.classList.contains('focused');
            for (var i = top; i < Math.min(list.length, top + vis); i++) {
                var ch = list[i];
                KTV.epg.request(ch);
                var rowEl = el('div', 'g-row');
                rowEl.style.top = ((i - top) * ROW_H) + 'px';
                var chEl = el('div', 'g-ch');
                chEl.appendChild(el('div', 'g-num', String(i + 1)));
                var logo = el('div', 'g-logo');
                if (ch.logo) logo.appendChild(KTV.img(ch.logo));
                chEl.appendChild(logo);
                chEl.appendChild(el('div', 'g-chname', ch.name));
                rowEl.appendChild(chEl);
                var track = el('div', 'g-track');
                var cells = cellsFor(ch);
                if (i === row) cellSel = pick(cells, focusTime);
                cells.forEach(function (c) {
                    var cEl = el('div', 'g-cell');
                    var left = px(c.start), right = px(c.stop);
                    cEl.style.left = left + 'px';
                    cEl.style.width = Math.max(6, right - left - 4) + 'px';
                    if (c.p) {
                        if (c.p.start <= now && c.p.stop > now) cEl.classList.add('now');
                        if (c.p.stop <= now) cEl.classList.add('past');
                        cEl.appendChild(el('div', 'g-title', c.p.title || 'Geen titel'));
                        cEl.appendChild(el('div', 'g-time', KTV.range(c.p)));
                    } else {
                        cEl.appendChild(el('div', 'g-title', ch.name));
                        cEl.appendChild(el('div', 'g-time', 'Geen programmagegevens'));
                    }
                    if (focused && i === row && cellSel && c.start === cellSel.start) cEl.classList.add('sel');
                    (function (ri, cc) {
                        cEl.addEventListener('mousemove', function () {
                            if (row === ri && cellSel && cellSel.start === cc.start) return;
                            row = ri; focusTime = cc.start; render();
                        });
                        cEl.addEventListener('click', function () { row = ri; focusTime = cc.start; render(); act(); });
                    })(i, c);
                    track.appendChild(cEl);
                });
                rowEl.appendChild(track);
                rowsEl.appendChild(rowEl);
            }
            renderRuler();
            showDetail();
        }

        function showDetail() {
            var ch = list[row];
            var p = cellSel && cellSel.p;
            if (!ch) return;
            if (!p) {
                dTitle.textContent = ch.name;
                dMeta.textContent = ch.group;
                dDesc.textContent = 'Druk op OK om live te kijken.';
                return;
            }
            var n = Date.now();
            var meta = ch.name + '  ·  ' + KTV.day(p.start) + '  ' + KTV.range(p);
            if (p.start <= n && p.stop > n) meta += '  ·  Nu';
            if (KTV.catchup.can(ch, p, n)) meta += '  ·  Terugkijken mogelijk';
            dTitle.textContent = p.title || ch.name;
            dMeta.textContent = meta;
            dDesc.textContent = p.desc || '';
        }

        function shift(d) {
            var n = ws + d;
            if (n < minStart || n + WINDOW > maxEnd + STEP) return false;
            ws = n;
            return true;
        }

        grid._key = function (e) {
            var k = e.keyCode;
            if (!cellSel) return false;
            if (k === K.RIGHT) {
                var t = cellSel.stop;
                if (t >= ws + WINDOW) { if (shift(STEP)) focusTime = t; }
                else focusTime = t;
                render(); return true;
            }
            if (k === K.LEFT) {
                var t2 = cellSel.start - 1;
                if (t2 < ws) { if (shift(-STEP)) focusTime = Math.max(t2, ws); else return true; }
                else focusTime = t2;
                render();
                if (cellSel) focusTime = cellSel.start;
                return true;
            }
            if (k === K.UP) {
                if (row === 0) return false;
                anchor(); row--; render(); return true;
            }
            if (k === K.DOWN) { if (row < list.length - 1) { anchor(); row++; render(); } return true; }
            if (k === K.CH_UP) { anchor(); row = Math.max(0, row - visibleRows()); render(); return true; }
            if (k === K.CH_DOWN) { anchor(); row = Math.min(list.length - 1, row + visibleRows()); render(); return true; }
            return false;
        };
        function anchor() { if (cellSel && (focusTime < cellSel.start || focusTime >= cellSel.stop)) focusTime = cellSel.start; }
        grid._focus = function () { render(); };
        grid._blur = function () { render(); };
        grid._act = act;
        grid.addEventListener('wheel', function (e) { row = Math.max(0, Math.min(list.length - 1, row + (e.deltaY > 0 ? 1 : -1))); render(); e.preventDefault(); });

        function act() {
            var ch = list[row];
            var p = cellSel && cellSel.p;
            var n = Date.now();
            if (!ch) return;
            if (!p || (p.start <= n && p.stop > n && !KTV.catchup.can(ch, p, n))) {
                KTV.screens.push(KTV.Player.live(list, row));
                return;
            }
            if (p.start > n) { KTV.toast(p.title + ' begint ' + KTV.day(p.start).toLowerCase() + ' om ' + KTV.time(p.start)); return; }
            if (p.stop > n) {
                KTV.dialog.choose(p.title, '', ['Live kijken', 'Vanaf het begin kijken (' + KTV.time(p.start) + ')'], function (i) {
                    if (i === 0) KTV.screens.push(KTV.Player.live(list, row));
                    if (i === 1) KTV.playItem(KTV.Player.catchupItem(ch, p));
                });
                return;
            }
            if (KTV.catchup.can(ch, p, n)) KTV.playItem(KTV.Player.catchupItem(ch, p));
            else KTV.toast('Deze uitzending is niet terug te kijken bij je aanbieder');
        }

        function onEpg() { render(); }
        KTV.epg.onUpdate(onEpg);
        var timer = setInterval(function () { clock.textContent = KTV.time(Date.now()); render(); }, 30000);

        s.show = function () { KTV.focus.set(grid); render(); };
        s.resume = function () { render(); };
        s.destroy = function () { clearInterval(timer); KTV.epg.offUpdate(onEpg); };
        return s;
    };

    /* ======================= Films / Series ======================= */

    function posterCell(item, progress) {
        var c = el('div', 'gcell');
        var inner = el('div', 'gcell-inner');
        inner.appendChild(el('div', 'poster-name', item.name));
        var img = item.poster || item.cover;
        if (img) inner.appendChild(KTV.img(img, 'card-img'));
        if (progress > 0) {
            var bar = el('div', 'bar bottom'); var fill = el('div', 'bar-fill'); fill.style.width = (progress * 100) + '%';
            bar.appendChild(fill); inner.appendChild(bar);
        }
        c.appendChild(inner);
        c.appendChild(el('div', 'gcell-name', item.name));
        return c;
    }

    KTV.Media = function (isSeries) {
        var s = screen('media');
        var r = s.root;
        var head = el('div', 'page-head');
        head.appendChild(el('div', 'page-title', isSeries ? 'Series' : 'Films'));
        var search = el('input', 'input search f');
        search.placeholder = 'Zoeken';
        head.appendChild(search);
        r.appendChild(head);
        var body = el('div', 'media-body');
        var colCats = el('div', 'col cats');
        var colGrid = el('div', 'col grid');
        body.appendChild(colCats); body.appendChild(colGrid);
        r.appendChild(body);
        var msg = el('div', 'center-msg');
        r.appendChild(msg);

        var all = [], cat = '__all';

        var cats = new KTV.VList({
            rowH: 64, cls: 'groups-list',
            render: function (g, i, row) {
                row.appendChild(el('div', 'g-name', g.name));
                row.appendChild(el('div', 'g-count', String(g.count)));
                row.classList.toggle('active', g.key === cat);
            },
            onSelect: function (g) { clearTimeout(s._t); s._t = setTimeout(function () { setCat(g.key); }, 150); },
            onAct: function (g) { setCat(g.key); KTV.focus.set(grid.root); },
            onKey: function (e) { if (e.keyCode === K.RIGHT) { KTV.focus.set(grid.root); return true; } return false; }
        });
        colCats.appendChild(cats.root);

        var grid = new KTV.VGrid({
            cols: 6, cellH: 420, empty: '',
            render: function (it) {
                var w = !isSeries ? KTV.history.get('mv:' + it.id) : null;
                return posterCell(it, w && KTV.history.resumable(w) ? KTV.history.progress(w) : 0);
            },
            onAct: function (it) {
                if (isSeries) KTV.screens.push(KTV.SeriesScreen(it));
                else KTV.screens.push(KTV.MovieDetail(it));
            }
        });
        colGrid.appendChild(grid.root);

        function resumeItems() {
            var byKey = {};
            if (isSeries) {
                var bySid = {};
                all.forEach(function (x) { bySid[x.id] = x; });
                var out = [], seen = {};
                KTV.history.resumeList().forEach(function (w) {
                    if (w.kind === 'episode' && bySid[w.sid] && !seen[w.sid]) { seen[w.sid] = 1; out.push(bySid[w.sid]); }
                });
                return out;
            }
            all.forEach(function (m) { byKey['mv:' + m.id] = m; });
            return KTV.history.resumeList().filter(function (w) { return byKey[w.key]; }).map(function (w) { return byKey[w.key]; });
        }

        function buildCats() {
            var list = [];
            var res = resumeItems();
            if (res.length) list.push({ key: '__resume', name: 'Verder kijken', count: res.length });
            list.push({ key: '__all', name: isSeries ? 'Alle series' : 'Alle films', count: all.length });
            var counts = {};
            all.forEach(function (x) { counts[x.category] = (counts[x.category] || 0) + 1; });
            (isSeries ? D.seriesCats : D.movieCats).forEach(function (c) { list.push({ key: c, name: c, count: counts[c] || 0 }); });
            var idx = 0;
            list.forEach(function (g, i) { if (g.key === cat) idx = i; });
            cats.setItems(list, idx);
        }

        function itemsFor(key) {
            if (key === '__resume') return resumeItems();
            if (key === '__all') return all;
            return all.filter(function (x) { return x.category === key; });
        }

        function setCat(key, keep) {
            if (key === cat && !keep && grid.items.length) return;
            cat = key;
            search.value = '';
            grid.setItems(itemsFor(key), keep ? grid.index : 0);
            cats.refresh();
        }

        search.addEventListener('input', function () {
            var q = search.value.trim().toLowerCase();
            if (!q) { setCat(cat, true); return; }
            grid.setItems(all.filter(function (x) { return x.name.toLowerCase().indexOf(q) >= 0; }), 0);
        });

        s.show = function () {
            var a = KTV.account.get();
            if (!a || a.type !== 'xtream') {
                msg.textContent = 'Films en series werken als je inlogt met server, gebruikersnaam en wachtwoord.';
                return;
            }
            msg.innerHTML = '<span class="spinner"></span><div>' + (isSeries ? 'Series' : 'Films') + ' laden…</div>';
            (isSeries ? D.loadSeries() : D.loadMovies()).then(function (l) {
                msg.innerHTML = '';
                all = l || [];
                if (!all.length) { msg.textContent = isSeries ? 'Je aanbieder heeft geen series.' : 'Je aanbieder heeft geen films.'; return; }
                cat = resumeItems().length ? '__resume' : '__all';
                buildCats();
                grid.setItems(itemsFor(cat), 0);
                KTV.focus.set(grid.root);
            }, function (e) { msg.textContent = 'Laden mislukt. ' + ((e && e.message) || ''); });
        };
        s.resume = function () { if (all.length) { buildCats(); setCat(cat, true); } };
        s.onKey = function (e) {
            if (e.keyCode === K.UP && (KTV.focus.cur === grid.root || KTV.focus.cur === cats.root)) { KTV.focus.set(search); return true; }
            return false;
        };
        return s;
    };

    /* ======================= Filmdetails ======================= */

    KTV.MovieDetail = function (m) {
        var s = screen('detail');
        var r = s.root;
        var bg = el('div', 'detail-bg');
        var bgImg = KTV.img(m.poster, 'detail-bg-img');
        bg.appendChild(bgImg);
        bg.appendChild(el('div', 'detail-fade'));
        r.appendChild(bg);
        var box = el('div', 'detail-box');
        var poster = el('div', 'detail-poster');
        if (m.poster) poster.appendChild(KTV.img(m.poster));
        var info = el('div', 'detail-info');
        info.appendChild(el('div', 'hero-label', 'Film'));
        info.appendChild(el('div', 'detail-title', m.name));
        var meta = el('div', 'detail-meta', [m.category, m.rating && m.rating !== '0' ? 'Score ' + m.rating : ''].filter(Boolean).join('  ·  '));
        info.appendChild(meta);
        var plot = el('div', 'detail-plot', '');
        info.appendChild(plot);
        var btns = el('div', 'detail-btns');
        info.appendChild(btns);
        box.appendChild(poster); box.appendChild(info);
        r.appendChild(box);

        function item() {
            return { key: 'mv:' + m.id, kind: 'movie', title: m.name, sub: m.category, img: m.poster, url: D.movieUrl(m), plot: plot.textContent };
        }
        function buttons() {
            btns.innerHTML = '';
            var w = KTV.history.get('mv:' + m.id);
            if (w && KTV.history.resumable(w)) {
                var cont = KTV.button('Verder kijken vanaf ' + KTV.clock(w.pos), 'play', 'primary');
                cont._act = function () { KTV.screens.push(KTV.Player.vod(item(), w.pos)); };
                var again = KTV.button('Vanaf het begin', 'replay');
                again._act = function () { KTV.screens.push(KTV.Player.vod(item(), 0)); };
                btns.appendChild(cont); btns.appendChild(again);
                var bar = el('div', 'bar detail-bar'); var fill = el('div', 'bar-fill'); fill.style.width = (KTV.history.progress(w) * 100) + '%';
                bar.appendChild(fill); btns.appendChild(bar);
                KTV.focus.set(cont);
            } else {
                var play = KTV.button('Afspelen', 'play', 'primary');
                play._act = function () { KTV.screens.push(KTV.Player.vod(item(), 0)); };
                btns.appendChild(play);
                KTV.focus.set(play);
            }
        }
        s.show = function () {
            buttons();
            D.movieDetails(m).then(function (d) {
                plot.textContent = d.plot || '';
                var extra = [d.year, d.genre, d.duration].filter(Boolean).join('  ·  ');
                if (extra) meta.textContent = (meta.textContent ? meta.textContent + '  ·  ' : '') + extra;
                if (d.backdrop) { bgImg.src = d.backdrop; bgImg.style.visibility = 'visible'; }
            });
        };
        s.resume = function () { buttons(); };
        return s;
    };

    /* ======================= Serie ======================= */

    KTV.SeriesScreen = function (x) {
        var s = screen('series');
        var r = s.root;
        var head = el('div', 'series-head');
        var cover = el('div', 'series-cover');
        if (x.cover) cover.appendChild(KTV.img(x.cover));
        var info = el('div', 'series-info');
        info.appendChild(el('div', 'hero-label', 'Serie'));
        info.appendChild(el('div', 'detail-title', x.name));
        info.appendChild(el('div', 'detail-meta', [x.category, x.rating && x.rating !== '0' ? 'Score ' + x.rating : ''].filter(Boolean).join('  ·  ')));
        var plot = el('div', 'series-plot', x.plot || '');
        info.appendChild(plot);
        var cont = KTV.button('', 'play', 'primary');
        cont.style.display = 'none';
        info.appendChild(cont);
        head.appendChild(cover); head.appendChild(info);
        r.appendChild(head);

        var body = el('div', 'series-body');
        var colS = el('div', 'col seasons');
        var colE = el('div', 'col episodes');
        body.appendChild(colS); body.appendChild(colE);
        r.appendChild(body);
        var msg = el('div', 'center-msg low', '');
        r.appendChild(msg);

        var data = null, season = -1, allEps = [];

        var seasons = new KTV.VList({
            rowH: 64, cls: 'groups-list',
            render: function (g, i, row) {
                row.appendChild(el('div', 'g-name', 'Seizoen ' + g.number));
                row.appendChild(el('div', 'g-count', String(g.episodes.length)));
                row.classList.toggle('active', g.number === season);
            },
            onSelect: function (g) { clearTimeout(s._t); s._t = setTimeout(function () { showSeason(g.number); }, 120); },
            onAct: function (g) { showSeason(g.number); KTV.focus.set(eps.root); },
            onKey: function (e) { if (e.keyCode === K.RIGHT) { KTV.focus.set(eps.root); return true; } return false; }
        });
        colS.appendChild(seasons.root);

        var eps = new KTV.VList({
            rowH: 112, cls: 'ep-list', empty: '',
            render: function (e, i, row) {
                row.className += ' ep-row';
                row.appendChild(el('div', 'ep-num', String(e.number)));
                var t = el('div', 'ep-text');
                t.appendChild(el('div', 'ep-title', e.title));
                if (e.plot) t.appendChild(el('div', 'ep-desc', e.plot));
                var w = KTV.history.get('ep:' + e.id);
                if (w && KTV.history.resumable(w)) {
                    var bar = el('div', 'bar'); var fill = el('div', 'bar-fill'); fill.style.width = (KTV.history.progress(w) * 100) + '%';
                    bar.appendChild(fill); t.appendChild(bar);
                }
                row.appendChild(t);
                var badge = w && KTV.history.finished(w) ? 'Gezien' : w && KTV.history.resumable(w) ? KTV.clock(w.pos) : e.duration ? Math.round(e.duration / 60) + ' min' : '';
                row.appendChild(el('div', 'ep-badge', badge));
            },
            onAct: function (e) { play(e); },
            onKey: function (ev) { if (ev.keyCode === K.LEFT) { KTV.focus.set(seasons.root); return true; } return false; }
        });
        colE.appendChild(eps.root);

        function epItem(e) {
            return {
                key: 'ep:' + e.id, kind: 'episode', title: x.name, sub: D.episodeLabel(e) + '  ·  ' + e.title,
                img: e.image || x.cover, url: D.episodeUrl(e), dur: e.duration * 1000, sid: x.id, plot: e.plot || x.plot
            };
        }
        function play(e) {
            KTV.queue = { series: x, episodes: allEps, item: epItem };
            KTV.playItem(epItem(e));
        }
        function lastWatched() {
            var hist = KTV.history.all().filter(function (w) { return w.kind === 'episode' && w.sid === x.id; });
            if (!hist.length) return null;
            var w = hist[0];
            for (var i = 0; i < allEps.length; i++) {
                if ('ep:' + allEps[i].id === w.key) return KTV.history.finished(w) && allEps[i + 1] ? allEps[i + 1] : allEps[i];
            }
            return null;
        }
        function updateCont() {
            var e = lastWatched();
            if (!e) { cont.style.display = 'none'; return; }
            var w = KTV.history.get('ep:' + e.id);
            cont.querySelector('span').textContent = w && KTV.history.resumable(w)
                ? 'Verder kijken: ' + D.episodeLabel(e) + ' vanaf ' + KTV.clock(w.pos)
                : 'Kijk verder: ' + D.episodeLabel(e) + '  ' + e.title;
            cont.style.display = '';
            cont._act = function () { play(e); };
        }
        function showSeason(n) {
            if (n === season) return;
            season = n;
            var sObj = null;
            data.seasons.forEach(function (z) { if (z.number === n) sObj = z; });
            eps.setItems(sObj ? sObj.episodes : [], 0);
            seasons.refresh();
        }
        s.show = function () {
            msg.innerHTML = '<span class="spinner"></span>';
            D.seriesInfo(x).then(function (d) {
                msg.innerHTML = '';
                data = d;
                if (d.plot) plot.textContent = d.plot;
                allEps = [];
                d.seasons.forEach(function (z) { allEps = allEps.concat(z.episodes); });
                if (!d.seasons.length) { msg.textContent = 'Geen afleveringen gevonden.'; return; }
                var last = lastWatched();
                var start = last ? last.season : d.seasons[0].number;
                var idx = 0;
                d.seasons.forEach(function (z, i) { if (z.number === start) idx = i; });
                seasons.setItems(d.seasons, idx);
                showSeason(start);
                if (last) {
                    eps.items.forEach(function (e, i) { if (e.id === last.id) { eps.index = i; eps.ensureVisible(); eps.render(); } });
                }
                updateCont();
                KTV.focus.set(cont.style.display === 'none' ? eps.root : cont);
            }, function (e) { msg.textContent = 'Laden mislukt. ' + ((e && e.message) || ''); });
        };
        s.resume = function () { if (data) { eps.refresh(); updateCont(); } };
        return s;
    };
})();
