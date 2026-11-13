// node test/roles.test.mjs [surveyDir]
// Runs assets/roles.js's classify() over clickables captured from real sites and checks known cases.
import fs from 'fs'; import vm from 'vm'; import path from 'path'; import { fileURLToPath } from 'url';
const here = path.dirname(fileURLToPath(import.meta.url));
const ctx = { window: {} }; vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(here, '../assets/roles.js'), 'utf8'), ctx);
const { classify } = ctx.window.__fpRoles;
const sig = (o, r) => ({ tag: o.tag || r.t, role: r.role || '', label: r.al || '', text: r.tx || '', icons: r.ic || '', cls: r.cl || '', w: r.w, h: r.h, small: r.w <= 72 && r.h <= 72, iconOnly: !r.tx, hasImg: !!r.img, inNav: r.y < 90 && r.h < 90, inRail: false });

let fails = 0;
const expect = (name, row, want) => { const got = classify(sig({}, row)); if (got !== want) { fails++; console.log(`FAIL ${name}: ${JSON.stringify(row.al || row.tx)} want ${want} got ${got}`); } };
const R = (t, al, tx, ic = '', cl = '', w = 100, h = 40, extra = {}) => ({ t, al, tx, ic, cl, w, h, y: 300, role: '', img: false, ...extra });
// Cases seen on real sites
expect('site A close icon', R('button', 'Close', '', 'lucide,lucide-x', 'absolute top-1.5', 28, 28), 'close');
expect('site A CLOSE text', R('button', 'CLOSE', 'CLOSE', '', 'mt-4 w-full', 300, 40), 'close');
expect('site B close', R('button', 'Close', '', '', 'tf-x', 32, 32), 'close');
expect('site B not now', R('button', '', 'Not now', '', 'tf-later', 120, 40), 'close');
expect('site C dismiss notice', R('button', 'Dismiss notice', '', 'icons,icon-close', 'site-notice__close', 24, 24), 'close');
expect('site D close banner', R('button', 'Close banner', '', '', 'absolute text-xl', 24, 24), 'close');
expect('icon-only x (side panel)', R('button', '', '', 'lucide-x', '', 28, 28), 'close');
expect('site F got it', R('button', '', 'Got it!', '', 'flex-1 rounded-lg', 200, 40), 'accept');
expect('site A play', R('button', 'Play', 'Play', 'lucide,lucide-play', 'flex items-center gap-2', 120, 48), 'play');
expect('site G play icon only', R('button', 'Play', '', 'lucide-icon,lucide,lucide-play', 'relative rounded-full', 56, 56), 'play');
expect('site B hero play', R('button', 'Play', 'Play', '', 'hero-play-btn flex', 120, 48), 'play');
expect('site E play link', R('a', 'Play', 'Play', '', 'group/play', 120, 48), 'play');
expect('overlay play link', R('a', 'Play Clayface', '', '', '', 300, 400), 'play');
expect('NOT watch parties', R('button', 'Watch Parties', '', 'lucide,lucide-users', 'text-white', 24, 24), 'menu');
expect('NOT playlists', R('a', 'Playlists', 'Playlists', '', 'unpressed', 80, 30), 'link');
expect('NOT watchlist', R('a', 'Watchlist', 'Watchlist', '', 'nav-item', 80, 30), 'link');
expect('NOT multi stream', R('a', 'Multi Stream', 'Multi Stream', '', 'hidden md:flex', 100, 30), 'link');
expect('search button', R('button', 'Search', '', 'lucide,lucide-search', 'text-white', 24, 24), 'search');
expect('search icon only', R('button', '', '', 'lucide-icon,lucide,lucide-search', 'relative flex', 24, 24), 'search');
expect('search input', R('input', 'Search anime', '', '', 'no-focus flex-1', 400, 40), 'field');
expect('account menu', R('button', 'Account', '', '', '', 40, 40), 'menu');
expect('carousel dot', R('button', 'Clayface', '', '', 'HomeBanner', 24, 24, { role: 'tab' }), 'dot');
expect('poster tile', R('a', '', 'Clayface 2026', '', 'w-[140px]', 180, 270, { img: true, y: 600 }), 'tile');
expect('player fullscreen', R('button', '', '', 'lucide-maximize', '', 36, 36), 'fullscreen');
expect('player subtitles', R('button', '', 'Subtitles', 'lucide-captions', '', 100, 40), 'subtitles');
expect('player servers', R('button', '', 'Servers', '', '', 100, 40), 'servers');
expect('player settings', R('button', '', 'Settings', '', '', 100, 40), 'settings');
expect('player episodes', R('button', '', 'Episodes', '', '', 100, 40), 'episodes');
expect('seek back', R('button', '', '', 'lucide-rotate-ccw', '', 48, 48), 'seek-back');
expect('seek fwd', R('button', 'Forward 10 seconds', '', '', '', 48, 48), 'seek-fwd');

expect('site H play/pause', R('button', 'Play/Pause', '', 'iconify,iconify--solar', '', 64, 64), 'playpause');
expect('site H back 10s', R('button', 'Back 10s', '', 'iconify,iconify--solar', '', 64, 64), 'seek-back');
expect('site H forward 10s', R('button', 'Forward 10s', '', 'iconify,iconify--solar', '', 64, 64), 'seek-fwd');
expect('site H mute', R('button', 'Mute', '', 'iconify,iconify--solar', '', 64, 64), 'mute');
expect('site H fullscreen', R('button', 'Fullscreen', '', 'iconify,iconify--solar', '', 64, 64), 'fullscreen');
expect('site H back', R('button', 'Back', '', 'iconify,iconify--solar', '', 52, 52), 'back');
expect('error page go back', R('button', '', 'Go Back', '', 'rounded-full', 121, 44), 'back');
expect('site I enter site', R('a', 'Enter Site', 'Enter Site', 'fas,fa-arrow-circle-right', '', 180, 50), 'enter');
expect('site J season/episode', R('button', '', 'S 1 E 1', '', '', 37, 141), 'episodes');
expect('site K episodes', R('button', '', 'Episodes', '', '', 100, 40), 'episodes');

// Role histogram per site
const dir = process.argv[2] || new URL('./fixtures/pages', import.meta.url).pathname;   // a folder of saved site pages
if (fs.existsSync(dir)) for (const f of fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort()) {
  let d; try { d = JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')); } catch { continue; }
  const h = {}; for (const r of d.rows) { const k = classify(sig({}, r)); h[k] = (h[k] || 0) + 1; }
  console.log(f.replace('.json', '').padEnd(18), Object.entries(h).sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}:${v}`).join(' '));
}
console.log(fails ? `\n${fails} FAILED` : '\nall role expectations pass');
process.exit(fails ? 1 : 0);
