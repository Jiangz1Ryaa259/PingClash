// SPDX-License-Identifier: GPL-3.0-only
// Resume reconciles an existing scene; only a visible connection change adds spin.
export function syncHostedFace(sim, host, lifecycle, THREE, flipSpin) {
  const desired = host.back ? Math.PI : 0;
  const entering = host.active === false || host.animateFace === false ||
    lifecycle.turn === null || lifecycle.resume !== host.resume;
  const up = new THREE.Vector3(0, 1, 0).applyQuaternion(sim.body.quaternion);
  if (entering) {
    const normal = new THREE.Vector3(0, 0, 1).applyQuaternion(sim.body.quaternion);
    const unfinished = normal.z * (host.back ? -1 : 1) < .85 || Math.abs(sim.body.angular.dot(up)) > .5;
    if (lifecycle.turn !== desired || unfinished) {
      sim.body.quaternion.setFromAxisAngle(new THREE.Vector3(0, 1, 0), desired);
      sim.body.previousQuaternion.copy(sim.body.quaternion);
      sim.body.angular.set(0, 0, 0);
      sim.twist = desired; sim.twistRaw = desired;
    }
    lifecycle.resume = host.resume;
    lifecycle.turn = desired;
  } else if (desired !== lifecycle.turn) {
    sim.body.angular.addScaledVector(up, flipSpin * (desired > lifecycle.turn ? 1 : -1));
    lifecycle.turn = desired;
    lifecycle.flips++;
  }
  sim.turn = desired;
}
