// SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { adaptReference } from '../adapt.mjs';

const original = (await readFile(new URL('../reference/Lanyard.jsx', import.meta.url), 'utf8')).replace(/\r\n/g, '\n');
const adapted = await adaptReference();
const provenance = JSON.parse(await readFile(new URL('../reference.json', import.meta.url), 'utf8'));
function section(source, name) {
  const start = source.indexOf(`const ${name} =`);
  assert.notEqual(start, -1, name);
  const indent = source.slice(source.lastIndexOf('\n', start) + 1, start);
  const end = source.indexOf('\n' + indent + 'const ', start + 8);
  return source.slice(start, end === -1 ? source.length : end);
}
test('the preserved export matches its recorded checksum', () => {
  assert.equal(createHash('sha256').update(original).digest('hex'), provenance.sha256);
});
for (const name of [...provenance.protectedSections, 'FOIL_VERTEX_HEAD', 'FOIL_VERTEX_BODY',
  'FOIL_FRAGMENT_HEAD', 'FOIL_FRAGMENT_BODY']) {
  test(`reference geometry, material or physics remains unchanged: ${name}`, () => {
    assert.equal(section(adapted, name), section(original, name));
  });
}
test('physical materials and complete hardware meshes are preserved', () => {
  const begin = '    const frontMaterial = new THREE.MeshPhysicalMaterial';
  const end = '    const sim = createSimulation();';
  assert.equal(adapted.slice(adapted.indexOf(begin), adapted.indexOf(end)),
    original.slice(original.indexOf(begin), original.indexOf(end)));
});
test('the offline document forbids connections, frames, inline scripts and objects', async () => {
  const html = await readFile(new URL('../../../app/src/main/assets/lanyard/index.html', import.meta.url), 'utf8');
  for (const directive of ["connect-src 'none'", "frame-src 'none'", "object-src 'none'", "script-src 'self'"]) {
    assert.ok(html.includes(directive));
  }
  assert.ok(!html.includes('unsafe-eval'));
  assert.ok(!html.includes('https://'));
});
test('the committed offline bundle matches every recorded hash', async () => {
  const hashes = JSON.parse(await readFile(new URL('../bundle-integrity.json', import.meta.url), 'utf8'));
  for (const [name, hash] of Object.entries(hashes)) {
    const bytes = await readFile(new URL('../../../app/src/main/assets/lanyard/' + name, import.meta.url));
    assert.equal(createHash('sha256').update(bytes).digest('hex'), hash);
  }
});
