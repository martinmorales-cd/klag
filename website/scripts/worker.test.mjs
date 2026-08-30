// Covers the JSON-RPC framing in src/worker.ts that sits above the tools in mcp-docs.mjs:
// notification handling and dispatch failures. The tools themselves are covered by
// mcp-docs.test.mjs.
//
// Imports the .ts entry directly — Node 22 strips the types, so no build step here.

import assert from 'node:assert/strict';
import { test } from 'node:test';

import worker from '../src/worker.ts';

// Stands in for the Worker static-asset binding. `fetch` is whatever the test needs.
const envWith = (fetch) => ({ ASSETS: { fetch } });

const post = (body, env = envWith(async () => new Response('', { status: 404 }))) =>
  worker.fetch(
    new Request('https://klag.dev/mcp', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    }),
    env,
  );

test('notifications get no response body, whatever the method', async () => {
  // A JSON-RPC notification is any payload without an id. Clients send
  // notifications/cancelled as well as notifications/initialized, and answering
  // either with a result or an error is a protocol violation.
  for (const method of ['notifications/initialized', 'notifications/cancelled', 'tools/list']) {
    const response = await post({ jsonrpc: '2.0', method });
    assert.equal(response.status, 202, `${method} should be accepted with no body`);
    assert.equal(await response.text(), '', `${method} should return an empty body`);
  }
});

test('a request carrying an id still gets a response', async () => {
  const response = await post({ jsonrpc: '2.0', id: 1, method: 'tools/list' });
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.id, 1);
  assert.ok(Array.isArray(body.result.tools));
});

test('an asset-fetch failure becomes a JSON-RPC error, not a Worker 500', async () => {
  const env = envWith(async () => {
    throw new Error('asset service unavailable');
  });
  const response = await post(
    { jsonrpc: '2.0', id: 7, method: 'resources/read', params: { uri: 'https://klag.dev/llms.txt' } },
    env,
  );
  const body = await response.json();
  assert.equal(response.status, 200);
  assert.equal(body.id, 7);
  assert.equal(body.error.code, -32603);
  // The upstream message stays in the Worker log, not in the client-visible envelope.
  assert.equal(body.error.message, 'Internal error');
});

// A stub HTML page, so the Link header logic runs the way it does for a real docs page.
const htmlEnv = () =>
  envWith(async () => new Response('<html></html>', {
    status: 200,
    headers: { 'content-type': 'text/html; charset=utf-8' },
  }));

const linkHeaderFor = async (path) => {
  const response = await worker.fetch(
    new Request(`https://klag.dev${path}`, { headers: { accept: 'text/html' } }),
    htmlEnv(),
  );
  return response.headers.get('link') ?? '';
};

test('rel="alternate" names the page\'s own markdown twin, not the corpus index', async () => {
  const link = await linkHeaderFor('/ai/mcp/');
  assert.match(link, /<https:\/\/klag\.dev\/ai\/mcp\.md>; rel="alternate"/);
  // llms.txt is the whole-corpus index; calling it an alternate of this page is the bug.
  assert.doesNotMatch(link, /llms\.txt>; rel="alternate"/);
  assert.match(link, /<https:\/\/klag\.dev\/llms\.txt>; rel="index"/);
});

test('the homepage twin is /index.md', async () => {
  assert.match(await linkHeaderFor('/'), /<https:\/\/klag\.dev\/index\.md>; rel="alternate"/);
});

test('a path outside the corpus advertises no alternate', async () => {
  // Nothing generated a twin for it, so pointing at one would be a dead link.
  const link = await linkHeaderFor('/not-a-real-page/');
  assert.doesNotMatch(link, /rel="alternate"/);
  assert.match(link, /rel="service-desc"/);
});
