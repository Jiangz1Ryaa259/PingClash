// SPDX-License-Identifier: GPL-3.0-only
import { build } from 'esbuild';
import { mkdir, readFile, writeFile, copyFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { adaptReference } from './adapt.mjs';
await adaptReference();
const output = new URL('../../app/src/main/assets/lanyard/', import.meta.url);
await mkdir(output, { recursive: true });
await build({ entryPoints: [fileURLToPath(new URL('./src/entry.jsx', import.meta.url))],
  outfile: fileURLToPath(new URL('renderer.js', output)), bundle: true, minify: true,
  format: 'iife', jsx: 'automatic', target: ['chrome80'], define: { 'process.env.NODE_ENV': '"production"' },
  legalComments: 'external', sourcemap: false });
await writeFile(new URL('index.html', output), `<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'">
<link rel="stylesheet" href="renderer.css"></head><body><div id="root"></div><script src="renderer.js" defer></script></body></html>`);
await writeFile(new URL('renderer.css', output),
  'html,body,#root{margin:0;position:fixed;inset:0;width:100%;height:100vh;overflow:hidden;background:transparent;}\n' +
  (await readFile(new URL('./src/Lanyard.css', import.meta.url), 'utf8')).replace(/\r\n/g, '\n'));
const licenseDir = new URL('../../app/src/main/assets/licenses/lanyard/', import.meta.url);
await mkdir(licenseDir, { recursive: true });
await copyFile(new URL('./LICENSE-ReactBits.md', import.meta.url), new URL('ReactBits.md', licenseDir));
for (const pkg of ['react', 'react-dom', 'three']) {
  await copyFile(new URL(`./node_modules/${pkg}/LICENSE`, import.meta.url), new URL(pkg + '.txt', licenseDir));
}
const hashes = {};
for (const name of ['index.html', 'renderer.js', 'renderer.css', 'renderer.js.LEGAL.txt']) {
  const bytes = await readFile(new URL(name, output));
  hashes[name] = createHash('sha256').update(bytes).digest('hex');
}
await writeFile(new URL('./bundle-integrity.json', import.meta.url), JSON.stringify(hashes, null, 2) + '\n');
console.log('Built offline renderer:', hashes['renderer.js']);
