// Generates GEO (generative-engine-optimization) files for klag.dev:
//   public/llms.txt       - concise index: project summary + linked page list
//   public/llms-full.txt  - full concatenated docs for direct LLM ingestion
//
// Walks src/content/docs/**, reads frontmatter (title/description) and body,
// so the files never drift from the actual docs. Run at build time via the
// "build" npm script before `astro build`.

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
const DEFAULT_PUBLIC = join(ROOT, 'public');
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

// file path -> site URL path (strip extension, drop /index)
function toUrlPath(file, docsDir) {
  let p = relative(docsDir, file).replace(/\\/g, '/').replace(/\.(md|mdx)$/, '');
  if (p === 'index') return '/';
  if (p.endsWith('/index')) p = p.slice(0, -'/index'.length);
  return `/${p}/`;
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

const quickFacts = `## Quick start

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

## MCP tools

The opt-in, read-only MCP endpoint provides \`list_consumer_groups\`, \`get_consumer_group_lag\`, \`find_lagging_groups\`, and \`diagnose\`. Set \`MCP_ENABLED=true\` and select a metrics reporter so its in-memory snapshot is populated.
`;

export async function generateLlms({
  docsDir = DEFAULT_DOCS,
  outputDir = DEFAULT_PUBLIC,
} = {}) {
  const files = (await walk(docsDir)).sort();
  const pages = [];
  for (const file of files) {
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
  console.log(`gen-llms: wrote llms.txt + llms-full.txt (${pages.length} pages)`);
  return { index, full, pageCount: pages.length };
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await generateLlms();
}
