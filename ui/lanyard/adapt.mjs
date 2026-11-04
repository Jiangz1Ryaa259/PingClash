import { readFile, writeFile } from 'node:fs/promises';

export async function adaptReference() {
  let source = (await readFile(new URL('./reference/Lanyard.jsx', import.meta.url), 'utf8')).replace(/\r\n/g, '\n');
  source = "import { syncHostedFace } from './hostLifecycle.mjs';\nimport { createHostedMotion } from './hostMotion.mjs';\n" +
    "import { installHostFace } from './hostFace.mjs';\n" + source;
  const replace = (before, after) => {
    if (source.split(before).length !== 2) throw new Error('Reference integration point changed');
    source = source.replace(before, after);
  };
  replace('  cache.set(url, promise);', `  if (cache.size >= 8) cache.delete(cache.keys().next().value);
  cache.set(url, promise);`);
  replace('      let viewHeight = layout.height / clamp(s.size, 0.15, 0.9);',
    '      const hostFrame = window.__pingclashFrame;\n      let viewHeight = hostFrame?.height > 0 ? layout.height * hostFrame.viewportHeight / hostFrame.height : layout.height / clamp(s.size, 0.15, 0.9);');
  replace('      if (viewHeight * aspect < minimumWidth) viewHeight = minimumWidth / aspect;',
    '      if (!hostFrame?.height && viewHeight * aspect < minimumWidth) viewHeight = minimumWidth / aspect;');
  replace('      const cardTop = viewHeight / 2 - viewHeight * mix(0.12, 0.42, clamp(s.strapLength, 0, 1));',
    '      const cardTop = viewHeight / 2 - viewHeight * (hostFrame?.height > 0 ? hostFrame.top / hostFrame.viewportHeight : mix(0.12, 0.42, clamp(s.strapLength, 0, 1)));');
  replace('      const anchorX = ((ANCHORS[s.anchor] ?? 0.5) - 0.5) * viewWidth;',
    '      const anchorX = ((hostFrame?.height > 0 ? hostFrame.centerX / hostFrame.viewportWidth : ANCHORS[s.anchor] ?? 0.5) - 0.5) * viewWidth;');
  replace('      const framingKey = `${layoutKey}|${s.size}|${s.anchor}|${s.strapLength}|${view.width}|${view.height}`;',
    '      const framingKey = `${layoutKey}|${s.size}|${s.anchor}|${s.strapLength}|${view.width}|${view.height}|${window.__pingclashFrame?.height}|${window.__pingclashFrame?.top}|${window.__pingclashFrame?.centerX}`;');
  replace('      cardGroup.position.copy(sim.body.position);', '      syncNative();\n      cardGroup.position.copy(sim.body.position);');
  replace('      backTexture.needsUpdate = true;\n      start();',
    `      backTexture.needsUpdate = true;
      for (const texture of [frontTexture, backTexture, grain, weave]) renderer.initTexture(texture);
      window.__lanyardPaintedPhase = window.__lanyardTexturePhases?.get(images.front?.src) || "";
      if (!warming) {
        warming = true;
        renderer.compileAsync(scene, camera).then(() => {
          if (!alive) return;
          shadersPrepared = true;
          if (window.__pingclashFrame?.active) { render(); start(); }
        }).catch(() => { window.__lanyardReady = false; });
      }
      if (window.__pingclashFrame?.active) render();
      start();`);
  // A reset must restore the authoritative native face, including the twist
  // accumulator. Otherwise a resize or numerical recovery leaves the front
  // visible while the host still believes it already flipped to the back.
  replace('  sim.twistRaw = 0;\n  sim.calm = 0;', `  sim.twistRaw = 0;
  const host = window.__pingclashFrame;
  if (host) {
    const desired = host.back ? Math.PI : 0;
    body.quaternion.setFromAxisAngle(new THREE.Vector3(0, 1, 0), desired);
    body.previousQuaternion.copy(body.quaternion);
    sim.turn = desired; sim.twist = desired; sim.twistRaw = desired;
  }
  sim.calm = 0;`);
  replace('      renderer.render(scene, camera);', `      renderer.render(scene, camera);
      const normal = new THREE.Vector3(0, 0, 1).applyQuaternion(sim.body.quaternion);
      window.__lanyardPose = { normalZ: normal.z, turn: sim.turn, twist: sim.twist, held: hostedMotion.state.stage === "held", x: sim.body.position.x, y: sim.body.position.y, rest: sim.rest, stage: hostedMotion.state.stage, entries: hostedMotion.state.entries };
      window.__lanyardReady = shadersPrepared && canvas.clientHeight > 100 && !!images.front && !!images.back && renderer.info.render.calls > 0 && !renderer.getContext().isContextLost();
      window.__lanyardRenderedEpoch = window.__pingclashFrame?.epoch ?? -1;
      window.__lanyardRenderedResume = window.__pingclashFrame?.resume ?? -1;
      window.__lanyardFlipCount = hostedFace.flips;
      window.__lanyardFrames = (window.__lanyardFrames || 0) + 1;`);
  // The ProfileCard-style face layer chains after the (unchanged) upstream materials.
  replace('    const sim = createSimulation();\n', `    const sim = createSimulation();
    const hostFaceLayout = installHostFace(THREE, [
      { material: frontMaterial, theme: 'light' }, { material: backMaterial, theme: 'dark' }]);
`);
  replace('      foilUniforms.foilAspect.value = layout.height / layout.width;',
    '      foilUniforms.foilAspect.value = layout.height / layout.width;\n      hostFaceLayout(layout);');
  replace('    const tick = now => {', `    const hostedFace = { turn: null, resume: -1, flips: 0 };
    let shadersPrepared = false;
    let warming = false;
    const hostedMotion = createHostedMotion(THREE, JOINTS);
    const syncNative = (dt = 0) => {
      const host = window.__pingclashFrame;
      if (!host) return false;
      syncHostedFace(sim, host, hostedFace, THREE, FLIP_SPIN);
      const worldHeight = 2 * camera.position.z * Math.tan(FOV * Math.PI / 360);
      return hostedMotion.update(sim, host, worldHeight, dt);
    };
    const onNativeFrame = () => {
      const host = window.__pingclashFrame;
      if (!host?.active) {
        cancelAnimationFrame(raf); raf = 0;
        syncNative();
        return;
      }
      if (!host.motion || !raf) { syncNative(); render(); }
      if (!host.motion) { cancelAnimationFrame(raf); raf = 0; return; }
      // Retain an already queued tick: frequent pointer updates must not keep
      // restarting the clock and starve flip/landing integration.
      start();
    };
    window.addEventListener('pingclash-frame', onNativeFrame);

    const tick = now => {`);
  replace('      raf = 0;\n      if (!alive) return;', '      raf = 0;\n      if (!alive || document.hidden || window.__pingclashFrame?.active === false) return;');
  replace('      const dt = Math.min(1 / 30, Math.max(1 / 240, (now - last) / 1000));',
    '      const motionDt = Math.max(0, (now - last) / 1000);\n      const dt = Math.min(1 / 30, Math.max(1 / 240, motionDt));');
  replace('      const current = physics();', '      const hosted = syncNative(motionDt);\n      const current = physics();');
  // The host moves the badge's slot when a sidebar opens or closes, possibly while another
  // page is shown and the simulation is paused. Carry the whole rig with the anchor, so the
  // strap never stretches across the screen and touches still land on the card.
  replace(`      sim.anchor.set(anchorX, anchorY, 0);
      configureSimulation(sim, layout, anchorY - hangTop);`, `      const shift = new THREE.Vector3(anchorX, anchorY, 0).sub(sim.anchor);
      sim.anchor.set(anchorX, anchorY, 0);
      configureSimulation(sim, layout, anchorY - hangTop);
      if (placed && shift.lengthSq() > 0) {
        for (const node of sim.nodes) node.add(shift);
        for (const node of sim.previous) node.add(shift);
        sim.body.position.add(shift);
        sim.body.previous.add(shift);
        sim.grab = null;
      }`);
  replace('      stepSimulation(sim, dt, current, time);', '      if (!hosted && window.__pingclashFrame?.motion !== false) stepSimulation(sim, dt, current, time);');
  replace("      document.removeEventListener('visibilitychange', onVisibility);", `      window.removeEventListener('pingclash-frame', onNativeFrame);
      window.__lanyardReady = false;
      document.removeEventListener('visibilitychange', onVisibility);`);
  await writeFile(new URL('./src/Lanyard.jsx', import.meta.url),
    '// React Bits Lanyard; license: ../LICENSE-ReactBits.md. Android host adaptations: adapt.mjs.\n' + source);
  return source;
}
