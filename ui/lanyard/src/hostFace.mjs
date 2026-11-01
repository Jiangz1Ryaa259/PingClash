// SPDX-License-Identifier: GPL-3.0-only
// Host look for the card faces, after React Bits' ProfileCard: a frame with a recessed
// window and a footer. The upstream material and foil stay unchanged; this layer is
// chained after them. Inside the window the print sits a little below the surface, so
// it shifts with the viewing angle (parallax), the frame casts a soft inner shadow and
// the window's wall shows at a steep angle. A holographic foil shows only through a
// pattern in the window and catches the light as the card tilts.

// Face layout in canvas pixels (1024 x 1440, portrait), shared by painting and shader.
export const FACE = Object.freeze({
  width: 1024, height: 1440,
  window: Object.freeze({ x: 32, y: 136, width: 960, height: 1090, radius: 52 }),
  footer: Object.freeze({ y: 1226, bottom: 1410 }),
  emblemY: 136 + 1090 * .4
});

// Front: light (ProfileCard's light theme, the foil multiplies); back: dark (the foil glows).
export const THEMES = Object.freeze({
  // ink: the rosette's print (linear RGB); the foil then tints or lights it.
  light: Object.freeze({ wall: [0.47, 0.49, 0.53], holo: 0.8, glow: 0, ink: [0.09, 0.15, 0.18], inkAmount: .72 }),
  dark: Object.freeze({ wall: [0.012, 0.03, 0.035], holo: 0.8, glow: 1, ink: [0.38, 0.69, 0.62], inkAmount: .6 })
});

const DEPTH = 0.085; // how far the print sits below the surface, world units

const FRAGMENT_HEAD = `
uniform vec2 hostCard;
uniform vec4 hostWindow;
uniform float hostRadius;
uniform float hostDepth;
uniform vec3 hostWall;
uniform float hostHolo;
uniform float hostGlow;
uniform vec2 hostEmblem;
uniform vec4 hostSeed;
uniform vec4 hostShape;
uniform vec3 hostInkColor;
uniform float hostInkAmount;

float hostBox(vec2 p) {
  vec2 q = abs(p - hostWindow.xy) - hostWindow.zw + hostRadius;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - hostRadius;
}

vec3 hostHue(float h) {
  vec3 k = clamp(abs(fract(h + vec3(0.0, 2.0, 1.0) / 3.0) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
  return mix(vec3(1.0), k, 0.55);
}

float hostLine(float d, float width, float aa) {
  return 1.0 - smoothstep(width - aa, width + aa, abs(d));
}

// The card's signature, after security printing: a guilloche rosette as the centrepiece,
// and faint interlaced guilloche bands across the window. Its shape (petals, harmonics,
// phase, wavelengths) comes from the active profile, so every profile has its own.
const float HOST_ROSETTE = 0.36;

float hostRosette(vec2 p) {
  vec2 q = (p - hostEmblem) / HOST_ROSETTE;
  float r = length(q);
  float t = atan(q.y, q.x);
  float rosette = 0.0;
  for (int j = 0; j < 7; j++) {
    float fj = float(j);
    float amp = 0.045 + 0.006 * fj;
    float ring = 0.2 + 0.13 * fj + amp * sin(hostSeed.x * t + hostSeed.z + fj * 0.7)
      + amp * 0.3 * sin(hostSeed.x * hostSeed.y * t - fj * 1.3);
    float d = r - ring;
    rosette = max(rosette, hostLine(d, 0.011, max(fwidth(d), 1e-4)));
  }
  // A small rose at the heart, so the centre is not empty.
  float heart = r - 0.11 * (0.55 + 0.45 * cos(hostSeed.x * t + hostSeed.z));
  rosette = max(rosette, hostLine(heart, 0.011, max(fwidth(heart), 1e-4)));
  return rosette * (1.0 - smoothstep(1.02, 1.1, r));
}

float hostBands(vec2 p) {
  float band = 0.0;
  for (int j = 0; j < 2; j++) {
    float side = j == 0 ? 1.0 : -1.0;
    float v = p.y * hostShape.z + side * 0.32 * sin(p.x * 6.2832 * hostShape.x + hostSeed.z)
      + 0.14 * sin(p.x * 6.2832 * hostShape.y - side * hostSeed.z);
    band = max(band, hostLine(fract(v + 0.5) - 0.5, 0.05, max(fwidth(v), 1e-4)));
  }
  return band * smoothstep(1.0, 1.25, length((p - hostEmblem) / HOST_ROSETTE));
}
`;

const MAP_BODY = `
float hostIn = 0.0;
float hostSeen = 0.0;
vec2 hostUv = vec2(0.0);
vec3 hostN = vec3(0.0, 0.0, 1.0);
vec3 hostT = vec3(1.0, 0.0, 0.0);
vec3 hostB = vec3(0.0, 1.0, 0.0);
float hostInk = 0.0;
float hostMask = 0.0;
#ifdef USE_MAP
{
  // Computed everywhere (no branch), so screen-space derivatives and mipmaps stay defined.
  vec2 cardP = vMapUv * hostCard;
  float edge = hostBox(cardP);
  float aa = max(fwidth(edge), 1e-5);
  hostIn = 1.0 - smoothstep(-aa, aa, edge);
  {
    vec3 q0 = dFdx(-vViewPosition), q1 = dFdy(-vViewPosition);
    vec2 s0 = dFdx(vMapUv), s1 = dFdy(vMapUv);
    float det = s0.x * s1.y - s1.x * s0.y;
    vec3 du = det == 0.0 ? vec3(hostCard.x, 0.0, 0.0) : (q0 * s1.y - q1 * s0.y) / det;
    vec3 dv = det == 0.0 ? vec3(0.0, hostCard.y, 0.0) : (q1 * s0.x - q0 * s1.x) / det;
    hostN = normalize(cross(q0, q1));
    hostT = normalize(du); hostB = normalize(dv);
    vec3 eye = normalize(vViewPosition);
    float facing = max(dot(eye, hostN), 0.35);
    // Ray into the recess: where it meets the print, hostDepth below the surface.
    vec3 slide = -(eye - hostN * dot(eye, hostN)) * hostDepth / facing;
    hostUv = vMapUv + vec2(dot(slide, du) / dot(du, du), dot(slide, dv) / dot(dv, dv));
    float inner = hostBox(hostUv * hostCard);
    float wall = smoothstep(-0.004, 0.004, inner);
    hostSeen = hostIn * (1.0 - wall);
    vec4 print = texture2D(map, hostUv);
    // The frame shades the print near its edges, most from above (light comes from the top).
    float shade = mix(0.7, 1.0, smoothstep(0.0, 0.07, -inner));
    float top = hostWindow.y + hostWindow.w - hostUv.y * hostCard.y;
    shade *= mix(0.82, 1.0, smoothstep(0.0, 0.16, top));
    vec3 recess = mix(print.rgb * shade, hostWall, wall);
    hostInk = dot(print.rgb, vec3(0.2126, 0.7152, 0.0722));
    // The rosette is printed in ink (so it reads at rest), and the foil shimmers on it.
    vec2 sig = hostUv * hostCard / hostCard.x;
    float rose = hostRosette(sig) * (1.0 - wall);
    recess = mix(recess, hostInkColor * shade, rose * hostInkAmount);
    hostMask = 0.12 + 0.88 * max(rose, hostBands(sig) * 0.45);
    diffuseColor.rgb = mix(diffuseColor.rgb, diffuse * recess, hostIn);
  }
}
#endif
`;

const OPAQUE_BODY = `
if (hostHolo > 0.0 && hostSeen > 0.0) {
  vec3 eye = normalize(vViewPosition);
  // How the card is turned toward the eye drives the foil, like ProfileCard's pointer.
  vec2 tilt = vec2(dot(eye, hostT), dot(eye, hostB));
  vec2 p = hostUv * hostCard / hostCard.x;
  vec3 spectrum = hostHue(fract(p.y * 0.85 + tilt.y * 1.7 + tilt.x * 0.45 + hostSeed.w));
  float bar = fract((p.x - p.y) * 2.4 + tilt.x * 1.9 + tilt.y * 0.7);
  float bars = 0.55 + 0.45 * smoothstep(0.5, 0.0, abs(bar - 0.5));
  // It lights up where the key light reflects toward the eye.
  float catchLight = pow(max(dot(normalize(foilKey + eye), hostN), 0.0), 14.0);
  float lit = clamp(0.22 + 1.5 * length(tilt) + 0.35 * catchLight, 0.0, 1.0);
  float mask = hostMask;
  vec3 holo = spectrum * bars;
  if (hostGlow > 0.5) {
    // Dark face: the foil glows (color dodge), but not over the bright text.
    float amount = hostHolo * hostSeen * mask * lit * (1.0 - smoothstep(0.1, 0.32, hostInk));
    outgoingLight += (holo * 0.3 + outgoingLight * holo * 0.9) * amount;
  } else {
    // Light face: the foil tints the white print (multiply), like ProfileCard's light theme.
    float amount = hostHolo * hostSeen * mask * lit;
    outgoingLight *= mix(vec3(1.0), holo * 0.95, amount);
  }
}
`;

// Every installed face, so the signature can follow the active profile.
const installed = [];
let current = signature('');

// FNV-1a, so the same profile always gets the same signature.
function hash(text) {
  let h = 0x811c9dc5;
  for (const c of String(text)) h = Math.imul(h ^ c.codePointAt(0), 0x01000193) >>> 0;
  return h;
}

// Signature parameters: petals, harmonic, phase and hue shift; band wavelengths and density.
export function signature(text) {
  const h = hash(text || 'PingClash');
  return {
    seed: [5 + h % 7, 2 + (h >>> 3) % 3, ((h >>> 6) & 255) / 255 * Math.PI * 2, ((h >>> 20) & 255) / 255],
    shape: [1.5 + ((h >>> 14) & 15) / 15 * 2.5, 3 + ((h >>> 24) & 7) / 7 * 3, 15 + ((h >>> 11) & 7), 0]
  };
}

export function setSignature(text) {
  current = signature(text);
  const { seed, shape } = current;
  for (const uniforms of installed) {
    uniforms.hostSeed.value.set(...seed);
    uniforms.hostShape.value.set(...shape);
  }
}

// Chain the host layer after the upstream foil; returns a function that updates the
// card size when the layout changes.
export function installHostFace(THREE, materials) {
  const first = current;
  const windowRect = (width, height) => {
    const w = FACE.window, sx = width / FACE.width, sy = height / FACE.height;
    const halfX = w.width / 2 * sx, halfY = w.height / 2 * sy;
    return new THREE.Vector4((w.x + w.width / 2) * sx, height - (w.y + w.height / 2) * sy, halfX, halfY);
  };
  const all = materials.map(({ material, theme }) => {
    const look = THEMES[theme];
    const uniforms = {
      hostCard: { value: new THREE.Vector2(1.6, 2.25) },
      hostWindow: { value: windowRect(1.6, 2.25) },
      hostRadius: { value: FACE.window.radius / FACE.width * 1.6 },
      hostDepth: { value: DEPTH },
      hostWall: { value: new THREE.Vector3(...look.wall) },
      hostHolo: { value: look.holo },
      hostGlow: { value: look.glow },
      // The rosette's centre, in card widths from the bottom-left (see paintCard).
      hostEmblem: { value: new THREE.Vector2(.5, (FACE.height - FACE.emblemY) / FACE.width) },
      hostSeed: { value: new THREE.Vector4(...first.seed) },
      hostShape: { value: new THREE.Vector4(...first.shape) },
      hostInkColor: { value: new THREE.Vector3(...look.ink) },
      hostInkAmount: { value: look.inkAmount }
    };
    installed.push(uniforms);
    if (installed.length > 8) installed.shift();
    const upstream = material.onBeforeCompile;
    material.onBeforeCompile = (shader, renderer) => {
      upstream.call(material, shader, renderer);
      Object.assign(shader.uniforms, uniforms);
      shader.fragmentShader = shader.fragmentShader
        .replace('#include <common>', `#include <common>\n${FRAGMENT_HEAD}`)
        .replace('#include <map_fragment>', `#include <map_fragment>\n${MAP_BODY}`)
        .replace('#include <opaque_fragment>', `${OPAQUE_BODY}\n#include <opaque_fragment>`);
    };
    material.needsUpdate = true;
    return uniforms;
  });
  return layout => {
    for (const uniforms of all) {
      uniforms.hostCard.value.set(layout.width, layout.height);
      uniforms.hostWindow.value.copy(windowRect(layout.width, layout.height));
      uniforms.hostRadius.value = FACE.window.radius / FACE.width * layout.width;
    }
  };
}
