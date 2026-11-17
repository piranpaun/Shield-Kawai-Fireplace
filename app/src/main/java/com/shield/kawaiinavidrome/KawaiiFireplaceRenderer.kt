package com.shield.kawaiinavidrome

import android.opengl.GLES32
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class KawaiiFireplaceRenderer : GLSurfaceView.Renderer {
    private var program = 0
    private var timeLocation = 0
    private var resolutionLocation = 0
    private var bassLocation = 0
    private var trebleLocation = 0

    private var bgColLoc = 0
    private var brickColLoc = 0
    private var flameOuterLoc = 0
    private var flameMidLoc = 0
    private var flameCoreLoc = 0
    private var glowColLoc = 0

    private var startTime = System.currentTimeMillis()
    private var width = 1920f
    private var height = 1080f

    @Volatile var bassEnergy: Float = 0.1f
    @Volatile var trebleEnergy: Float = 0.1f
    @Volatile var currentTheme: KawaiiTheme = KawaiiThemes.list[0]

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

        uniform vec3 uBgColor;
        uniform vec3 uBrickColor;
        uniform vec3 uFlameOuter;
        uniform vec3 uFlameMid;
        uniform vec3 uFlameCore;
        uniform vec3 uGlowColor;

        out vec4 fragColor;

        float hash12(vec2 p) {
            vec3 p3  = fract(vec3(p.xyx) * .1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f*f*(3.0-2.0*f);
            return mix(mix(hash12(i + vec2(0.0,0.0)), hash12(i + vec2(1.0,0.0)), u.x),
                       mix(hash12(i + vec2(0.0,1.0)), hash12(i + vec2(1.0,1.0)), u.x), u.y);
        }

        float fbm(vec2 p) {
            float v = 0.0;
            float a = 0.5;
            vec2 shift = vec2(100.0);
            mat2 rot = mat2(cos(0.5), sin(0.5), -sin(0.5), cos(0.50));
            for (int i = 0; i < 5; ++i) {
                v += a * noise(p);
                p = rot * p * 2.0 + shift;
                a *= 0.5;
            }
            return v;
        }

        float sdRoundBox(vec2 p, vec2 b, vec4 r) {
            r.xy = (p.x>0.0)?r.xy : r.zw;
            r.x  = (p.y>0.0)?r.x  : r.y;
            vec2 q = abs(p)-b+r.x;
            return min(max(q.x,q.y),0.0) + length(max(q,0.0)) - r.x;
        }

        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5*(b-a)/k, 0.0, 1.0);
            return mix(b, a, h) - k*h*(1.0-h);
        }

        vec3 getNormal(vec2 p, float strength) {
            vec2 eps = vec2(0.005, 0.0);
            float nh = fbm(p * 25.0);
            float nx = fbm((p + eps.xy) * 25.0) - nh;
            float ny = fbm((p + eps.yx) * 25.0) - nh;
            return normalize(vec3(-nx * strength, -ny * strength, 1.0));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;

            float distFactor = smoothstep(0.4, -0.4, length(uv - vec2(0.0, -0.1)));
            vec2 heatDist = vec2(
                sin(uv.y * 30.0 + uTime * 12.0) * 0.004 * (1.0 + uBass),
                cos(uv.x * 20.0 + uTime * 8.0) * 0.002
            ) * distFactor;

            vec2 dstUv = uv + heatDist;
            vec3 col = uBgColor * (1.0 - 0.3 * length(uv - vec2(0.0, 0.4)));

            // Ventana trasera con nieve en paralaje
            for (float w = -1.0; w <= 1.0; w += 2.0) {
                vec2 winCenter = vec2(w * 0.72, 0.18);
                float windowSDF = sdRoundBox(dstUv - winCenter, vec2(0.14, 0.24), vec4(0.02));
                if (windowSDF < 0.0) {
                    vec3 windowBg = vec3(0.04, 0.03, 0.09);
                    for (float i = 1.0; i < 4.0; i++) {
                        vec2 snowUv = (dstUv - winCenter) * (12.0 * i) + vec2(sin(uTime * 0.5 + i), uTime * (1.4 / i));
                        float snowParticles = step(0.982, hash12(floor(snowUv)));
                        windowBg = mix(windowBg, vec3(0.88, 0.92, 1.0), snowParticles * (0.32 * i));
                    }
                    float crossBar = min(abs(dstUv.x - winCenter.x), abs(dstUv.y - winCenter.y));
                    float frame = max(smoothstep(0.012, 0.0, abs(windowSDF)), smoothstep(0.008, 0.0, crossBar));
                    col = mix(windowBg, vec3(0.32, 0.20, 0.16), frame);
                }
            }

            float chimney = sdRoundBox(dstUv - vec2(0.0, -0.05), vec2(0.64, 0.45), vec4(0.04));
            float hearth = sdRoundBox(dstUv - vec2(0.0, -0.16), vec2(0.40, 0.30), vec4(0.06));
            float mantel = sdRoundBox(dstUv - vec2(0.0, 0.38), vec2(0.70, 0.05), vec4(0.03));

            vec2 fireLightPos = vec2(0.0, -0.2);
            float lightDistance = length(dstUv - fireLightPos);
            float fireGlowMap = 1.0 / (1.0 + lightDistance * 2.6);
            vec3 fireGlowColor = uGlowColor * (1.2 + 0.35 * sin(uTime * 10.0) + uBass * 0.85) * fireGlowMap;

            if (chimney < 0.0 && hearth > 0.0) {
                vec2 brickUv = dstUv * vec2(8.0, 16.0) + vec2(0.0, -0.05);
                brickUv.x += step(1.0, mod(brickUv.y, 2.0)) * 0.5;
                vec2 brickId = floor(brickUv);

                float brickEdges = sdRoundBox(fract(brickUv) - 0.5, vec2(0.44, 0.42), vec4(0.06));
                vec3 brickNormal = getNormal(dstUv, 0.05);
                vec3 lightDir = normalize(vec3(fireLightPos - dstUv, 0.18));
                float diff = max(dot(brickNormal, lightDir), 0.0);
                float ao = smoothstep(-0.06, 0.02, brickEdges);

                vec3 bColor = mix(uBrickColor, uBrickColor * 0.72, hash12(brickId));
                vec3 finalBrick = mix(vec3(0.18, 0.1, 0.12), bColor, smoothstep(-0.03, -0.01, brickEdges));
                finalBrick *= (diff * 1.3 + 0.25) * ao;
                finalBrick += fireGlowColor * 0.55 * ao;
                col = finalBrick;
            }

            if (mantel < 0.0) {
                vec3 woodNormal = getNormal(dstUv * vec2(1.0, 4.0), 0.02);
                vec3 lightDir = normalize(vec3(fireLightPos - dstUv, 0.12));
                float diff = max(dot(woodNormal, lightDir), 0.0);
                col = vec3(0.45, 0.25, 0.18) * (diff * 1.1 + 0.2) + fireGlowColor * 0.2;
            }

            if (hearth < 0.0) {
                float innerOcclusion = smoothstep(-0.35, 0.15, hearth);
                col = uBgColor * 0.18 * innerOcclusion + fireGlowColor * 1.35;
            }

            // Guirnalda con esferas navideñas brillantes
            float garland = sdRoundBox(dstUv - vec2(0.0, 0.34), vec2(0.58, 0.02), vec4(0.01));
            if (garland < 0.05 && abs(dstUv.x) < 0.60) {
                float leafPattern = sin(dstUv.x * 45.0) * 0.022;
                if (abs(dstUv.y - 0.34) < 0.035 + leafPattern) {
                    vec3 greenG = vec3(0.14, 0.48, 0.26);
                    col = mix(col, greenG * (0.85 + 0.3 * sin(dstUv.x * 16.0)), smoothstep(0.02, 0.0, garland));
                    float ballGrid = sin(dstUv.x * 18.0);
                    if (abs(ballGrid) > 0.90) {
                        float ballSDF = length(dstUv - vec2(dstUv.x, 0.33)) - 0.022;
                        if (ballSDF < 0.0) {
                            vec3 ballCol = (ballGrid > 0.0) ? vec3(0.95, 0.18, 0.25) : vec3(1.0, 0.80, 0.20);
                            col = ballCol * (0.75 + 0.35 * sin(uTime * 6.0 + dstUv.x * 10.0));
                        }
                    }
                }
            }

            // Troncos con brasas vivas
            float log1 = sdRoundBox(dstUv - vec2(-0.11, -0.34), vec2(0.16, 0.05), vec4(0.02));
            float log2 = sdRoundBox(dstUv - vec2(0.11, -0.34), vec2(0.16, 0.05), vec4(0.02));
            float logs = min(log1, log2);
            if (logs < 0.0) {
                vec3 logNormal = getNormal(dstUv * vec2(5.0, 2.0), 0.08);
                vec3 lightDir = normalize(vec3(fireLightPos - dstUv, 0.2));
                float diff = max(dot(logNormal, lightDir), 0.0);
                vec3 barkColor = vec3(0.32, 0.17, 0.11) * (diff + 0.15);
                float emberPattern = fbm(dstUv * 40.0 - uTime * 0.6);
                vec3 ember = vec3(1.0, 0.28, 0.06) * smoothstep(0.42, 0.72, emberPattern) * (1.1 + uBass);
                col = mix(barkColor + ember, vec3(0.1), smoothstep(-0.01, 0.0, logs));
            }

            // Fuego Volumétrico Kawaii
            vec2 fUv = dstUv - vec2(0.0, -0.21);
            fUv.y /= 1.0 + 0.08 * sin(uTime * 8.0) + uBass * 0.38;
            fUv.x += (fbm(fUv * 3.5 - vec2(0.0, uTime * 2.8)) - 0.5) * 0.32 * (fUv.y + 0.12);

            float flameBase = sdRoundBox(fUv, vec2(0.19, 0.13), vec4(0.08));
            float flameTip = length(fUv - vec2(0.0, 0.20)) - 0.065;
            float finalFlameSDF = smin(flameBase, flameTip, 0.16);

            if (finalFlameSDF < 0.05) {
                float edgeDist = smoothstep(0.05, -0.04, finalFlameSDF);
                vec3 flameColor = mix(col, uFlameOuter, edgeDist);

                float noiseFlame = fbm(dstUv * 10.0 - vec2(0.0, uTime * 4.5)) * 0.16;
                float innerSDF = finalFlameSDF + noiseFlame;

                if (innerSDF < 0.0) {
                    flameColor = mix(uFlameOuter, uFlameMid, smoothstep(0.0, -0.04, innerSDF));
                    if (innerSDF < -0.04) {
                        flameColor = mix(uFlameMid, uFlameCore, smoothstep(-0.04, -0.1, innerSDF));
                    }
                    if (innerSDF < -0.09) {
                        flameColor = mix(uFlameCore, vec3(1.0, 0.98, 0.92), smoothstep(-0.09, -0.14, innerSDF));
                    }

                    // Carita Kawaii expresiva
                    vec2 faceUv = dstUv - vec2(0.0, -0.22 + uBass * 0.04);
                    float eyeL = length(faceUv - vec2(-0.065, 0.02)) - 0.022;
                    float eyeR = length(faceUv - vec2(0.065, 0.02)) - 0.022;

                    float shineSize = 0.007 + uTreble * 0.008;
                    float shineL = length(faceUv - vec2(-0.057, 0.028)) - shineSize;
                    float shineR = length(faceUv - vec2(0.073, 0.028)) - shineSize;
                    float blushL = length(faceUv - vec2(-0.095, -0.01)) - (0.024 + uBass * 0.01);
                    float blushR = length(faceUv - vec2(0.095, -0.01)) - (0.024 + uBass * 0.01);

                    if (min(blushL, blushR) < 0.0) flameColor = mix(flameColor, vec3(1.0, 0.28, 0.48), 0.7);
                    if (min(eyeL, eyeR) < 0.0) flameColor = vec3(0.12, 0.05, 0.08);
                    if (min(shineL, shineR) < 0.0) flameColor = vec3(1.0);

                    if (uBass > 0.35) {
                        float openMouth = length(faceUv - vec2(0.0, -0.015)) - (0.014 + uBass * 0.018);
                        if (openMouth < 0.0) {
                            flameColor = mix(vec3(0.12, 0.05, 0.08), vec3(0.92, 0.28, 0.35), step(-0.005, openMouth));
                        }
                    } else {
                        float smile = abs(length(faceUv - vec2(0.0, -0.005)) - 0.024) - 0.004;
                        if (smile < 0.0 && faceUv.y < -0.005 && abs(faceUv.x) < 0.028) {
                            flameColor = vec3(0.12, 0.05, 0.08);
                        }
                    }
                }
                col = mix(col, flameColor, edgeDist);
            }

            // 15 corrientes de chispas dinámicas
            for (float i = 1.0; i <= 15.0; i++) {
                float id = i * 145.23;
                float sparkSeed = hash12(vec2(id));
                float life = fract(uTime * (0.22 + sparkSeed * 0.15) + sparkSeed);
                float xOffset = sin(life * 8.0 + id) * 0.08 * (life + 0.3);
                vec2 sparkPos = vec2((sparkSeed - 0.5) * 0.45 + xOffset, -0.34 + life * 0.85);
                float sparkSize = (0.004 + 0.006 * hash12(vec2(id + 1.2))) * (1.0 - life) * (1.0 + uTreble);
                float sparkSDF = length(dstUv - sparkPos) - sparkSize;
                if (sparkSDF < 0.0) {
                    vec3 sparkCol = mix(vec3(1.0, 0.9, 0.45), uFlameCore, life);
                    col = mix(col, sparkCol * 1.6, smoothstep(0.0, -0.005, sparkSDF));
                }
            }

            // Gorro navideño y calcetines colgantes animados
            vec2 hatUv = dstUv - vec2(-0.46, 0.38);
            float hatBase = sdRoundBox(hatUv, vec2(0.08, 0.022), vec4(0.015));
            float hatCone = length(hatUv - vec2(-0.015, 0.05)) - 0.06;
            float pomPom = length(hatUv - vec2(0.045, 0.09)) - 0.025;
            if (hatCone < 0.0) col = mix(col, vec3(0.88, 0.14, 0.22), smoothstep(-0.01, 0.0, hatCone));
            if (min(hatBase, pomPom) < 0.0) col = mix(col, vec3(0.97, 0.97, 1.0), smoothstep(-0.01, 0.0, min(hatBase, pomPom)));

            for (float s = -1.0; s <= 1.0; s += 2.0) {
                vec2 sockUv = dstUv - vec2(s * 0.28, 0.22);
                float swing = sin(uTime * 3.0 + s) * 0.04 * (1.0 + uBass * 0.3);
                sockUv.x += swing * (sockUv.y < 0.0 ? -sockUv.y : 0.0);
                float leg = sdRoundBox(sockUv - vec2(0.0, 0.03), vec2(0.03, 0.07), vec4(0.01));
                float foot = sdRoundBox(sockUv - vec2(sockUv.y < 0.0 ? sockUv.y * 0.5 : 0.0, -0.03), vec2(0.05, 0.03), vec4(0.02));
                float trim = sdRoundBox(sockUv - vec2(0.0, 0.1), vec2(0.034, 0.015), vec4(0.008));
                float body = min(leg, foot);
                if (body < 0.0) {
                    vec3 pattern = (s > 0.0) ? vec3(0.85, 0.14, 0.18) : vec3(0.18, 0.56, 0.28);
                    col = mix(col, pattern * (0.85 + 0.2 * sin(sockUv.y * 50.0)), smoothstep(-0.01, 0.0, body));
                }
                if (trim < 0.0) col = mix(col, vec3(0.96, 0.95, 0.92), smoothstep(-0.008, 0.0, trim));
            }

            float vignette = uv.x*uv.x + uv.y*uv.y;
            col *= clamp(1.0 - vignette * 0.32, 0.0, 1.0);
            col = pow(col, vec3(0.95));

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

        bgColLoc = GLES32.glGetUniformLocation(program, "uBgColor")
        brickColLoc = GLES32.glGetUniformLocation(program, "uBrickColor")
        flameOuterLoc = GLES32.glGetUniformLocation(program, "uFlameOuter")
        flameMidLoc = GLES32.glGetUniformLocation(program, "uFlameMid")
        flameCoreLoc = GLES32.glGetUniformLocation(program, "uFlameCore")
        glowColLoc = GLES32.glGetUniformLocation(program, "uGlowColor")
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

        val t = currentTheme
        GLES32.glUniform3fv(bgColLoc, 1, t.bgColor, 0)
        GLES32.glUniform3fv(brickColLoc, 1, t.brickColor, 0)
        GLES32.glUniform3fv(flameOuterLoc, 1, t.flameOuter, 0)
        GLES32.glUniform3fv(flameMidLoc, 1, t.flameMid, 0)
        GLES32.glUniform3fv(flameCoreLoc, 1, t.flameCore, 0)
        GLES32.glUniform3fv(glowColLoc, 1, t.glowColor, 0)

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
