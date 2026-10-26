/* KijkTV voor LG webOS - schermen: inloggen, home, live tv, geschiedenis, instellingen. */
'use strict';

(function () {
    var el = KTV.el, D = KTV.data, K = KTV.KEY;

    function screen(cls) {
        var s = { root: el('div', 'screen ' + cls) };
        return s;
    }

    function button(text, icon, cls) {
        var b = el('div', 'btn f ' + (cls || ''));
        b.innerHTML = (icon ? KTV.icon(icon) : '') + '<span></span>';
        b.querySelector('span').textContent = text;
        return b;
    }
    KTV.button = button;

    function field(label, type, placeholder) {
        var wrap = el('div', 'field');
        wrap.appendChild(el('div', 'field-label', label));
        var input = el('input', 'input f');
        input.type = type || 'text';
        input.placeholder = placeholder || '';
        input.setAttribute('autocomplete', 'off');
        input.setAttribute('autocapitalize', 'none');
        input.setAttribute('spellcheck', 'false');
        wrap.appendChild(input);
        return { wrap: wrap, input: input };
    }

    /** Speelt een film/aflevering/uitzending; vraagt "verder kijken?" als je al begonnen was. */
    KTV.playItem = function (item) {
        var saved = KTV.history.get(item.key);
        if (saved && KTV.history.resumable(saved)) {
            KTV.dialog.choose(item.title, '', [
                'Verder kijken vanaf ' + KTV.clock(saved.pos),
                'Opnieuw beginnen'
            ], function (i) {
                if (i === 0) KTV.screens.push(KTV.Player.vod(item, saved.pos));
                if (i === 1) KTV.screens.push(KTV.Player.vod(item, 0));
            });
        } else {
            KTV.screens.push(KTV.Player.vod(item, 0));
        }
    };

    /* ======================= Inloggen ======================= */

    KTV.Login = function () {
        var s = screen('login');
        var r = s.root;
        var left = el('div', 'login-form');
        left.appendChild(el('div', 'login-logo', 'KijkTV'));
        left.appendChild(el('h1', '', 'Inloggen'));
        left.appendChild(el('p', 'muted', 'Vul de gegevens in die je van je IPTV-aanbieder hebt gekregen.'));
        var server = field('Server-link', 'url', 'http://server.nl:8080');
        var user = field('Gebruikersnaam', 'text', '');
        var pass = field('Wachtwoord', 'password', '');
        left.appendChild(server.wrap); left.appendChild(user.wrap); left.appendChild(pass.wrap);
        var loginBtn = button('Inloggen', null, 'primary');
        left.appendChild(loginBtn);
        var msg = el('div', 'login-msg');
        left.appendChild(msg);

        var right = el('div', 'login-side');
        right.appendChild(el('h2', '', 'Eén lange link?'));
        right.appendChild(el('p', 'muted', 'Heb je een M3U-link gekregen (eventueel met username= en password= erin)? Plak of typ die hier.'));
        var m3u = field('Playlist-link', 'url', 'http://…');
        right.appendChild(m3u.wrap);
        var m3uBtn = button('Link gebruiken');
        right.appendChild(m3uBtn);
        right.appendChild(el('p', 'muted small', 'Tip: met de LG ThinQ-app op je telefoon kun je tekst typen en plakken op de tv.'));
        r.appendChild(left);
        r.appendChild(right);

        var a = KTV.account.get();
        if (a && a.type === 'xtream') { server.input.value = a.server; user.input.value = a.user; pass.input.value = a.pass; }
        if (a && a.type === 'm3u') m3u.input.value = a.url;

        var busy = false;
        function tryLogin(acc) {
            if (busy) return;
            if (!acc) { msg.className = 'login-msg error'; msg.textContent = 'Vul server-link, gebruikersnaam en wachtwoord in.'; return; }
            busy = true;
            msg.className = 'login-msg';
            msg.innerHTML = '<span class="spinner small"></span> Inloggen en zenders ophalen…';
            D.load(acc).then(function () {
                busy = false;
                KTV.account.set(acc);
                KTV.epg.clear();
                D.movies = null; D.series = null;
                KTV.screens.reset(KTV.Home());
            }, function (e) {
                busy = false;
                msg.className = 'login-msg error';
                msg.textContent = (e && e.message) || 'Er ging iets mis. Probeer het opnieuw.';
            });
        }
        loginBtn._act = function () { tryLogin(KTV.account.fromInput(server.input.value, user.input.value, pass.input.value, '')); };
        m3uBtn._act = function () { tryLogin(KTV.account.fromInput('', '', '', m3u.input.value)); };

        s.show = function () { KTV.focus.set(server.input.value ? loginBtn : server.input); };
        s.back = function () { if (KTV.account.get() && KTV.screens.stack.length > 1) KTV.screens.pop(); else KTV.exit(); };
        return s;
    };

    /* ======================= Menubalk ======================= */

    function navBar(active) {
        var bar = el('div', 'nav');
        bar.appendChild(el('div', 'nav-logo', 'KijkTV'));
        var items = [
            ['home', 'Home'], ['live', 'Live tv'], ['guide', 'Tv-gids'],
            ['movies', 'Films'], ['series', 'Series'], ['history', 'Geschiedenis']
        ];
        items.forEach(function (it) {
            var b = el('div', 'nav-btn f' + (it[0] === active ? ' active' : ''), it[1]);
            b._act = function () { KTV.go(it[0]); };
            bar.appendChild(b);
        });
        bar.appendChild(el('div', 'spacer'));
        var clock = el('div', 'nav-clock', KTV.time(Date.now()));
        bar.appendChild(clock);
        var set = el('div', 'nav-btn nav-icon f');
        set.innerHTML = KTV.icon('settings');
        set._act = function () { KTV.settingsMenu(); };
        bar.appendChild(set);
        bar._clock = clock;
        return bar;
    }

    KTV.go = function (where) {
        var top = KTV.screens.top();
        if (where === 'home') { while (KTV.screens.stack.length > 1) KTV.screens.pop(); return; }
        if (where === 'live') KTV.screens.push(KTV.Live());
        if (where === 'guide') {
            var favs = KTV.fav.channels();
            KTV.screens.push(KTV.Guide(favs.length ? favs : D.channels, 0, favs.length ? 'Jouw zenders' : 'Alle zenders'));
        }
        if (where === 'movies') KTV.screens.push(KTV.Media(false));
        if (where === 'series') KTV.screens.push(KTV.Media(true));
        if (where === 'history') KTV.screens.push(KTV.History());
        return top;
    };

    /* ======================= Home ======================= */

    function wideCard(c) {
        var e = el('div', 'card wide');
        var inner = el('div', 'card-inner');
        if (c.image) inner.appendChild(KTV.img(c.image, 'card-img'));
        if (c.logo) inner.appendChild(KTV.img(c.logo, 'card-logo'));
        inner.appendChild(el('div', 'card-shade'));
        var txt = el('div', 'card-text');
        var t = el('div', 'card-title', c.title);
        var sub = el('div', 'card-sub', c.sub || '');
        var bar = el('div', 'bar'); var fill = el('div', 'bar-fill'); bar.appendChild(fill);
        txt.appendChild(t); txt.appendChild(sub); txt.appendChild(bar);
        inner.appendChild(txt);
        e.appendChild(inner);
        c.update = function () {
            var p = c.progress, s2 = c.sub;
            if (c.channel) {
                var now = KTV.epg.now(c.channel);
                s2 = now ? KTV.time(now.start) + '  ' + now.title : c.channel.group;
                p = now ? KTV.progress(now) : 0;
            }
            sub.textContent = s2 || '';
            bar.style.display = p > 0 ? 'block' : 'none';
            fill.style.width = (p * 100) + '%';
        };
        c.update();
        return e;
    }

    function posterCard(c) {
        var e = el('div', 'card poster');
        var inner = el('div', 'card-inner');
        inner.appendChild(el('div', 'poster-name', c.title));
        if (c.image) inner.appendChild(KTV.img(c.image, 'card-img'));
        if (c.progress > 0) {
            var bar = el('div', 'bar bottom'); var fill = el('div', 'bar-fill');
            fill.style.width = (c.progress * 100) + '%'; bar.appendChild(fill); inner.appendChild(bar);
        }
        e.appendChild(inner);
        c.update = function () { };
        return e;
    }

    KTV.Home = function () {
        var s = screen('home');
        var r = s.root;
        var heroBg = el('div', 'hero-bg');
        var heroImg = el('img', 'hero-img');
        heroImg.onerror = function () { heroImg.style.visibility = 'hidden'; };
        heroBg.appendChild(heroImg);
        heroBg.appendChild(el('div', 'hero-fade'));
        r.appendChild(heroBg);
        var nav = navBar('home');
        r.appendChild(nav);
        var hero = el('div', 'hero');
        var hLabel = el('div', 'hero-label'), hTitle = el('div', 'hero-title'), hMeta = el('div', 'hero-meta');
        var hBar = el('div', 'bar hero-bar'), hFill = el('div', 'bar-fill'); hBar.appendChild(hFill);
        var hDesc = el('div', 'hero-desc');
        [hLabel, hTitle, hMeta, hBar, hDesc].forEach(function (x) { hero.appendChild(x); });
        r.appendChild(hero);
        var rows = el('div', 'home-rows');
        r.appendChild(rows);
        var msg = el('div', 'center-msg');
        r.appendChild(msg);
        var cardsAll = [];
        var heroCard = null;
        var firstBuild = true;

        function showHero(c) {
            heroCard = c;
            hLabel.textContent = c.label || '';
            hTitle.textContent = c.title;
            var desc = c.desc || '', meta = c.meta || '', p = c.progress || 0;
            if (c.channel) {
                var now = KTV.epg.now(c.channel), next = KTV.epg.next(c.channel);
                meta = now ? 'Nu  ' + KTV.range(now) + '  ·  ' + now.title : c.channel.group;
                desc = (now && now.desc ? now.desc : '') + (next ? (now && now.desc ? '\n' : '') + 'Straks ' + KTV.time(next.start) + '  ' + next.title : '');
                p = now ? KTV.progress(now) : 0;
            }
            hMeta.textContent = meta;
            hDesc.textContent = desc;
            hBar.style.display = p > 0 ? 'block' : 'none';
            hFill.style.width = (p * 100) + '%';
            var img = c.heroImage || null;
            if (img) { heroImg.style.visibility = 'visible'; if (heroImg.src !== img) heroImg.src = img; }
            else { heroImg.style.visibility = 'hidden'; heroImg.removeAttribute('src'); }
        }

        function channelCards(list, label) {
            return list.map(function (ch, i) {
                KTV.epg.request(ch);
                return {
                    title: ch.name, logo: ch.logo, channel: ch, label: label,
                    act: function () { KTV.screens.push(KTV.Player.live(list, i)); }
                };
            });
        }

        function addRow(title, cards, poster) {
            if (!cards.length) return;
            cards.forEach(function (c) { cardsAll.push(c); });
            var row = new KTV.HRow({
                title: title, cards: cards,
                renderCard: function (c) { return poster ? posterCard(c) : wideCard(c); },
                onSelect: showHero
            });
            row.root.classList.toggle('posters', !!poster);
            rows.appendChild(row.root);
        }

        function build() {
            var focusedRow = -1, focusedIdx = 0;
            var vps = rows.querySelectorAll('.hrow-viewport');
            for (var i = 0; i < vps.length; i++) if (vps[i] === KTV.focus.cur) focusedRow = i;
            rows.innerHTML = '';
            cardsAll = [];
            if (!D.channels.length) return;
            msg.textContent = '';

            var resume = KTV.history.resumeList().slice(0, 20).map(function (w) {
                return {
                    title: w.title, sub: w.sub, image: w.img, progress: KTV.history.progress(w), label: 'Verder kijken',
                    meta: 'Gestopt bij ' + KTV.clock(w.pos) + (w.dur ? ' van ' + KTV.clock(w.dur) : ''), heroImage: w.img,
                    act: function () { KTV.playItem(w); }
                };
            });
            addRow('Verder kijken', resume);

            var favs = KTV.fav.channels();
            if (favs.length) addRow('Jouw zenders', channelCards(favs.slice(0, 40), 'Jouw zenders'));
            else addRow('Zenders', channelCards(D.channels.slice(0, 40), 'Live tv'));

            var byUrl = {};
            D.channels.forEach(function (c) { byUrl[c.url] = c; });
            var recent = [];
            KTV.history.all().forEach(function (w) { if (w.kind === 'live' && byUrl[w.url] && recent.length < 15) recent.push(byUrl[w.url]); });
            addRow('Laatst gekeken', channelCards(recent, 'Laatst gekeken'));

            if (D.movies && D.movies.length) {
                var nm = D.movies.slice().sort(function (a, b) { return b.added - a.added; }).slice(0, 30);
                addRow('Nieuwe films', nm.map(function (m) {
                    return {
                        title: m.name, image: m.poster, heroImage: m.poster, label: 'Film',
                        meta: [m.category, m.rating && m.rating !== '0' ? 'Score ' + m.rating : ''].filter(Boolean).join('  ·  '),
                        act: function () { KTV.screens.push(KTV.MovieDetail(m)); }
                    };
                }), true);
            }
            if (D.series && D.series.length) {
                var ns = D.series.slice().sort(function (a, b) { return b.added - a.added; }).slice(0, 30);
                addRow('Nieuwe series', ns.map(function (x) {
                    return {
                        title: x.name, image: x.cover, heroImage: x.cover, label: 'Serie', desc: x.plot,
                        meta: [x.category, x.rating && x.rating !== '0' ? 'Score ' + x.rating : ''].filter(Boolean).join('  ·  '),
                        act: function () { KTV.screens.push(KTV.SeriesScreen(x)); }
                    };
                }), true);
            }

            var vps2 = rows.querySelectorAll('.hrow-viewport');
            if (focusedRow >= 0 && vps2[Math.min(focusedRow, vps2.length - 1)]) KTV.focus.set(vps2[Math.min(focusedRow, vps2.length - 1)]);
            else if (firstBuild || !KTV.focus.cur || !document.body.contains(KTV.focus.cur)) {
                if (vps2[0]) KTV.focus.set(vps2[0]);
            }
            if (vps2.length) firstBuild = false;
            if (!heroCard && cardsAll[0]) showHero(cardsAll[0]);
        }

        function loadAll() {
            var a = KTV.account.get();
            if (!a) { KTV.screens.reset(KTV.Login()); return; }
            if (D.channels.length) { build(); loadVod(); return; }
            msg.innerHTML = '<span class="spinner"></span><div>Zenders laden…</div>';
            D.load(a).then(function () { build(); loadVod(); }, function (e) {
                msg.innerHTML = '';
                KTV.dialog.choose('Zenders laden mislukt', (e && e.message) || '', ['Opnieuw proberen', 'Inloggegevens wijzigen'], function (i) {
                    if (i === 0) loadAll();
                    if (i === 1) KTV.screens.push(KTV.Login());
                });
            });
        }

        var vodBusy = false;
        function loadVod() {
            var a = KTV.account.get();
            if (!a || a.type !== 'xtream' || vodBusy || (D.movies && D.series)) return;
            vodBusy = true;
            D.loadMovies().then(build, function () { }).then(function () {
                return D.loadSeries().then(build, function () { });
            }).then(function () { vodBusy = false; });
        }

        function onEpg() {
            cardsAll.forEach(function (c) { if (c.channel && c.update) c.update(); });
            if (heroCard && heroCard.channel) showHero(heroCard);
        }
        KTV.epg.onUpdate(onEpg);
        var timer = setInterval(function () { nav._clock.textContent = KTV.time(Date.now()); onEpg(); }, 20000);

        s.show = function () { loadAll(); };
        s.resume = function () { if (D.channels.length) build(); KTV.devmode.renewIfNeeded(false); };
        s.reload = function () { D.channels = []; loadAll(); };
        s.back = function () { KTV.exit(); };
        s.destroy = function () { clearInterval(timer); KTV.epg.offUpdate(onEpg); };
        KTV.home = s;
        return s;
    };

    /* ======================= Live tv ======================= */

    function channelRow(ch, i, row) {
        row.className += ' ch-row';
        row.appendChild(el('div', 'ch-num', String(i + 1)));
        var logo = el('div', 'ch-logo');
        if (ch.logo) logo.appendChild(KTV.img(ch.logo));
        row.appendChild(logo);
        var txt = el('div', 'ch-text');
        txt.appendChild(el('div', 'ch-name', ch.name));
        var now = KTV.epg.now(ch);
        if (now) txt.appendChild(el('div', 'ch-now', KTV.time(now.start) + '  ' + now.title));
        row.appendChild(txt);
        if (ch.archive) { var a = el('div', 'ch-badge'); a.innerHTML = KTV.icon('history'); row.appendChild(a); }
        if (KTV.fav.has(ch.url)) { var f = el('div', 'ch-badge star'); f.innerHTML = KTV.icon('star'); row.appendChild(f); }
    }
    KTV.channelRow = channelRow;

    KTV.Live = function () {
        var s = screen('live');
        var r = s.root;
        var head = el('div', 'page-head');
        head.appendChild(el('div', 'page-title', 'Live tv'));
        var search = el('input', 'input search f');
        search.placeholder = 'Zoek een zender';
        head.appendChild(search);
        var clock = el('div', 'nav-clock', KTV.time(Date.now()));
        head.appendChild(clock);
        r.appendChild(head);

        var body = el('div', 'live-body');
        var groupsTitle = el('div', 'col-title', 'Groepen');
        var chTitle = el('div', 'col-title', '');
        var colG = el('div', 'col groups'); colG.appendChild(groupsTitle);
        var colC = el('div', 'col channels'); colC.appendChild(chTitle);
        var info = el('div', 'col info');
        body.appendChild(colG); body.appendChild(colC); body.appendChild(info);
        r.appendChild(body);
        r.appendChild(el('div', 'hint', 'OK: kijken   ·   OK lang ingedrukt: favoriet'));

        var current = [];
        var groupKey = KTV.store.get('lastGroup', '__all');

        var groups = new KTV.VList({
            rowH: 64, cls: 'groups-list',
            render: function (g, i, row) {
                row.appendChild(el('div', 'g-name', g.name));
                row.appendChild(el('div', 'g-count', String(g.count)));
                row.classList.toggle('active', g.key === groupKey);
            },
            onSelect: function (g) { clearTimeout(s._gt); s._gt = setTimeout(function () { selectGroup(g.key); }, 150); },
            onAct: function (g) { selectGroup(g.key); KTV.focus.set(channels.root); },
            onKey: function (e) {
                if (e.keyCode === K.RIGHT) { KTV.focus.set(channels.root); return true; }
                return false;
            }
        });
        colG.appendChild(groups.root);

        var channels = new KTV.VList({
            rowH: 80, cls: 'ch-list', empty: 'Geen zenders',
            render: channelRow,
            onSelect: function (ch) { showInfo(ch); clearTimeout(s._et); s._et = setTimeout(function () { KTV.epg.request(ch); }, 250); },
            onAct: function (ch, i) {
                KTV.store.set('lastChannel', ch.url);
                KTV.screens.push(KTV.Player.live(current, i));
            },
            onKey: function (e) {
                if (e.keyCode === K.LEFT) { KTV.focus.set(groups.root); return true; }
                return false;
            }
        });
        channels.root._long = function () {
            var ch = channels.current();
            if (!ch) return;
            var on = KTV.fav.toggle(ch.url);
            KTV.toast(on ? ch.name + ' toegevoegd aan favorieten' : ch.name + ' verwijderd uit favorieten');
            buildGroups();
            if (groupKey === '__fav') selectGroup('__fav', true); else channels.refresh();
        };
        colC.appendChild(channels.root);

        function groupList() {
            var g = [{ key: '__fav', name: 'Favorieten', count: KTV.fav.channels().length },
                { key: '__all', name: 'Alle zenders', count: D.channels.length }];
            D.groups.forEach(function (x) { g.push({ key: x.name, name: x.name, count: x.list.length }); });
            return g;
        }
        function buildGroups() {
            var g = groupList();
            var idx = 0;
            g.forEach(function (x, i) { if (x.key === groupKey) idx = i; });
            groups.setItems(g, idx);
        }
        function listFor(key) {
            if (key === '__fav') return KTV.fav.channels();
            if (key === '__all') return D.channels;
            for (var i = 0; i < D.groups.length; i++) if (D.groups[i].name === key) return D.groups[i].list;
            return [];
        }
        function selectGroup(key, keepIndex) {
            if (key === groupKey && !keepIndex && current.length) return;
            groupKey = key;
            KTV.store.set('lastGroup', key);
            search.value = '';
            current = listFor(key);
            var name = key === '__fav' ? 'Favorieten' : key === '__all' ? 'Alle zenders' : key;
            chTitle.textContent = name + '  ·  ' + current.length + ' zenders';
            channels.setEmpty(key === '__fav' ? 'Nog geen favorieten. Houd OK ingedrukt op een zender.' : 'Geen zenders');
            var idx = 0;
            var last = KTV.store.get('lastChannel', null);
            current.forEach(function (c, i) { if (c.url === last) idx = i; });
            channels.setItems(current, keepIndex ? channels.index : idx);
            groups.refresh();
        }

        search.addEventListener('input', function () {
            var q = search.value.trim().toLowerCase();
            if (!q) { selectGroup(groupKey, true); return; }
            current = D.channels.filter(function (c) { return c.name.toLowerCase().indexOf(q) >= 0; });
            chTitle.textContent = 'Zoeken: "' + search.value.trim() + '"  ·  ' + current.length + ' gevonden';
            channels.setItems(current, 0);
        });

        function showInfo(ch) {
            info.innerHTML = '';
            var logo = el('div', 'info-logo');
            if (ch.logo) logo.appendChild(KTV.img(ch.logo));
            info.appendChild(logo);
            info.appendChild(el('div', 'info-name', ch.name));
            var meta = ch.group + (ch.archive ? '  ·  ' + ch.archive + (ch.archive === 1 ? ' dag' : ' dagen') + ' terugkijken' : '');
            info.appendChild(el('div', 'info-meta', meta));
            var now = KTV.epg.now(ch), next = KTV.epg.next(ch);
            if (now) {
                var box = el('div', 'info-box');
                box.appendChild(el('div', 'info-label', 'Nu  ·  ' + KTV.range(now)));
                box.appendChild(el('div', 'info-title', now.title));
                var bar = el('div', 'bar'); var fill = el('div', 'bar-fill'); fill.style.width = (KTV.progress(now) * 100) + '%';
                bar.appendChild(fill); box.appendChild(bar);
                if (now.desc) box.appendChild(el('div', 'info-desc', now.desc));
                info.appendChild(box);
            }
            if (next) {
                var nb = el('div', 'info-next');
                nb.appendChild(el('span', 'info-label', 'Straks  ' + KTV.time(next.start) + '  '));
                nb.appendChild(el('span', '', next.title));
                info.appendChild(nb);
            }
        }

        function onEpg() {
            channels.refresh();
            var ch = channels.current();
            if (ch) showInfo(ch);
        }
        KTV.epg.onUpdate(onEpg);
        var timer = setInterval(function () { clock.textContent = KTV.time(Date.now()); onEpg(); }, 30000);

        s.show = function () {
            buildGroups();
            selectGroup(groupKey, false);
            KTV.focus.set(channels.root);
        };
        s.resume = function () {
            var last = KTV.store.get('lastChannel', null);
            current.forEach(function (c, i) { if (c.url === last && channels.index !== i) { channels.index = i; channels.ensureVisible(); } });
            channels.refresh();
            buildGroups();
        };
        s.onKey = function (e) {
            if (e.keyCode === K.UP && KTV.focus.cur === channels.root) { KTV.focus.set(search); return true; }
            return false;
        };
        s.destroy = function () { clearInterval(timer); KTV.epg.offUpdate(onEpg); };
        return s;
    };

    /* ======================= Geschiedenis ======================= */

    KTV.History = function () {
        var s = screen('history');
        var r = s.root;
        var head = el('div', 'page-head');
        head.appendChild(el('div', 'page-title', 'Geschiedenis'));
        var filters = el('div', 'filters');
        var defs = [['all', 'Alles'], ['resume', 'Verder kijken'], ['vod', 'Films en series'], ['live', 'Live tv']];
        var btns = {};
        var filter = KTV.history.resumeList().length ? 'resume' : 'all';
        defs.forEach(function (d) {
            var b = el('div', 'chip f', d[1]);
            b._act = function () { setFilter(d[0]); KTV.focus.set(list.root); };
            b._focus = function () { setFilter(d[0]); };
            btns[d[0]] = b;
            filters.appendChild(b);
        });
        head.appendChild(filters);
        head.appendChild(el('div', 'spacer'));
        var clear = button('Geschiedenis wissen', 'trash');
        head.appendChild(clear);
        r.appendChild(head);

        var list = new KTV.VList({
            rowH: 104, cls: 'hist-list', empty: 'Nog niets bekeken.',
            render: function (w, i, row) {
                row.className += ' hist-row';
                var img = el('div', 'hist-img');
                if (w.img) img.appendChild(KTV.img(w.img));
                row.appendChild(img);
                var t = el('div', 'hist-text');
                var kind = { live: 'Live tv', movie: 'Film', episode: 'Serie', catchup: 'Terugkijken' }[w.kind] || '';
                t.appendChild(el('div', 'hist-title', w.title));
                var state = KTV.history.finished(w) ? '  ·  uitgekeken' : KTV.history.resumable(w) ? '  ·  gestopt bij ' + KTV.clock(w.pos) : '';
                t.appendChild(el('div', 'hist-sub', kind + (w.sub ? '  ·  ' + w.sub : '') + state));
                if (KTV.history.resumable(w)) {
                    var bar = el('div', 'bar'); var fill = el('div', 'bar-fill'); fill.style.width = (KTV.history.progress(w) * 100) + '%';
                    bar.appendChild(fill); t.appendChild(bar);
                }
                row.appendChild(t);
                row.appendChild(el('div', 'hist-when', KTV.day(w.at) + '\n' + KTV.time(w.at)));
            },
            onAct: function (w) { play(w); }
        });
        list.root._long = function () {
            var w = list.current();
            if (!w) return;
            KTV.dialog.choose(w.title, 'Verwijderen uit je geschiedenis?', ['Verwijderen', 'Annuleren'], function (i) {
                if (i === 0) { KTV.history.remove(w.key); refresh(); }
            });
        };
        r.appendChild(list.root);
        r.appendChild(el('div', 'hint', 'OK: afspelen   ·   OK lang ingedrukt: verwijderen'));

        clear._act = function () {
            KTV.dialog.choose('Geschiedenis wissen?', 'Alles wat je bekeken hebt en alle punten om verder te kijken worden gewist. Je favorieten blijven bewaard.',
                ['Alles wissen', 'Annuleren'], function (i) {
                    if (i === 0) { KTV.history.clear(); KTV.toast('Geschiedenis gewist'); refresh(); }
                }, 1);
        };

        function setFilter(f) { if (f === filter && list.items.length) return; filter = f; refresh(); }
        function refresh() {
            Object.keys(btns).forEach(function (k) { btns[k].classList.toggle('active', k === filter); });
            var all = KTV.history.all();
            var l = all.filter(function (w) {
                if (filter === 'resume') return KTV.history.resumable(w);
                if (filter === 'vod') return KTV.history.isVideo(w);
                if (filter === 'live') return w.kind === 'live';
                return true;
            });
            list.setEmpty(!all.length ? 'Nog niets bekeken.' : filter === 'resume' ? 'Niets om verder te kijken.' : 'Niets in deze lijst.');
            list.setItems(l, Math.min(list.index, l.length - 1));
        }
        function play(w) {
            if (w.kind === 'live') {
                var idx = -1;
                D.channels.forEach(function (c, i) { if (c.url === w.url) idx = i; });
                if (idx < 0) { KTV.toast('Deze zender staat niet meer in je lijst'); return; }
                KTV.screens.push(KTV.Player.live(D.channels, idx));
                return;
            }
            KTV.playItem(w);
        }
        s.show = function () { refresh(); KTV.focus.set(list.items.length ? list.root : btns[filter]); };
        s.resume = function () { refresh(); };
        return s;
    };

    /* ======================= Instellingen ======================= */

    KTV.settingsMenu = function () {
        var a = KTV.account.get();
        var who = !a ? 'Niet ingelogd' : a.type === 'xtream' ? 'Ingelogd als ' + a.user + (D.info ? '  ·  ' + D.info : '') : 'M3U-playlist';
        var fmt = { auto: 'Automatisch', hls: 'HLS', ts: 'MPEG-TS' }[KTV.settings.get('format')] || 'Automatisch';
        KTV.dialog.choose('Instellingen', who, [
            'Inloggegevens wijzigen',
            'Streamtype: ' + fmt,
            'Zenderlijst vernieuwen',
            'Programmagids vernieuwen',
            'Ontwikkelaarsmodus verlengen (laatst: ' + KTV.devmode.label() + ')',
            'Over KijkTV'
        ], function (i) {
            if (i === 0) KTV.screens.push(KTV.Login());
            if (i === 1) {
                var cur = ['auto', 'hls', 'ts'].indexOf(KTV.settings.get('format'));
                KTV.dialog.choose('Streamtype', 'Hapert een zender of start hij niet? Probeer dan een ander type.',
                    ['Automatisch (aanbevolen)', 'HLS', 'MPEG-TS'], function (j) {
                        if (j >= 0) { KTV.settings.set('format', ['auto', 'hls', 'ts'][j]); KTV.toast('Opgeslagen'); }
                    }, cur < 0 ? 0 : cur);
            }
            if (i === 2) { while (KTV.screens.stack.length > 1) KTV.screens.pop(); if (KTV.home) KTV.home.reload(); }
            if (i === 3) { KTV.epg.clear(); KTV.toast('Programmagids wordt opnieuw opgehaald'); }
            if (i === 4) {
                if (!KTV.devmode.onTv()) { KTV.toast('Alleen beschikbaar op de tv'); return; }
                KTV.devmode.renewIfNeeded(true).then(function (ok) {
                    KTV.toast(ok ? 'Ontwikkelaarsmodus verlengd' : 'Automatisch verlengen lukte niet. Gebruik de Developer Mode-app.');
                });
            }
            if (i === 5) KTV.dialog.choose('KijkTV 1.1 voor LG', 'IPTV-speler voor je eigen abonnement.\nGeen reclame, geen kosten.', ['Sluiten'], null);
        });
    };
})();
