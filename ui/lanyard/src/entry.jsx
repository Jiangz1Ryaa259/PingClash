// SPDX-License-Identifier: GPL-3.0-only
import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import Lanyard from './Lanyard.jsx';
import { FACE, setSignature } from './hostFace.mjs';

const state = { active: false, presented: false, animateFace: false, motion: true, held: false, back: false, dx: 0, dy: 0,
  viewportHeight: 800, viewportWidth: 360, centerX: 180, top: 180, height: 266, fontScale: 1,
  phase: 'idle', label: '', status: '', profile: '', mode: '', epoch: 0, resume: 0 };
window.__pingclashFrame = state;
window.__lanyardTexturePhases = new Map();
let configure;
let previousKey = '';
let signed = '';

// Status dot colors, by connection phase.
const DOTS = { idle: '#9ca3af', connecting: '#f59e0b', established: '#22c55e', stopping: '#f59e0b', error: '#ef4444' };
// ProfileCard's light and dark themes, in PingClash's ink and teal.
const LOOKS = {
  front: { frame: ['#f7f8fa', '#e7e9ee'], edge: 'rgba(24,24,27,.07)', lip: 'rgba(255,255,255,.95)',
    tile: ['#ffffff', '#eceef3'], ink: '#17242a', muted: 'rgba(23,36,42,.6)',
    halo: 'rgba(255,255,255,.9)', pill: ['#ffffff', '#f3f4f7'],
    pillEdge: 'rgba(24,24,27,.09)', shadow: 'rgba(23,36,42,.22)' },
  back: { frame: ['#1d3135', '#11232a'], edge: 'rgba(255,255,255,.08)', lip: 'rgba(255,255,255,.07)',
    tile: ['#24423f', '#0e2025'], ink: '#eef4f3', muted: 'rgba(238,244,243,.6)',
    halo: 'rgba(165,217,207,.22)', pill: ['#2b4448', '#1c3236'],
    pillEdge: 'rgba(255,255,255,.08)', shadow: 'rgba(0,0,0,.45)' }
};

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath(); ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
}
function gradient(ctx, y0, y1, [from, to]) {
  const g = ctx.createLinearGradient(0, y0, 0, y1); g.addColorStop(0, from); g.addColorStop(1, to); return g;
}
// One line: shrunk a little to fit, then ellipsized.
function fitText(ctx, value, x, y, size, width, weight = 600) {
  let px = size * Math.min(state.fontScale, 1.8);
  const smallest = px * .78;
  const font = () => { ctx.font = `${weight} ${px}px sans-serif`; };
  font();
  while (px > smallest && ctx.measureText(value).width > width) { px -= 2; font(); }
  let text = value;
  if (ctx.measureText(text).width > width) {
    while (text && ctx.measureText(text + '…').width > width) text = [...text].slice(0, -1).join('');
    text += '…';
  }
  ctx.fillText(text, x, y);
}
function power(ctx, x, y, r) {
  ctx.beginPath(); ctx.arc(x, y, r, -Math.PI * .32, Math.PI * 1.32); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(x, y - r * 1.12); ctx.lineTo(x, y - r * .1); ctx.stroke();
}

// Faces after ProfileCard: a frame, a recessed window (hostFace.mjs gives it depth, the
// profile's rosette and the foil) with the name, and a footer with the status and a pill.
function paintCard(back) {
  const look = back ? LOOKS.back : LOOKS.front;
  const { width: W, height: H, window: win, footer } = FACE;
  const canvas = document.createElement('canvas');
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = gradient(ctx, 0, H, look.frame); ctx.fillRect(0, 0, W, H);
  // Window: backdrop, a soft light behind the centrepiece, and the profile with its mode.
  // The centrepiece is the profile's own guilloche rosette, printed and foiled by hostFace.mjs.
  ctx.save();
  roundRect(ctx, win.x, win.y, win.width, win.height, win.radius); ctx.clip();
  ctx.fillStyle = gradient(ctx, win.y, win.y + win.height, look.tile);
  ctx.fillRect(win.x, win.y, win.width, win.height);
  const cx = W / 2, cy = FACE.emblemY;
  const halo = ctx.createRadialGradient(cx, cy, 0, cx, cy, 300);
  halo.addColorStop(0, look.halo); halo.addColorStop(1, 'rgba(255,255,255,0)');
  ctx.fillStyle = halo; ctx.fillRect(cx - 300, cy - 300, 600, 600);
  ctx.fillStyle = look.ink; ctx.textAlign = 'left';
  const textW = win.width - 120;
  fitText(ctx, state.profile || 'PingClash', win.x + 60, win.y + win.height - (state.mode ? 122 : 64), 76, textW);
  if (state.mode) { ctx.fillStyle = look.muted; fitText(ctx, state.mode, win.x + 60, win.y + win.height - 60, 46, textW, 500); }
  ctx.restore();
  // Lip around the window: a hairline edge and a highlight.
  roundRect(ctx, win.x - 2, win.y - 2, win.width + 4, win.height + 4, win.radius + 2);
  ctx.lineWidth = 4; ctx.strokeStyle = look.edge; ctx.stroke();
  roundRect(ctx, win.x - 5, win.y - 5, win.width + 10, win.height + 10, win.radius + 5);
  ctx.lineWidth = 2; ctx.strokeStyle = look.lip; ctx.stroke();
  // Footer: status with its dot and the action, and the one power pill (stop on the back).
  const mid = (footer.y + footer.bottom) / 2 + 6, left = win.x + 52;
  const pillW = 150, pillH = 108, pillX = W - win.x - 44 - pillW;
  ctx.save();
  ctx.shadowColor = look.shadow; ctx.shadowBlur = 24; ctx.shadowOffsetY = 10;
  roundRect(ctx, pillX, mid - pillH / 2, pillW, pillH, 36);
  ctx.fillStyle = gradient(ctx, mid - pillH / 2, mid + pillH / 2, look.pill); ctx.fill();
  ctx.restore();
  ctx.lineWidth = 3; ctx.strokeStyle = look.pillEdge; ctx.stroke();
  ctx.strokeStyle = look.ink; ctx.fillStyle = look.ink; ctx.lineWidth = 9; ctx.lineCap = 'round';
  if (back) { roundRect(ctx, pillX + pillW / 2 - 18, mid - 18, 36, 36, 7); ctx.fill(); }
  else power(ctx, pillX + pillW / 2, mid + 3, 24);
  const textX = left + 8, footW = pillX - textX - 36, dot = DOTS[state.phase] || DOTS.idle;
  const top = state.label ? mid - 8 : mid + 12;
  ctx.fillStyle = dot; ctx.beginPath(); ctx.arc(textX + 10, top - 15, 10, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = look.ink; fitText(ctx, state.status, textX + 34, top, 44, footW - 34);
  if (state.label) { ctx.fillStyle = look.muted; fitText(ctx, state.label, textX, mid + 52, 38, footW, 500); }
  roundRect(ctx, 3, 3, W - 6, H - 6, 74); ctx.lineWidth = 4; ctx.strokeStyle = look.edge; ctx.stroke();
  const image = canvas.toDataURL('image/png');
  if (window.__lanyardTexturePhases.size >= 8) window.__lanyardTexturePhases.delete(window.__lanyardTexturePhases.keys().next().value);
  window.__lanyardTexturePhases.set(image, state.phase);
  return image;
}

// The faces change only with what is printed on them. Layout updates (position, size,
// viewport) reuse the encoded images instead of drawing and PNG-encoding two 1024x1440
// canvases again, which stalled the renderer while the home moved between pages.
let faces = { key: null, front: '', back: '' };
function paintedFaces() {
  const key = JSON.stringify([state.phase, state.label, state.status, state.profile, state.mode,
    Math.min(state.fontScale, 1.8)]);
  if (faces.key !== key) faces = { key, front: paintCard(false), back: paintCard(true) };
  return faces;
}

function options() {
  const size = Math.max(.15, Math.min(.6, state.height / state.viewportHeight));
  const fraction = state.top / state.viewportHeight;
  const { front, back } = paintedFaces();
  return { size, strapLength: Math.max(0, Math.min(1, (fraction - .12) / .3)),
    frontImage: front, backImage: back, cardColor: '#ffffff',
    // A glossy coat over the printed frame; the holographic foil is the host layer
    // (hostFace.mjs), seen only through the pattern in the recessed window.
    finish: 'glossy', orientation: 'portrait', anchor: 'center', cornerRadius: .3,
    strapColor: '#111111', strapWidth: .65, metal: 'silver', gravity: 1, damping: .5,
    elasticity: .5, breeze: 0, intro: false, interactive: false };
}

function App() {
  const [props, setProps] = useState(options());
  configure = setProps;
  return <Lanyard {...props} />;
}

window.PingClashScene = Object.freeze({
  frame(data) {
    if (!data || typeof data !== 'object') return;
    if (Number.isSafeInteger(data.epoch) && data.epoch >= 0) state.epoch = data.epoch;
    if (Number.isSafeInteger(data.resume) && data.resume >= 0) state.resume = data.resume;
    for (const key of ['viewportHeight', 'viewportWidth', 'centerX', 'top', 'height', 'fontScale', 'dx', 'dy']) {
      if (Number.isFinite(data[key])) state[key] = Math.max(-10000, Math.min(10000, data[key]));
    }
    for (const key of ['active', 'presented', 'animateFace', 'motion', 'held', 'back']) state[key] = data[key] === true;
    for (const key of ['phase', 'label', 'status', 'profile', 'mode']) {
      if (typeof data[key] === 'string') state[key] = data[key].slice(0, 120);
    }
    if (state.profile !== signed) { signed = state.profile; setSignature(signed); }
    // Android may initialize an offscreen WebView with a zero CSS vh. Use the
    // measured native aspect ratio until/after its first visible compositor frame.
    const height = Math.max(1, window.innerWidth * state.viewportHeight / Math.max(1, state.viewportWidth));
    for (const element of [document.documentElement, document.body, document.getElementById('root')]) {
      element.style.height = `${height}px`;
    }
    const key = [state.viewportHeight, state.viewportWidth, state.centerX, state.top, state.height,
      state.fontScale, state.phase, state.label, state.status, state.profile, state.mode].join('|');
    if (key !== previousKey && configure) { previousKey = key; configure(options()); }
    window.dispatchEvent(new Event('pingclash-frame'));
  },
  status() {
    return { ready: window.__lanyardReady === true, frames: window.__lanyardFrames || 0,
      active: state.active, engine: 'three', roughness: .42, clearcoat: 1,
      metalness: 1, metalRoughness: .16, weaveNormal: .7,
      pose: window.__lanyardPose || null, epoch: window.__lanyardRenderedEpoch ?? -1,
      resume: window.__lanyardRenderedResume ?? -1, flips: window.__lanyardFlipCount || 0,
      paintedPhase: window.__lanyardPaintedPhase || "" };
  }
});
createRoot(document.getElementById('root')).render(<App />);
