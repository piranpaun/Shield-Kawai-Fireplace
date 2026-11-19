package com.shield.kawaiinavidrome

import android.opengl.GLES32
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp

/**
 * Single-pass, music-reactive kawaii fireplace.
 *
 * The scene uses a recessed firebox, warm dynamic light, cool window fill,
 * contact shadows, and a soft tone mapper. The public audio and theme API
 * remains compatible with the original renderer.
 */
class KawaiiFireplaceRenderer : GLSurfaceView.Renderer {
    private var program = 0
    private var positionHandle = -1

    private var timeLocation = -1
    private var resolutionLocation = -1
    private var bassLocation = -1
    private var trebleLocation = -1

    private var bgColLoc = -1
    private var brickColLoc = -1
    private var flameOuterLoc = -1
    private var flameMidLoc = -1
    private var flameCoreLoc = -1
    private var glowColLoc = -1

    private var startTimeNs = 0L
    private var lastFrameTimeNs = 0L
    private var width = 1920f
    private var height = 1080f

    private var smoothBass = 0.1f
    private var smoothTreble = 0.1f

    @Volatile
    var bassEnergy: Float = 0.1f

    @Volatile
    var trebleEnergy: Float = 0.1f

    @Volatile
    var currentTheme: KawaiiTheme = KawaiiThemes.list[0]

    private val vertexShaderCode = """
        #version 320 es
        in vec4 aPosition;

        void main() {
            gl_Position = aPosition;
        }
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

        const float PI = 3.14159265359;

        float hash12(vec2 p) {
            vec3 p3 = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(
                mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
                mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x),
                u.y
            );
        }

        float fbm(vec2 p) {
            float value = 0.0;
            value += 0.5000 * noise(p);
            p = mat2(1.6, 1.2, -1.2, 1.6) * p;
            value += 0.2500 * noise(p);
            p = mat2(1.6, 1.2, -1.2, 1.6) * p;
            value += 0.1250 * noise(p);
            return value;
        }

        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }

        float sdBox2D(vec2 p, vec2 b, float r) {
            vec2 d = abs(p) - b + r;
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
        }

        float sdSegment(vec2 p, vec2 a, vec2 b) {
            vec2 pa = p - a;
            vec2 ba = b - a;
            float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
            return length(pa - ba * h);
        }

        float sdStar4(vec2 p, float r) {
            p = abs(p);
            return p.x + p.y + abs(p.x - p.y) * 0.55 - r;
        }

        // Use ordered edges. Reversed smoothstep edges are undefined in GLSL.
        float fillMask(float distanceValue, float aa) {
            return 1.0 - smoothstep(-aa, aa, distanceValue);
        }

        vec3 shadeClay(
            vec3 baseCol,
            float sdf,
            vec2 grad,
            vec2 lightDir,
            float bevel
        ) {
            float interior = clamp(-sdf / bevel, 0.0, 1.0);
            float z = sqrt(clamp(
                1.0 - pow(1.0 - interior, 2.0),
                0.025,
                1.0
            ));
            vec2 g = grad / max(bevel, 0.001);
            float gLen = length(g);
            g = gLen > 1.5 ? g * (1.5 / gLen) : g;

            vec3 normal = normalize(vec3(g * (1.0 - z) * 0.35, z));
            vec3 lDir = normalize(vec3(lightDir, 0.72));
            float diffuse = clamp((dot(normal, lDir) + 0.20) / 1.20, 0.0, 1.0);
            float rim = pow(1.0 - normal.z, 2.4) * 0.18;
            float specular = pow(
                clamp(dot(reflect(-lDir, normal), vec3(0.0, 0.0, 1.0)), 0.0, 1.0),
                20.0
            ) * 0.12;
            float contact = mix(0.76, 1.0, smoothstep(0.0, bevel * 0.7, -sdf));

            return baseCol * (0.40 + 0.64 * diffuse) * contact
                + baseCol * rim
                + vec3(specular);
        }

        vec3 shadeOrb(vec3 baseCol, vec2 p, float radius, vec2 lightDir) {
            vec2 q = clamp(p / radius, vec2(-1.0), vec2(1.0));
            float z = sqrt(max(0.001, 1.0 - dot(q, q)));
            vec3 normal = normalize(vec3(q, z));
            vec3 lDir = normalize(vec3(lightDir, 0.8));
            float diffuse = max(dot(normal, lDir), 0.0);
            float specular = pow(
                max(dot(reflect(-lDir, normal), vec3(0.0, 0.0, 1.0)), 0.0),
                34.0
            );
            float rim = pow(1.0 - z, 2.0) * 0.16;
            return baseCol * (0.30 + 0.78 * diffuse)
                + baseCol * rim
                + vec3(1.0, 0.88, 0.66) * specular * 0.55;
        }

        vec3 renderBrickWall(
            vec2 p,
            vec2 scale,
            vec2 firePos,
            float bass,
            float fireLight,
            float cavity
        ) {
            vec2 st = p * scale;
            float row = floor(st.y);
            st.x += mod(row, 2.0) * 0.5;
            vec2 id = floor(st);
            vec2 f = fract(st) - 0.5;

            vec2 d2 = abs(f) - vec2(0.45, 0.41);
            float brickSdf = min(max(d2.x, d2.y), 0.0)
                + length(max(d2, 0.0)) - 0.04;

            float h = hash12(id);
            float grain = noise(st * vec2(7.0, 15.0) + id * 0.37);
            vec3 brickTone = uBrickColor * (0.84 + 0.25 * h);
            brickTone *= 0.91 + 0.15 * grain;
            brickTone.r += 0.025 * (h - 0.5);

            vec2 localNormal = f * vec2(1.4, 1.8);
            float bevel = 1.0 - smoothstep(-0.14, 0.0, brickSdf);
            vec3 normal = normalize(vec3(
                -localNormal * (1.0 - bevel * 0.65),
                0.55 + bevel * 0.45
            ));

            vec3 toFire = normalize(vec3(firePos - p, 0.30));
            float fireDiffuse = max(dot(normal, toFire), 0.0);
            float topDiffuse = max(
                dot(normal, normalize(vec3(-0.25, 0.60, 0.80))),
                0.0
            );
            float distanceToFire = length(firePos - p);
            float attenuation = 1.0 / (1.0 + distanceToFire * distanceToFire * 3.0);

            // Cool room fill keeps the orange firelight from clipping the bricks.
            vec3 litBrick = brickTone * (0.27 + 0.43 * topDiffuse);
            litBrick += brickTone * vec3(0.09, 0.12, 0.19);
            litBrick += (uGlowColor * 1.15 + vec3(0.24, 0.065, 0.006))
                * fireDiffuse * attenuation * fireLight * (1.15 + bass * 0.22);

            float edgeOcclusion = mix(0.70, 1.0, bevel);
            litBrick *= edgeOcclusion * (1.0 - cavity * 0.70);

            vec3 mortarColor = uBrickColor * (0.17 + 0.08 * grain);
            mortarColor *= 1.0 - cavity * 0.65;
            float mask = 1.0 - smoothstep(-0.02, 0.01, brickSdf);
            return mix(mortarColor, litBrick, mask);
        }

        // A rounded base and a tapered tip keep the character soft and readable.
        float sdFlameShape(vec2 p, float w, float h, float sway) {
            p.y += h * 0.45;
            float yNorm = clamp(p.y / h, 0.0, 1.0);
            p.x -= sway * pow(yNorm, 1.55);

            float body = 0.38 + 0.62 * sin(pow(yNorm, 0.65) * PI);
            float tip = 1.0 - smoothstep(0.72, 1.0, yNorm);
            float radius = w * body * (1.0 - 0.25 * yNorm) * tip;
            vec2 d = vec2(
                abs(p.x) - radius,
                abs(p.y - h * 0.5) - h * 0.5
            );
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        vec3 acesSoft(vec3 x) {
            return clamp(
                (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14),
                0.0,
                1.0
            );
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;
            float px = 1.25 / max(uResolution.y, 1.0);
            vec2 fireCenter = vec2(0.0, -0.20);

            float slowFlicker = noise(vec2(uTime * 2.1, 4.7));
            float fireLight = 0.86 + 0.09 * slowFlicker
                + 0.035 * sin(uTime * 11.3)
                + 0.025 * sin(uTime * 17.7 + 1.4)
                + uBass * 0.10;
            float bounce = sin(uTime * 5.4) * 0.006 + uBass * 0.022;

            // ---------------------------------------------------------------------
            // 1. PLASTER WALL, COOL WINDOW FILL, AND SOFT SNOW
            // ---------------------------------------------------------------------
            float plaster = fbm(uv * vec2(4.0, 5.0));
            vec3 col = uBgColor * (0.64 + 0.12 * plaster + 0.08 * uv.y);
            col += vec3(0.018, 0.028, 0.052) * (0.55 + uv.y);

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                vec2 windowCenter = vec2(side * 0.72, 0.12);
                vec2 wPos = uv - windowCenter;
                float windowOuter = sdBox2D(wPos, vec2(0.22, 0.36), 0.012);
                float windowPane = sdBox2D(wPos, vec2(0.18, 0.32), 0.008);

                float coolSpill = exp(-length(wPos * vec2(0.85, 0.70)) * 4.0);
                col += vec3(0.025, 0.045, 0.085) * coolSpill * 0.32;

                if (windowOuter < 0.012) {
                    vec3 sky = mix(
                        vec3(0.035, 0.055, 0.13),
                        vec3(0.09, 0.13, 0.25),
                        clamp(uv.y + 0.5, 0.0, 1.0)
                    );
                    float moonHaze = exp(-length(wPos - vec2(-side * 0.07, 0.20)) * 5.0);
                    sky += vec3(0.12, 0.17, 0.25) * moonHaze * 0.35;

                    for (float layer = 1.0; layer <= 3.0; layer += 1.0) {
                        vec2 snowUv = wPos * (7.0 + layer * 4.0);
                        snowUv.y += uTime * (0.42 / layer);
                        snowUv.x += sin(uTime * 0.35 + layer * 2.0) * 0.24;
                        vec2 snowId = floor(snowUv);
                        vec2 snowF = fract(snowUv) - 0.5;
                        vec2 randomOffset = vec2(
                            hash12(snowId) - 0.5,
                            hash12(snowId + 17.3) - 0.5
                        ) * 0.52;
                        float flakeDistance = length(snowF - randomOffset);
                        float radius = (0.105 / layer)
                            * step(0.64, hash12(snowId + 91.1));
                        if (radius > 0.0) {
                            float flake = 1.0 - smoothstep(
                                radius * 0.35,
                                radius,
                                flakeDistance
                            );
                            sky += vec3(0.82, 0.90, 1.0) * flake * (0.72 / layer);
                        }
                    }

                    float snowLine1 = -0.01 + 0.012 * sin(wPos.x * 25.0)
                        + 0.006 * sin(wPos.x * 53.0);
                    float snowLine2 = -0.30 + 0.014 * cos(wPos.x * 20.0);
                    float bank1 = (1.0 - smoothstep(0.018, 0.034, abs(wPos.y + 0.01)))
                        * (1.0 - smoothstep(snowLine1, snowLine1 + 0.006, wPos.y));
                    float bank2 = (1.0 - smoothstep(-0.27, -0.25, wPos.y))
                        * (1.0 - smoothstep(snowLine2, snowLine2 + 0.006, wPos.y));
                    float snowBank = max(bank1, bank2);
                    vec3 snowColor = vec3(0.72, 0.82, 0.94)
                        + vec3(0.12) * max(wPos.y - snowLine1, 0.0) * 12.0;
                    sky = mix(sky, snowColor, clamp(snowBank, 0.0, 1.0));

                    float crossBars = min(
                        abs(wPos.x) - 0.016,
                        abs(wPos.y + 0.02) - 0.016
                    );
                    float skyRegion = max(-windowPane, -crossBars);
                    float woodGrain = noise(wPos * vec2(42.0, 8.0));
                    vec3 woodColor = vec3(0.40, 0.215, 0.125)
                        * (0.76 + 0.24 * woodGrain);
                    woodColor *= 0.82 + 0.18 * (1.0 - abs(wPos.x) * 2.0);
                    woodColor += uGlowColor * 0.11 * fireLight;

                    float paneShadow = 1.0 - smoothstep(0.0, 0.035, -windowPane);
                    sky *= 1.0 - paneShadow * 0.32;
                    vec3 windowFinal = mix(
                        woodColor,
                        sky,
                        smoothstep(-0.004, 0.004, skyRegion)
                    );
                    col = mix(col, windowFinal, fillMask(windowOuter, px));
                }
            }

            // ---------------------------------------------------------------------
            // 2. BRICK SURROUND, RECESSED FIREBOX, STONE HEARTH, AND MANTEL
            // ---------------------------------------------------------------------
            float mainWall = sdBox2D(uv - vec2(0.0, 0.02), vec2(0.54, 0.42), 0.02);
            if (mainWall < 0.012) {
                vec3 wallBricks = renderBrickWall(
                    uv,
                    vec2(4.5, 9.5),
                    fireCenter,
                    uBass,
                    fireLight,
                    0.0
                );
                float mantelShadow = exp(-abs(uv.y - 0.325) * 26.0)
                    * (1.0 - smoothstep(0.50, 0.59, abs(uv.x)));
                wallBricks *= 1.0 - mantelShadow * 0.30;
                col = mix(col, wallBricks, fillMask(mainWall, px));
            }

            // The dark recess separates the fire from the front brick plane.
            vec2 openingP = uv - vec2(0.0, -0.185);
            float opening = sdBox2D(openingP, vec2(0.318, 0.235), 0.052);
            float openingShadow = (1.0 - smoothstep(0.0, 0.040, opening))
                * smoothstep(-0.004, 0.006, opening);
            col *= 1.0 - openingShadow * 0.28;

            if (opening < 0.008) {
                vec2 backSt = (uv + vec2(0.0, 0.08)) * vec2(7.0, 13.0);
                backSt.x += mod(floor(backSt.y), 2.0) * 0.5;
                vec2 backF = abs(fract(backSt) - 0.5);
                float backJoint = smoothstep(0.39, 0.47, max(backF.x, backF.y));
                float backNoise = noise(backSt * 2.4);

                vec3 cavityColor = uBrickColor * (0.075 + 0.055 * backNoise);
                cavityColor *= 1.0 - backJoint * 0.52;
                cavityColor += vec3(0.009, 0.010, 0.016);

                float sideOcclusion = smoothstep(0.20, 0.318, abs(uv.x));
                float topOcclusion = smoothstep(-0.06, 0.07, uv.y);
                float backOcclusion = smoothstep(-0.40, -0.26, uv.y);
                cavityColor *= 1.0 - sideOcclusion * 0.50;
                cavityColor *= 1.0 - topOcclusion * 0.55;
                cavityColor *= 0.58 + 0.42 * backOcclusion;

                float reflectedFire = exp(
                    -length((uv - vec2(0.0, -0.32)) * vec2(1.25, 1.0)) * 4.4
                );
                cavityColor += uGlowColor * reflectedFire * 0.15 * fireLight;

                float soot = exp(-length((uv - vec2(0.0, 0.015)) * vec2(1.2, 2.8)) * 5.0);
                cavityColor *= 1.0 - soot * 0.35;
                col = mix(col, cavityColor, fillMask(opening, px));
            }

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                vec2 pillarPos = uv - vec2(side * 0.46, -0.20);
                float pillarSdf = sdBox2D(pillarPos, vec2(0.15, 0.22), 0.025);
                if (pillarSdf > 0.0 && pillarSdf < 0.026) {
                    float shadow = 1.0 - smoothstep(0.0, 0.026, pillarSdf);
                    col *= 1.0 - shadow * 0.25;
                }
                if (pillarSdf < 0.008) {
                    vec3 pillarBricks = renderBrickWall(
                        uv + vec2(0.12 * side, 0.03),
                        vec2(4.2, 8.8),
                        fireCenter,
                        uBass,
                        fireLight,
                        0.0
                    );
                    float innerEdge = smoothstep(0.075, 0.15, abs(pillarPos.x));
                    pillarBricks *= 0.86 + 0.18 * innerEdge;
                    col = mix(col, pillarBricks, fillMask(pillarSdf, px));
                }
            }

            vec2 hearthFloorPos = uv - vec2(0.0, -0.44);
            float floorSdf = sdBox2D(hearthFloorPos, vec2(0.76, 0.055), 0.025);
            if (floorSdf < 0.008) {
                vec2 tileUv = vec2(uv.x * 5.0, (uv.y + 0.44) * 9.0);
                vec2 tileId = floor(tileUv);
                vec2 tileF = fract(tileUv) - 0.5;
                float tileSdf = sdBox2D(tileF, vec2(0.46, 0.42), 0.08);
                float tileNoise = noise(tileUv * 7.0 + tileId);
                vec3 tileColor = uBrickColor * 0.80 * (0.75 + 0.22 * tileNoise);
                tileColor *= 0.72 + 0.28 * smoothstep(-0.12, 0.02, tileSdf);
                float centerGlow = 1.0 / (1.0 + abs(uv.x) * 3.5);
                tileColor += uGlowColor * centerGlow * (0.25 + uBass * 0.12) * fireLight;
                tileColor = mix(
                    uBrickColor * 0.20,
                    tileColor,
                    1.0 - smoothstep(-0.02, 0.02, tileSdf)
                );
                float frontEdge = smoothstep(-0.050, -0.025, hearthFloorPos.y);
                tileColor *= 0.72 + 0.28 * frontEdge;
                col = mix(col, tileColor, fillMask(floorSdf, px));
            }

            // Contact shadow anchors the logs and character to the hearth.
            float logShadow = exp(
                -dot(
                    (uv - vec2(0.0, -0.392)) * vec2(2.0, 16.0),
                    (uv - vec2(0.0, -0.392)) * vec2(2.0, 16.0)
                )
            );
            col *= 1.0 - logShadow * 0.28;

            float mantelSdf = sdBox2D(uv - vec2(0.0, 0.39), vec2(0.60, 0.05), 0.02);
            if (mantelSdf < 0.008) {
                vec3 mantelColor = shadeClay(
                    uBrickColor * 0.79,
                    mantelSdf,
                    uv - vec2(0.0, 0.39),
                    vec2(-0.25, -0.85),
                    0.04
                );
                mantelColor += uGlowColor * 0.035 * fireLight;
                col = mix(col, mantelColor, fillMask(mantelSdf, px));
            }

            // ---------------------------------------------------------------------
            // 3. BARK, END GRAIN, AND LOW EMBERS
            // ---------------------------------------------------------------------
            for (float side = -1.0; side <= 1.0; side += 2.0) {
                for (float index = 0.0; index < 2.0; index += 1.0) {
                    vec2 logCenter = vec2(
                        side * (0.21 + index * 0.07),
                        -0.33 + index * 0.045
                    );
                    vec2 lp = uv - logCenter;
                    float angle = side * (-0.22 + index * 0.12);
                    mat2 rotation = mat2(
                        cos(angle), -sin(angle),
                        sin(angle), cos(angle)
                    );
                    vec2 rlp = rotation * lp;

                    float bodySdf = sdBox2D(rlp, vec2(0.09, 0.042), 0.038);
                    vec2 endPos = lp - vec2(-side * 0.055, -0.01);
                    float endSdf = length(endPos * vec2(1.15, 1.0)) - 0.042;

                    if (bodySdf < 0.006) {
                        float cylinder = sqrt(clamp(
                            1.0 - pow(rlp.y / 0.042, 2.0),
                            0.0,
                            1.0
                        ));
                        float barkNoise = fbm(rlp * vec2(52.0, 13.0) + index * 3.7);
                        float barkGroove = 0.72 + 0.28 * sin(
                            rlp.x * 150.0 + barkNoise * 7.0
                        );
                        vec3 bark = vec3(0.30, 0.145, 0.078)
                            * (0.34 + 0.70 * cylinder)
                            * (0.72 + 0.30 * barkNoise)
                            * (0.88 + 0.12 * barkGroove);

                        float fireSide = clamp(-side * rlp.x * 8.0 + 0.38, 0.0, 1.0);
                        float emberPulse = 0.80 + 0.12 * sin(uTime * 6.7 + index)
                            + 0.08 * slowFlicker + uBass * 0.16;
                        bark += vec3(1.0, 0.27, 0.025)
                            * pow(fireSide, 2.0) * emberPulse;
                        col = mix(col, bark, fillMask(bodySdf, px));
                    }

                    if (endSdf < 0.005) {
                        float ringNoise = noise(endPos * 160.0);
                        float rings = sin(length(endPos) * 195.0 + ringNoise * 2.5) * 0.055;
                        vec3 woodCut = vec3(0.67, 0.40, 0.20) + rings;
                        woodCut *= 0.76 + 0.24 * ringNoise;
                        woodCut = mix(
                            vec3(0.20, 0.085, 0.040),
                            woodCut,
                            1.0 - smoothstep(-0.014, -0.003, endSdf)
                        );
                        col = mix(col, woodCut, fillMask(endSdf, px));
                    }
                }
            }

            for (float ember = 0.0; ember < 10.0; ember += 1.0) {
                float seed = hash12(vec2(ember * 7.3, 12.7));
                float seed2 = hash12(vec2(ember * 3.9, 41.2));
                vec2 emberPos = vec2((seed - 0.5) * 0.48, -0.392 + seed2 * 0.025);
                float pulse = 0.55 + 0.45 * sin(uTime * (2.0 + seed * 4.0) + seed * 18.0);
                pulse = 0.35 + 0.65 * pulse * pulse;
                float emberDistance = length(uv - emberPos) - (0.003 + seed2 * 0.004);
                float emberCore = fillMask(emberDistance, px);
                float emberGlow = exp(-length(uv - emberPos) * 75.0);
                col += vec3(1.0, 0.16, 0.012) * emberGlow * pulse * 0.18;
                col = mix(col, vec3(1.0, 0.34, 0.035), emberCore * pulse);
            }

            // ---------------------------------------------------------------------
            // 4. KAWAII FIRE CHARACTER WITH ORGANIC MOTION
            // ---------------------------------------------------------------------
            vec2 fp = uv - vec2(0.0, -0.35);
            fp.y /= 1.0 + bounce * 1.25;
            fp.x += sin(uTime * 3.1 + fp.y * 9.0) * 0.004
                * smoothstep(-0.02, 0.35, fp.y);

            float auraDistance = length((uv - vec2(0.0, -0.16)) * vec2(0.92, 0.76));
            float broadAura = exp(-auraDistance * 4.4);
            float closeAura = exp(-auraDistance * 10.0);
            col += uGlowColor * (broadAura * 0.28 + closeAura * 0.13)
                * fireLight * (0.90 + uBass * 0.16);

            float sway = sin(uTime * 3.8) * 0.022
                + (noise(vec2(uTime * 1.7, 8.2)) - 0.5) * 0.025;
            float mainLobe = sdFlameShape(fp, 0.21, 0.48, sway);
            float leftLobe = sdFlameShape(
                fp - vec2(-0.08, 0.03),
                0.12,
                0.34,
                sway - 0.030
            );
            float rightLobe = sdFlameShape(
                fp - vec2(0.08, 0.02),
                0.12,
                0.32,
                sway + 0.030
            );
            float outerFlame = smin(mainLobe, smin(leftLobe, rightLobe, 0.06), 0.06);

            float armWave = sin(uTime * 5.5) * 0.012 + uBass * 0.022;
            float armL = sdSegment(
                fp,
                vec2(-0.14, 0.14),
                vec2(-0.22, 0.17 + armWave)
            ) - 0.024;
            float armR = sdSegment(
                fp,
                vec2(0.14, 0.14),
                vec2(0.22, 0.19 - armWave)
            ) - 0.024;
            float footL = sdBox2D(
                fp - vec2(-0.065, -0.015),
                vec2(0.026, 0.035),
                0.022
            );
            float footR = sdBox2D(
                fp - vec2(0.065, -0.015),
                vec2(0.026, 0.035),
                0.022
            );
            outerFlame = smin(
                outerFlame,
                min(min(armL, armR), min(footL, footR)),
                0.025
            );

            vec2 midP = fp + vec2(
                sin(uTime * 4.7) * 0.004,
                0.0
            );
            float midFlame = smin(
                sdFlameShape(midP - vec2(0.0, 0.01), 0.165, 0.38, sway * 0.8),
                min(
                    sdFlameShape(midP - vec2(-0.06, 0.02), 0.09, 0.25, -0.02),
                    sdFlameShape(midP - vec2(0.06, 0.02), 0.09, 0.24, 0.02)
                ),
                0.05
            );
            float coreFlame = sdFlameShape(
                fp - vec2(0.0, 0.015),
                0.125,
                0.27,
                sway * 0.5
            );

            if (outerFlame < 0.008) {
                vec3 outerColor = shadeClay(
                    uFlameOuter,
                    outerFlame,
                    fp - vec2(0.0, 0.18),
                    vec2(-0.15, 0.55),
                    0.045
                );
                outerColor = mix(outerColor, uFlameOuter, 0.24);
                outerColor *= 0.94 + fireLight * 0.10;
                col = mix(col, outerColor, fillMask(outerFlame, px));
            }
            if (midFlame < 0.008) {
                vec3 midColor = shadeClay(
                    uFlameMid,
                    midFlame,
                    fp - vec2(0.0, 0.15),
                    vec2(-0.10, 0.55),
                    0.035
                );
                float innerVariation = noise(fp * vec2(18.0, 12.0) - vec2(0.0, uTime * 1.6));
                midColor = mix(midColor, uFlameMid, 0.32 + innerVariation * 0.08);
                col = mix(col, midColor, fillMask(midFlame, px));
            }
            if (coreFlame < 0.008) {
                vec3 coreColor = shadeClay(
                    uFlameCore,
                    coreFlame,
                    fp - vec2(0.0, 0.12),
                    vec2(0.0, 0.50),
                    0.030
                );
                coreColor = mix(coreColor, uFlameCore * 1.08, 0.42);
                col = mix(col, coreColor, fillMask(coreFlame, px));
            }

            // The face stays quiet while the body and arms react to the music.
            vec2 faceP = fp - vec2(0.0, 0.13);
            vec2 symFace = vec2(abs(faceP.x) - 0.052, faceP.y - 0.015);
            float eyeArc = abs(length(symFace - vec2(0.0, -0.012)) - 0.020) - 0.0055;
            eyeArc = max(eyeArc, -(symFace.y + 0.010));
            float cheek = length(
                (symFace - vec2(0.012, -0.026)) * vec2(0.8, 1.3)
            ) - 0.016;
            vec2 mouthP = faceP - vec2(0.0, -0.018);
            float mouthRadius = 0.024 + min(uBass, 1.0) * 0.012;
            float mouthSdf = max(length(mouthP) - mouthRadius, mouthP.y);

            float cheekMask = fillMask(cheek, 0.005) * 0.74;
            col = mix(col, vec3(0.96, 0.42, 0.48), cheekMask);
            col = mix(col, vec3(0.24, 0.08, 0.08), fillMask(eyeArc, px));

            if (mouthSdf < 0.005) {
                float tongue = length(
                    mouthP - vec2(0.0, -mouthRadius * 0.85)
                ) - mouthRadius * 0.75;
                vec3 mouthColor = mix(
                    vec3(0.30, 0.055, 0.075),
                    vec3(0.94, 0.38, 0.42),
                    fillMask(tongue, 0.002)
                );
                col = mix(col, mouthColor, fillMask(mouthSdf, px));
            }

            // ---------------------------------------------------------------------
            // 5. SMALL MAGIC SPARKS
            // ---------------------------------------------------------------------
            for (float index = 1.0; index <= 9.0; index += 1.0) {
                float seed = hash12(vec2(index * 37.1, index * 19.7));
                float t = fract(uTime * (0.16 + seed * 0.11) + seed);
                vec2 starPos = vec2(
                    sin(index * 2.4 + t * 4.0) * (0.14 + 0.08 * seed),
                    -0.08 + t * 0.36
                );
                float size = (0.010 + 0.011 * fract(seed * 7.3))
                    * sin(t * PI) * (1.0 + min(uTreble, 1.5) * 0.40);
                if (size > 0.001) {
                    float starDistance = mod(index, 2.0) < 0.5
                        ? sdStar4(uv - starPos, size)
                        : length(uv - starPos) - size * 0.45;
                    float starMask = fillMask(starDistance, px);
                    float starGlow = exp(-length(uv - starPos) * 95.0);
                    col += vec3(1.0, 0.78, 0.34) * starGlow * 0.055;
                    col = mix(col, vec3(1.0, 0.96, 0.72), starMask * 0.92);
                }
            }

            // ---------------------------------------------------------------------
            // 6. SANTA HAT AND STOCKINGS
            // ---------------------------------------------------------------------
            vec2 hatP = uv - vec2(-0.46, 0.06);
            float hatBody = sdFlameShape(
                (hatP - vec2(-0.02, -0.02)) * vec2(1.0, 1.2),
                0.11,
                0.18,
                -0.09
            );
            float hatBrim = sdBox2D(
                hatP - vec2(0.0, -0.025),
                vec2(0.105, 0.026),
                0.022
            );
            float hatPom = length(hatP - vec2(-0.115, 0.045)) - 0.034;

            if (hatBody < 0.007) {
                float clothNoise = noise(hatP * 95.0);
                vec3 redCloth = shadeClay(
                    vec3(0.76, 0.095, 0.13) * (0.91 + 0.09 * clothNoise),
                    hatBody,
                    hatP,
                    vec2(0.50, 0.50),
                    0.035
                );
                redCloth *= 0.90 + fireLight * 0.10;
                col = mix(col, redCloth, fillMask(hatBody, px));
            }
            float whiteFluff = min(hatBrim, hatPom);
            if (whiteFluff < 0.007) {
                vec3 fluffColor = shadeClay(
                    vec3(0.88, 0.90, 0.96),
                    whiteFluff,
                    hatP,
                    vec2(0.40, 0.60),
                    0.025
                );
                if (hatPom < hatBrim) {
                    fluffColor = shadeOrb(
                        vec3(0.88, 0.90, 0.96),
                        hatP - vec2(-0.115, 0.045),
                        0.034,
                        vec2(0.40, 0.60)
                    );
                }
                col = mix(col, fluffColor, fillMask(whiteFluff, px));
            }

            for (float sock = 0.0; sock < 2.0; sock += 1.0) {
                vec2 anchor = vec2(0.18 + sock * 0.22, 0.33 - sock * 0.02);
                vec2 sp = uv - anchor;
                float swingAngle = -0.15 + sin(uTime * 2.2 + sock * 1.5) * 0.045;
                sp = mat2(
                    cos(swingAngle), -sin(swingAngle),
                    sin(swingAngle), cos(swingAngle)
                ) * sp;

                float cuff = sdBox2D(sp, vec2(0.038, 0.018), 0.012);
                float leg = sdBox2D(sp - vec2(0.0, -0.055), vec2(0.032, 0.052), 0.022);
                float foot = sdBox2D(sp - vec2(0.022, -0.095), vec2(0.048, 0.026), 0.024);
                float body = smin(leg, foot, 0.015);

                if (body < 0.007) {
                    vec3 sockColor = sock < 0.5
                        ? vec3(0.75, 0.11, 0.15)
                        : vec3(0.16, 0.39, 0.18);
                    if (sock > 0.5 && abs(sp.y + 0.045) < 0.012) {
                        sockColor = vec3(0.82, 0.60, 0.15);
                    }
                    float knit = noise(sp * vec2(120.0, 85.0));
                    sockColor *= 0.91 + knit * 0.09;
                    vec3 sockLit = shadeClay(
                        sockColor,
                        body,
                        sp,
                        vec2(-0.50, 0.50),
                        0.025
                    );
                    if (sock < 0.5 && length(sp - vec2(0.048, -0.098)) < 0.028) {
                        sockLit = shadeClay(
                            vec3(0.89, 0.89, 0.94),
                            body,
                            sp,
                            vec2(-0.50, 0.50),
                            0.020
                        );
                    }
                    sockLit *= 0.90 + fireLight * 0.10;
                    col = mix(col, sockLit, fillMask(body, px));
                }
                if (cuff < 0.007) {
                    vec3 cuffLit = shadeClay(
                        vec3(0.88, 0.89, 0.94),
                        cuff,
                        sp,
                        vec2(0.0, 0.70),
                        0.015
                    );
                    col = mix(col, cuffLit, fillMask(cuff, px));
                }
            }

            // ---------------------------------------------------------------------
            // 7. GARLAND, ORNAMENTS, AND BELLS
            // ---------------------------------------------------------------------
            float garlandCurveY = 0.42 - 0.10 * cos(clamp(uv.x * 2.4, -PI, PI));
            if (abs(uv.x) < 0.68 && uv.y > 0.24) {
                float leafClusters = sin(uv.x * 38.0) * 0.015
                    + cos(uv.x * 75.0) * 0.008;
                float garlandSdf = abs(uv.y - garlandCurveY) + leafClusters - 0.052;
                if (garlandSdf < 0.009) {
                    float needles = noise(uv * vec2(180.0, 95.0));
                    vec3 pineColor = shadeClay(
                        vec3(0.105, 0.34, 0.15) * (0.82 + needles * 0.24),
                        garlandSdf,
                        vec2(0.0, uv.y - garlandCurveY),
                        vec2(-0.20, -0.65),
                        0.04
                    );
                    pineColor += uGlowColor * 0.025 * fireLight;
                    col = mix(col, pineColor, fillMask(garlandSdf, px));
                }

                for (float ball = -5.0; ball <= 5.0; ball += 1.0) {
                    if (abs(ball) < 0.5) {
                        continue;
                    }
                    float bx = ball * 0.11;
                    float by = 0.42 - 0.10 * cos(clamp(bx * 2.4, -PI, PI)) - 0.035;
                    vec2 bp = uv - vec2(bx, by);
                    float ballSdf = length(bp) - 0.024;
                    if (ballSdf < 0.006) {
                        vec3 ballBase = mod(abs(ball), 2.0) < 0.5
                            ? vec3(0.78, 0.085, 0.12)
                            : vec3(0.84, 0.57, 0.12);
                        vec3 ballLit = shadeOrb(ballBase, bp, 0.024, vec2(-0.45, 0.60));
                        col = mix(col, ballLit, fillMask(ballSdf, px));
                    }
                }
            }

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                vec2 bellP = uv - vec2(side * 0.038, 0.46);
                float bellAngle = side * 0.35;
                bellP = mat2(
                    cos(bellAngle), -sin(bellAngle),
                    sin(bellAngle), cos(bellAngle)
                ) * bellP;
                float bellSdf = sdBox2D(
                    bellP,
                    vec2(0.032 - bellP.y * 0.3, 0.038),
                    0.016
                );
                if (bellSdf < 0.006) {
                    vec3 goldBell = shadeClay(
                        vec3(0.86, 0.60, 0.16),
                        bellSdf,
                        bellP,
                        vec2(-0.25, -0.80),
                        0.025
                    );
                    col = mix(col, goldBell, fillMask(bellSdf, px));
                }
            }

            // A restrained vignette and highlight rolloff preserve dark detail.
            float vignette = dot(uv * vec2(0.86, 1.0), uv * vec2(0.86, 1.0));
            col *= clamp(1.06 - vignette * 0.22, 0.72, 1.06);
            col = acesSoft(max(col, vec3(0.0)));
            col = pow(col, vec3(0.94));

            // Static sub-LSB dither reduces gradient banding without visible shimmer.
            col += (hash12(gl_FragCoord.xy) - 0.5) / 255.0;
            fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
        }
    """.trimIndent()

    private val quadVertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private lateinit var vertexBuffer: FloatBuffer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        vertexBuffer = ByteBuffer.allocateDirect(quadVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(quadVertices)
                position(0)
            }

        startTimeNs = System.nanoTime()
        lastFrameTimeNs = 0L
        smoothBass = bassEnergy.coerceIn(0f, 1.5f)
        smoothTreble = trebleEnergy.coerceIn(0f, 1.5f)

        GLES32.glClearColor(0f, 0f, 0f, 1f)
        GLES32.glDisable(GLES32.GL_DEPTH_TEST)
        GLES32.glDisable(GLES32.GL_BLEND)

        val vertexShader = loadShader(GLES32.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES32.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES32.glCreateProgram()
        GLES32.glAttachShader(program, vertexShader)
        GLES32.glAttachShader(program, fragmentShader)
        GLES32.glLinkProgram(program)
        GLES32.glDeleteShader(vertexShader)
        GLES32.glDeleteShader(fragmentShader)

        val linkStatus = IntArray(1)
        GLES32.glGetProgramiv(program, GLES32.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES32.glGetProgramInfoLog(program)
            GLES32.glDeleteProgram(program)
            program = 0
            Log.e(TAG, "Fireplace program link failed: $log")
            return
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
        positionHandle = GLES32.glGetAttribLocation(program, "aPosition")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES32.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
    }

    override fun onDrawFrame(gl: GL10?) {
        if (program == 0 || positionHandle < 0) {
            return
        }

        val nowNs = System.nanoTime()
        val elapsed = (nowNs - startTimeNs) / 1_000_000_000f
        val deltaSeconds = if (lastFrameTimeNs == 0L) {
            1f / 60f
        } else {
            ((nowNs - lastFrameTimeNs) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
        }
        lastFrameTimeNs = nowNs

        val bassTarget = bassEnergy.coerceIn(0f, 1.5f)
        val trebleTarget = trebleEnergy.coerceIn(0f, 1.5f)
        smoothBass = smoothAudio(smoothBass, bassTarget, deltaSeconds, 18f, 5f)
        smoothTreble = smoothAudio(smoothTreble, trebleTarget, deltaSeconds, 24f, 8f)

        GLES32.glClear(GLES32.GL_COLOR_BUFFER_BIT)
        GLES32.glUseProgram(program)

        GLES32.glUniform1f(timeLocation, elapsed)
        GLES32.glUniform2f(resolutionLocation, width, height)
        GLES32.glUniform1f(bassLocation, smoothBass)
        GLES32.glUniform1f(trebleLocation, smoothTreble)

        val theme = currentTheme
        GLES32.glUniform3fv(bgColLoc, 1, theme.bgColor, 0)
        GLES32.glUniform3fv(brickColLoc, 1, theme.brickColor, 0)
        GLES32.glUniform3fv(flameOuterLoc, 1, theme.flameOuter, 0)
        GLES32.glUniform3fv(flameMidLoc, 1, theme.flameMid, 0)
        GLES32.glUniform3fv(flameCoreLoc, 1, theme.flameCore, 0)
        GLES32.glUniform3fv(glowColLoc, 1, theme.glowColor, 0)

        GLES32.glEnableVertexAttribArray(positionHandle)
        GLES32.glVertexAttribPointer(
            positionHandle,
            2,
            GLES32.GL_FLOAT,
            false,
            0,
            vertexBuffer
        )
        GLES32.glDrawArrays(GLES32.GL_TRIANGLE_STRIP, 0, 4)
        GLES32.glDisableVertexAttribArray(positionHandle)
    }

    private fun smoothAudio(
        current: Float,
        target: Float,
        deltaSeconds: Float,
        attack: Float,
        release: Float
    ): Float {
        val rate = if (target > current) attack else release
        val blend = 1f - exp(-deltaSeconds * rate)
        return current + (target - current) * blend
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES32.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("OpenGL could not create a shader")
        }

        GLES32.glShaderSource(shader, shaderCode)
        GLES32.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES32.glGetShaderiv(shader, GLES32.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES32.glGetShaderInfoLog(shader)
            GLES32.glDeleteShader(shader)
            throw IllegalStateException("Fireplace shader compilation failed: $log")
        }
        return shader
    }

    private companion object {
        const val TAG = "KawaiiFireplace"
    }
}
