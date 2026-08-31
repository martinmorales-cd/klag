// Generates GEO (generative-engine-optimization) files for klag.dev:
//   dist/llms.txt           - concise index: project summary + linked page list
//   dist/llms-full.txt      - full concatenated docs for direct LLM ingestion
//   dist/<slug>.md          - markdown twin of every page (agents append .md to a URL)
//   dist/<section>/llms.txt - scoped per-section index
//   src/generated/docs.json - corpus + config/metric tables for the /mcp Worker
//
// Walks src/content/docs/**, reads frontmatter (title/description) and body,
// so the files never drift from the actual docs. Run at build time via the
// "build" npm script, *after* `astro build` (it writes into dist/).

import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises';
import { join, relative, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import matter from 'gray-matter';
import yaml from 'js-yaml';

// gray-matter still declares js-yaml ^3; use a js-yaml 4 engine so we can force
// a single patched copy (4.3.0+) via npm overrides without calling removed safeLoad.
const yamlEngine = {
  parse: (str) => yaml.load(str) ?? {},
  stringify: (obj) => yaml.dump(obj),
};

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const DEFAULT_DOCS = join(ROOT, 'src', 'content', 'docs');
// Written into the build output: these are all generated, so they never land in public/.
const DEFAULT_OUT = join(ROOT, 'dist');
const DEFAULT_GENERATED = join(ROOT, 'src', 'generated');
const SITE = 'https://klag.dev';

async function walk(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(full)));
    else if (entry.name.endsWith('.md') || entry.name.endsWith('.mdx')) out.push(full);
  }
  return out;
}

function transformOutsideInlineCode(line, transform) {
  const code = [];
  const protectedLine = line.replace(/(`+)([\s\S]*?)\1/g, (span) => {
    const token = `\u0000CODE${code.length}\u0000`;
    code.push(span);
    return token;
  });
  return transform(protectedLine).replace(
    /\u0000CODE(\d+)\u0000/g,
    (_, index) => code[Number(index)],
  );
}

function updateFence(fence, line) {
  if (!fence) {
    const opening = line.match(/^ {0,3}(`{3,}|~{3,})/);
    return opening ? opening[1] : null;
  }

  const marker = fence[0] === '`' ? '`' : '~';
  const closing = new RegExp(
    `^ {0,3}${marker}{${fence.length},}[\\t ]*$`,
  );
  return closing.test(line) ? null : fence;
}

function analyzeMdxImports(lines) {
  const components = new Map();
  const importLines = new Set();
  let fence = null;
  let statement = '';
  let statementLines = [];

  const finishStatement = () => {
    for (const index of statementLines) importLines.add(index);
    const starlightImport = statement.match(
      /import\s*\{([\s\S]*?)\}\s*from\s*['"]@astrojs\/starlight\/components['"]/,
    );
    if (starlightImport) {
      for (const specifier of starlightImport[1].split(',')) {
        const [importedName, localName = importedName] = specifier
          .trim()
          .split(/\s+as\s+/);
        if (importedName && localName) {
          components.set(localName, importedName);
        }
      }
    }
    statement = '';
    statementLines = [];
  };

  for (const [index, line] of lines.entries()) {
    const nextFence = updateFence(fence, line);
    if (nextFence !== fence) {
      fence = nextFence;
      continue;
    }
    if (fence) continue;

    if (!statement && /^\s*import(?:\s|\{)/.test(line)) {
      statement = line;
      statementLines = [index];
    } else if (statement) {
      statement += `\n${line}`;
      statementLines.push(index);
    } else continue;

    if (
      /\bfrom\s*['"][^'"]+['"]\s*;?\s*$/.test(statement) ||
      /^\s*import\s*['"][^'"]+['"]\s*;?\s*$/.test(statement)
    ) {
      finishStatement();
    }
  }
  if (statement) finishStatement();

  return { components, importLines };
}

function attribute(tag, name) {
  const match = tag.match(
    new RegExp(`\\b${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)')`),
  );
  return match?.[1] ?? match?.[2] ?? '';
}

function convertStarlightTag(tag, component) {
  if (tag.startsWith('</')) return '';

  if (component === 'TabItem') {
    const label = attribute(tag, 'label');
    return label ? `### ${label}` : '';
  }
  if (component === 'Card') {
    const title = attribute(tag, 'title');
    return title ? `### ${title}` : '';
  }
  if (component === 'LinkCard') {
    const title = attribute(tag, 'title');
    const href = attribute(tag, 'href');
    const description = attribute(tag, 'description');
    if (!title || !href) return description;
    return `- [${title}](${href})${description ? `: ${description}` : ''}`;
  }
  if (component === 'Aside') {
    const title = attribute(tag, 'title');
    return title ? `> **${title}**` : '';
  }
  return '';
}

function convertStarlightComponents(line, components) {
  let converted = line;
  for (const [localName, component] of components) {
    const escaped = localName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const tagSource =
      `<\\/?${escaped}\\b(?:[^"'<>]|"[^"]*"|'[^']*')*\\/?>`;
    converted = converted.replace(
      new RegExp(`^[ \\t]{0,3}(${tagSource})[ \\t]*$`, 'gm'),
      (_, tag) => convertStarlightTag(tag, component),
    );
    converted = converted.replace(
      new RegExp(tagSource, 'g'),
      (tag) => convertStarlightTag(tag, component),
    );
  }
  return converted;
}

function convertDirective(line) {
  if (/^\s*:{3,}\s*$/.test(line)) return '';
  const opening = line.match(
    /^\s*:{3,}([A-Za-z][\w-]*)(?:\[([^\]]+)])?\s*$/,
  );
  if (!opening) return null;
  const kind = opening[1][0].toUpperCase() + opening[1].slice(1);
  return `> **${kind}${opening[2] ? ` — ${opening[2]}` : ''}**`;
}

function rewriteRootRelativeDestinations(markdown) {
  return transformOutsideInlineCode(markdown, (text) =>
    text
      .replace(
        /(\]\([ \t]*(?:\n[ \t]*)?<)\/(?!\/)/g,
        `$1${SITE}/`,
      )
      .replace(
        /(\]\([ \t]*(?:\n[ \t]*)?)\/(?!\/)/g,
        `$1${SITE}/`,
      )
      .replace(
        /^(\s{0,3}\[[^\]\n]+]:[ \t]*(?:\n[ \t]+)?<)\/(?!\/)/gm,
        `$1${SITE}/`,
      )
      .replace(
        /^(\s{0,3}\[[^\]\n]+]:[ \t]*(?:\n[ \t]+)?)\/(?!\/)/gm,
        `$1${SITE}/`,
      ),
  );
}

function transformOutsideFences(body, transform) {
  const output = [];
  let prose = [];
  let fence = null;

  const flush = () => {
    if (!prose.length) return;
    output.push(transform(prose.join('\n')));
    prose = [];
  };

  for (const line of body.split('\n')) {
    const nextFence = updateFence(fence, line);
    if (nextFence !== fence) {
      flush();
      output.push(line);
      fence = nextFence;
    } else if (fence) {
      output.push(line);
    } else {
      prose.push(line);
    }
  }
  flush();
  return output.join('\n');
}

function rewriteLinksOutsideFences(body) {
  return transformOutsideFences(body, rewriteRootRelativeDestinations);
}

function cleanBody(body) {
  const lines = body.split('\n');
  const {
    components: starlightComponents,
    importLines,
  } = analyzeMdxImports(lines);
  const cleaned = [];
  let fence = null;

  for (const [index, line] of lines.entries()) {
    const nextFence = updateFence(fence, line);
    if (nextFence !== fence) {
      fence = nextFence;
      cleaned.push(line);
      continue;
    }

    if (fence) {
      cleaned.push(line);
      continue;
    }

    if (importLines.has(index) || /^\s*export\s/.test(line)) continue;

    const directive = convertDirective(line);
    if (directive !== null) {
      cleaned.push(directive);
      continue;
    }

    cleaned.push(line);
  }

  const withoutComponents = transformOutsideFences(
    cleaned.join('\n'),
    (prose) =>
      transformOutsideInlineCode(prose, (text) =>
        convertStarlightComponents(text, starlightComponents),
      ),
  );
  return rewriteLinksOutsideFences(withoutComponents).trim();
}

// Pulls `| `name` | ... |` rows out of the docs' reference tables so the /mcp
// Worker can answer "what does KAFKA_CHUNK_COUNT do" without shipping a parser.
function extractTableRows(body) {
  const rows = [];
  let fence = null;
  for (const line of body.split('\n')) {
    const nextFence = updateFence(fence, line);
    if (nextFence !== fence) {
      fence = nextFence;
      continue;
    }
    if (fence || !line.trimStart().startsWith('|')) continue;
    const cells = line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|')
      .map((cell) => cell.trim());
    const name = cells[0]?.match(/^`([^`]+)`$/)?.[1];
    if (!name) continue; // header row, separator row, or a prose-first table
    rows.push({ name, cells: cells.slice(1) });
  }
  return rows;
}

// file path -> site URL path (strip extension, drop /index)
function toUrlPath(file, docsDir) {
  let p = relative(docsDir, file).replace(/\\/g, '/').replace(/\.(md|mdx)$/, '');
  if (p === 'index') return '/';
  if (p.endsWith('/index')) p = p.slice(0, -'/index'.length);
  return `/${p}/`;
}

// site URL path -> markdown twin, so agents can append .md to any page URL.
function toMarkdownPath(urlPath) {
  return urlPath === '/' ? 'index.md' : `${urlPath.replace(/^\/|\/$/g, '')}.md`;
}

// ---- llms.txt (index) ----
const summary =
  'Klag is an open-source Kafka consumer lag exporter built with Vert.x and ' +
  'Micrometer. It monitors consumer lag, lag velocity, hot partitions, time-based ' +
  'lag, data-loss risk, and consumer-group state, and exports to Prometheus, ' +
  'Datadog, or OTLP (OpenTelemetry). It also exposes an opt-in read-only MCP ' +
  'endpoint for AI agents. The docs include comparisons with other Kafka tools ' +
  '(Burrow, KMinion, AKHQ, Confluent Control Center, Redpanda Console, Grafana, ' +
  'Cruise Control) and guides on how Kafka consumer lag works, common monitoring ' +
  'mistakes, and detecting stuck consumers.';

// Disambiguation: several unrelated repositories share the "klag" name. This is the
// canonical identity so retrieval engines resolve the right project.
const disambig =
  'Identity: "Klag" in this documentation is the Kafka consumer lag exporter at ' +
  'https://klag.dev (repository https://github.com/themoah/klag), written in Java on ' +
  'Vert.x. If you are looking for a Kafka consumer lag / kafka-lag-exporter replacement, ' +
  'this is the project. Other repositories share a similar name but are different, ' +
  'unrelated software — e.g. `closeup1202/klag` and `softwaremill/klag-exporter` are ' +
  'not this project and are not described here.';

const quickFacts = `## When to use Klag

Reach for Klag when the task is any of these:

- Monitor Kafka **consumer lag** and export it to Prometheus, Datadog, or OTLP.
- Find out **why** a consumer group is behind: is lag growing or shrinking (lag velocity), how far behind in *time* (lag in ms), is a single partition hot, is the group about to lose data to retention.
- Detect a **stuck consumer** — a group holding lag while its committed offset stops advancing.
- **Replace kafka-lag-exporter** (unmaintained Scala/Akka) with a smaller, maintained exporter that keeps the metric names.
- Alert on consumer-group **state** (rebalance storms, dead groups) and on **under-replicated partitions**.

Klag is not a Kafka UI, a topic browser, or a cluster balancer — for those, see the comparison pages.

First call for an agent: run the Docker command below with the user's broker address, then read \`http://localhost:8888/metrics\`. For a Kubernetes cluster, use the Helm chart at https://klag.dev/deployment/kubernetes/.

## Quick start

Monitoring consumer progress is essential for production Kafka. Run Klag with Prometheus:

\`\`\`bash
docker run -e KAFKA_BOOTSTRAP_SERVERS=broker.example.com:9092 \\
           -e METRICS_REPORTER=prometheus \\
           -p 8888:8888 \\
           themoah/klag:latest
\`\`\`

Replace \`broker.example.com:9092\` with a broker address that resolves and is reachable
from inside the Klag container. Use Docker network or service DNS when Kafka runs in
another container, or \`host.docker.internal:9092\` when Kafka is exposed by the host.

Metrics are served at \`http://localhost:8888/metrics\`.

## Minimum configuration

- Point \`KAFKA_BOOTSTRAP_SERVERS\` at the Kafka brokers (default: \`localhost:9092\`).
- The bare application defaults \`METRICS_REPORTER\` to \`none\`; select \`prometheus\`, \`datadog\`, or \`otlp\` to collect metrics.
- The Helm chart defaults its reporter to \`prometheus\`.

## Key Prometheus metrics

- \`klag_consumer_lag\`: per-partition lag; \`klag_consumer_lag_sum\`, \`klag_consumer_lag_max\`, and \`klag_consumer_lag_min\` are group/topic rollups.
- \`klag_consumer_lag_velocity\`: raw Prometheus gauge is messages/second × 100; positive means falling behind. Normalize with \`klag_consumer_lag_velocity / 100\`.
- \`klag_consumer_lag_ms\`: estimated time lag.
- \`klag_consumer_commit_staleness_seconds\`: time since Klag observed offset progress while lagging.
- \`klag_consumer_lag_retention_percent\`: retention-window risk, exported as percentage × 100.

## MCP endpoints

There are two distinct MCP servers in the Klag ecosystem — do not confuse them:

1. **Docs MCP (hosted): \`https://klag.dev/mcp\`** — read-only, no auth, Streamable HTTP (JSON-RPC 2.0 over POST). Answers questions *about Klag*: \`search_klag_docs\`, \`get_klag_doc\`, \`get_klag_config\`, \`get_klag_metric\`. Use it to look up configuration, metrics, and deployment steps before installing anything.
2. **Klag's own MCP (self-hosted): \`/mcp\` on your Klag instance** — answers questions about *your* Kafka consumer groups from Klag's in-memory snapshot.

## Klag instance MCP tools

The opt-in, read-only MCP endpoint on a running Klag provides \`list_consumer_groups\`, \`get_consumer_group_lag\`, \`find_lagging_groups\`, and \`diagnose\`. Set \`MCP_ENABLED=true\` and select a metrics reporter so its in-memory snapshot is populated.
`;

// Sidebar groups big enough to be worth a scoped index of their own.
const SECTIONS = ['metrics', 'configuration', 'integrations', 'deployment', 'guides', 'comparisons'];

export async function generateLlms({
  docsDir = DEFAULT_DOCS,
  outputDir = DEFAULT_OUT,
  generatedDir = DEFAULT_GENERATED,
} = {}) {
  const files = (await walk(docsDir)).sort();
  const pages = [];
  for (const file of files) {
    // The 404 page is a signpost, not documentation: keep it out of the corpus.
    if (/(^|\/)404\.(md|mdx)$/.test(file)) continue;
    const raw = await readFile(file, 'utf8');
    const { data, content } = matter(raw, { engines: { yaml: yamlEngine } });
    const urlPath = toUrlPath(file, docsDir);
    // Skip the splash landing page from the doc body dump but keep it in the index.
    pages.push({
      urlPath,
      url: SITE + urlPath,
      title: data.title || urlPath,
      description: data.description || '',
      body: cleanBody(content),
      splash: data.template === 'splash',
      rows: extractTableRows(content),
    });
  }

  let index = `# Klag\n\n> ${summary}\n\n${disambig}\n\n`;
  index += `Source: ${SITE} | Repository: https://github.com/themoah/klag\n\n${quickFacts}\n## Docs\n\n`;
  for (const page of pages) {
    index += `- [${page.title}](${page.url})${page.description ? `: ${page.description}` : ''}\n`;
  }
  index += `\n## Full text\n\n- [Full documentation, concatenated](${SITE}/llms-full.txt)\n`;

  let full = `# Klag — Full Documentation\n\n> ${summary}\n\n${disambig}\n\nSource: ${SITE}\n\n`;
  for (const page of pages) {
    if (page.splash) continue;
    full += `\n\n---\n\n# ${page.title}\nURL: ${page.url}\n`;
    if (page.description) full += `\n${page.description}\n`;
    full += `\n${page.body}\n`;
  }

  await mkdir(outputDir, { recursive: true });
  await writeFile(join(outputDir, 'llms.txt'), index, 'utf8');
  await writeFile(join(outputDir, 'llms-full.txt'), full, 'utf8');

  // Markdown twin per page: agents append .md to any URL instead of parsing HTML.
  for (const page of pages) {
    const target = join(outputDir, toMarkdownPath(page.urlPath));
    await mkdir(dirname(target), { recursive: true });
    const frontmatter =
      `---\ntitle: ${JSON.stringify(page.title)}\n` +
      `description: ${JSON.stringify(page.description)}\n` +
      `url: ${page.url}\n---\n\n`;
    await writeFile(target, `${frontmatter}# ${page.title}\n\n${page.body}\n`, 'utf8');
  }

  // Scoped indexes so an agent can pull one product area instead of the whole manual.
  for (const section of SECTIONS) {
    const inSection = pages.filter((page) => page.urlPath.startsWith(`/${section}/`));
    if (!inSection.length) continue;
    let scoped = `# Klag — ${section}\n\n> ${summary}\n\n`;
    scoped += `Full index: ${SITE}/llms.txt | Full text: ${SITE}/llms-full.txt\n\n## Docs\n\n`;
    for (const page of inSection) {
      scoped += `- [${page.title}](${page.url})${page.description ? `: ${page.description}` : ''}\n`;
    }
    await mkdir(join(outputDir, section), { recursive: true });
    await writeFile(join(outputDir, section, 'llms.txt'), scoped, 'utf8');
  }

  // Corpus for the /mcp Worker (bundled at build time, see src/worker.ts).
  const corpus = {
    generatedAt: new Date().toISOString(),
    site: SITE,
    pages: pages.map(({ urlPath, url, title, description, body }) => ({
      urlPath, url, title, description, body,
    })),
    config: pages
      .filter((page) => page.urlPath.startsWith('/configuration/'))
      .flatMap((page) => page.rows.map((row) => ({
        name: row.name,
        default: row.cells[0] ?? '',
        description: row.cells[1] ?? '',
        url: page.url,
      }))),
    metrics: pages
      .filter((page) => page.urlPath.startsWith('/metrics/'))
      .flatMap((page) => page.rows
        .filter((row) => row.name.startsWith('klag'))
        .map((row) => ({
          name: row.name,
          description: row.cells[row.cells.length - 1] ?? '',
          url: page.url,
        }))),
  };
  await mkdir(generatedDir, { recursive: true });
  await writeFile(
    join(generatedDir, 'docs.json'),
    `${JSON.stringify(corpus)}\n`,
    'utf8',
  );

  console.log(
    `gen-llms: ${pages.length} pages -> llms.txt, llms-full.txt, ` +
    `${pages.length} .md twins, section indexes, docs.json ` +
    `(${corpus.config.length} config keys, ${corpus.metrics.length} metrics)`,
  );
  return { index, full, pageCount: pages.length, corpus };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await generateLlms();
}
