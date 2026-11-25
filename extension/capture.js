// Capture uses only browser-owned active-tab metadata.
const safeQuery = new Set(['q', 'query', 'search', 'page', 'v', 'list', 't', 'index', 'lang', 'sort', 'tab', 'view']);
const secret = (s) => s.includes('eyJ') || s.split(/[^a-zA-Z0-9_-]/).some((p) => p.length >= 40 && !p.includes('-') && (/[A-Z]/.test(p) || /^[a-fA-F0-9]+$/.test(p)));
export function sanitizeUrl(raw) {
  if (typeof raw !== 'string' || raw.length > 8192) return null;
  try {
    const u = new URL(raw);
    if (!['http:', 'https:'].includes(u.protocol)) return null;
    u.username = ''; u.password = '';
    const pairs = [...u.searchParams].map(([k, v]) => [k.slice(0, 80), safeQuery.has(k.toLowerCase()) && v.length <= 256 && !secret(v) ? v : 'REDACTED']);
    u.pathname = u.pathname.split('/').map((p) => secret(p) ? 'REDACTED' : p).join('/');
    u.search = '';
    for (const [k, v] of pairs) u.searchParams.append(k, v);
    const f = u.hash.slice(1);
    if (!(f.startsWith('/') && !f.includes('?') && !f.includes('=') && !secret(f))) u.hash = '';
    return u.href;
  } catch { return null; }
}
const cleanTitle = (title) => Array.from(title.replace(/https?:\/\/[^\s<>"']+/gi, (raw) => sanitizeUrl(raw) || '').replace(/[\x00-\x1f\x7f]/g, '')).slice(0, 512).join('');
const withoutScheme = (url) => url.replace(/^https?:\/\//i, '');
function isUrlTitle(title, url) {
  // Inspect only the authority. Chromium can decode or truncate the rest of its URL title.
  const display = withoutScheme(title.trim());
  const authority = display.split(/[/?#\s]/, 1)[0];
  if (!authority || /\s/.test(display[authority.length] ?? '')) return false;
  try {
    const host = (value) => new URL(value).host.replace(/^www\./, '');
    return host(new URL(url).protocol + '//' + authority) === host(url);
  } catch {
    return false;
  }
}
export function observation(win, idle, enabled) {
  const focused = Boolean(win?.focused && win.type !== 'devtools' && !win.incognito);
  const tab = focused ? win.tabs?.find((t) => t.active && !t.incognito) : null;
  const url = enabled && tab ? sanitizeUrl(tab.url) : null;
  let title = null;
  if (url && tab.status !== 'loading' && typeof tab.title === 'string') {
    // Chromium may elide the scheme and www, and render escaped spaces literally.
    title = cleanTitle(isUrlTitle(tab.title, url) ? withoutScheme(url) : tab.title);
  }
  return {
    focused, idle, enabled,
    tab_id: enabled && tab ? tab.id : null,
    window_id: enabled && focused ? win.id : null,
    url,
    title,
    audible: Boolean(url && tab.audible),
    visibility: !enabled || !tab ? 'none' : url ? 'page' : 'restricted',
  };
}
