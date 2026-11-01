// SPDX-License-Identifier: GPL-3.0-only
// Screen-space gestures and a readiness-gated, one-shot entrance. The upstream
// material/mesh and free-body solver remain unchanged. Rest length is immutable
// during grabs: stretched webbing is tension, never extra slack.
export function springStep(value, velocity, target, dt, frequency, damping) {
  const a = damping * frequency;
  const b = frequency * Math.sqrt(1 - damping * damping);
  const x = value - target;
  const c = (velocity + a * x) / b;
  const decay = Math.exp(-a * dt), sin = Math.sin(b * dt), cos = Math.cos(b * dt);
  return [target + decay * (x * cos + c * sin),
    decay * ((c * b - a * x) * cos - (x * b + a * c) * sin)];
}

export function createHostedMotion(THREE, joints) {
  const state = { stage: 'preparing', entered: false, entries: 0, resume: -1,
    elapsed: 0, face: 0, spin: 0, roll: 0, rollVelocity: 0, pitch: 0, pitchVelocity: 0,
    gravity: 0, cord: 0, taut: false, lean: 0, leanVelocity: 0, settleAfter: 0, fade: 0,
    origin: new THREE.Vector3(), velocity: new THREE.Vector3(), grabPrev: new THREE.Vector3() };
  const up = new THREE.Vector3(0, 1, 0), forward = new THREE.Vector3(0, 0, 1);
  const across = new THREE.Vector3(1, 0, 0);
  const base = new THREE.Vector3(), hang = new THREE.Vector3();
  const dropSeconds = .50;
  // The card is a rigid body on an elastic strap that only pulls, clipped at its top
  // (body.hang): free fall while slack, a catch when taut, a yank back up, slack flight,
  // smaller catches. Because the strap pulls at the clip and not at the centre, every
  // catch and swing also turns the card, with its own inertia, so it wobbles and lags
  // behind the strap like a real badge. It rocks toward the screen on each yank, so it
  // catches the light. The strap is sized so the card rests exactly at its layout position.
  const cordFrequency = 21; // cord stiffness, rad/s
  const cordDamping = .13; // energy lost per catch: a lively, clearly bouncing strap
  const airDrag = .42; // slowly calms the swing and the slack arcs
  const spinDrag = 2.4; // air on the card's own rotation: a few wobbles, no whip
  const holdSpinDrag = 6; // fingers damp the turn while the card is held
  const swingStart = .7; // initial sideways speed, world units/s
  const rockFrequency = 9, rockDamping = .22, rockKick = .010; // forward/back rock
  // Then the air thickens (up to fadeDrag over fadeSeconds) so the swing dies out on its
  // own, and a gentle spring removes what is left, so it can never drift. A throw may
  // swing out for longer than the entrance.
  const settleAfter = 1.5, throwSettleAfter = 3.6, fadeDrag = 4, fadeSeconds = 1.4;
  // As in React Bits' Lanyard, cord tension saturates (hold * tanh(k * stretch / hold)), so a
  // long pull snaps back hard but never explodes; hold = 3.85 g is its default elasticity.
  const holdShare = 3.85;
  const throwCap = 60; // fastest release, world units/s (the reference caps the body at 60)
  // As in React Bits' ProfileCard, the card leans toward the drag (up to about 12 degrees),
  // so the foil and glare slide across it while it is pulled.
  const leanStrength = .21;

  // Rest length that puts the card exactly at its layout position under this gravity.
  function cordFor(sim, gravity) {
    const k = cordFrequency * cordFrequency, hold = holdShare * gravity;
    hang.copy(sim.body.hang).add(base);
    return Math.max(.01, sim.anchor.distanceTo(hang) - hold / k * Math.atanh(gravity / hold));
  }
  // Inverse moment of inertia about the screen normal (a 1.6 x 2.25 card without one).
  const spinWeight = sim => sim.body.inverseInertia?.z || 1.575;
  // Strap pull on the clip of a card at p turned by roll; returns [fx, fy, torque].
  function strap(sim, p, v, hold, damping) {
    const c = Math.cos(state.roll), sn = Math.sin(state.roll), h = sim.body.hang;
    const ox = h.x * c - h.y * sn, oy = h.x * sn + h.y * c;
    const rx = p.x + ox - sim.anchor.x, ry = p.y + oy - sim.anchor.y;
    const length = Math.hypot(rx, ry) || 1e-6, stretch = length - state.cord;
    // Above the anchor the webbing folds over instead of pushing (the card starts there).
    if (ry >= 0 || stretch <= 0) { state.taut = false; return [0, 0, 0]; }
    const dx = rx / length, dy = ry / length;
    const radial = (v.x - state.rollVelocity * oy) * dx + (v.y + state.rollVelocity * ox) * dy;
    const k = cordFrequency * cordFrequency;
    const pull = hold * Math.tanh(k * stretch / hold) + damping * radial;
    if (!state.taut) {
      // The yank rocks the card toward the screen in proportion to the catch.
      state.pitchVelocity -= rockKick * Math.max(0, radial) * rockFrequency;
      state.taut = true;
    }
    if (pull <= 0) return [0, 0, 0];
    const fx = -dx * pull, fy = -dy * pull;
    return [fx, fy, ox * fy - oy * fx];
  }

  function cable(sim) {
    hang.copy(sim.body.hang).applyQuaternion(sim.body.quaternion).add(sim.body.position);
    const distance = sim.anchor.distanceTo(hang);
    // A pulled strap is straight. Above the anchor, retain a small hanging fold
    // rather than allowing unconstrained intermediate particles to explode.
    const slack = Math.max(0, sim.rest * joints - distance);
    for (let i = 0; i <= joints; i++) {
      const t = i / joints;
      sim.nodes[i].lerpVectors(sim.anchor, hang, t);
      sim.nodes[i].y -= Math.sin(Math.PI * t) * slack * .24;
      sim.previous[i].copy(sim.nodes[i]);
      sim.velocities[i].set(0, 0, 0);
    }
    for (let i = 0; i < joints; i++) sim.lengths[i] = sim.nodes[i].distanceTo(sim.nodes[i + 1]);
    sim.impulses.fill(0);
    sim.body.previous.copy(sim.body.position);
    sim.body.previousQuaternion.copy(sim.body.quaternion);
    sim.body.velocity.set(0, 0, 0);
    sim.body.angular.set(0, 0, 0);
    sim.grab = null;
    sim.calm = 0;
  }
  function face(sim, host, dt) {
    [state.face, state.spin] = springStep(state.face, state.spin, host.back ? Math.PI : 0, dt, 16, .82);
    sim.body.quaternion.setFromAxisAngle(up, state.face + state.lean);
    sim.body.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(forward, state.roll));
    sim.body.quaternion.premultiply(new THREE.Quaternion().setFromAxisAngle(across, state.pitch));
    sim.turn = host.back ? Math.PI : 0;
    sim.twist = state.face; sim.twistRaw = state.face;
  }
  function settle(sim, host) {
    state.stage = 'idle'; state.velocity.set(0, 0, 0);
    state.face = host.back ? Math.PI : 0; state.spin = 0; state.roll = 0; state.rollVelocity = 0;
    state.pitch = 0; state.pitchVelocity = 0; state.lean = 0; state.leanVelocity = 0;
    sim.body.position.copy(base); face(sim, host, 0); cable(sim); sim.calm = 2;
  }
  // Rigid card on the strap: gravity, air, and the strap pull at the clip, which both
  // moves and turns it. Small fixed substeps keep the stiff catch stable.
  function rope(sim, dt) {
    const c = 2 * cordDamping * cordFrequency, hold = holdShare * state.gravity;
    const steps = Math.max(1, Math.ceil(dt * 480));
    const h = dt / steps, p = sim.body.position, v = state.velocity, spin = spinWeight(sim);
    for (let i = 0; i < steps; i++) {
      // Drag calms the swing and the bounce; the first free fall keeps its exact timing.
      const drag = state.stage === 'drop' ? 0 : airDrag + state.fade;
      const [sx, sy, torque] = strap(sim, p, v, hold, c);
      v.x += (sx - drag * v.x) * h; v.y += (sy - state.gravity - drag * v.y) * h; v.z = 0;
      p.x += v.x * h; p.y += v.y * h; p.z = base.z;
      state.rollVelocity += (torque * spin - (spinDrag + state.fade) * state.rollVelocity) * h;
      state.roll += state.rollVelocity * h;
      if (state.stage === 'drop' && state.taut) { state.stage = 'landing'; state.elapsed = 0; }
      [state.pitch, state.pitchVelocity] = springStep(state.pitch, state.pitchVelocity, 0, h, rockFrequency, rockDamping);
      [state.lean, state.leanVelocity] = springStep(state.lean, state.leanVelocity, 0, h, rockFrequency, rockDamping);
    }
  }
  // While held at its centre, the stretched strap still turns the card about the finger.
  function turnHeld(sim, dt) {
    const steps = Math.max(1, Math.ceil(dt * 480)), h = dt / steps, spin = spinWeight(sim);
    const still = new THREE.Vector3();
    for (let i = 0; i < steps; i++) {
      const [, , torque] = strap(sim, sim.body.position, still, holdShare * state.gravity, 0);
      state.rollVelocity += (torque * spin - holdSpinDrag * state.rollVelocity) * h;
      state.roll += state.rollVelocity * h;
    }
  }
  function calm(sim) {
    return sim.body.position.distanceTo(base) < .001 && state.velocity.length() < .008 &&
      Math.abs(state.roll) < .001 && Math.abs(state.rollVelocity) < .01 &&
      Math.abs(state.pitch) < .001 && Math.abs(state.lean) < .001 && Math.abs(state.spin) < .01;
  }
  function update(sim, host, worldHeight, dt = 0) {
    base.set(sim.anchor.x, worldHeight / 2 - worldHeight *
      (host.top + host.height / 2) / host.viewportHeight, 0);
    // The entrance fall takes dropSeconds from just above the screen; the same gravity
    // drives every bounce, including the snap back after a pull.
    const dropHeight = worldHeight / 2 + worldHeight * host.height / host.viewportHeight - base.y;
    const gravity = 2 * dropHeight / (dropSeconds * dropSeconds);
    const resumed = state.resume !== -1 && state.resume !== host.resume;
    state.resume = host.resume;
    if (!host.active || !host.motion) {
      if (state.entered || (host.active && host.presented)) {
        state.entered = true; settle(sim, host);
      }
      else {
        sim.body.position.copy(base);
        sim.body.position.y = worldHeight / 2 + worldHeight * host.height / host.viewportHeight;
        state.face = host.back ? Math.PI : 0; face(sim, host, 0); cable(sim);
      }
      return true;
    }
    if (resumed && state.entered && state.stage !== 'idle') settle(sim, host);
    if (!state.entered) {
      if (!host.presented) {
        sim.body.position.copy(base);
        sim.body.position.y = worldHeight / 2 + worldHeight * host.height / host.viewportHeight;
        state.face = host.back ? Math.PI : 0; face(sim, host, 0); cable(sim);
        return true;
      }
      state.entered = true; state.entries++;
      state.stage = 'drop'; state.elapsed = 0;
      state.origin.copy(base);
      state.origin.y = worldHeight / 2 + worldHeight * host.height / host.viewportHeight;
      state.face = host.back ? Math.PI : 0;
      state.roll = 0; state.rollVelocity = 0; state.pitch = 0; state.pitchVelocity = 0;
      state.gravity = gravity; state.cord = cordFor(sim, gravity); state.settleAfter = settleAfter;
      state.taut = false;
      sim.body.position.copy(state.origin);
      state.velocity.set(swingStart, 0, 0);
    }
    if (host.held) {
      if (state.stage !== 'held') {
        state.origin.copy(sim.body.position); state.stage = 'held';
        state.velocity.set(0, 0, 0);
        const normal = new THREE.Vector3(0, 0, 1).applyQuaternion(sim.body.quaternion);
        const desired = host.back ? Math.PI : 0;
        const difference = Math.atan2(normal.x, normal.z) - desired;
        state.face = desired + Math.atan2(Math.sin(difference), Math.cos(difference));
        state.spin = 0;
        state.grabPrev.copy(sim.body.position);
        state.gravity = gravity; state.cord = cordFor(sim, gravity);
      }
      // Native dx/dy are unmirrored screen coordinates, on either face.
      sim.body.position.copy(state.origin).add(new THREE.Vector3(
        host.dx / host.viewportHeight * worldHeight, -host.dy / host.viewportHeight * worldHeight, 0));
      if (dt > 0) turnHeld(sim, dt);
      // Finger speed (smoothed) becomes the throw on release and drives the lean.
      if (dt > 0) {
        const blend = 1 - Math.exp(-dt * 30), p = sim.body.position, v = state.velocity;
        v.x += ((p.x - state.grabPrev.x) / dt - v.x) * blend;
        v.y += ((p.y - state.grabPrev.y) / dt - v.y) * blend;
        v.z = 0;
      }
      state.grabPrev.copy(sim.body.position);
      const scale = worldHeight * .8;
      [state.lean, state.leanVelocity] = springStep(state.lean, state.leanVelocity,
        leanStrength * Math.tanh(state.velocity.x / scale), dt, 14, .6);
      [state.pitch, state.pitchVelocity] = springStep(state.pitch, state.pitchVelocity,
        -leanStrength * Math.tanh(state.velocity.y / scale), dt, 14, .6);
      face(sim, host, dt); cable(sim); return true;
    }
    if (state.stage === 'held') {
      // Let go: the stretched strap flings the card back, it overshoots on a slack arc,
      // is caught again and swings out, exactly like the entrance.
      state.stage = 'landing'; state.elapsed = 0; state.taut = false;
      state.gravity = gravity; state.cord = cordFor(sim, gravity); state.settleAfter = throwSettleAfter;
      if (state.velocity.length() > throwCap) state.velocity.setLength(throwCap);
    }
    if (state.stage === 'drop' || state.stage === 'landing') {
      state.elapsed += dt;
      const late = state.stage === 'landing' ? state.elapsed - state.settleAfter : 0;
      state.fade = fadeDrag * Math.min(1, Math.max(0, late) / fadeSeconds);
      rope(sim, dt);
      if (state.stage === 'landing' && late > fadeSeconds) {
        state.stage = 'return'; state.elapsed = 0;
      }
      if (state.stage === 'landing' && calm(sim)) { settle(sim, host); return true; }
    } else if (state.stage === 'return') {
      state.elapsed += dt;
      for (const axis of ['x', 'y', 'z']) {
        [sim.body.position[axis], state.velocity[axis]] = springStep(
          sim.body.position[axis], state.velocity[axis], base[axis], dt, 10, .74);
      }
      [state.roll, state.rollVelocity] = springStep(state.roll, state.rollVelocity, 0, dt, 9, .55);
      [state.pitch, state.pitchVelocity] = springStep(state.pitch, state.pitchVelocity, 0, dt, 9, .55);
      if (calm(sim)) {
        settle(sim, host); return true;
      }
    } else {
      if (sim.body.velocity.lengthSq() + sim.body.angular.lengthSq() < 1e-10) {
        sim.calm = 2; return true;
      }
      return false;
    }
    face(sim, host, dt); cable(sim); return true;
  }
  return { state, update };
}
