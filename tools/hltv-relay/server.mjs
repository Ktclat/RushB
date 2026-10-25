import { createServer } from 'node:http';
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { allowedUrl, authorized, sameResource } from './policy.mjs';

const root = dirname(fileURLToPath(import.meta.url));
const data = resolve(root, '../../.local/relay');
await mkdir(data, { recursive: true });
const tokenPath = resolve(data, 'token.txt');
let token;
try { token = (await readFile(tokenPath, 'utf8')).trim(); }
catch { token = randomBytes(32).toString('hex'); await writeFile(tokenPath, token, { mode: 0o600 }); }
if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Invalid relay token file');

const profile = process.env.MATCHSCOPE_BROWSER_PROFILE || resolve(data, 'browser-profile');
const port = Number(process.env.MATCHSCOPE_RELAY_PORT || 8765);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid port');
let context, page, active = false, lastStarted = 0, cooldownUntil = 0;
const cache = new Map();

async function browserPage() {
  if (context) return page;
  context = await chromium.launchPersistentContext(profile, {
    channel: process.env.MATCHSCOPE_BROWSER_CHANNEL || 'msedge',
    headless: false,
    viewport: { width: 1200, height: 850 },
  });
  page = context.pages()[0] || await context.newPage();
  page.setDefaultNavigationTimeout(30_000);
  context.on('close', () => { context = undefined; page = undefined; });
  return page;
}

async function load(url) {
  const existing = cache.get(url);
  if (existing && Date.now() - Date.parse(existing.capturedAt) < 120_000) return { ...existing, cached: true };
  if (Date.now() < cooldownUntil) throw Object.assign(new Error('HLTV 访问冷却中，请稍后再试。'), { code: 'cooldown' });
  const remaining = 3_000 - (Date.now() - lastStarted);
  if (remaining > 0) await new Promise(r => setTimeout(r, remaining));
  lastStarted = Date.now();
  const tab = await browserPage();
  const response = await tab.goto(url, { waitUntil: 'domcontentloaded' });
  if (response?.status() === 429) {
    cooldownUntil = Date.now() + 300_000;
    throw Object.assign(new Error('HLTV 请求限速，已暂停 5 分钟。'), { code: 'rate_limited' });
  }
  if (response && response.status() >= 400 && response.status() !== 403)
    throw Object.assign(new Error(`HLTV 返回 HTTP ${response.status()}。`), { code: 'upstream_http' });
  // Let the normal browser complete its load. Interactive verification remains human-controlled.
  try {
    await tab.waitForFunction(() => {
      const title = document.title;
      return /HLTV\.org/i.test(title) && !document.querySelector('#challenge-running, #cf-challenge-running, #challenge-stage');
    }, undefined, { timeout: 30_000 });
  } catch {
    cooldownUntil = Date.now() + 30_000;
    throw Object.assign(new Error('请在电脑端 HLTV 窗口完成浏览器验证，再回到应用重试。'), { code: 'verification_required' });
  }
  if (!sameResource(url, tab.url())) throw Object.assign(new Error('HLTV 跳转到了其他页面，未导入数据。'), { code: 'unexpected_page' });
  const html = await tab.evaluate(() => {
    const root = document.documentElement.cloneNode(true);
    root.querySelectorAll('script,style,iframe,form,input,textarea,noscript').forEach(n => n.remove());
    for (const element of root.querySelectorAll('*')) {
      for (const name of element.getAttributeNames()) if (/^on/i.test(name)) element.removeAttribute(name);
    }
    return root.outerHTML;
  });
  if (Buffer.byteLength(html, 'utf8') > 8 * 1024 * 1024) throw Object.assign(new Error('来源页面过大。'), { code: 'too_large' });
  const result = { version: 1, url, resolvedUrl: tab.url(), capturedAt: new Date().toISOString(), cached: false, html };
  cache.set(url, result);
  if (cache.size > 40) cache.delete(cache.keys().next().value);
  console.log(`[${result.capturedAt}] Read ${new URL(url).pathname} (${Buffer.byteLength(html)} bytes)`);
  return result;
}

const server = createServer(async (req, res) => {
  const send = (status, body) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' }); res.end(JSON.stringify(body)); };
  // No LAN binding and no CORS: a website must never borrow the user's browser session.
  if (req.headers.origin || req.headers['sec-fetch-site'] === 'cross-site') return send(403, { code: 'origin_denied' });
  if (!['127.0.0.1', 'localhost'].some(host => req.headers.host === `${host}:${port}`)) return send(403, { code: 'host_denied' });
  if (!authorized(req.headers.authorization, token)) return send(401, { code: 'unauthorized', message: '中继令牌不正确。' });
  if (req.method !== 'GET') return send(405, { code: 'method_not_allowed' });
  const endpoint = new URL(req.url, `http://127.0.0.1:${port}`);
  if (endpoint.pathname === '/health') return send(200, { version: 1, status: 'ready', browser: !!context });
  const target = endpoint.searchParams.get('url');
  if (endpoint.pathname !== '/page' || !allowedUrl(target)) return send(400, { code: 'invalid_source', message: '只支持指定的 HLTV 公共资料页面。' });
  if (active) return send(429, { code: 'busy', message: '中继正在读取其他页面，请稍后重试。' });
  active = true;
  try { send(200, await load(target)); }
  catch (error) {
    console.error(error.code || 'browser_error', error.message);
    send(503, { code: error.code || 'browser_error', message: error.code ? error.message : '浏览器读取失败，请检查电脑端窗口和网络。' });
  }
  finally { active = false; }
});
server.requestTimeout = 70_000;
server.listen(port, '127.0.0.1', () => {
  console.log(`RushB relay: http://127.0.0.1:${port}`);
  console.log(`Token saved in ${tokenPath} (never include this file in Git).`);
  console.log('Android USB: adb reverse tcp:8765 tcp:8765');
});
async function stop() { server.close(); await context?.close(); process.exit(0); }
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
