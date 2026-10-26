/* Nepgegevens om de LG-app in een gewone browser te testen (wordt NIET meegeleverd in de app). */
(function () {
    function svg(w, h, bg, text, fg) {
        var s = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '"><rect width="100%" height="100%" fill="' + bg + '"/>' +
            '<text x="50%" y="54%" font-family="Arial" font-weight="bold" font-size="' + Math.round(h / 3.2) + '" fill="' + (fg || '#fff') + '" text-anchor="middle" dominant-baseline="middle">' + text + '</text></svg>';
        return 'data:image/svg+xml;base64,' + btoa(s);
    }
    var colors = ['#1f6feb', '#d1242f', '#8250df', '#1a7f37', '#bf8700', '#cf222e', '#0969da', '#6e7781'];
    var chNames = ['NPO 1', 'NPO 2', 'NPO 3', 'RTL 4', 'RTL 5', 'SBS 6', 'RTL 7', 'Veronica', 'Net5', 'RTL 8', 'SBS 9', 'RTL Z',
        'Ziggo Sport', 'ESPN 1', 'Discovery', 'National Geographic', 'Nickelodeon', 'Disney Channel', 'Comedy Central', 'MTV',
        'BBC One', 'BBC Two', 'CNN', 'Eurosport 1', 'Film1 Premiere', 'Viceland'];
    var cats = [['1', 'Nederland'], ['2', 'Sport'], ['3', 'Documentaires'], ['4', 'Kinderen'], ['5', 'Internationaal']];
    var shows = ['NOS Journaal', 'Goedemorgen Nederland', 'Het Klokhuis', 'Boer zoekt Vrouw', 'RTL Nieuws', 'Wie is de Mol?',
        'Studio Sport', 'Natuur in Nederland', 'Expeditie Robinson', 'De Wereld Draait Door', 'Formule 1: Grand Prix', 'Eredivisie Live',
        'Planet Earth III', 'Top Gear', 'Spongebob', 'The Voice', 'Jinek', 'Op1', 'EenVandaag', 'Kassa', 'Zondag met Lubach'];
    var live = chNames.map(function (n, i) {
        return [n, 100 + i, i % 5 === 3 ? '' : svg(200, 120, colors[i % colors.length], n.split(' ')[0].slice(0, 5)), 'ch' + i + '.nl',
            String((i % 5) + 1), i % 3 === 0 ? 7 : 0];
    });
    var films = ['Dune: Deel Twee', 'Oppenheimer', 'Soof 3', 'De Oost', 'Inception', 'Interstellar', 'The Batman', 'Top Gun: Maverick',
        'Alles is Familie', 'Flodder', 'Turks Fruit', 'Black Book', 'Avatar', 'Barbie', 'Wonka', 'Napoleon', 'Elf', 'Paw Patrol'];
    var vod = films.map(function (n, i) { return [n, 500 + i, svg(300, 450, colors[(i + 3) % 8], n.slice(0, 4)), String((i % 3) + 1), 'mp4', String(6 + (i % 4)) + '.4', 1700000000 + i * 1000]; });
    var ser = ['Wie is de Mol?', 'Penoza', 'Undercover', 'The Last of Us', 'Succession', 'Toon', 'Mocro Maffia', 'Stranger Things', 'Fargo', 'Dark'];
    var series = ser.map(function (n, i) { return [n, 900 + i, svg(300, 450, colors[(i + 5) % 8], n.slice(0, 4)), String((i % 2) + 1),
        'Een spannende serie over ' + n + '. Elke aflevering zit vol verrassingen.', '8.' + i, 1700000000 + i]; });

    function epgFor(sid) {
        var out = [], now = Date.now();
        var t = now - (now % 1800000) - 6 * 3600000 - (sid % 3) * 900000;
        var i = sid;
        while (t < now + 24 * 3600000) {
            var len = [30, 45, 60, 90, 25][i % 5] * 60000;
            out.push({ title: btoa(unescape(encodeURIComponent(shows[i % shows.length]))), description: btoa(unescape(encodeURIComponent('Beschrijving van ' + shows[i % shows.length] + '. Met interviews, reportages en meer.'))),
                start_timestamp: String(Math.floor(t / 1000)), stop_timestamp: String(Math.floor((t + len) / 1000)) });
            t += len; i++;
        }
        return out;
    }

    window.KTV_MOCK = function (method, p) {
        return new Promise(function (resolve, reject) {
            setTimeout(function () {
                if (method === 'xtreamList') {
                    if (p.kind === 'live') resolve({ cats: cats, items: live });
                    else if (p.kind === 'vod') resolve({ cats: [['1', 'Nieuw'], ['2', 'Nederlands'], ['3', 'Actie']], items: vod });
                    else resolve({ cats: [['1', 'Nederlands'], ['2', 'Internationaal']], items: series });
                    return;
                }
                if (method === 'fetch') {
                    var u = p.url;
                    if (u.indexOf('get_simple_data_table') >= 0) {
                        var sid = parseInt(u.split('stream_id=')[1], 10);
                        resolve({ status: 200, body: JSON.stringify({ epg_listings: epgFor(sid) }) });
                    } else if (u.indexOf('get_series_info') >= 0) {
                        var eps = {};
                        [1, 2].forEach(function (s) {
                            eps[s] = [];
                            for (var e = 1; e <= 8; e++) eps[s].push({ id: String(s * 100 + e), episode_num: e, season: s, title: 'Aflevering ' + e, container_extension: 'mp4', info: { plot: 'In deze aflevering gebeurt er van alles.', duration_secs: 2700 } });
                        });
                        resolve({ status: 200, body: JSON.stringify({ info: { plot: 'Volledige beschrijving van de serie.' }, episodes: eps }) });
                    } else if (u.indexOf('get_vod_info') >= 0) {
                        resolve({ status: 200, body: JSON.stringify({ info: { plot: 'Een meeslepende film vol actie en emotie. Gebaseerd op het bekende boek.', releasedate: '2024', genre: 'Drama', duration: '2u 26m' } }) });
                    } else if (u.indexOf('player_api.php') >= 0) {
                        if (u.indexOf('password=fout') >= 0) resolve({ status: 200, body: JSON.stringify({ user_info: { auth: 0 } }) });
                        else resolve({ status: 200, body: JSON.stringify({ user_info: { auth: 1, status: 'Active', exp_date: '1830000000', allowed_output_formats: ['m3u8', 'ts'], max_connections: '2' }, server_info: { timezone: 'Europe/Amsterdam' } }) });
                    } else reject(new Error('onbekend'));
                }
            }, 60);
        });
    };
})();
