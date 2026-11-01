// SPDX-License-Identifier: GPL-3.0-only
// Pixel-footprint filtered waves, lit domain-warped clouds and a soft bright sky;
// no image or video input. A NumPy reference of this shader was used to tune it.
@file:Suppress("MagicNumber")

package dev.pingclash.app

import android.graphics.Paint
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics

@RequiresApi(33)
@Composable
internal fun RealtimeOcean(modifier: Modifier, clouds: Boolean) {
    val shader = remember { RuntimeShader(OCEAN_SHADER) }
    val paint = remember(shader) { Paint().apply { this.shader = shader } }
    val active = LocalMotionAllowed.current && rememberForeground()
    // Clouds and swell move slowly: redraw at most 30 times a second rather than every
    // display frame (often 90-120 Hz). Halves or better the GPU work, including the glass
    // navigation that re-samples this layer; time keeps flowing across pauses.
    val time = remember { mutableFloatStateOf(0f) }
    if (active)
        LaunchedEffect(Unit) {
            val origin = withFrameNanos { it } - (time.floatValue * 1e9).toLong()
            var shown = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - shown >= SCENE_FRAME_NANOS) {
                        shown = now
                        time.floatValue = ((now - origin) / 1e9 % SCENE_LOOP_SECONDS).toFloat()
                    }
                }
            }
        }
    val depth = LocalSceneDepth.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Canvas(
        modifier.testTag("ocean-backdrop").semantics {
            this[DecorativeEffectRunning] = active
            this[SceneDiveProgress] = depth.floatValue
        }
    ) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("clock", time.floatValue)
        shader.setFloatUniform("night", if (dark) 1f else 0f)
        shader.setFloatUniform("cloudOnly", if (clouds) 1f else 0f)
        shader.setFloatUniform("dive", depth.floatValue.coerceIn(0f, 1f))
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }
}

private const val SCENE_FRAME_NANOS = 33_000_000L
private const val SCENE_LOOP_SECONDS = 125.663706

internal const val OCEAN_SHADER =
    """
uniform float2 resolution;
uniform float clock;
uniform float night;
uniform float cloudOnly;
uniform float dive;
// normalize(.10, .26, 1.0)
const float3 SUN = float3(.09633, .25046, .96332);
float hash(float2 p) {return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453);}
float noise(float2 p) {
 float2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);
 return mix(mix(hash(i),hash(i+float2(1,0)),f.x),mix(hash(i+float2(0,1)),hash(i+float2(1,1)),f.x),f.y);
}
// Rotated octaves; any octave finer than a pixel (fw) fades out, so clouds and
// their reflections never alias into shimmer, streaks or moire rings.
float fbm(float2 p,float fw) {
 p=float2(.866*p.x-.5*p.y,.5*p.x+.866*p.y);
 float v=0.,a=.5,f=1.;
 for(int i=0;i<5;i++){
  v+=a*noise(p)*(1.-smoothstep(.35,1.,f*fw));
  p=float2(1.6*p.x-1.2*p.y+3.1,1.2*p.x+1.6*p.y-1.7);a*=.5;f*=2.;
 }
 return v;
}
float fbm3(float2 p,float fw) {
 p=float2(.866*p.x-.5*p.y,.5*p.x+.866*p.y);
 float v=0.,a=.5,f=1.;
 for(int i=0;i<3;i++){
  v+=a*noise(p)*(1.-smoothstep(.35,1.,f*fw));
  p=float2(1.6*p.x-1.2*p.y+3.1,1.2*p.x+1.6*p.y-1.7);a*=.5;f*=2.;
 }
 return v;
}
float3 baseSky(float3 r) {
 float h=sqrt(clamp(r.y,0.,1.));
 float3 day=mix(float3(.90,.95,.97),float3(.47,.70,.89),h);
 float3 dark=mix(float3(.16,.23,.32),float3(.03,.06,.12),h);
 float s=max(dot(r,SUN),0.);
 float halo=pow(s,24.)*.10+pow(s,160.)*.28;
 return mix(day,dark,night)+halo*mix(float3(1.,.93,.80),float3(.30,.36,.46),night);
}
float3 sky(float3 r) {
 float3 c=baseSky(r);
 float y=max(r.y,.03);
 float2 p=float2(r.x/y*2.+clock*.012,r.z/y*2.+clock*.004);
 float fw=4./(y*y*resolution.y);
 float2 w=float2(fbm3(p*.5+float2(4.,0.),fw*.5),fbm3(p*.5-float2(0.,2.),fw*.5));
 float2 q=p+1.3*w;
 float d=fbm(q,fw);
 float lit=fbm3(q+float2(SUN.x*.35,SUN.z*.105),fw);
 float cover=smoothstep(.36,.62,d)*smoothstep(.02,.20,r.y);
 float shade=clamp(.62+(d-lit)*2.6,0.,1.);
 float3 cc=mix(mix(float3(.78,.84,.91),float3(.97,.975,.97),shade),
  mix(float3(.12,.16,.23),float3(.30,.36,.46),shade),night);
 float s=max(dot(r,SUN),0.);
 cc+=pow(s,12.)*.10*(1.-night);
 c=mix(c,cc,cover*.88);
 return c+pow(s,900.)*1.4*mix(float3(1.,.95,.85),float3(.40,.45,.55),night)*(1.-cover);
}
// Reflected sky on the water: same deck and light, without warp or self-shading.
float3 skyLite(float3 r) {
 float3 c=baseSky(r);
 float y=max(r.y,.03);
 float2 p=float2(r.x/y*2.+clock*.012,r.z/y*2.+clock*.004);
 float d=fbm3(p+float2(.6,-.4),4./(y*y*resolution.y));
 float cover=smoothstep(.36,.62,d)*smoothstep(.02,.20,r.y);
 return mix(c,mix(float3(.92,.94,.96),float3(.24,.30,.40),night),cover*.8);
}
// Directional waves at golden-angle headings break up any repeating pattern.
// Each fades once finer than about two pixels, so the far sea becomes a calm
// mirror of the sky instead of aliasing into coloured rings.
// Headings advance by the golden angle (rotation by cos/sin 2.39996) and the deep-water speed
// sqrt(9.8k) grows by sqrt(1.47) per octave, so no trig or sqrt is needed per wave.
float2 turn(float2 u) {return float2(u.x*-.737369-u.y*.675463,u.x*.675463+u.y*-.737369);}
float waveHeight(float2 p,float fw) {
 float h=0.,amp=.11,k=.62,w=2.46495;float2 u=float2(.921061,.389418);
 for(int i=0;i<3;i++){
  h+=amp*(1.-smoothstep(.3,1.,k*fw))*sin(dot(p,u)*k-clock*w*.55);
  u=turn(u);amp*=.66;k*=1.47;w*=1.212436;
 }
 return h;
}
float2 waveSlope(float2 p,float fw) {
 float2 s=float2(0.);float amp=.11,k=.62,w=2.46495;float2 u=float2(.921061,.389418);
 for(int i=0;i<11;i++){
  s+=amp*(1.-smoothstep(.3,1.,k*fw))*k*u*cos(dot(p,u)*k-clock*w*.55);
  u=turn(u);amp*=.66;k*=1.47;w*=1.212436;
 }
 return s;
}
half4 main(float2 pixel) {
 float2 uv=(pixel/resolution-.5)*float2(resolution.x/resolution.y,1.);
 float3 ray=normalize(float3(uv.x,(.36-dive*.3)-pixel.y/resolution.y,1.05));
 float3 color;
 if(cloudOnly>.5) {
  // Looking up at the cloud deck: overhead at the top, horizon at the bottom.
  color=sky(normalize(float3(ray.x,.16+.80*(1.-pixel.y/resolution.y),ray.z)));
 } else if(ray.y>=0.) {
  color=sky(ray);
 } else {
  float down=max(.012,-ray.y);
  float dist=min(140.,1.6/down);
  for(int i=0;i<4;i++){
   float h0=waveHeight(ray.xz*dist,dist/(resolution.y*down)*1.2);
   dist=mix(dist,clamp((1.6-h0)/down,0.,140.),.5);
  }
  float fw=dist/(resolution.y*down)*1.2;
  float2 sl=waveSlope(ray.xz*dist,fw);
  float3 n=normalize(float3(-sl.x,1.,-sl.y));
  float dn=dot(ray,n);
  float3 refl=ray-2.*dn*n;
  float fres=.02+.98*pow(1.-max(-dn,0.),5.);
  float3 deep=mix(float3(.09,.36,.45),float3(.01,.05,.09),night);
  float3 shallow=mix(float3(.30,.66,.70),float3(.03,.09,.14),night);
  float lift=clamp(n.x*SUN.x+n.z*SUN.z*.3+.5,0.,1.);
  float3 water=mix(deep,shallow,lift*.55);
  float3 rs=skyLite(float3(refl.x,max(abs(refl.y),.02),refl.z));
  color=mix(water,rs*.92,fres*.80+.05);
  float spec=max(-dot(reflect(-SUN,n),ray),0.);
  float rough=smoothstep(4.,70.,dist);
  color+=pow(spec,mix(240.,36.,rough))*mix(.60,.22,rough)*mix(float3(1.,.95,.82),float3(.35,.40,.50),night);
  color=mix(color,baseSky(float3(ray.x,.02,ray.z)),smoothstep(14.,120.,dist)*.85);
 }
 color=mix(color,float3(.03,.20,.27),dive*.7);
 color*=1.-.05*length(uv);
 // Dither so the soft gradients never band into visible rings.
 color+=(hash(pixel)-.5)/255.;
 return half4(clamp(color,0.,1.),1.);
}
"""
