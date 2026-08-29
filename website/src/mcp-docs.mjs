// Tool implementations for the klag.dev documentation MCP server.
//
// Kept out of src/worker.ts (which is JSON-RPC and HTTP glue) so the branching parts —
// search scoring, path resolution, config/metric lookup, content negotiation — are plain
// functions that `node --test` can exercise without a Worker runtime. See mcp-docs.test.mjs.
//
// This server answers questions *about* Klag. A running Klag instance exposes its own /mcp
// that answers questions about the user's Kafka consumer groups; keep the two distinct in
// every tool description, because an agent that conflates them will query the wrong thing.

import corpus from './generated/docs.json' with { type: 'json' };

export const SITE = 'https://klag.dev';

export const pages = corpus.pages;
export const configKeys = corpus.config;
export const metrics = corpus.metrics;

export const TOOLS = [
  {
    name: 'search_klag_docs',
    title: 'Search the Klag documentation',
    description:
      'Full-text search across klag.dev, the documentation for Klag (the Kafka consumer ' +
      'lag exporter). Use it to answer questions about how Klag works, how to deploy it, ' +
      'what a metric means, or how it compares to Burrow, KMinion, AKHQ, Redpanda Console, ' +
      'Cruise Control, Grafana, or Confluent Control Center. Returns ranked page excerpts ' +
      'with their URLs. This searches documentation only; it does not read any Kafka cluster.',
    inputSchema: {
      type: 'object',
      properties: {
        query: {
          type: 'string',
          description: 'Search terms, e.g. "lag velocity", "helm values", "ACL permissions".',
        },
        limit: {
          type: 'integer',
          description: 'Maximum number of pages to return (1-20).',
          default: 5,
          minimum: 1,
          maximum: 20,
        },
      },
      required: ['query'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
  },
  {
    name: 'get_klag_doc',
    title: 'Read one Klag documentation page',
    description:
      'Return the full markdown of a single klag.dev page by its URL path, e.g. ' +
      '"/metrics/lag-velocity/" or "/deployment/kubernetes/". Use after search_klag_docs ' +
      'when an excerpt is not enough. Call with no path to list every available page.',
    inputSchema: {
      type: 'object',
      properties: {
        path: {
          type: 'string',
          description: 'Page path such as "/getting-started/quick-start/". Omit to list all pages.',
        },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
  },
  {
    name: 'get_klag_config',
    title: 'Look up Klag configuration',
    description:
      'Look up Klag environment variables: name, default value, and what they do ' +
      '(KAFKA_BOOTSTRAP_SERVERS, METRICS_REPORTER, MCP_ENABLED, HOT_PARTITION_*, ...). ' +
      'Use when configuring or deploying Klag. Call with no name to list every key.',
    inputSchema: {
      type: 'object',
      properties: {
        name: {
          type: 'string',
          description: 'Env var name or prefix, e.g. "METRICS_" or "KAFKA_CHUNK_COUNT".',
        },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
  },
  {
    name: 'get_klag_metric',
    title: 'Look up a Klag metric',
    description:
      'Look up the metrics Klag exports (klag.consumer.lag, klag.consumer.lag.velocity, ' +
      'klag.consumer.commit.staleness_seconds, klag.partition.under_replicated, ...), with ' +
      'their meaning and tags. Use when writing PromQL, dashboards, or alert rules against ' +
      'Klag. Call with no name to list every metric.',
    inputSchema: {
      type: 'object',
      properties: {
        name: {
          type: 'string',
          description: 'Metric name or fragment, e.g. "lag.velocity" or "klag_consumer_lag".',
        },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
  },
];

export const RESOURCES = [
  {
    uri: `${SITE}/llms.txt`,
    name: 'klag-llms-index',
    title: 'Klag docs index (llms.txt)',
    description: 'Project summary, quick start, key metrics, and a linked index of every page.',
    mimeType: 'text/markdown',
  },
  {
    uri: `${SITE}/llms-full.txt`,
    name: 'klag-llms-full',
    title: 'Klag documentation, concatenated (llms-full.txt)',
    description: 'The full text of every klag.dev page in one document.',
    mimeType: 'text/markdown',
  },
];

// ponytail: substring scoring over ~40 pages, built at deploy time. Swap for a real
// index only if the corpus outgrows one Worker bundle.
export function searchPages(query, limit) {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!terms.length) return [];
  return pages
    .map((page) => {
      const title = page.title.toLowerCase();
      const description = page.description.toLowerCase();
      const body = page.body.toLowerCase();
      let score = 0;
      for (const term of terms) {
        if (title.includes(term)) score += 10;
        if (description.includes(term)) score += 4;
        const hits = body.split(term).length - 1;
        score += Math.min(hits, 10);
      }
      return { page, score };
    })
    .filter((hit) => hit.score > 0)
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map(({ page, score }) => ({ page, score, excerpt: excerptFor(page, terms) }));
}

function excerptFor(page, terms) {
  const body = page.body;
  const at = body.toLowerCase().indexOf(terms[0]);
  if (at < 0) return body.slice(0, 320);
  const start = Math.max(0, at - 160);
  return `${start > 0 ? '…' : ''}${body.slice(start, start + 480)}…`;
}

/**
 * Resolves whatever an agent passes as a page identifier to a corpus urlPath.
 * Accepts a full URL, a bare slug, a markdown-twin path, and the several spellings of
 * the homepage ("", "/", "index", "index.md") — agents guess all of them.
 */
export function resolveDocPath(input) {
  const trimmed = String(input ?? '')
    .trim()
    .replace(/^https?:\/\/[^/]+/, '')
    .replace(/\.(md|mdx|html)$/i, '')
    .replace(/^\/+|\/+$/g, '');
  if (!trimmed || trimmed === 'index' || trimmed === 'home') return '/';
  const wanted = `/${trimmed.replace(/\/index$/, '')}/`;
  return pages.find((page) => page.urlPath === wanted)?.urlPath ?? null;
}

/**
 * Whether an Accept header actually prefers markdown over HTML, honouring q-values.
 * `text/html, text/markdown;q=0.1` is a request for HTML, not markdown.
 */
export function prefersMarkdown(accept) {
  if (!accept) return false;
  const quality = (type) => {
    let best = -1;
    for (const part of accept.split(',')) {
      const [media, ...params] = part.trim().split(';').map((piece) => piece.trim());
      if (media.toLowerCase() !== type) continue;
      const q = params
        .map((param) => param.match(/^q=([0-9.]+)$/i)?.[1])
        .find((value) => value !== undefined);
      best = Math.max(best, q === undefined ? 1 : Number(q));
    }
    return best;
  };
  const markdown = quality('text/markdown');
  if (markdown <= 0) return false;
  return markdown >= quality('text/html');
}

function textResult(value) {
  return { content: [{ type: 'text', text: value }] };
}

/**
 * Runs one MCP tool. Returns either an MCP result, or `{ error }` for a tool-level
 * failure the caller renders with isError: true.
 */
export function callTool(name, args = {}) {
  if (name === 'search_klag_docs') {
    const query = typeof args.query === 'string' ? args.query : '';
    if (!query.trim()) return { error: 'query is required and must be a non-empty string' };
    const limit = Math.min(Math.max(Number(args.limit) || 5, 1), 20);
    const hits = searchPages(query, limit);
    if (!hits.length) {
      return textResult(
        `No klag.dev page matched "${query}". Try broader terms, or read ${SITE}/llms.txt ` +
        'for the full page index.',
      );
    }
    return textResult(hits.map((hit) =>
      `## ${hit.page.title}\nURL: ${hit.page.url}\n${hit.page.description}\n\n${hit.excerpt}`,
    ).join('\n\n---\n\n'));
  }

  if (name === 'get_klag_doc') {
    const requested = typeof args.path === 'string' ? args.path : '';
    if (!requested.trim()) {
      return textResult(pages.map((page) => `- ${page.urlPath} — ${page.title}`).join('\n'));
    }
    const urlPath = resolveDocPath(requested);
    const page = urlPath && pages.find((candidate) => candidate.urlPath === urlPath);
    if (!page) {
      return {
        error: `No page at "${requested}". Call get_klag_doc with no path to list every page.`,
      };
    }
    return textResult(`# ${page.title}\nURL: ${page.url}\n\n${page.body}`);
  }

  if (name === 'get_klag_config') {
    const needle = typeof args.name === 'string' ? args.name.toLowerCase() : '';
    const matches = needle
      ? configKeys.filter((key) =>
          key.name.toLowerCase().includes(needle) ||
          key.description.toLowerCase().includes(needle))
      : configKeys;
    if (!matches.length) {
      return {
        error: `No Klag configuration key matches "${args.name}". ` +
          'Call get_klag_config with no name to list every key.',
      };
    }
    return textResult(matches.map((key) =>
      `${key.name}\n  default: ${key.default || '(none)'}\n  ${key.description}\n  docs: ${key.url}`,
    ).join('\n\n'));
  }

  if (name === 'get_klag_metric') {
    // Prometheus renders dots as underscores, and some metric names carry both
    // (klag.consumer.commit.staleness_seconds). Normalise every separator on both
    // sides so either spelling resolves.
    const separators = /[_.]/g;
    const raw = typeof args.name === 'string' ? args.name.toLowerCase() : '';
    const needle = raw.replace(separators, '.');
    const matches = needle
      ? metrics.filter((metric) =>
          metric.name.toLowerCase().replace(separators, '.').includes(needle) ||
          metric.description.toLowerCase().includes(raw))
      : metrics;
    if (!matches.length) {
      return {
        error: `No Klag metric matches "${args.name}". Prometheus renders dots as ` +
          'underscores (klag.consumer.lag -> klag_consumer_lag); both spellings work here.',
      };
    }
    return textResult(matches.map((metric) =>
      `${metric.name}\n  ${metric.description}\n  docs: ${metric.url}`,
    ).join('\n\n'));
  }

  return { error: `Unknown tool: ${name}` };
}
