const http = require('http'), zlib = require('zlib');
const svc = require('../service/service.js');
const srv = http.createServer((req, res) => {
  const u = req.url;
  if (u.startsWith('/redirect')) { res.writeHead(302, { Location: '/player_api.php?username=a&password=b&action=get_live_streams' }); return res.end(); }
  let body;
  if (u.includes('get_live_categories')) body = [{ category_id: '1', category_name: 'NL' }];
  else if (u.includes('get_live_streams')) body = [{ name: 'NPO 1', stream_id: 1, stream_icon: 'http://x/l.png', epg_channel_id: 'npo1', category_id: '1', tv_archive: 1, tv_archive_duration: 7 }, { name: 'Geen id' }];
  else if (u.includes('get_vod_categories')) body = [];
  else if (u.includes('get_vod_streams')) body = [{ name: 'Film', stream_id: '5', container_extension: 'mkv', added: '1700000000', rating: null }];
  else if (u.includes('player_api.php')) body = { user_info: { auth: 1 } };
  const data = zlib.gzipSync(JSON.stringify(body));
  res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Encoding': 'gzip' });
  res.end(data);
}).listen(0, async () => {
  const p = { server: 'http://127.0.0.1:' + srv.address().port, user: 'a', pass: 'b' };
  try {
    console.log('fetch:', JSON.stringify(await svc.run('fetch', { url: p.server + '/player_api.php?username=a&password=b' })));
    console.log('redirect:', (await svc.run('fetch', { url: p.server + '/redirect' })).body.slice(0, 40));
    console.log('live:', JSON.stringify(await svc.run('xtreamList', Object.assign({ kind: 'live' }, p))));
    console.log('vod:', JSON.stringify(await svc.run('xtreamList', Object.assign({ kind: 'vod' }, p))));
    try { await svc.run('fetch', { url: 'http://127.0.0.1:1/x', timeout: 3000 }); } catch (e) { console.log('fout netjes:', e.message); }
  } catch (e) { console.log('MISLUKT', e); }
  srv.close();
});
