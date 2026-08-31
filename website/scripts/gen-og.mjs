// Renders public/og.png (1200x630), the social/share card referenced by og:image.
//
// Run by hand after changing the wording or the logo: `npm run gen:og`. The PNG is
// committed, so the build never needs sharp at deploy time.

import { writeFile } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import sharp from 'sharp';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const WIDTH = 1200;
const HEIGHT = 630;

const escapeXml = (value) =>
  value.replace(/[<>&]/g, (char) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' })[char]);

function card({ titleLines, tagline, footer }) {
  const fonts = "system-ui,-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${WIDTH}" height="${HEIGHT}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#0b1220"/>
      <stop offset="100%" stop-color="#111c33"/>
    </linearGradient>
    <linearGradient id="rule" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#38bdf8"/>
      <stop offset="100%" stop-color="#818cf8"/>
    </linearGradient>
  </defs>
  <rect width="${WIDTH}" height="${HEIGHT}" fill="url(#bg)"/>
  <rect x="0" y="0" width="${WIDTH}" height="10" fill="url(#rule)"/>
${titleLines.map((line, i) => `  <text x="80" y="${330 + i * 84}" font-family="${fonts}" font-size="68" font-weight="700" fill="#f8fafc">${escapeXml(line)}</text>`).join('\n')}
  <text x="80" y="${330 + titleLines.length * 84 + 14}" font-family="${fonts}" font-size="34" font-weight="400" fill="#94a3b8">${escapeXml(tagline)}</text>
  <text x="80" y="562" font-family="${fonts}" font-size="26" font-weight="500" fill="#38bdf8">${escapeXml(footer)}</text>
</svg>`;
}

export async function generateOg({ outputDir = join(ROOT, 'public') } = {}) {
  const svg = card({
    titleLines: ['Klag — Kafka', 'consumer lag exporter'],
    tagline: 'Lag velocity, hot partitions, stuck consumers, retention risk.',
    footer: 'klag.dev  ·  Prometheus · Datadog · OTLP  ·  Apache-2.0',
  });

  const logo = await sharp(join(ROOT, 'src', 'assets', 'klag-logo.png'))
    .resize({ width: 176, fit: 'inside' })
    .toBuffer();

  const png = await sharp(Buffer.from(svg))
    .composite([{ input: logo, top: 72, left: 80 }])
    .png()
    .toBuffer();

  const target = join(outputDir, 'og.png');
  await writeFile(target, png);
  console.log(`gen-og: wrote ${target} (${WIDTH}x${HEIGHT}, ${(png.length / 1024).toFixed(0)} KB)`);
  return target;
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  await generateOg();
}
