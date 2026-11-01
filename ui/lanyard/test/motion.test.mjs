// SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { createHostedMotion } from '../src/hostMotion.mjs';

function fixture() {
  const vectors = () => Array.from({ length: 5 }, () => new THREE.Vector3());
  const sim = { nodes: vectors(), previous: vectors(), velocities: vectors(),
    lengths: new Float64Array(4), impulses: new Float64Array(4), rest: .4,
    anchor: new THREE.Vector3(0, 5, 0),
    body: { position: new THREE.Vector3(), previous: new THREE.Vector3(),
      quaternion: new THREE.Quaternion(), previousQuaternion: new THREE.Quaternion(),
      velocity: new THREE.Vector3(), angular: new THREE.Vector3(), hang: new THREE.Vector3(0, 1, 0) } };
  const host = { active: true, presented: false, motion: true, held: false, back: false,
    resume: 1, viewportHeight: 1000, top: 300, height: 225, dx: 0, dy: 0 };
  const motion = createHostedMotion(THREE, 4);
  const step = (n = 1, dt = 1 / 120) => { for (let i = 0; i < n; i++) motion.update(sim, host, 10, dt); };
  return { sim, host, motion, step };
}
test('waits above the screen until the native compositor acknowledges readiness', () => {
  const f = fixture(); f.step(240);
  assert.equal(f.motion.state.entries, 0); assert.ok(f.sim.body.position.y > 6);
  assert.equal(f.motion.state.stage, 'preparing');
  f.host.presented = true; f.step(30);
  const early = f.sim.body.position.y;
  f.step(30);
  assert.ok(early > f.sim.body.position.y); assert.equal(f.motion.state.stage, 'landing');
  assert.equal(f.motion.state.entries, 1);
  f.step(500); assert.equal(f.motion.state.stage, 'idle');
  assert.ok(Math.abs(f.sim.body.position.y - .875) < .001);
});
test('fall accelerates, landing stretches, and resume never replays the entry', () => {
  const f = fixture(); f.host.presented = true; f.step(1);
  const y0 = f.sim.body.position.y; f.step(12); const y1 = f.sim.body.position.y;
  f.step(12); const y2 = f.sim.body.position.y;
  assert.ok(y1 - y2 > y0 - y1);
  f.step(36); f.step(6); assert.ok(f.sim.body.position.y < .875);
  f.host.active = false; f.step(); f.host.active = true; f.host.resume++; f.step();
  assert.equal(f.motion.state.stage, 'idle'); assert.equal(f.motion.state.entries, 1);
});
test('reduced motion presents the true face immediately and does not replay when re-enabled', () => {
  const f = fixture(); f.host.motion = false; f.host.back = true; f.host.presented = true; f.step();
  assert.equal(f.motion.state.stage, 'idle');
  assert.ok(new THREE.Vector3(0, 0, 1).applyQuaternion(f.sim.body.quaternion).z < -.99);
  f.host.motion = true; f.step(120); assert.equal(f.motion.state.entries, 0);
});
for (const back of [false, true]) test(`unrestricted, stationary screen-space grab is stable on ${back ? 'back' : 'front'}`, () => {
  const f = fixture(); f.host.back = back; f.host.presented = true; f.step(500);
  const natural = f.sim.rest, initial = f.sim.body.position.clone();
  f.host.held = true; f.step(); f.host.dx = -850; f.host.dy = 1400; f.step(10);
  assert.ok(Math.abs(f.sim.body.position.x - initial.x + 8.5) < .0001);
  assert.ok(Math.abs(f.sim.body.position.y - initial.y + 14) < .0001);
  const held = f.sim.body.position.clone(); f.step(1000);
  assert.ok(held.equals(f.sim.body.position)); assert.equal(f.sim.rest, natural);
  // Every segment is collinear under tension, without increasing its rest length.
  for (let i = 1; i < 4; i++) {
    const direction = f.sim.nodes[i].clone().sub(f.sim.anchor).normalize();
    assert.ok(direction.dot(f.sim.nodes[4].clone().sub(f.sim.anchor).normalize()) > .99999);
  }
  f.host.held = false; f.step(1000);
  assert.equal(f.motion.state.stage, 'idle'); assert.ok(f.sim.body.position.distanceTo(initial) < .001);
  assert.ok(new THREE.Vector3(0, 0, 1).applyQuaternion(f.sim.body.quaternion).z * (back ? -1 : 1) > .99);
});
test('rapid regrabs, large drags and frame-rate variation remain finite without accumulating slack', () => {
  const f = fixture(); f.host.presented = true; f.step(500);
  for (let i = 0; i < 120; i++) {
    f.host.held = true; f.host.dx = 0; f.host.dy = 0; f.step();
    f.host.dx = Math.sin(i * 3) * 2000; f.host.dy = Math.cos(i * 7) * 2000; f.step(3, 1 / 30);
    f.host.held = false; f.step(5, 1 / 240);
    assert.ok(Number.isFinite(f.sim.body.position.length())); assert.equal(f.sim.rest, .4);
  }
  f.step(2000); assert.equal(f.motion.state.stage, 'idle');
});
