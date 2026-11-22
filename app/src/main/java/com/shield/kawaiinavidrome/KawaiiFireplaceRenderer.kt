package com.shield.kawaiinavidrome

import android.content.Context
import android.opengl.GLES32
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class KawaiiFireplaceRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private var program = 0
    private var timeLocation = 0
    private var resolutionLocation = 0
    private var bassLocation = 0
    private var trebleLocation = 0
    private var baseTexLocation = 0
    private var baseTexId = 0

    private var startTime = System.currentTimeMillis()
    private var width = 1920f
    private var height = 1080f

    @Volatile var bassEnergy: Float = 0.1f
    @Volatile var trebleEnergy: Float = 0.1f

    private val vertexShaderCode = """
        #version 320 es
        in vec4 aPosition;
        void main() { gl_Position = aPosition; }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 320 es
        precision highp float;

        uniform vec2 uResolution;
        uniform float uTime;
        uniform float uBass;
        uniform float uTreble;
        uniform sampler2D uBaseTex;

        out vec4 fragColor;

        float hash12(vec2 p) {
            vec3 p3  = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        vec2 hash22(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.xx + p3.yz) * p3.zy);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i + vec2(0.0, 0.0)), hash12(i + vec2(1.0, 0.0)), u.x),
                       mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
        }

        float fbm(vec2 p) {
            float v = 0.0;
            float a = 0.5;
            mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
            for (int i = 0; i < 5; i++) {
                v += a * noise(p);
                p = rot * p * 2.04 + vec2(19.3, 27.9);
                a *= 0.5;
            }
            return v;
        }

        float sdBox2D(vec2 p, vec2 b, float r) {
            vec2 d = abs(p) - b + r;
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
        }

        vec3 fireGradient(float t) {
            vec3 c1 = vec3(0.58, 0.07, 0.01);
            vec3 c2 = vec3(0.97, 0.30, 0.02);
            vec3 c3 = vec3(1.00, 0.74, 0.18);
            vec3 c4 = vec3(1.00, 0.97, 0.86);
            vec3 col = mix(c1, c2, smoothstep(0.06, 0.34, t));
            col = mix(col, c3, smoothstep(0.34, 0.68, t));
            col = mix(col, c4, smoothstep(0.68, 0.95, t));
            return col;
        }

        vec3 renderCandleFlame(vec3 bgCol, vec2 imgUv, vec2 flameCenter, float scale, float aspect, float phase, float flicker) {
            vec2 d = (imgUv - flameCenter) * vec2(aspect, 1.0);
            float yNorm = clamp((-d.y + 0.008 * scale) / (0.026 * scale), 0.0, 1.0);
            float sway = sin(uTime * 5.2 + phase) * 0.0022 * scale * pow(yNorm, 1.4);
            d.x -= sway;

            float widthAtY = (0.0062 * scale) * sin(pow(yNorm, 0.55) * 3.14159) * (1.0 - 0.35 * yNorm);
            float inFlame = length(vec2(d.x / max(widthAtY, 0.0004), (d.y + 0.003 * scale) / (0.013 * scale)));

            vec3 outCol = bgCol;
            if (inFlame < 1.35) {
                float core = smoothstep(1.15, 0.15, inFlame);
                vec3 flameCol = mix(vec3(1.0, 0.52, 0.08), vec3(1.0, 0.97, 0.82), pow(core, 1.5));
                float wickBase = smoothstep(0.28, 0.0, yNorm) * core;
                flameCol = mix(flameCol, vec3(0.45, 0.65, 1.0), wickBase * 0.45);
                outCol = mix(outCol, flameCol, smoothstep(1.25, 0.55, inFlame));
            }

            float haloDist = length((imgUv - flameCenter) * vec2(aspect, 1.0));
            float halo = exp(-haloDist * (52.0 / scale)) * 0.52 * flicker;
            outCol += vec3(1.0, 0.68, 0.24) * halo;
            return outCol;
        }

        float sampleSlowSnow(vec2 uv, float aspect, float time) {
            vec2 p = vec2(uv.x * aspect, uv.y);
            float totalSnow = 0.0;
            for (float layer = 1.0; layer <= 3.0; layer += 1.0) {
                float scale = 14.0 + layer * 9.0;
                float fallSpeed = 0.048 / (0.65 + layer * 0.35);
                float wind = 0.025 * sin(time * 0.25 + layer * 1.8);

                vec2 gridUv = p * scale - vec2(wind * scale, time * fallSpeed * scale);
                vec2 cell = floor(gridUv);
                vec2 local = fract(gridUv) - 0.5;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 nb = vec2(float(dx), float(dy));
                        vec2 id = cell + nb;
                        vec2 rnd = hash22(id + vec2(layer * 37.1, layer * 13.7));
                        if (rnd.x > 0.36) continue;

                        float sway = sin(time * (0.85 + rnd.y * 0.9) + rnd.x * 20.0) * 0.22;
                        vec2 center = nb + (rnd - 0.5) * 0.68 + vec2(sway, 0.0);
                        float d = length(local - center);

                        float radius = mix(0.055, 0.145, fract(rnd.y * 6.31)) / (0.7 + layer * 0.35);
                        float blur = radius * (0.45 + 0.35 * layer);
                        float flake = smoothstep(radius + blur, radius * 0.2, d);
                        totalSnow += flake * (0.85 / (0.65 + layer * 0.35));
                    }
                }
            }
            return clamp(totalSnow, 0.0, 1.0);
        }

        float sampleStarSparkle(vec2 imgUv, vec2 center, float aspect, float radius) {
            vec2 d = (imgUv - center) * vec2(aspect, 1.0);
            float dist = length(d);
            if (dist > radius * 2.2) return 0.0;

            float crossH = exp(-abs(d.y) * (280.0 / (radius * 40.0))) * exp(-abs(d.x) * (75.0 / (radius * 40.0)));
            float crossV = exp(-abs(d.x) * (280.0 / (radius * 40.0))) * exp(-abs(d.y) * (75.0 / (radius * 40.0)));
            vec2 diag = vec2(d.x + d.y, d.x - d.y) * 0.7071;
            float diagStar = exp(-abs(diag.x) * (360.0 / (radius * 40.0))) * exp(-abs(diag.y) * (130.0 / (radius * 40.0)))
                           + exp(-abs(diag.y) * (360.0 / (radius * 40.0))) * exp(-abs(diag.x) * (130.0 / (radius * 40.0)));
            float core = exp(-dist * (120.0 / (radius * 40.0)));
            return clamp(crossH + crossV + diagStar * 0.55 + core * 0.9, 0.0, 1.6);
        }

        void main() {
            vec2 imgUv = vec2(gl_FragCoord.x / uResolution.x, 1.0 - gl_FragCoord.y / uResolution.y);
            float aspect = uResolution.x / uResolution.y;

            vec3 baseTex = texture(uBaseTex, imgUv).rgb;
            vec3 col = baseTex;

            float fireFlicker = 0.92 + 0.08 * sin(uTime * 11.7) * cos(uTime * 7.3) + uBass * 0.32;

            // =====================================================================
            // 1. TALL PROCEDURAL FIREPLACE FIRE (COMPLETELY REPLACING STILL FIRE)
            // =====================================================================
            vec2 boxCenter = vec2(0.495, 0.722);
            vec2 boxHalf = vec2(0.186, 0.188);
            float boxSdf = sdBox2D(imgUv - boxCenter, boxHalf, 0.012);

            if (boxSdf < 0.008) {
                float boxMask = smoothstep(0.006, -0.004, boxSdf);
                float lum = dot(baseTex, vec3(0.299, 0.587, 0.114));
                float fireChroma = max(0.0, baseTex.r - baseTex.b);

                // Protect dark foreground logs and iron grate bars while suppressing
                // the entire painted yellow/orange fire from bottom coals to top arch
                float isLogZone = smoothstep(0.74, 0.80, imgUv.y) * smoothstep(0.19, 0.10, abs(imgUv.x - 0.495));
                float staticFireSuppress = smoothstep(0.22, 0.50, lum)
                                         * smoothstep(0.10, 0.26, fireChroma)
                                         * (1.0 - isLogZone * 0.78);

                vec3 darkSootBrick = vec3(0.22, 0.08, 0.03) * (0.78 + 0.32 * fbm(imgUv * 32.0));
                vec3 hearthBg = mix(baseTex, darkSootBrick, staticFireSuppress * 0.96);

                // Glowing ember bed along the bottom grate
                if (imgUv.y > 0.84) {
                    float coalPulse = 0.75 + 0.25 * sin(uTime * 6.0 + imgUv.x * 40.0) + uBass * 0.35;
                    float emberMask = smoothstep(0.38, 0.70, lum) * smoothstep(0.84, 0.88, imgUv.y);
                    hearthBg += vec3(0.90, 0.26, 0.03) * emberMask * (coalPulse - 0.82);
                }

                // Taller & wider normalized fire volume reaching the top of the firebox (y ~ 0.53)
                vec2 fUv = vec2(
                    (imgUv.x - 0.495) / 0.168,
                    (0.885 - imgUv.y) / (0.355 + uBass * 0.032)
                );

                if (fUv.y > -0.05 && fUv.y < 1.15 && abs(fUv.x) < 1.15) {
                    float yH = clamp(fUv.y, 0.0, 1.0);
                    vec2 flow1 = vec2(fUv.x * 3.0, fUv.y * 2.2 - uTime * 2.8);
                    vec2 flow2 = vec2(fUv.x * 6.2 + 3.7, fUv.y * 4.8 - uTime * 4.5);
                    float turb = (fbm(flow1) - 0.5) * 0.50 + (fbm(flow2) - 0.5) * 0.24;

                    // Keep body full higher up, tapering cleanly into tall flame spires near the top
                    float taper = 1.0 - pow(yH, 1.55) * 0.68;
                    float wx = (fUv.x + turb * (0.20 + yH * 0.85)) / max(taper, 0.15);

                    float tongues = (sin(wx * 5.2 + fbm(flow1 * 1.5) * 3.8) * 0.24
                                   + cos(wx * 3.1 - uTime * 3.2) * 0.14) * smoothstep(0.05, 0.75, yH);
                    float coreDist = length(vec2(wx * 0.96, max(0.0, yH - 0.34) * 0.92));
                    float density = (1.08 - coreDist + tongues + (fbm(flow2) - 0.44) * 0.46)
                                  * smoothstep(1.08, 0.22, yH)
                                  * smoothstep(-0.04, 0.08, fUv.y);

                    // Keep the front wooden logs partially in front of the lower flame base
                    float frontLogOcclusion = isLogZone * smoothstep(0.55, 0.24, lum) * smoothstep(0.36, 0.10, yH);
                    density *= (1.0 - frontLogOcclusion * 0.85);

                    if (density > 0.015) {
                        float temp = clamp(density * (0.98 + uBass * 0.35), 0.0, 1.0);
                        vec3 fCol = fireGradient(temp);
                        float alpha = smoothstep(0.015, 0.34, density);
                        hearthBg = mix(hearthBg, fCol, clamp(alpha, 0.0, 1.0));
                        hearthBg += fCol * alpha * 0.26;
                    }
                }

                // Rising sparks & embers inside the firebox
                for (float k = 1.0; k <= 16.0; k += 1.0) {
                    float sd = hash12(vec2(k * 43.1, 17.9));
                    float life = fract(uTime * (0.24 + sd * 0.20) + sd);
                    vec2 spPos = vec2(
                        0.495 + (sd - 0.5) * 0.28 + sin(life * 8.5 + k) * 0.020,
                        0.86 - life * 0.32
                    );
                    float dSp = length((imgUv - spPos) * vec2(aspect, 1.0));
                    float rSp = (0.0022 + 0.002 * fract(sd * 7.3)) * (1.0 - life) * (1.0 + uTreble * 0.45);
                    if (dSp < rSp * 2.5) {
                        float spGlow = smoothstep(rSp * 2.5, rSp * 0.2, dSp) * (1.0 - life * 0.55);
                        hearthBg += vec3(1.0, 0.78, 0.28) * spGlow * 1.3;
                    }
                }

                col = mix(col, hearthBg, boxMask);
            }

            // Dynamic warm firelight bounce onto surrounding stones & stockings
            float surroundDist = length((imgUv - vec2(0.495, 0.72)) * vec2(aspect * 0.68, 1.0));
            float bounceGlow = exp(-surroundDist * 2.3) * (fireFlicker - 0.92);
            col += vec3(1.0, 0.46, 0.10) * bounceGlow * 0.48;

            // =====================================================================
            // 2. PROCEDURAL CANDLE FLAMES IN BOTH LANTERNS
            // =====================================================================
            float flickerTop = 0.88 + 0.12 * sin(uTime * 8.4 + 0.7) * cos(uTime * 5.9) + uBass * 0.12;
            vec2 topFlamePos = vec2(0.284, 0.125);
            float topDotMask = smoothstep(0.015, 0.005, length((imgUv - vec2(0.284, 0.123)) * vec2(aspect, 1.0)));
            col = mix(col, vec3(0.46, 0.26, 0.11), topDotMask * 0.88);
            col = renderCandleFlame(col, imgUv, topFlamePos, 0.92, aspect, 0.4, flickerTop);
            float topGlassBox = sdBox2D(imgUv - vec2(0.284, 0.135), vec2(0.019, 0.056), 0.004);
            if (topGlassBox < 0.0) {
                col += vec3(1.0, 0.62, 0.18) * (flickerTop - 0.88) * 0.55;
            }

            float flickerBot = 0.88 + 0.12 * sin(uTime * 7.6 + 2.3) * cos(uTime * 6.3 + 1.1) + uBass * 0.12;
            vec2 botFlamePos = vec2(0.772, 0.856);
            float botDotMask = smoothstep(0.020, 0.007, length((imgUv - vec2(0.772, 0.854)) * vec2(aspect, 1.0)));
            col = mix(col, vec3(0.48, 0.27, 0.11), botDotMask * 0.88);
            col = renderCandleFlame(col, imgUv, botFlamePos, 1.18, aspect, 2.5, flickerBot);
            float botGlassBox = sdBox2D(imgUv - vec2(0.772, 0.860), vec2(0.024, 0.072), 0.005);
            if (botGlassBox < 0.0) {
                col += vec3(1.0, 0.62, 0.18) * (flickerBot - 0.88) * 0.55;
            }

            // =====================================================================
            // 3. SLOW FALLING SNOW OUTSIDE THE RIGHT WINDOW
            // =====================================================================
            if (imgUv.x > 0.848 && imgUv.y < 0.608) {
                float vBar = smoothstep(0.006, 0.013, abs(imgUv.x - 0.967));
                float hBar1 = smoothstep(0.007, 0.014, abs(imgUv.y - 0.206));
                float hBar2 = smoothstep(0.007, 0.014, abs(imgUv.y - 0.446));
                float leftFrame = smoothstep(0.848, 0.856, imgUv.x);
                float botSill = smoothstep(0.608, 0.598, imgUv.y);
                float coolPaneMask = smoothstep(0.72, 0.92, baseTex.b / max(baseTex.r, 0.05));

                float glassMask = vBar * hBar1 * hBar2 * leftFrame * botSill * coolPaneMask;
                if (glassMask > 0.01) {
                    float snow = sampleSlowSnow(imgUv, aspect, uTime);
                    col = mix(col, vec3(0.94, 0.97, 1.0), snow * glassMask * 0.88);
                }
            }

            // =====================================================================
            // 4. RANDOM SPARKS / GLINTS ON THE CHRISTMAS TREE ORNAMENTS (LEFT)
            // =====================================================================
            vec2 baubles[6];
            baubles[0] = vec2(0.036, 0.182);
            baubles[1] = vec2(0.106, 0.362);
            baubles[2] = vec2(0.048, 0.558);
            baubles[3] = vec2(0.132, 0.538);
            baubles[4] = vec2(0.141, 0.688);
            baubles[5] = vec2(0.088, 0.940);

            for (int i = 0; i < 6; i++) {
                float fi = float(i);
                float speed = 1.35 + fract(fi * 0.37) * 0.85;
                float phase = fi * 2.17;
                float wave = sin(uTime * speed + phase) * sin(uTime * (speed * 0.61) + fi * 4.1);
                float twinkle = pow(clamp(wave, 0.0, 1.0), 6.0) * (1.15 + uTreble * 0.85);

                if (twinkle > 0.02) {
                    float spark = sampleStarSparkle(imgUv, baubles[i], aspect, 0.026);
                    col += vec3(1.0, 0.94, 0.74) * spark * twinkle;
                }
            }

            if (imgUv.x < 0.24) {
                float isFairyLight = smoothstep(0.75, 0.98, baseTex.r) * smoothstep(0.55, 0.85, baseTex.g);
                if (isFairyLight > 0.05) {
                    float fairyPulse = 0.85 + 0.25 * sin(uTime * 3.4 + imgUv.x * 90.0 + imgUv.y * 65.0) + uTreble * 0.35;
                    col += vec3(1.0, 0.85, 0.45) * isFairyLight * (fairyPulse - 0.85) * 0.45;
                }
            }

            fragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private lateinit var vertexBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(quadVertices)
                position(0)
            }

        val vShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES32.glCreateProgram().also {
            GLES32.glAttachShader(it, vShader)
            GLES32.glAttachShader(it, fShader)
            GLES32.glLinkProgram(it)
        }

        timeLocation = GLES32.glGetUniformLocation(program, "uTime")
        resolutionLocation = GLES32.glGetUniformLocation(program, "uResolution")
        bassLocation = GLES32.glGetUniformLocation(program, "uBass")
        trebleLocation = GLES32.glGetUniformLocation(program, "uTreble")
        baseTexLocation = GLES32.glGetUniformLocation(program, "uBaseTex")

        baseTexId = TextureGenerator.loadFireplaceBaseTexture(context)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES32.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glUseProgram(program)

        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
        GLES32.glUniform1f(timeLocation, elapsed)
        GLES32.glUniform2f(resolutionLocation, width, height)
        GLES32.glUniform1f(bassLocation, bassEnergy)
        GLES32.glUniform1f(trebleLocation, trebleEnergy)

        GLES32.glActiveTexture(GLES32.GL_TEXTURE0)
        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, baseTexId)
        GLES32.glUniform1i(baseTexLocation, 0)

        val posHandle = GLES32.glGetAttribLocation(program, "aPosition")
        GLES32.glEnableVertexAttribArray(posHandle)
        GLES32.glVertexAttribPointer(posHandle, 2, GLES32.GL_FLOAT, false, 0, vertexBuffer)
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glDisableVertexAttribArray(posHandle)
    }

    private fun loadShader(type: Int, shaderCode: String): Int =
        GLES32.glCreateShader(type).also { shader ->
            GLES32.glShaderSource(shader, shaderCode)
            GLES32.glCompileShader(shader)
        }
}
