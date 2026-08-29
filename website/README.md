# klag.dev website

Docs + promotional site for [Klag](https://github.com/themoah/klag), built with
[Astro](https://astro.build) + [Starlight](https://starlight.astro.build) and deployed as a
**Cloudflare Worker with static assets** at [klag.dev](https://klag.dev).

The Worker (`src/worker.ts`) sits in front of the built site. It serves the documentation
MCP endpoint at `/mcp`, rewrites `/sitemap.xml`, negotiates `Accept: text/markdown`, and
adds the agent-discovery `Link` headers. Everything else falls through to `dist/` via the
`ASSETS` binding.

## Local development

```bash
cd website
npm install
npm run dev        # http://localhost:4321 — Astro only, no Worker
npm run preview    # build + `wrangler dev` — the real thing, Worker included
```

Node >= 22.12 (see `.node-version`).

## Build

```bash
npm run build      # astro build -> dist/, then gen-llms.mjs and gen-skills.mjs
npm run check      # wrangler types + astro check
npm test           # generator tests (node --test)
```

`npm run build` writes several **generated** artifacts into `dist/` — never edit them by
hand, and never commit them:

| Output | Produced by |
| --- | --- |
| `llms.txt`, `llms-full.txt` | `scripts/gen-llms.mjs` |
| `<slug>.md` markdown twin of every page | `scripts/gen-llms.mjs` |
| `<section>/llms.txt` scoped indexes | `scripts/gen-llms.mjs` |
| `src/generated/docs.json` (the corpus the Worker bundles) | `scripts/gen-llms.mjs` |
| `skills/**` + `.well-known/agent-skills/index.json` | `scripts/gen-skills.mjs`, from `../plugin` |
| `openapi.json` re-stamped with the app version | `scripts/gen-skills.mjs`, from `build.gradle.kts` |

`public/og.png` is generated too, but by hand — run `npm run gen:og` after changing the
wording or the logo, and commit the result.

## Content

All docs live in `src/content/docs/**` as Markdown/MDX. The sidebar is configured in
`astro.config.mjs`. Add a page by dropping a `.md`/`.mdx` file with `title` +
`description` frontmatter and adding it to the sidebar.

Content is sourced from the repo's `README.md` and `CLAUDE.md` — keep them in sync when
project facts change.

## Agent-facing surface

Hand-written files under `public/.well-known/` (`ai-catalog.json`, `agent-card.json`,
`api-catalog`, `mcp/server-card.json`), plus `public/openapi.json`, `public/pricing.md`,
and `public/agents.md`. `/developers/` documents the whole surface for humans. When you add
a resource, add it to `ai-catalog.json` as well or agents will not find it.

## Analytics

Cloudflare Web Analytics (cookieless). The beacon is injected only when
`CF_ANALYTICS_TOKEN` is set in the build environment.

## Deploy

**Deploys run in CI, not from a laptop.** `.github/workflows/website.yml` builds, tests and
type-checks every push and PR touching `website/**` or `plugin/**`, and publishes the `klag`
Worker on push to `main`. A local `wrangler deploy` would ship whatever happens to be in the
working tree, so there is deliberately no `npm run deploy` script.

The deploy job runs `npm ci` -> `npm run build` -> `npx wrangler deploy`, then verifies the
live agent surface (`/mcp` answers `tools/list`, the well-known files return 200, an unknown
path still returns 404) and fails the run if any of it is wrong.

Required repository secrets:

| Secret | Purpose |
| --- | --- |
| `CLOUDFLARE_API_TOKEN` | API token with the **Edit Cloudflare Workers** template scopes. |
| `CLOUDFLARE_ACCOUNT_ID` | The account that owns the `klag` Worker. |
| `CF_ANALYTICS_TOKEN` | Optional. Without it the analytics beacon is simply not injected. |

`klag.dev` is attached to the `klag` Worker as a custom domain (`wrangler.jsonc`); there is
no Pages project.

**Break glass.** If CI is down and the site must ship, build and publish by hand from
`website/` on Node 22+, and say so in the PR:

```bash
npm ci && npm run build && npx wrangler deploy
```
