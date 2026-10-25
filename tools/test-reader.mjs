import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import assert from 'node:assert/strict';

const script = readFileSync(new URL('../app/src/main/assets/hltv-reader/reader.js', import.meta.url), 'utf8');
function capture(title, body, challenge = false) {
  let callback, result;
  const window = {}; window.top = window;
  runInNewContext(script, {
    window, location: {href: 'https://www.hltv.org/events/8932/test'},
    document: {title, readyState: 'complete', body: {innerText: body}, querySelector: () => challenge ? {} : null,
      documentElement: {cloneNode: () => ({querySelectorAll: () => [], outerHTML: '<html>public event</html>'})}},
    browser: {runtime: {connectNative: () => ({onMessage: {addListener: f => callback = f}, postMessage: value => result = value})}},
  });
  callback({type: 'capture', requestId: 9});
  return result;
}
assert.equal(capture('Attention Required! | Cloudflare', 'Sorry, you have been blocked').state, 'blocked');
assert.equal(capture('Just a moment...', 'Verify you are human', true).state, 'challenge');
assert.equal(capture('Event overview | HLTV.org', 'Event data').state, 'document');
assert.equal(capture('Event overview | HLTV.org', 'Event data').requestId, 9);
console.log('Reader regression: 4 assertions passed');

async function captureJson(url, options = {}) {
  let callback, result, calls = 0;
  const window = {}; window.top = window;
  const data = new TextEncoder().encode(options.body ?? '{"matchData":{"match":{"matchId":2399415}}}');
  runInNewContext(script, {
    window, URL, AbortController, TextDecoder, setTimeout, clearTimeout,
    location: {href: 'https://www.hltv.org/matches/2399415/-', origin: 'https://www.hltv.org'},
    document: {title: 'Match | HLTV.org', readyState: 'complete', body: {innerText: 'Match'}, querySelector: () => null},
    fetch: async (target, init) => {
      calls++;
      assert.equal(init.credentials, 'same-origin');
      assert.equal(init.redirect, 'error');
      assert.equal(init.headers.Accept, 'application/json');
      if (options.fail) throw new Error('network');
      let read = false;
      return {url: target, status: options.status ?? 200, headers: {get: key => key === 'content-type' ? 'application/json' : null},
        body: {getReader: () => ({read: async () => read ? {done: true} : (read = true, {done: false, value: data}), cancel: async () => {}})}};
    },
    browser: {runtime: {connectNative: () => ({onMessage: {addListener: f => callback = f}, postMessage: value => result = value})}},
  });
  const message = {type: 'capture', requestId: 10, jsonUrl: url};
  await callback(message); await callback(message);
  return {result, calls};
}
const endpoint = 'https://www.hltv.org/mobile/MatchScreen?matchId=2399415';
const success = await captureJson(endpoint);
assert.equal(success.result.state, 'json-response');
assert.equal(success.calls, 1);
assert.equal(success.result.status, 200);
assert.equal(JSON.parse(success.result.body).matchData.match.matchId, 2399415);
for (const url of ['invalid', 'https://example.com/mobile/MatchesV4', 'https://www.hltv.org/mobile/integrity/session',
  'https://www.hltv.org/mobile/MatchScreen?matchId=0', endpoint + '&matchId=1', endpoint + '#fragment',
  'https://www.hltv.org/mobile/MatchesV4?unexpected=1']) {
  const refused = await captureJson(url);
  assert.equal(refused.calls, 0);
}
assert.equal((await captureJson(endpoint, {status: 401})).result.status, 401);
assert.equal((await captureJson(endpoint, {fail: true})).result.state, 'json-network-error');
assert.equal((await captureJson(endpoint, {body: '汉'.repeat(1500000)})).result.state, 'oversize');
console.log('JSON reader: transport, single fetch, route validation, errors and byte limit passed');

// HLTV currently serves RSS as text/plain, which Gecko displays inside a <pre>.
function captureRss(xml) {
  let callback, result, calls = 0;
  const window = {}; window.top = window;
  runInNewContext(script, {
    window, URL,
    location: {href: 'https://www.hltv.org/rss/news', origin: 'https://www.hltv.org'},
    document: {title: '', readyState: 'complete', body: {innerText: xml},
      querySelector: q => q === 'body > pre' ? {textContent: xml} : null},
    fetch: () => { calls++; throw new Error('already rendered RSS must not be downloaded twice'); },
    browser: {runtime: {connectNative: () => ({onMessage: {addListener: f => callback = f}, postMessage: value => result = value})}},
  });
  callback({type: 'capture', requestId: 11, rssUrl: 'https://www.hltv.org/rss/news'});
  return {result, calls};
}
const rss = '<?xml version="1.0"?><rss><channel><item><title>A &amp; B</title></item></channel></rss>';
assert.equal(captureRss(rss).result.html, rss);
assert.equal(captureRss(rss).result.state, 'document');
assert.equal(captureRss(rss).calls, 0);
assert.equal(captureRss('<rss>' + 'x'.repeat(4 * 1024 * 1024)).result.state, 'oversize');
console.log('RSS reader: original XML, no duplicate fetch and size limit passed');
