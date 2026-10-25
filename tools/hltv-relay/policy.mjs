import { timingSafeEqual } from 'node:crypto';

export function allowedUrl(value) {
  try {
    const u = new URL(value);
    if (u.protocol !== 'https:' || !['www.hltv.org', 'hltv.org'].includes(u.hostname) || u.port || u.username || u.password || u.hash) return false;
    if (!/^\/(matches|results|ranking\/teams|rss\/news)\/?$/.test(u.pathname) &&
        !/^\/(matches|player|team|events)\/\d+\/[a-zA-Z0-9-]+$/.test(u.pathname)) return false;
    if (u.search && u.pathname !== '/results') return false;
    for (const [key, value] of u.searchParams) {
      if (!['startDate', 'endDate'].includes(key) || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    }
    return true;
  } catch { return false; }
}

export function authorized(header, token) {
  const expected = Buffer.from(`Bearer ${token}`);
  const actual = Buffer.from(header || '');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function sameResource(expected, actual) {
  try {
    const a = new URL(expected), b = new URL(actual);
    if (b.protocol !== 'https:' || !['www.hltv.org', 'hltv.org'].includes(b.hostname)) return false;
    const detail = /^\/(matches|player|team|events)\/(\d+)\//;
    const x = a.pathname.match(detail), y = b.pathname.match(detail);
    if (x) return !!y && x[1] === y[1] && x[2] === y[2];
    if (a.pathname === '/ranking/teams') return /^\/ranking\/teams(?:\/|$)/.test(b.pathname);
    return a.pathname === b.pathname && [...a.searchParams].every(([k,v]) => b.searchParams.get(k) === v);
  } catch { return false; }
}
