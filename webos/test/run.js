const { chromium } = require('/opt/npm-tools/node_modules/playwright');
const path = require('path');
const out = process.argv[2] || '/tmp/claude-0/shots';
require('fs').mkdirSync(out, { recursive: true });
(async () => {
  const browser = await chromium.launch({ executablePath: require('fs').readdirSync('/opt/pw-browsers').filter(d=>d.startsWith('chromium-'))[0] ? '/opt/pw-browsers/chromium-1243/chrome-linux64/chrome' : undefined, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 } });
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR ' + e.message + '\n' + (e.stack||'').split('\n').slice(0,3).join('\n')));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text()); });
  await page.goto('file://' + path.resolve(__dirname, 'test.html'));
  const key = async (k, n = 1, wait = 120) => { for (let i = 0; i < n; i++) { await page.keyboard.press(k); await page.waitForTimeout(wait); } };
  const shot = async (name) => { await page.waitForTimeout(500); await page.screenshot({ path: `${out}/${name}.png` }); };
  const steps = JSON.parse(process.argv[3] || '[]');
  for (const s of steps) {
    if (s.shot) await shot(s.shot);
    else if (s.key) await key(s.key, s.n || 1, s.wait || 120);
    else if (s.type) { await page.keyboard.type(s.type); }
    else if (s.wait) await page.waitForTimeout(s.wait);
    else if (s.eval) { const r = await page.evaluate(s.eval); if (r !== undefined) console.log('EVAL', JSON.stringify(r)); }
    else if (s.down) { await page.keyboard.down(s.down); await page.waitForTimeout(s.hold || 900); await page.keyboard.up(s.down); }
  }
  console.log(errors.length ? errors.join('\n') : 'geen fouten');
  await browser.close();
})();
