/* KijkTV voor LG webOS - speler (live tv, films, afleveringen, terugkijken) met pauzescherm. */
'use strict';

(function () {
    var el = KTV.el, D = KTV.data, K = KTV.KEY;

    function build() {
        var root = el('div', 'screen player');
        var video = el('video', 'video');
        video.setAttribute('preload', 'auto');
        root.appendChild(video);
        var spinner = el('div', 'spinner big');
        root.appendChild(spinner);
        var error = el('div', 'player-error');
        root.appendChild(error);

        var info = el('div', 'pinfo');
        var num = el('div', 'pinfo-num'), logo = el('div', 'pinfo-logo'), txt = el('div', 'pinfo-text');
        var name = el('div', 'pinfo-name'), now = el('div', 'pinfo-now');
        var bar = el('div', 'bar pinfo-bar'), fill = el('div', 'bar-fill'); bar.appendChild(fill);
        var next = el('div', 'pinfo-next'), hint = el('div', 'pinfo-hint');
        [name, now, bar, next, hint].forEach(function (x) { txt.appendChild(x); });
        info.appendChild(num); info.appendChild(logo); info.appendChild(txt);
        root.appendChild(info);

        var pause = el('div', 'pause');
        var pTop = el('div', 'pause-top');
        var pLabel = el('div', 'pause-label'), pTitle = el('div', 'pause-title'), pSub = el('div', 'pause-sub'), pDesc = el('div', 'pause-desc');
        [pLabel, pTitle, pSub, pDesc].forEach(function (x) { pTop.appendChild(x); });
        pause.appendChild(pTop);
        var pBottom = el('div', 'pause-bottom');
        var pState = el('div', 'pause-state');
        pState.innerHTML = KTV.icon('pause') + '<span>Gepauzeerd</span>';
        pBottom.appendChild(pState);
        var pTime = el('div', 'pause-time');
        var pPos = el('div', 'pause-pos'), pBar = el('div', 'bar pause-bar'), pFill = el('div', 'bar-fill'), pLeft = el('div', 'pause-left');
        pBar.appendChild(pFill);
        pTime.appendChild(pPos); pTime.appendChild(pBar); pTime.appendChild(pLeft);
        pBottom.appendChild(pTime);
        var pBtns = el('div', 'pause-btns');
        var bBack = KTV.button('10 sec', 'rewind', 'pbtn');
        var bPlay = KTV.button('Verder kijken', 'play', 'pbtn');
        var bFwd = KTV.button('10 sec', 'forward', 'pbtn');
        var bRestart = KTV.button('Vanaf begin', 'replay', 'pbtn');
        var bNext = KTV.button('Volgende aflevering', 'next', 'pbtn');
        [bBack, bPlay, bFwd, bRestart, bNext].forEach(function (b) { pBtns.appendChild(b); });
        pBottom.appendChild(pBtns);
        pause.appendChild(pBottom);
        root.appendChild(pause);

        return {
            root: root, video: video, spinner: spinner, error: error,
            info: info, num: num, logo: logo, name: name, now: now, bar: bar, fill: fill, next: next, hint: hint,
            pause: pause, pLabel: pLabel, pTitle: pTitle, pSub: pSub, pDesc: pDesc, pPos: pPos, pFill: pFill, pLeft: pLeft, pBar: pBar,
            bBack: bBack, bPlay: bPlay, bFwd: bFwd, bRestart: bRestart, bNext: bNext
        };
    }

    function setLogo(box, src) {
        box.innerHTML = '';
        box.style.display = src ? '' : 'none';
        if (src) box.appendChild(KTV.img(src));
    }

    function stopVideo(v) {
        try { v.pause(); } catch (e) { }
        v.removeAttribute('src');
        try { v.load(); } catch (e) { }
    }

    function pauseShown(u) { return u.pause.classList.contains('open'); }

    function showPause(u, o) {
        u.pLabel.textContent = o.label;
        u.pTitle.textContent = o.title;
        u.pSub.textContent = o.sub || '';
        u.pDesc.textContent = o.desc || '';
        [u.bBack, u.bFwd, u.pBar, u.pPos, u.pLeft].forEach(function (x) { x.style.display = o.seekable ? '' : 'none'; });
        u.bRestart.style.display = o.restart ? '' : 'none';
        u.bNext.style.display = o.next ? '' : 'none';
        if (o.next) u.bNext.querySelector('span').textContent = o.next;
        u.info.classList.remove('show');
        u.pause.classList.add('open');
        KTV.focus.set(u.bPlay);
    }

    function hidePause(u) {
        u.pause.classList.remove('open');
        if (KTV.focus.cur && u.pause.contains(KTV.focus.cur)) {
            KTV.focus.cur.classList.remove('focused');
            KTV.focus.cur = null;
        }
    }

    function updatePauseTime(u, pos, dur) {
        u.pPos.textContent = KTV.clock(pos);
        u.pLeft.textContent = dur > 0 ? '-' + KTV.clock(Math.max(0, dur - pos)) : '';
        u.pFill.style.width = dur > 0 ? (Math.min(1, pos / dur) * 100) + '%' : '0%';
    }

    KTV.Player = {};

    KTV.Player.catchupItem = function (ch, p) {
        return {
            key: 'cu:' + ch.sid + ':' + p.start, kind: 'catchup', title: p.title || ch.name,
            sub: ch.name + '  ·  ' + KTV.day(p.start) + ' ' + KTV.range(p), img: ch.logo,
            url: KTV.catchup.url(ch, p), dur: p.stop - p.start, plot: p.desc
        };
    };

    /* ======================= Live tv ======================= */

    KTV.Player.live = function (list, startIndex) {
        var u = build();
        var s = { root: u.root };
        var v = u.video;
        var index = Math.max(0, Math.min(startIndex, list.length - 1));
        var url = '', retried = false, altTried = false;
        var hideTimer = null, zapTimer = null, recTimer = null, tick = null;
        var lastLeft = 0, enterTimer = null, enterLong = false;

        function ch() { return list[index]; }

        function play() {
            var c = ch();
            retried = false; altTried = false;
            KTV.store.set('lastChannel', c.url);
            KTV.epg.request(c);
            start(D.liveUrl(c));
            clearTimeout(recTimer);
            recTimer = setTimeout(function () {
                KTV.history.record({ key: 'live:' + c.url, kind: 'live', title: c.name, sub: c.group, img: c.logo, url: c.url });
            }, 10000);
            showInfo();
        }

        function start(src) {
            url = src;
            u.error.classList.remove('show');
            u.spinner.classList.add('show');
            v.src = src;
            var p = v.play();
            if (p && p.catch) p.catch(function () { });
        }

        function showInfo() {
            var c = ch();
            u.num.textContent = String(index + 1);
            setLogo(u.logo, c.logo);
            u.name.textContent = c.name;
            updateInfo();
            u.info.classList.add('show');
            clearTimeout(hideTimer);
            hideTimer = setTimeout(function () { u.info.classList.remove('show'); }, 5000);
        }

        function updateInfo() {
            var c = ch();
            var n = KTV.epg.now(c), nx = KTV.epg.next(c);
            u.now.textContent = n ? 'Nu  ' + KTV.range(n) + '   ' + n.title : c.group;
            u.bar.style.display = n ? 'block' : 'none';
            if (n) u.fill.style.width = (KTV.progress(n) * 100) + '%';
            u.next.textContent = nx ? 'Straks  ' + KTV.time(nx.start) + '   ' + nx.title : '';
            u.hint.textContent = n && KTV.catchup.can(c, n) ? 'Druk 2x op links om dit programma vanaf het begin te kijken   ·   OK lang: pauze' : 'OK lang ingedrukt: pauze';
        }

        function zap(d) {
            hidePause(u);
            if (list.length < 2) return;
            index = (index + d + list.length) % list.length;
            var c = ch();
            u.num.textContent = String(index + 1);
            setLogo(u.logo, c.logo);
            u.name.textContent = c.name;
            updateInfo();
            u.info.classList.add('show');
            clearTimeout(zapTimer);
            zapTimer = setTimeout(play, 450);
        }

        function togglePause() {
            if (v.paused) {
                hidePause(u);
                var p = v.play(); if (p && p.catch) p.catch(function () { });
                showInfo();
                return;
            }
            v.pause();
            var c = ch(), n = KTV.epg.now(c), nx = KTV.epg.next(c);
            var sub = c.name + (n ? '  ·  ' + KTV.range(n) : '') + (nx ? '  ·  straks: ' + nx.title : '');
            showPause(u, {
                label: 'LIVE TV', title: (n && n.title) || c.name, sub: sub, desc: n ? n.desc : '',
                seekable: false, restart: !!(n && KTV.catchup.can(c, n)), next: null
            });
        }

        u.bPlay._act = togglePause;
        u.bRestart._act = function () {
            var n = KTV.epg.now(ch());
            if (n) { hidePause(u); KTV.screens.pop(); KTV.playItem(KTV.Player.catchupItem(ch(), n)); }
        };

        v.addEventListener('waiting', function () { u.spinner.classList.add('show'); });
        v.addEventListener('playing', function () { u.spinner.classList.remove('show'); u.error.classList.remove('show'); });
        v.addEventListener('error', function () {
            if (!retried) { retried = true; start(url); return; }
            var alt = !altTried ? D.altUrl(url) : null;
            if (alt) { altTried = true; start(alt); return; }
            u.spinner.classList.remove('show');
            u.error.textContent = 'Deze zender speelt nu niet af.\nDruk op OK om het opnieuw te proberen, of kies een andere zender.';
            u.error.classList.add('show');
        });

        function onKeyUp(e) {
            if (e.keyCode !== K.ENTER) return;
            var wasShort = enterTimer !== null && !enterLong;
            clearTimeout(enterTimer);
            enterTimer = null;
            enterLong = false;
            if (!wasShort || pauseShown(u)) return;
            if (u.error.classList.contains('show')) play(); else showInfo();
        }
        document.addEventListener('keyup', onKeyUp, true);

        s.onKey = function (e) {
            var k = e.keyCode;
            if (pauseShown(u)) {
                if (k === K.PLAY || k === K.PLAYPAUSE) { togglePause(); return true; }
                return false;
            }
            if (k === K.UP || k === K.CH_DOWN) { zap(-1); return true; }
            if (k === K.DOWN || k === K.CH_UP) { zap(1); return true; }
            if (k === K.ENTER) {
                if (!e.repeat && enterTimer === null) {
                    enterLong = false;
                    enterTimer = setTimeout(function () { enterLong = true; togglePause(); }, 650);
                }
                return true;
            }
            if (k === K.LEFT || k === K.RW) {
                var n = KTV.epg.now(ch()), t = Date.now();
                if (n && KTV.catchup.can(ch(), n) && t - lastLeft < 1500) {
                    lastLeft = 0;
                    KTV.screens.pop();
                    KTV.playItem(KTV.Player.catchupItem(ch(), n));
                    return true;
                }
                lastLeft = t;
                showInfo();
                return true;
            }
            if (k === K.RIGHT || k === K.INFO) { showInfo(); return true; }
            if (k === K.PAUSE || k === K.PLAY || k === K.PLAYPAUSE) { togglePause(); return true; }
            return false;
        };

        function onEpg() { if (u.info.classList.contains('show')) updateInfo(); }
        KTV.epg.onUpdate(onEpg);

        s.show = function () {
            play();
            tick = setInterval(function () { if (u.info.classList.contains('show')) updateInfo(); }, 15000);
        };
        s.pause = function () { stopVideo(v); };
        s.resume = function () { play(); };
        s.destroy = function () {
            clearTimeout(hideTimer); clearTimeout(zapTimer); clearTimeout(recTimer); clearInterval(tick);
            document.removeEventListener('keyup', onKeyUp, true);
            KTV.epg.offUpdate(onEpg);
            stopVideo(v);
        };
        return s;
    };

    /* ======================= Films, afleveringen, terugkijken ======================= */

    KTV.Player.vod = function (item, startPos) {
        var u = build();
        var s = { root: u.root };
        var v = u.video;
        var retried = false, hideTimer = null, saveTimer = null;
        var repeatCount = 0;
        var variants = item.kind === 'catchup' ? KTV.catchup.variants(item.url) : [item.url], variant = 0, readyOnce = false, startAt = 0;
        var plot = item.plot || '';
        u.num.style.display = 'none';

        function dur() {
            var d = v.duration;
            return isFinite(d) && d > 0 ? d * 1000 : (item.dur || 0);
        }
        function pos() { return (v.currentTime || 0) * 1000; }

        function start(at) {
            retried = false;
            variant = 0; readyOnce = false; startAt = at || 0;
            u.error.classList.remove('show');
            u.spinner.classList.add('show');
            KTV.history.record(item);
            v.src = variants[0];
            var started = false;
            v.onloadedmetadata = function () {
                if (!started && at > 0) { try { v.currentTime = at / 1000; } catch (e) { } }
                started = true;
            };
            var p = v.play(); if (p && p.catch) p.catch(function () { });
            setLogo(u.logo, item.img);
            u.name.textContent = item.title;
            showInfo();
        }

        function save() {
            var p = pos();
            if (p > 0) KTV.history.update(item.key, p, dur());
        }

        function nextEpisode() {
            var q = KTV.queue;
            if (item.kind !== 'episode' || !q || !q.series || q.series.id !== item.sid) return null;
            for (var i = 0; i < q.episodes.length; i++) {
                if ('ep:' + q.episodes[i].id === item.key) return q.episodes[i + 1] || null;
            }
            return null;
        }

        function showInfo() {
            updateInfo();
            u.info.classList.add('show');
            clearTimeout(hideTimer);
            if (!v.paused) hideTimer = setTimeout(function () { u.info.classList.remove('show'); }, 4000);
        }
        function updateInfo() {
            var d = dur(), p = pos();
            u.now.textContent = (item.kind === 'catchup' ? 'Terugkijken  ·  ' : '') + (item.sub || '');
            u.bar.style.display = 'block';
            u.fill.style.width = d > 0 ? (Math.min(1, p / d) * 100) + '%' : '0%';
            u.next.textContent = KTV.clock(p) + ' / ' + (d > 0 ? KTV.clock(d) : '--:--') + (v.paused ? '   ·   Gepauzeerd' : '');
            var nx = nextEpisode();
            u.hint.textContent = nx ? 'Hierna: ' + D.episodeLabel(nx) + '  ' + nx.title : '';
        }

        function seek(ms) {
            var t = Math.max(0, pos() + ms);
            var d = dur();
            if (d > 0) t = Math.min(t, d - 3000);
            try { v.currentTime = t / 1000; } catch (e) { }
            if (pauseShown(u)) updatePauseTime(u, t, d); else showInfo();
        }

        function togglePause() {
            if (v.paused) {
                hidePause(u);
                var p = v.play(); if (p && p.catch) p.catch(function () { });
                showInfo();
                return;
            }
            v.pause();
            save();
            var nx = nextEpisode();
            showPause(u, {
                label: item.kind === 'episode' ? 'SERIE' : item.kind === 'catchup' ? 'TERUGKIJKEN' : 'FILM',
                title: item.title, sub: item.sub, desc: plot, seekable: true, restart: true,
                next: nx ? 'Volgende: ' + D.episodeLabel(nx) : null
            });
            updatePauseTime(u, pos(), dur());
        }

        function goNext() {
            var nx = nextEpisode();
            var q = KTV.queue;
            if (!nx || !q) { KTV.screens.pop(); return; }
            hidePause(u);
            item = q.item(nx);
            plot = item.plot || '';
            start(0);
        }

        u.bPlay._act = togglePause;
        u.bBack._act = function () { seek(-10000); };
        u.bFwd._act = function () { seek(10000); };
        u.bRestart._act = function () { try { v.currentTime = 0; } catch (e) { } togglePause(); };
        u.bNext._act = function () { save(); goNext(); };

        v.addEventListener('waiting', function () { u.spinner.classList.add('show'); });
        v.addEventListener('playing', function () { readyOnce = true; u.spinner.classList.remove('show'); u.error.classList.remove('show'); });
        v.addEventListener('ended', function () {
            var d = dur();
            if (d > 0) KTV.history.update(item.key, d, d);
            goNext();
        });
        v.addEventListener('error', function () {
            if (!readyOnce && variant + 1 < variants.length) {
                variant++;
                v.src = variants[variant];
                v.onloadedmetadata = function () { if (startAt > 0) { try { v.currentTime = startAt / 1000; } catch (e) { } } };
                var pv = v.play(); if (pv && pv.catch) pv.catch(function () { });
                return;
            }
            if (!retried) {
                retried = true;
                var at = pos();
                v.src = variants[variant];
                v.onloadedmetadata = function () { if (at > 0) { try { v.currentTime = at / 1000; } catch (e) { } } };
                var p = v.play(); if (p && p.catch) p.catch(function () { });
                return;
            }
            u.spinner.classList.remove('show');
            u.error.textContent = item.kind === 'catchup'
                ? 'Dit moment is niet terug te kijken.\nJe aanbieder heeft hier waarschijnlijk geen opname van.\nProbeer een later tijdstip of een andere dag.'
                : 'Dit kan nu niet afgespeeld worden.\nDruk op OK om het opnieuw te proberen.';
            u.error.classList.add('show');
        });

        s.onKey = function (e) {
            var k = e.keyCode;
            if (pauseShown(u)) {
                if (k === K.PLAY || k === K.PLAYPAUSE) { togglePause(); return true; }
                if (k === K.RW) { seek(-10000); return true; }
                if (k === K.FF) { seek(10000); return true; }
                return false;
            }
            if (k === K.LEFT || k === K.RIGHT || k === K.RW || k === K.FF) {
                repeatCount = e.repeat ? repeatCount + 1 : 0;
                var step = repeatCount > 20 ? 120000 : repeatCount > 8 ? 60000 : repeatCount > 2 ? 30000 : 10000;
                seek(k === K.LEFT || k === K.RW ? -step : step);
                return true;
            }
            if (k === K.ENTER) {
                if (e.repeat) return true;
                if (u.error.classList.contains('show')) start(pos()); else togglePause();
                return true;
            }
            if (k === K.PAUSE || k === K.PLAY || k === K.PLAYPAUSE) { togglePause(); return true; }
            if (k === K.STOP) { save(); KTV.screens.pop(); return true; }
            if (k === K.UP || k === K.DOWN || k === K.INFO) { showInfo(); return true; }
            return false;
        };

        s.show = function () {
            start(startPos || 0);
            saveTimer = setInterval(function () {
                save();
                if (u.info.classList.contains('show')) updateInfo();
                if (pauseShown(u)) updatePauseTime(u, pos(), dur());
            }, 1000 * 10);
            if (item.kind === 'movie' && !plot) {
                var id = parseInt(String(item.key).replace('mv:', ''), 10);
                D.movieDetails({ id: id }).then(function (d) { plot = d.plot || ''; });
            }
        };
        s.pause = function () { save(); v.pause(); };
        s.resume = function () { };
        s.back = function () { save(); KTV.screens.pop(); };
        s.destroy = function () { save(); clearTimeout(hideTimer); clearInterval(saveTimer); stopVideo(v); if (KTV.sync) KTV.sync.flush(); };
        return s;
    };
})();
