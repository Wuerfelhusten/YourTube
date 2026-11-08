#!/usr/bin/env node
// Builds YourTube's offline adblock snapshot (app/src/main/assets/adblock) the same way
// Brave's brave-core-crx-packager builds its components:
//
//  1. Filter lists: every component Brave enables by default (minus iOS-only ones) plus the
//     optional YouTube lists, taken from Brave's list_catalog.json.
//  2. Resources (scriptlets + redirects): uBlock Origin's builtin scriptlets and
//     web_accessible_resources from Brave's uBlock fork, plus Brave's own resources.json.
//
// Usage: node tools/adblock/build-snapshot.mjs
// Requires Node 20+ (global fetch) and git.

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const OUT = path.join(ROOT, 'app', 'src', 'main', 'assets', 'adblock');
const WORK = path.join(ROOT, 'build', 'adblock-work');

const CATALOG_URL = 'https://raw.githubusercontent.com/brave/adblock-resources/master/filter_lists/list_catalog.json';
const BRAVE_RESOURCES_URL = 'https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json';
const UBO_REPO = 'https://github.com/brave/uBlock.git';

const log = (...a) => console.log('[adblock-snapshot]', ...a);

async function fetchText(url) {
  const res = await fetch(url, { headers: { 'User-Agent': 'YourTube-snapshot-builder' } });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} for ${url}`);
  return res.text();
}

/** Which catalog components YourTube ships. */
function selectComponents(catalog) {
  const forAndroid = (c) => !c.platforms || c.platforms.length === 0 || c.platforms.includes('ANDROID');
  return catalog
    .filter(forAndroid)
    .filter((c) => c.default_enabled === true || c.title.startsWith('YouTube '))
    .map((c) => ({
      uuid: c.uuid,
      title: c.title,
      defaultEnabled: c.default_enabled === true,
      permissionMask: c.permission_mask ?? 0,
      sources: c.sources.map((s) => ({ url: s.url, title: s.title ?? '' })),
    }));
}

function fileNameFor(url) {
  const base = path.basename(new URL(url).pathname).replace(/[^A-Za-z0-9._-]/g, '_') || 'list.txt';
  const hash = createHash('sha1').update(url).digest('hex').slice(0, 10);
  return `${hash}-${base}`;
}

async function buildLists(components) {
  const listsDir = path.join(OUT, 'lists');
  await fs.mkdir(listsDir, { recursive: true });
  const done = new Map(); // url -> file
  for (const comp of components) {
    comp.lists = [];
    for (const src of comp.sources) {
      let file = done.get(src.url);
      if (!file) {
        file = fileNameFor(src.url);
        const text = await fetchText(src.url);
        if (text.trim().length === 0) throw new Error(`Empty list: ${src.url}`);
        await fs.writeFile(path.join(listsDir, file), text.replace(/\r/g, ''), 'utf8');
        done.set(src.url, file);
        log(`list ${(text.length / 1024).toFixed(0).padStart(5)} KB  ${src.url}`);
      }
      comp.lists.push({ url: src.url, title: src.title, file: `lists/${file}` });
    }
    delete comp.sources;
  }
  return done.size;
}

function mimeFromName(name) {
  const ext = path.extname(name).toLowerCase();
  return {
    '.css': 'text/css', '.gif': 'image/gif', '.html': 'text/html', '.js': 'application/javascript',
    '.json': 'application/json', '.mp3': 'audio/mp3', '.mp4': 'video/mp4', '.png': 'image/png',
    '.txt': 'text/plain', '.xml': 'text/xml',
  }[ext] ?? 'application/octet-stream';
}

async function buildResources() {
  const uboDir = path.join(WORK, 'uBlock');
  await fs.rm(uboDir, { recursive: true, force: true });
  await fs.mkdir(WORK, { recursive: true });
  log('cloning', UBO_REPO);
  execFileSync('git', ['clone', '-q', '--depth', '1', UBO_REPO, uboDir], { stdio: 'inherit' });
  const uboRev = execFileSync('git', ['-C', uboDir, 'rev-parse', '--short', 'HEAD']).toString().trim();

  // (a) Redirect resources ("web accessible resources"), like adblock-rust's resource-assembler.
  const { default: redirectMap } = await import(pathToFileURL(path.join(uboDir, 'src', 'js', 'redirect-resources.js')).href);
  const war = path.join(uboDir, 'src', 'web_accessible_resources');
  const resources = [];
  for (const [name, props] of redirectMap) {
    if (name === 'google-ima-dai.js') continue; // Brave excludes this one as well
    const mime = mimeFromName(name);
    const raw = await fs.readFile(path.join(war, name));
    const isText = mime === 'application/javascript' || mime === 'text/html' || mime === 'text/plain';
    const content = isText ? Buffer.from(raw.toString('utf8').replace(/\r/g, '')).toString('base64') : raw.toString('base64');
    const alias = props?.alias;
    resources.push({
      name,
      aliases: alias === undefined ? [] : Array.isArray(alias) ? alias : [alias],
      kind: { mime },
      content,
    });
  }
  const redirectCount = resources.length;

  // (b) Brave's own resources.
  const brave = JSON.parse(await fetchText(BRAVE_RESOURCES_URL));
  resources.push(...brave);

  // (c) uBO builtin scriptlets (ES module format), transformed exactly like Brave does.
  const { builtinScriptlets } = await import(pathToFileURL(path.join(uboDir, 'src', 'js', 'resources', 'scriptlets.js')).href);
  const known = new Set(builtinScriptlets.map((s) => s.name));
  for (const s of builtinScriptlets) {
    for (const dep of s.dependencies ?? []) {
      if (!known.has(dep)) log(`warning: scriptlet ${s.name} is missing dependency ${dep}`);
    }
    resources.push({
      name: s.name,
      aliases: s.aliases ?? [],
      kind: { mime: 'application/javascript' },
      content: Buffer.from(s.fn.toString()).toString('base64'),
      dependencies: s.dependencies ?? [],
    });
  }

  await fs.writeFile(path.join(OUT, 'resources.json'), JSON.stringify(resources), 'utf8');
  log(`resources: ${redirectCount} redirects, ${brave.length} Brave, ${builtinScriptlets.length} uBO scriptlets (brave/uBlock@${uboRev})`);
  return uboRev;
}

async function main() {
  await fs.rm(OUT, { recursive: true, force: true });
  await fs.mkdir(OUT, { recursive: true });

  const catalogText = await fetchText(CATALOG_URL);
  const components = selectComponents(JSON.parse(catalogText));
  log(`components: ${components.map((c) => `${c.title}${c.defaultEnabled ? '' : ' (optional)'}`).join(', ')}`);

  const listCount = await buildLists(components);
  const uboRev = await buildResources();

  const manifest = {
    format: 1,
    generatedAt: new Date().toISOString(),
    catalogSha1: createHash('sha1').update(catalogText).digest('hex'),
    uBlockRevision: uboRev,
    resources: 'resources.json',
    components,
  };
  await fs.writeFile(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2), 'utf8');
  log(`done: ${components.length} components, ${listCount} lists -> ${path.relative(ROOT, OUT)}`);
}

main().catch((e) => {
  console.error('[adblock-snapshot] FAILED:', e);
  process.exit(1);
});
