import test from 'node:test';
import assert from 'node:assert/strict';
import { allowedUrl, authorized, sameResource } from './policy.mjs';

test('allow only intended public HLTV resources', () => {
  for (const path of ['/matches', '/results?startDate=2026-10-17&endDate=2026-10-19', '/matches/2399415/a-vs-b', '/player/3741/niko', '/ranking/teams']) assert.equal(allowedUrl(`https://www.hltv.org${path}`), true);
  for (const url of ['http://www.hltv.org/matches','https://www.hltv.org.evil.test/matches','https://user@www.hltv.org/matches','https://www.hltv.org:444/matches','https://www.hltv.org/admin','https://127.0.0.1/matches','https://www.hltv.org/results?redirect=http://127.0.0.1','https://www.hltv.org/matches#x']) assert.equal(allowedUrl(url), false, url);
});
test('require exact bearer token', () => {
  assert.equal(authorized('Bearer secret', 'secret'), true);
  for (const input of [undefined, '', 'secret', 'Bearer other', 'Bearer secret ']) assert.equal(authorized(input, 'secret'), false);
});
test('canonical slugs may change but resource identity and date filters cannot', () => {
  assert.equal(sameResource('https://www.hltv.org/matches/1/-', 'https://www.hltv.org/matches/1/a-vs-b'), true);
  assert.equal(sameResource('https://www.hltv.org/matches/1/-', 'https://www.hltv.org/matches/2/a-vs-b'), false);
  assert.equal(sameResource('https://www.hltv.org/results?startDate=2026-10-17', 'https://www.hltv.org/results'), false);
});
