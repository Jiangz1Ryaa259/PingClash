// SPDX-License-Identifier: GPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import * as THREE from 'three';
import { syncHostedFace } from '../src/hostLifecycle.mjs';
function fixture() {
  const quaternion = new THREE.Quaternion();
  return { sim: { body: { quaternion, previousQuaternion: quaternion.clone(), angular: new THREE.Vector3() },
    twist: 0, twistRaw: 0, turn: 0 }, lifecycle: { turn: null, resume: -1, flips: 0 } };
}
function sync(f, back, resume) { syncHostedFace(f.sim, { back, resume }, f.lifecycle, THREE, 6); }
function normal(f) { return new THREE.Vector3(0, 0, 1).applyQuaternion(f.sim.body.quaternion).z; }
test('opening an already-connected scene selects the back without replaying a flip', () => {
  const f = fixture(); sync(f, true, 1);
  assert.equal(f.lifecycle.flips, 0); assert.ok(normal(f) < -.99); assert.equal(f.sim.body.angular.length(), 0);
});
test('ordinary resumes preserve the settled quaternion and never add flip impulses', () => {
  const f = fixture(); sync(f, true, 1);
  f.sim.body.quaternion.multiply(new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), .12));
  const before = f.sim.body.quaternion.clone();
  for (let resume = 2; resume < 10; resume++) sync(f, true, resume);
  assert.ok(f.sim.body.quaternion.equals(before)); assert.equal(f.lifecycle.flips, 0);
});
test('a real visible state change adds exactly one impulse despite duplicate host frames', () => {
  const f = fixture(); sync(f, false, 1); sync(f, true, 1);
  const impulse = f.sim.body.angular.clone(); sync(f, true, 1);
  assert.equal(f.lifecycle.flips, 1); assert.ok(impulse.length() > 0); assert.ok(f.sim.body.angular.equals(impulse));
});
test('a hidden state change reconciles silently when the scene returns', () => {
  const f = fixture(); sync(f, false, 1); sync(f, true, 2);
  assert.equal(f.lifecycle.flips, 0); assert.ok(normal(f) < -.99); assert.equal(f.sim.body.angular.length(), 0);
});
test('returning in the middle of a flip settles before revealing the scene', () => {
  const f = fixture(); sync(f, false, 1); sync(f, true, 1);
  f.sim.body.quaternion.setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.PI / 2);
  sync(f, true, 2);
  assert.equal(f.lifecycle.flips, 1); assert.ok(normal(f) < -.99); assert.equal(f.sim.body.angular.length(), 0);
});
test('an inactive host updates the face silently even before the resume epoch changes', () => {
  const f = fixture(); sync(f, true, 1);
  syncHostedFace(f.sim, { back: false, resume: 1, active: false }, f.lifecycle, THREE, 6);
  sync(f, false, 2);
  assert.equal(f.lifecycle.flips, 0); assert.ok(normal(f) > .99);
});
test('a newly attached home reconciles before the user explicitly asks for a flip', () => {
  const f = fixture(); sync(f, true, 1);
  syncHostedFace(f.sim, { back: false, resume: 1, active: true, animateFace: false }, f.lifecycle, THREE, 6);
  assert.equal(f.lifecycle.flips, 0); assert.ok(normal(f) > .99);
  syncHostedFace(f.sim, { back: true, resume: 1, active: true, animateFace: true }, f.lifecycle, THREE, 6);
  assert.equal(f.lifecycle.flips, 1); assert.ok(f.sim.body.angular.length() > 0);
});
