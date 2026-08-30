// klag.dev Worker: static assets plus a small read-only MCP server for the docs.
//
// This file is transport only — JSON-RPC framing, asset fall-through, and the headers
// agents look for. The tools themselves live in ./mcp-docs.mjs so they can be tested
// without a Worker runtime.
//
// The MCP server here answers questions *about* Klag (docs, config keys, metrics). It is
// not the MCP endpoint of a running Klag instance, which answers questions about that
// user's Kafka consumer groups.

import {
  SITE,
  TOOLS,
  RESOURCES,
  callTool,
  prefersMarkdown,
} from './mcp-docs.mjs';

interface Env {
  ASSETS: Fetcher;
}

const PROTOCOL_VERSION = '2025-11-25';
const JSON_TYPE = 'application/json';

// JSON-RPC 2.0 error codes, matching Klag's own McpProtocol.
const PARSE_ERROR = -32700;
const INVALID_REQUEST = -32600;
const METHOD_NOT_FOUND = -32601;
const INVALID_PARAMS = -32602;
const INTERNAL_ERROR = -32603;

async function readResource(uri: string, env: Env) {
  const known = RESOURCES.find((resource) => resource.uri === uri);
  if (!known) return null;
  const response = await env.ASSETS.fetch(new Request(uri));
  if (!response.ok) return null;
  return { uri, mimeType: known.mimeType, text: await response.text() };
}

function rpcResult(id: unknown, result: unknown) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id: unknown, code: number, message: string) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': JSON_TYPE, 'cache-control': 'no-store' },
  });
}

async function handleMcp(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'POST') {
    return new Response(
      JSON.stringify({ error: 'Method Not Allowed; use POST for JSON-RPC' }),
      { status: 405, headers: { 'content-type': JSON_TYPE, allow: 'POST' } },
    );
  }

  let body: any;
  try {
    body = await request.json();
  } catch {
    return json(rpcError(null, PARSE_ERROR, 'Parse error: body is not valid JSON'));
  }
  if (!body || typeof body !== 'object' || Array.isArray(body) || body.jsonrpc !== '2.0') {
    return json(rpcError(body?.id ?? null, INVALID_REQUEST, 'Invalid JSON-RPC 2.0 request'));
  }

  const { id, method, params } = body;
  // JSON-RPC notifications (no id) take no response body at all -- not a result and not
  // an error. Clients send notifications/cancelled as well as notifications/initialized,
  // and answering either with a body is a protocol violation.
  if (!('id' in body)) return new Response(null, { status: 202 });

  // env.ASSETS.fetch can reject transiently. Without this, the rejection escapes the
  // Worker as a 500 and the client sees no JSON-RPC error at all.
  try {
    return await dispatchMcp(id, method, params, env);
  } catch (err) {
    console.error('MCP dispatch failed', method, err);
    return json(rpcError(id, INTERNAL_ERROR, 'Internal error'));
  }
}

async function dispatchMcp(
  id: unknown,
  method: string,
  params: any,
  env: Env,
): Promise<Response> {
  switch (method) {
    case 'initialize':
      return json(rpcResult(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {}, resources: {} },
        serverInfo: { name: 'klag-docs', title: 'Klag documentation', version: '1.0.0' },
        instructions:
          'Read-only documentation for Klag, the Kafka consumer lag exporter. Search or read ' +
          'klag.dev pages, look up configuration keys and exported metrics. This server does ' +
          'not read any Kafka cluster — a self-hosted Klag instance exposes its own /mcp for that.',
      }));

    case 'ping':
      return json(rpcResult(id, {}));

    case 'notifications/initialized':
      return json(rpcResult(id, {}));

    case 'tools/list':
      return json(rpcResult(id, { tools: TOOLS }));

    case 'tools/call': {
      const name = params?.name;
      if (typeof name !== 'string') {
        return json(rpcError(id, INVALID_PARAMS, 'params.name is required'));
      }
      const outcome = callTool(name, params?.arguments ?? {});
      if ('error' in outcome) {
        // Tool-level failure: an MCP result with isError, not a JSON-RPC error.
        return json(rpcResult(id, {
          content: [{ type: 'text', text: outcome.error }],
          isError: true,
        }));
      }
      return json(rpcResult(id, outcome));
    }

    case 'resources/list':
      return json(rpcResult(id, { resources: RESOURCES }));

    case 'resources/read': {
      const uri = params?.uri;
      if (typeof uri !== 'string') {
        return json(rpcError(id, INVALID_PARAMS, 'params.uri is required'));
      }
      const contents = await readResource(uri, env);
      if (!contents) {
        return json(rpcError(id, INVALID_PARAMS, `Unknown resource: ${uri}`));
      }
      return json(rpcResult(id, { contents: [contents] }));
    }

    default:
      return json(rpcError(id ?? null, METHOD_NOT_FOUND, `Unknown method: ${method}`));
  }
}

// Agents that land on a page from web search, before reading llms.txt.
const DISCOVERY_LINKS = [
  `<${SITE}/llms.txt>; rel="alternate"; type="text/markdown"; title="llms.txt"`,
  `<${SITE}/.well-known/ai-catalog.json>; rel="service-desc"; type="application/json"`,
  `<${SITE}/openapi.json>; rel="service-desc"; type="application/json"`,
  `<${SITE}/mcp>; rel="related"; title="Klag docs MCP server"`,
].join(', ');

function withHeaders(
  response: Response,
  contentType: string | null,
  extra: Record<string, string> = {},
) {
  const headers = new Headers(response.headers);
  if (contentType) headers.set('content-type', contentType);
  for (const [key, value] of Object.entries(extra)) headers.set(key, value);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    if (path === '/mcp' || path === '/mcp/') return handleMcp(request, env);

    // The scanner-standard sitemap path; @astrojs/sitemap emits an index plus sitemap-0.
    if (path === '/sitemap.xml') {
      return env.ASSETS.fetch(new Request(new URL('/sitemap-0.xml', url), request));
    }

    // Cold-discovery path: an agent asking for markdown gets the .md twin of the page.
    if (request.method === 'GET' && !path.endsWith('.md')
        && prefersMarkdown(request.headers.get('accept'))) {
      const twin = path === '/' ? '/index.md' : `${path.replace(/\/$/, '')}.md`;
      const markdown = await env.ASSETS.fetch(new Request(new URL(twin, url), request));
      if (markdown.ok) return withHeaders(markdown, 'text/markdown; charset=utf-8');
    }

    const response = await env.ASSETS.fetch(request);

    // The 404 page is a signpost for agents, not content: it carries the site-wide
    // "index, follow" meta tag, so keep it out of indexes at the HTTP layer instead.
    if (response.status === 404) {
      return withHeaders(response, null, { 'x-robots-tag': 'noindex' });
    }

    if (path.endsWith('.md') || path.endsWith('/llms.txt') || path.endsWith('/llms-full.txt')) {
      return withHeaders(response, 'text/markdown; charset=utf-8');
    }
    if (path === '/.well-known/api-catalog') {
      return withHeaders(
        response,
        'application/linkset+json;profile="https://www.rfc-editor.org/info/rfc9727"',
      );
    }
    if ((response.headers.get('content-type') ?? '').includes('text/html')) {
      return withHeaders(response, null, {
        link: DISCOVERY_LINKS,
        // Content Signals: klag.dev is open documentation — search, AI input, and
        // training are all welcome. Mirrors public/robots.txt.
        'content-signal': 'search=yes, ai-input=yes, ai-train=yes',
      });
    }
    return response;
  },
};
