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

        float sdCircle(vec2 p, float r) { return length(p) - r; }
        float sdBox(vec2 p, vec2 b) {
            vec2 d = abs(p) - b;
            return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0);
        }
        float sdRoundedBox(vec2 p, vec2 b, float r) { return sdBox(p, b - r) - r; }
        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
        }
        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i + vec2(0.0,0.0)), hash(i + vec2(1.0,0.0)), u.x),
                       mix(hash(i + vec2(0.0,1.0)), hash(i + vec2(1.0,1.0)), u.x), u.y);
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;
            vec3 col = uBgColor;

            float snow = step(0.996, hash(floor(uv * 40.0 + vec2(0.0, uTime * 2.0))));
            col += snow * vec3(0.45);

            float outerFrame = sdRoundedBox(uv - vec2(0.0, -0.05), vec2(0.65, 0.45), 0.04);
            float hearthHole = sdRoundedBox(uv - vec2(0.0, -0.15), vec2(0.42, 0.32), 0.06);
            float mantel = sdRoundedBox(uv - vec2(0.0, 0.38), vec2(0.72, 0.06), 0.03);

            if (outerFrame < 0.0) {
                vec2 brickUV = uv * 8.0;
                brickUV.x += step(1.0, mod(brickUV.y, 2.0)) * 0.5;
                vec2 brickId = floor(brickUV);
                float brickBorder = sdRoundedBox(fract(brickUV) - 0.5, vec2(0.44, 0.38), 0.08);
                vec3 bCol = mix(uBrickColor, uBrickColor * 0.85, hash(brickId));
                col = mix(vec3(0.94, 0.88, 0.86), bCol, smoothstep(0.02, 0.0, brickBorder));
            }

            if (mantel < 0.0) col = vec3(0.55, 0.35, 0.25);
            float garland = sdCircle(vec2(mod(uv.x + 0.7, 0.2) - 0.1, uv.y - 0.33), 0.07);
            if (abs(uv.x) < 0.65 && garland < 0.0) col = vec3(0.25, 0.72, 0.45);

            if (hearthHole < 0.0) {
                col = uBgColor * 0.45;
                float glow = clamp(1.0 - length(uv - vec2(0.0, -0.22)) * 2.1, 0.0, 1.0);
                col += glow * uGlowColor * (0.65 + 0.25 * sin(uTime * 6.0) + uBass * 0.4);
            }

            float log1 = sdRoundedBox(uv - vec2(-0.12, -0.36), vec2(0.18, 0.055), 0.03);
            float log2 = sdRoundedBox(uv - vec2(0.12, -0.36), vec2(0.18, 0.055), 0.03);
            if (min(log1, log2) < 0.0) col = vec3(0.42, 0.24, 0.15);

            vec2 fUv = uv - vec2(0.0, -0.20);
            float flameBounce = 1.0 + 0.05 * sin(uTime * 8.0) + uBass * 0.35;
            fUv.y /= flameBounce;
            fUv.x += sin(fUv.y * 10.0 - uTime * 6.0) * (0.025 + uBass * 0.04) * (fUv.y + 0.2);

            float baseCircle = sdCircle(fUv, 0.19 + uBass * 0.03);
            float topTip = sdCircle(fUv - vec2(0.0, 0.16), 0.08);
            float n = noise(fUv * 8.0 - vec2(0.0, uTime * 5.0)) * 0.05;
            float flameSDF = smin(baseCircle, topTip, 0.18) - n;

            if (flameSDF < 0.0) {
                col = uFlameOuter;
                if (flameSDF < -0.04) col = uFlameMid;
                if (flameSDF < -0.09) col = uFlameCore;

                vec2 faceUv = uv - vec2(0.0, -0.23 + uBass * 0.04);
                float eyeL = sdCircle(faceUv - vec2(-0.065, 0.02), 0.022);
                float eyeR = sdCircle(faceUv - vec2(0.065, 0.02), 0.022);
                float shineSize = 0.007 + uTreble * 0.008;
                float shineL = sdCircle(faceUv - vec2(-0.058, 0.028), shineSize);
                float shineR = sdCircle(faceUv - vec2(0.072, 0.028), shineSize);
                float blushL = sdCircle(faceUv - vec2(-0.095, -0.01), 0.025 + uBass * 0.01);
                float blushR = sdCircle(faceUv - vec2(0.095, -0.01), 0.025 + uBass * 0.01);

                if (min(blushL, blushR) < 0.0) col = mix(col, vec3(1.0, 0.25, 0.5), 0.7);
                if (min(eyeL, eyeR) < 0.0) col = vec3(0.15, 0.05, 0.08);
                if (min(shineL, shineR) < 0.0) col = vec3(1.0);

                if (uBass > 0.35) {
                    float openMouth = sdCircle(faceUv - vec2(0.0, -0.02), 0.015 + uBass * 0.02);
                    if (openMouth < 0.0) col = vec3(0.85, 0.15, 0.25);
                } else {
                    float smile = abs(sdCircle(faceUv - vec2(0.0, -0.01), 0.025)) - 0.004;
                    if (smile < 0.0 && faceUv.y < -0.01) col = vec3(0.15, 0.05, 0.08);
                }
            }

            for (float i = 0.0; i < 5.0; i++) {
                vec2 sparkPos = vec2(
                    sin(uTime * 2.0 + i * 1.7) * 0.22,
                    mod(uTime * 0.25 + i * 0.15, 0.55) - 0.2
                );
                float spark = sdCircle(uv - sparkPos, 0.008 + uTreble * 0.015);
                if (spark < 0.0 && hearthHole < 0.0) col = uFlameCore;
            }

            vec2 hatUv = uv - vec2(-0.48, 0.38);
            float hatBase = sdRoundedBox(hatUv, vec2(0.09, 0.025), 0.015);
            float hatCone = sdCircle(hatUv - vec2(0.0, 0.05), 0.065);
            float pomPom = sdCircle(hatUv - vec2(0.06, 0.10), 0.028);
            if (hatCone < 0.0) col = vec3(0.9, 0.18, 0.25);
            if (min(hatBase, pomPom) < 0.0) col = vec3(0.98, 0.98, 1.0);

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
