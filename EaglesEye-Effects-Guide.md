# EaglesEye Effects Implementation Guide
### AGSL/GLSL, Jetpack Compose, full-speed Android — 31 effects (18 shipped set + 13 industry-standard + 8 novel)

---

## 0. Architecture decision — read this first

**Compose `RenderEffect`/AGSL does not touch your capture path.** Confirmed against Android's own docs: `Modifier.graphicsLayer` rasterizes composable content into an *offscreen UI buffer* as part of View/Canvas compositing. Your JPEG/video capture in Camera2/CameraX writes to a separate `Surface` (`ImageReader`/codec) that the camera HAL feeds directly — Compose never sees those bytes unless you deliberately re-route them through a Composable, which is the wrong call for a real capture pipeline (you'd be re-encoding a UI-composited bitmap instead of the actual sensor frame).

**Conclusion:** every effect that must land in the *saved* photo/video goes into `CameraGLRenderer`'s GL ping-pong chain as GLSL ES — same pattern as your existing `BokehEngine`/`FilmProcessor`. Reserve Compose `RuntimeShader`/`RenderEffect` for on-screen-only UI chrome (frosted sheet backgrounds, animated glow on the shutter button) — never for anything that needs to be in the output file.

AGSL is a SkSL dialect that "shares much of its syntax with GLSL fragment shaders" (Android Developers). So: every shader below is written once, in AGSL, for fast iteration in a Compose preview harness — then mechanically ported to GLSL ES for the real GL pass using the table in §1. AGSL isn't inherently faster or slower than hand-written GLSL — speed comes from pass count, resolution, and precision (§2), not the surface language.

---

## 1. AGSL ⇄ GLSL ES porting table

| AGSL | GLSL ES equivalent |
|---|---|
| `uniform shader tex;` | `uniform sampler2D tex;` |
| `tex.eval(coord)` (pixel-space) | `texture(tex, uv)` — ES 3.0, `uv` normalized 0..1 |
| `half`, `half4` | `mediump float`, `mediump vec4` |
| `float2/3/4` | `vec2/3/4` |
| `half4 main(float2 fragCoord)` | `void main()` + `out vec4 fragColor` (ES 3.0), sample via a `varying`/`in vec2 vUV` instead of raw pixel coords |
| *(none needed)* | `precision mediump float;` required at top |
| `layout(color) uniform vec4 c;` | plain `uniform vec4 c;` (you manage color space) |
| coords in pixels (`fragCoord`) | typically UV (0..1) via varying — convert with `uResolution` |

---

## 2. Performance framework — applies to all 31 effects, read once

1. **Resolution-scope blur passes.** Bloom, Halation, Tilt-Shift, Mist run their blur at ½ or ¼ target size, upsampled on composite (linear filter). Blurred content hides the downsample; you cut fragment count 4–16×.
2. **`mediump`/`half` everywhere** except UV math needing sub-pixel precision (fisheye/tilt-shift trig, LUT indexing). Adreno/Mali are meaningfully faster in half-precision.
3. **Compile once, bind per frame.** Never call `RuntimeShader(src)` or `glCompileShader` inside the render loop — cache the instance, touch only `setFloatUniform`/`glUniform*` per frame.
4. **Budget passes like frame-time, not a checklist.** A full-res 1080p pass costs roughly 0.3–0.8ms on a mid-range Adreno 6xx-class GPU. You have ~16.6ms/frame at 60fps total, including camera HAL + Compose UI. Treat 6–10 active passes as a reasonable ceiling.
5. **Merge passes where the math allows.** Bloom, Halation, and Mist all reduce to *threshold → blur → composite*. Run **one** bright-pass+blur chain, recomposite it three different ways, instead of three separate chains.
6. **Verify, don't guess.** Android GPU Inspector (AGI) for frame-by-frame shader cost; `adb shell dumpsys gfxinfo <pkg> framestats` for on-device per-frame timing; Perfetto's FrameTimeline (Android 12+) for jank attribution. Wrap `glBeginQuery(GL_TIME_ELAPSED,…)` around your chain in debug builds to log real GPU µs per pass.

---

## 3. Reusable wiring (define once, reuse for all 31)

**Compose / AGSL side:**

```kotlin
fun Modifier.agslEffect(
    shader: RuntimeShader,
    setUniforms: RuntimeShader.(width: Float, height: Float) -> Unit
): Modifier = this.graphicsLayer {
    shader.setUniforms(size.width, size.height)
    renderEffect = RenderEffect
        .createRuntimeShaderEffect(shader, "inputImage")
        .asComposeRenderEffect()
}

// usage
val fisheyeShader = remember { RuntimeShader(FISHEYE_AGSL) }
Modifier.agslEffect(fisheyeShader) { w, h ->
    setFloatUniform("uResolution", w, h)
    setFloatUniform("uStrength", intensity)
}
```

**GL pipeline side** (slots into your existing ping-pong chain the same way `FilmProcessor`'s passes work):

```kotlin
class ShaderPass(fragmentSrc: String) {
    private val program = compileProgram(VERTEX_PASSTHROUGH, fragmentSrc)
    private val uniformCache = mutableMapOf<String, Int>()
    private fun loc(name: String) = uniformCache.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    fun draw(inputTex: Int, outputFbo: Int, setUniforms: ShaderPass.() -> Unit) {
        GLES30.glUseProgram(program)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outputFbo)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTex)
        setUniforms()
        drawFullscreenQuad()
    }
    fun setFloat(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun setFloat2(name: String, x: Float, y: Float) = GLES30.glUniform2f(loc(name), x, y)
}
```

Every effect below = one fragment shader dropped into one `ShaderPass`. `inputImage`/`uTexture` is always the previous pass's output.

---

## 4. Master reference table

| # | Effect | Cat | Passes | Res | Cost | Depth? | Sensor? |
|---|---|---|---|---|---|---|---|
| 1 | Fisheye | LENS | 1 | full | Cheap | – | – |
| 2 | Tilt-Shift (fake) | LENS | blur + 1 | ½/full | Medium | – | – |
| 3 | Vignette | LENS | 1 (fusable) | full | Cheap | – | – |
| 4 | Anamorphic | LENS | kernel + 3–5 | ¼ | Medium | – | – |
| 5 | Wide-angle sim | LENS | 1 | full | Cheap | – | – |
| 6 | 180° warp | LENS | 1 | full | Cheap | – | – |
| 7 | Prism | LENS | 1 | full | Cheap | – | – |
| 8 | Light leaks | LIGHT | 1 | full | Cheap | – | – |
| 9 | Lens flare | LIGHT | 1 | full | Cheap | – | – |
| 10 | Bloom | LIGHT | thresh+blur+1 | ¼ | Medium | – | – |
| 11 | Halation | LIGHT | shares #10 | ¼ | Cheap* | – | – |
| 12 | Chromatic aberration | LIGHT | 1 | full | Cheap | – | – |
| 13 | Glitch/RGB shift | LIGHT | 1 | full | Cheap | – | – |
| 14 | Film emulation/LUT | COLOR | 1 | full | Cheap | – | – |
| 15 | Duotone | COLOR | 1 (fusable) | full | Cheap | – | – |
| 16 | Bleach bypass | COLOR | 1 (fusable) | full | Cheap | – | – |
| 17 | Cross-process | COLOR | 1 (fusable) | full | Cheap | – | – |
| 18 | B&W + grain | COLOR/TEX | 1+grain | full | Cheap | – | – |
| 19 | Mist | COLOR | shares blur+1 | ¼/full | Medium | – | – |
| 20 | Grain | TEX | 1 | full | Cheap | – | – |
| 21 | Dust/scratches | TEX | 1 | full | Cheap | – | – |
| 22 | VHS scanlines | TEX | 1 | full | Cheap | – | – |
| 23 | Double exposure | TEX | 1 | full | Cheap | – | – |
| 24 | Depth-aware halation | NOVEL | shares #10/11 | ¼ | Cheap* | ✅ | – |
| 25 | Motion-reactive grain | NOVEL | 0 new | – | Free | – | gyro |
| 26 | True focal-plane tilt-shift | NOVEL | shares #2 | ½/full | Medium | ✅ | – |
| 27 | Adaptive light-leak dir. | NOVEL | +1 reduction | 4×4 | Free | – | scene luma |
| 28 | Temp-reactive stock | NOVEL | 0 new | – | Free | – | clock/GPS |
| 29 | Chromatic depth separation | NOVEL | shares #12 | full | Cheap | ✅ | – |
| 30 | Depth-occluded double exp. | NOVEL | shares #23 | full | Cheap | ✅ | – |
| 31 | Sensor-matched grain | NOVEL | 0 new | – | Free | – | ISO (existing) |

---

## 5. LENS

### 5.1 Fisheye
Radial barrel distortion, `pow(r, 1+strength)`.
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uStrength;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 c = uv * 2.0 - 1.0;
    c.x *= uResolution.x / uResolution.y;
    float r = length(c);
    float theta = atan(c.y, c.x);
    float rD = pow(r, 1.0 + uStrength);
    float2 d = float2(cos(theta), sin(theta)) * rD;
    d.x /= uResolution.x / uResolution.y;
    float2 uv2 = d * 0.5 + 0.5;
    if (uv2.x < 0.0 || uv2.x > 1.0 || uv2.y < 0.0 || uv2.y > 1.0) return half4(0,0,0,1);
    return inputImage.eval(uv2 * uResolution);
}
```

### 5.2 Tilt-Shift (fake, screen-space band)
Composite sharp + pre-blurred image by a triangular Y-band mask.
```glsl
uniform shader sharpImage;
uniform shader blurredImage;
uniform float2 uResolution;
uniform float uFocusY, uFocusWidth, uFeather;
half4 main(float2 fragCoord) {
    float uvY = fragCoord.y / uResolution.y;
    float blend = smoothstep(uFocusWidth, uFocusWidth + uFeather, abs(uvY - uFocusY));
    return mix(sharpImage.eval(fragCoord), blurredImage.eval(fragCoord), blend);
}
```

### 5.3 Vignette
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uRadius, uSoftness, uIntensity;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 c = (uv - 0.5) * float2(uResolution.x / uResolution.y, 1.0);
    float vig = 1.0 - smoothstep(uRadius, uRadius + uSoftness, length(c)) * uIntensity;
    half4 col = inputImage.eval(fragCoord);
    return half4(col.rgb * vig, col.a);
}
```

### 5.4 Anamorphic
Horizontal streak flare from a bright-pass buffer:
```glsl
uniform shader brightPass;
uniform float2 uResolution;
uniform float uStreakLength;
uniform half3 uTint;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    half3 sum = half3(0.0);
    const int SAMPLES = 12;
    for (int i = 0; i < SAMPLES; i++) {
        float t = float(i) / float(SAMPLES - 1);
        float off = (t - 0.5) * uStreakLength;
        sum += brightPass.eval(float2((uv.x + off) * uResolution.x, fragCoord.y)).rgb * (1.0 - abs(t - 0.5) * 2.0);
    }
    return half4(sum / (float(SAMPLES) * 0.5) * uTint, 1.0);
}
```

### 5.5 Wide-angle / ultra-wide simulation
Mild pincushion (`pow(r, 1/(1+strength))`, strength 0.1–0.3).
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uStrength;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 c = uv * 2.0 - 1.0;
    c.x *= uResolution.x / uResolution.y;
    float r = length(c);
    float theta = atan(c.y, c.x);
    float rD = pow(r, 1.0 / (1.0 + uStrength));
    float2 d = float2(cos(theta), sin(theta)) * rD;
    d.x /= uResolution.x / uResolution.y;
    float2 uv2 = d * 0.5 + 0.5;
    if (uv2.x < 0.0 || uv2.x > 1.0 || uv2.y < 0.0 || uv2.y > 1.0) return half4(0,0,0,1);
    return inputImage.eval(uv2 * uResolution);
}
```

### 5.6 180° / spherical warp ("little planet")
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uZoom, uRotation;
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord / uResolution) * 2.0 - 1.0;
    uv.x *= uResolution.x / uResolution.y;
    float r = length(uv);
    float theta = atan(uv.y, uv.x) + uRotation;
    float rM = tan(r * uZoom);
    float2 s = float2(cos(theta), sin(theta)) * rM;
    s.x /= uResolution.x / uResolution.y;
    s = s * 0.5 + 0.5;
    if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) return half4(0,0,0,1);
    return inputImage.eval(s * uResolution);
}
```

### 5.7 Prism
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uAngle, uOffset, uOpacity;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 dir = float2(cos(uAngle), sin(uAngle));
    half4 base = inputImage.eval(fragCoord);
    half4 g1 = inputImage.eval((uv + dir * uOffset) * uResolution);
    half4 g2 = inputImage.eval((uv - dir * uOffset * 0.6) * uResolution);
    half3 c = mix(base.rgb, g1.rgb, uOpacity);
    c = mix(c, g2.rgb, uOpacity * 0.6);
    return half4(c, base.a);
}
```

---

## 6. LIGHT

### 6.8 Light leaks
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uTime;
uniform float2 uEntryPoint;
uniform half3 uLeakColor;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float pulse = 0.85 + 0.15 * sin(uTime * 0.6);
    float leak = pow(1.0 - smoothstep(0.0, 0.9 * pulse, distance(uv, uEntryPoint)), 2.2);
    half4 c = inputImage.eval(fragCoord);
    half3 screened = 1.0 - (1.0 - c.rgb) * (1.0 - uLeakColor * leak);
    return half4(screened, c.a);
}
```

### 6.9 Lens flare
```glsl
uniform shader inputImage;
uniform float2 uResolution, uLightPos;
uniform float uIntensity;
half3 ghost(float2 uv, float2 pos, float size, half3 tint) {
    return tint * smoothstep(size, 0.0, length(uv - pos));
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 toCenter = float2(0.5) - uLightPos;
    half3 flare = ghost(uv, uLightPos + toCenter * 0.3, 0.05, half3(1.0,0.9,0.7))
                + ghost(uv, uLightPos + toCenter * 0.6, 0.03, half3(0.6,0.8,1.0))
                + ghost(uv, uLightPos + toCenter * 1.0, 0.08, half3(1.0,1.0,0.9))
                + ghost(uv, uLightPos + toCenter * 1.4, 0.02, half3(0.9,0.6,1.0));
    half4 c = inputImage.eval(fragCoord);
    return half4(c.rgb + flare * uIntensity, c.a);
}
```

### 6.10 Bloom
```glsl
// pass A — bright-pass
uniform shader inputImage;
uniform float uThreshold;
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    float luma = dot(c.rgb, half3(0.2126,0.7152,0.0722));
    float w = max(luma - uThreshold, 0.0) / max(luma, 0.0001);
    return half4(c.rgb * w, 1.0);
}
```
```glsl
// pass C — composite
uniform shader sharpImage, bloomBlur;
uniform float uIntensity;
half4 main(float2 fragCoord) {
    half4 s = sharpImage.eval(fragCoord);
    half4 b = bloomBlur.eval(fragCoord);
    return half4(s.rgb + b.rgb * uIntensity, s.a);
}
```

### 6.11 Halation
```glsl
uniform shader sharpImage, halationBlur;
uniform half3 uTint;
uniform float uIntensity;
half4 main(float2 fragCoord) {
    half4 s = sharpImage.eval(fragCoord);
    half4 h = halationBlur.eval(fragCoord);
    return half4(s.rgb + h.rgb * uTint * uIntensity, s.a);
}
```

### 6.12 Chromatic aberration
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uStrength;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float2 dir = (uv - 0.5) * uStrength;
    half r = inputImage.eval((uv + dir) * uResolution).r;
    half4 g = inputImage.eval(uv * uResolution);
    half b = inputImage.eval((uv - dir) * uResolution).b;
    return half4(r, g.g, b, g.a);
}
```

### 6.13 Glitch / RGB shift
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uTime, uIntensity;
float hash(float n) { return fract(sin(n) * 43758.5453); }
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float block = floor(uv.y * 40.0 + uTime * 5.0);
    float jitter = (hash(block) - 0.5) * 0.05 * uIntensity * step(0.85, hash(block * 1.37));
    float2 uvJ = float2(uv.x + jitter, uv.y);
    half r = inputImage.eval(float2((uvJ.x + 0.01 * uIntensity) * uResolution.x, uvJ.y * uResolution.y)).r;
    half4 g = inputImage.eval(uvJ * uResolution);
    half b = inputImage.eval(float2((uvJ.x - 0.01 * uIntensity) * uResolution.x, uvJ.y * uResolution.y)).b;
    return half4(r, g.g, b, 1.0);
}
```

---

## 7. COLOR

### 7.14 Film emulation / 3D LUT
```glsl
uniform shader inputImage, lutTexture;
uniform float uLutN;
uniform float uIntensity;
half3 sampleLut(half3 c) {
    float scale = uLutN - 1.0;
    float bIdx = c.b * scale;
    float z0 = floor(bIdx), z1 = min(z0 + 1.0, scale), zf = fract(bIdx);
    float2 px = float2(0.5) + c.rg * scale;
    half3 s0 = lutTexture.eval(float2(px.x + z0 * uLutN, px.y)).rgb;
    half3 s1 = lutTexture.eval(float2(px.x + z1 * uLutN, px.y)).rgb;
    return mix(s0, s1, zf);
}
half4 main(float2 fragCoord) {
    half4 src = inputImage.eval(fragCoord);
    half3 graded = sampleLut(clamp(src.rgb, 0.0, 1.0));
    return half4(mix(src.rgb, graded, uIntensity), src.a);
}
```

### 7.15 Duotone / split-tone
```glsl
uniform shader inputImage;
uniform half3 uShadowColor, uHighlightColor;
uniform float uIntensity;
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    float luma = dot(c.rgb, half3(0.2126,0.7152,0.0722));
    half3 toned = mix(uShadowColor, uHighlightColor, luma);
    return half4(mix(c.rgb, toned, uIntensity), c.a);
}
```

### 7.16 Bleach bypass
```glsl
uniform shader inputImage;
uniform float uIntensity;
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    float luma = dot(c.rgb, half3(0.2126,0.7152,0.0722));
    half3 contrasted = (half3(luma) - 0.5) * 1.6 + 0.5;
    half3 result = mix(c.rgb, contrasted, 0.5);
    return half4(mix(c.rgb, result, uIntensity), c.a);
}
```

### 7.17 Cross-process
```glsl
uniform shader inputImage;
uniform float uIntensity;
half curve(half x, half lift, half g) { return pow(mix(x, x + lift * (1.0 - x), 0.5), g); }
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    half3 result = half3(curve(c.r, 0.02, 1.05), curve(c.g, 0.08, 0.85), curve(c.b, -0.05, 1.2));
    return half4(mix(c.rgb, result, uIntensity), c.a);
}
```

### 7.18 Black & white + grain
```glsl
uniform shader inputImage;
uniform half3 uWeights;
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    return half4(half3(dot(c.rgb, uWeights)), c.a);
}
```

### 7.19 Mist
```glsl
uniform shader inputImage, hazeBlur;
uniform float2 uResolution;
uniform half3 uMistColor;
uniform float uHorizonY, uIntensity;
half4 main(float2 fragCoord) {
    float uvY = fragCoord.y / uResolution.y;
    half4 sharp = inputImage.eval(fragCoord);
    half4 hazy = hazeBlur.eval(fragCoord);
    float depthFalloff = smoothstep(0.0, 1.0, uvY / max(uHorizonY, 0.001));
    half3 lifted = mix(sharp.rgb, uMistColor, 0.15 * uIntensity);
    half3 result = mix(lifted, mix(hazy.rgb, uMistColor, 0.4), depthFalloff * uIntensity);
    return half4(result, sharp.a);
}
```

---

## 8. TEXTURE

### 8.20 Film grain
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uFrameSeed, uIntensity, uSize;
float ign(float2 p) { return fract(52.9829189 * fract(dot(p, float2(0.06711056, 0.00583715)))); }
half4 main(float2 fragCoord) {
    half4 c = inputImage.eval(fragCoord);
    float luma = dot(c.rgb, half3(0.2126,0.7152,0.0722));
    float midtoneW = clamp(1.0 - abs(luma - 0.5) * 1.8, 0.15, 1.0);
    float n = ign(fragCoord / uSize + uFrameSeed * 17.0) - 0.5;
    return half4(c.rgb + n * 0.12 * uIntensity * midtoneW, c.a);
}
```

### 8.21 Dust / scratches
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uSeed, uIntensity;
float hash(float2 p) { return fract(sin(dot(p, float2(41.3, 289.1))) * 43758.5453); }
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    half4 c = inputImage.eval(fragCoord);
    float2 cell = floor(uv * float2(60.0, 90.0));
    float dust = step(0.997, hash(cell + uSeed)) * (hash(cell * 1.7) * 0.6 + 0.4);
    float col = floor(uv.x * 40.0);
    float scratch = step(0.985, hash(float2(col, floor(uSeed))))
                  * (1.0 - smoothstep(0.0, 0.0015, abs(fract(uv.x * 40.0) - 0.5) - 0.001));
    float mark = clamp(dust + scratch, 0.0, 1.0) * uIntensity;
    return half4(mix(c.rgb, half3(1.0), mark * 0.7), c.a);
}
```

### 8.22 VHS / scan lines
```glsl
uniform shader inputImage;
uniform float2 uResolution;
uniform float uTime, uIntensity;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float line = floor(uv.y * uResolution.y / 3.0);
    float jitter = (fract(sin(line * 12.9898 + uTime) * 43758.5453) - 0.5) * 0.004 * uIntensity;
    half4 c = inputImage.eval(float2(uv.x + jitter, uv.y) * uResolution);
    float scan = 0.92 + 0.08 * sin(uv.y * uResolution.y * 3.14159);
    c.rgb *= mix(1.0, scan, uIntensity);
    half rOff = inputImage.eval(float2((uv.x + 0.0015 * uIntensity) * uResolution.x, uv.y * uResolution.y)).r;
    c.r = mix(c.r, rOff, 0.5);
    return c;
}
```

### 8.23 Double exposure
```glsl
uniform shader frameA, frameB;
uniform float uMix;
half3 screenBlend(half3 a, half3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }
half4 main(float2 fragCoord) {
    half4 a = frameA.eval(fragCoord), b = frameB.eval(fragCoord);
    return half4(screenBlend(a.rgb, b.rgb * uMix), 1.0);
}
```

---

## 9. NOVEL — depth & sensor-driven

### 9.24 Depth-aware halation
```glsl
uniform shader sharpImage, halationBlur, depthMap;
uniform float uFocusPlane, uDepthBand, uIntensity;
uniform half3 uTint;
half4 main(float2 fragCoord) {
    half4 sharp = sharpImage.eval(fragCoord);
    half4 h = halationBlur.eval(fragCoord);
    float depth = depthMap.eval(fragCoord).r;
    float mask = 1.0 - smoothstep(0.0, uDepthBand, abs(depth - uFocusPlane));
    return half4(sharp.rgb + h.rgb * uTint * mask * uIntensity, sharp.a);
}
```

### 9.25 Motion-reactive grain
```kotlin
class MotionEnergyTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var smoothed = 0f
    fun start() = sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
    fun stop() = sm.unregisterListener(this)
    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0]*e.values[0] + e.values[1]*e.values[1] + e.values[2]*e.values[2])
        smoothed += (mag - smoothed) * 0.15f
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    fun energy(): Float = smoothed.coerceIn(0f, 3f) / 3f
}
```

### 9.26 True focal-plane tilt-shift
```glsl
uniform shader sharpImage, blurredImage, depthMap;
uniform float uFocusDepth, uDepthFeather;
half4 main(float2 fragCoord) {
    float depth = depthMap.eval(fragCoord).r;
    float blend = smoothstep(0.0, uDepthFeather, abs(depth - uFocusDepth));
    return mix(sharpImage.eval(fragCoord), blurredImage.eval(fragCoord), blend);
}
```

### 9.27 Adaptive light-leak direction
```glsl
// reduction pass — output target is a tiny 4x4 buffer
uniform shader inputImage;
uniform float2 uCellSize;
half4 main(float2 fragCoord) {
    float2 origin = fragCoord * uCellSize;
    half3 sum = half3(0.0);
    const int N = 4;
    for (int y = 0; y < N; y++) {
        for (int x = 0; x < N; x++) {
            sum += inputImage.eval(origin + (float2(x, y) + 0.5) * (uCellSize / float(N))).rgb;
        }
    }
    float luma = dot(sum / float(N * N), half3(0.2126, 0.7152, 0.0722));
    return half4(half3(luma), 1.0);
}
```

### 9.28 Temperature-reactive film stock
```kotlin
fun warmthFromTimeOfDay(hour: Int): Float {
    val goldenMorning = 1f - (abs(hour - 7) / 3f).coerceIn(0f, 1f)
    val goldenEvening = 1f - (abs(hour - 18) / 3f).coerceIn(0f, 1f)
    val midday = -0.3f * (1f - abs(hour - 13) / 6f).coerceIn(0f, 1f)
    return (goldenMorning + goldenEvening + midday).coerceIn(-1f, 1f)
}
```

### 9.29 Chromatic depth separation
```glsl
uniform shader inputImage, depthMap;
uniform float2 uResolution;
uniform float uFocusDepth, uMaxStrength;
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / uResolution;
    float depth = depthMap.eval(fragCoord).r;
    float strength = abs(depth - uFocusDepth) * uMaxStrength;
    float2 dir = (uv - 0.5) * strength;
    half r = inputImage.eval((uv + dir) * uResolution).r;
    half4 g = inputImage.eval(uv * uResolution);
    half b = inputImage.eval((uv - dir) * uResolution).b;
    return half4(r, g.g, b, 1.0);
}
```

### 9.30 Real double-exposure with depth occlusion
```glsl
uniform shader frameA, frameB, depthA;
uniform float uForegroundDepth, uMix;
half3 screenBlend(half3 a, half3 b) { return 1.0 - (1.0 - a) * (1.0 - b); }
half4 main(float2 fragCoord) {
    half4 a = frameA.eval(fragCoord), b = frameB.eval(fragCoord);
    float depth = depthA.eval(fragCoord).r;
    float isFg = 1.0 - smoothstep(uForegroundDepth - 0.05, uForegroundDepth + 0.05, depth);
    half3 blended = screenBlend(a.rgb, b.rgb * uMix);
    return half4(mix(blended, a.rgb, isFg), 1.0);
}
```

### 9.31 Sensor-noise-matched grain
```kotlin
fun grainParamsForIso(iso: Int): Pair<Float, Float> {
    val scale = sqrt(iso / 100f)
    val intensity = (0.03f * scale).coerceIn(0.02f, 0.18f)
    val size = (1.0f + scale * 0.3f).coerceIn(1f, 2.5f)
    return intensity to size
}
```

---

## 10. Verified references (license-checked)

| Source | License | Use for |
|---|---|---|
| [glfx.js](https://github.com/evanw/glfx.js) | **MIT** | Vignette/swirl/denoise GLSL |
| [gl-transitions](https://github.com/gl-transitions/gl-transitions) | **MIT** | Blend/transition math |
| [android/camera-samples](https://github.com/android/camera-samples) | **Apache-2.0** | CameraX/Camera2 reference |
| Interleaved Gradient Noise (Jimenez, SIGGRAPH 2014) | Published technique | Grain dithering |
