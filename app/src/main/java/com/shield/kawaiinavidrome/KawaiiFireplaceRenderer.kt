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

        // --- FUNCIONES MATEMÁTICAS Y RUIDO ---
        float hash12(vec2 p) {
            vec3 p3  = fract(vec3(p.xyx) * .1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float noise(vec2 p) {
            vec2 i = floor(p);
            vec2 f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash12(i + vec2(0.0,0.0)), hash12(i + vec2(1.0,0.0)), u.x),
                       mix(hash12(i + vec2(0.0,1.0)), hash12(i + vec2(1.0,1.0)), u.x), u.y);
        }

        float smin(float a, float b, float k) {
            float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
            return mix(b, a, h) - k * h * (1.0 - h);
        }

        float smax(float a, float b, float k) {
            return -smin(-a, -b, k);
        }

        // --- PRIMITIVAS 2D Y 3D ---
        float sdBox2D(vec2 p, vec2 b, float r) {
            vec2 d = abs(p) - b + r;
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
        }

        float sdSegment(vec2 p, vec2 a, vec2 b) {
            vec2 pa = p - a, ba = b - a;
            float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
            return length(pa - ba * h);
        }

        float sdStar4(vec2 p, float r) {
            p = abs(p);
            return (p.x + p.y + abs(p.x - p.y) * 0.55) - r;
        }

        // Sombreado volumétrico falso 3D (Clay Render look) a partir de un SDF 2D
        vec3 shadeClay(vec3 baseCol, float sdf, vec2 grad, vec2 lightDir, float bevel) {
            float z = sqrt(clamp(1.0 - pow(clamp(1.0 + sdf / bevel, 0.0, 1.0), 2.0), 0.01, 1.0));
            vec3 normal = normalize(vec3(grad * (1.0 - z), z));
            vec3 lDir = normalize(vec3(lightDir, 0.65));
            float diff = clamp(dot(normal, lDir), 0.0, 1.0);
            float rim = pow(1.0 - normal.z, 2.2) * 0.35;
            float spec = pow(clamp(dot(reflect(-lDir, normal), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 12.0) * 0.25;
            return baseCol * (0.42 + 0.68 * diff) + baseCol * rim + vec3(spec);
        }

        // Ladrillo 3D con bisel redondeado y sombra de mortero
        vec3 renderBrickWall(vec2 p, vec2 scale, vec2 firePos, float fireBoost) {
            vec2 st = p * scale;
            float row = floor(st.y);
            st.x += mod(row, 2.0) * 0.5;
            vec2 id = floor(st);
            vec2 f = fract(st) - 0.5;

            // Distancia al borde del ladrillo (negativo dentro del ladrillo)
            vec2 d2 = abs(f) - vec2(0.45, 0.41);
            float bSdf = min(max(d2.x, d2.y), 0.0) + length(max(d2, 0.0)) - 0.04;

            // Variación cálida por ladrillo
            float h = hash12(id);
            vec3 brickTone = uBrickColor * (0.88 + 0.22 * h);
            brickTone.r += 0.04 * (h - 0.5);

            // Normal 3D del ladrillo (redondeado en los bordes como en la imagen de referencia)
            vec2 localNorm = f * vec2(1.4, 1.8);
            float bevel = smoothstep(0.0, -0.14, bSdf);
            vec3 norm = normalize(vec3(-localNorm * (1.0 - bevel * 0.65), 0.55 + bevel * 0.45));

            // Luz cálida procedente del fuego + luz cenital suave
            vec3 toFire = normalize(vec3(firePos - p, 0.28));
            float fireDiff = max(dot(norm, toFire), 0.0);
            float topDiff = max(dot(norm, normalize(vec3(0.0, 0.6, 0.8))), 0.0);
            float distToFire = length(firePos - p);
            float atten = 1.0 / (1.0 + distToFire * distToFire * 2.4);

            vec3 litBrick = brickTone * (0.35 + 0.45 * topDiff);
            litBrick += (uGlowColor * 1.1 + vec3(0.3, 0.1, 0.0)) * fireDiff * atten * (1.3 + fireBoost);

            // Sombra oscura en la junta entre ladrillos (mortero hundido)
            vec3 mortarCol = uBrickColor * 0.25;
            float mask = smoothstep(0.01, -0.02, bSdf);
            return mix(mortarCol, litBrick, mask);
        }

        // Forma 2D de una llama individual (con punta curvada)
        float sdFlameShape(vec2 p, float w, float h, float sway) {
            p.y += h * 0.45;
            float yNorm = clamp(p.y / h, 0.0, 1.0);
            p.x -= sway * pow(yNorm, 1.6);
            float r = w * sin(pow(yNorm, 0.65) * 3.14159) * (1.0 - 0.35 * yNorm);
            vec2 d = vec2(abs(p.x) - r, abs(p.y - h * 0.5) - h * 0.5);
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;
            vec2 fireCenter = vec2(0.0, -0.14);
            float bounce = sin(uTime * 6.0) * 0.012 + uBass * 0.045;

            // =========================================================================
            // 1. PARED TRASERA Y VENTANAS DE MADERA CON NIEVE ACUMULADA
            // =========================================================================
            vec3 col = uBgColor * 0.8;

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                vec2 wPos = uv - vec2(side * 0.72, 0.12);
                float winOuter = sdBox2D(wPos, vec2(0.22, 0.36), 0.01);
                float winPane  = sdBox2D(wPos, vec2(0.18, 0.32), 0.008);

                if (winOuter < 0.01) {
                    // Cielo nocturno azul oscuro
                    vec3 sky = mix(vec3(0.05, 0.07, 0.16), vec3(0.10, 0.14, 0.28), uv.y + 0.5);

                    // Copos de nieve redondos y suaves en 3 planos de profundidad
                    for (float l = 1.0; l <= 3.0; l++) {
                        vec2 sUv = wPos * (7.0 + l * 4.0);
                        sUv.y += uTime * (0.45 / l);
                        sUv.x += sin(uTime * 0.4 + l * 2.0) * 0.25;
                        vec2 sId = floor(sUv);
                        vec2 sF = fract(sUv) - 0.5;
                        vec2 randOffset = vec2(hash12(sId) - 0.5, hash12(sId + 17.3) - 0.5) * 0.5;
                        float flake = length(sF - randOffset);
                        float radius = (0.10 / l) * step(0.65, hash12(sId + 91.1));
                        sky += vec3(0.9, 0.95, 1.0) * smoothstep(radius, radius * 0.2, flake) * (0.9 / l);
                    }

                    // Nieve acumulada sobre los travesaños de la ventana
                    float snowBank1 = wPos.y - (-0.01 + 0.015 * sin(wPos.x * 25.0));
                    float snowBank2 = wPos.y - (-0.30 + 0.018 * cos(wPos.x * 20.0));
                    if ((abs(wPos.y + 0.01) < 0.035 && snowBank1 < 0.0) || (wPos.y < -0.27 && snowBank2 < 0.0)) {
                        sky = vec3(0.82, 0.88, 0.96);
                    }

                    // Marco de madera cálida con travesaños en cruz
                    float crossBars = min(abs(wPos.x) - 0.016, abs(wPos.y + 0.02) - 0.016);
                    float isWood = max(-winPane, -crossBars);
                    vec3 woodCol = vec3(0.46, 0.26, 0.16) * (0.8 + 0.3 * (1.0 - abs(wPos.x) * 2.0));
                    woodCol += uGlowColor * 0.18;

                    vec3 winFinal = mix(woodCol, sky, smoothstep(-0.004, 0.004, isWood));
                    col = mix(col, winFinal, smoothstep(0.005, -0.005, winOuter));
                }
            }

            // =========================================================================
            // 2. CHIMENEA DE LADRILLOS 3D (PARED + PILARES DELANTEROS + SUELO DE LOSAS)
            // =========================================================================
            // Pared principal de ladrillos
            float mainWall = sdBox2D(uv - vec2(0.0, 0.02), vec2(0.54, 0.42), 0.02);
            if (mainWall < 0.005) {
                vec3 wallBricks = renderBrickWall(uv, vec2(4.5, 9.5), fireCenter, uBass);
                // Sombra suave bajo la repisa y en el centro donde arde el fuego
                float hearthShadow = smoothstep(0.32, 0.05, length((uv - vec2(0.0, -0.18)) * vec2(0.85, 1.1)));
                wallBricks = mix(wallBricks, wallBricks * 0.45 + uGlowColor * 0.25, hearthShadow * 0.5);
                col = mix(col, wallBricks, smoothstep(0.005, -0.005, mainWall));
            }

            // Pilares laterales en relieve (como en la imagen de referencia)
            for (float s = -1.0; s <= 1.0; s += 2.0) {
                vec2 pillarPos = uv - vec2(s * 0.46, -0.20);
                float pillarSDF = sdBox2D(pillarPos, vec2(0.15, 0.22), 0.025);
                if (pillarSDF < 0.015) {
                    // Sombra proyectada por el pilar sobre la pared trasera
                    col *= smoothstep(0.0, 0.015, pillarSDF) * 0.4 + 0.6;
                }
                if (pillarSDF < 0.004) {
                    vec3 pBricks = renderBrickWall(uv + vec2(0.12 * s, 0.03), vec2(4.2, 8.8), fireCenter, uBass) * 1.08;
                    col = mix(col, pBricks, smoothstep(0.004, -0.004, pillarSDF));
                }
            }

            // Base de losas de piedra redondeadas en el suelo
            vec2 hearthFloorPos = uv - vec2(0.0, -0.44);
            float floorSDF = sdBox2D(hearthFloorPos, vec2(0.76, 0.055), 0.025);
            if (floorSDF < 0.004) {
                vec2 tileUv = vec2(uv.x * 5.0, (uv.y + 0.44) * 9.0);
                vec2 tileF = fract(tileUv) - 0.5;
                float tileSdf = sdBox2D(tileF, vec2(0.46, 0.42), 0.08);
                vec3 tileCol = uBrickColor * 0.92 * (0.75 + 0.35 * smoothstep(0.02, -0.12, tileSdf));
                float centerGlow = 1.0 / (1.0 + abs(uv.x) * 3.5);
                tileCol += uGlowColor * centerGlow * (0.45 + uBass * 0.3);
                tileCol = mix(uBrickColor * 0.25, tileCol, smoothstep(0.02, -0.02, tileSdf));
                col = mix(col, tileCol, smoothstep(0.004, -0.004, floorSDF));
            }

            // Repisa superior escalonada
            float mantelSDF = sdBox2D(uv - vec2(0.0, 0.39), vec2(0.60, 0.05), 0.02);
            if (mantelSDF < 0.004) {
                vec3 mantelCol = shadeClay(uBrickColor * 0.85, mantelSDF, uv - vec2(0.0, 0.39), vec2(0.0, -1.0), 0.04);
                col = mix(col, mantelCol, smoothstep(0.004, -0.004, mantelSDF));
            }

            // =========================================================================
            // 3. TRONCOS 3D EN PERSPECTIVA CON CORTEZA Y BRASAS ENCENDIDAS
            // =========================================================================
            for (float side = -1.0; side <= 1.0; side += 2.0) {
                for (float idx = 0.0; idx < 2.0; idx += 1.0) {
                    vec2 logCenter = vec2(side * (0.21 + idx * 0.07), -0.33 + idx * 0.045);
                    vec2 lp = uv - logCenter;
                    // Rotar ligeramente hacia el centro
                    float ang = side * (-0.22 + idx * 0.12);
                    mat2 rot = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
                    vec2 rlp = rot * lp;

                    // Cuerpo cilíndrico del tronco + cara circular frontal
                    float bodySdf = sdBox2D(rlp, vec2(0.09, 0.042), 0.038);
                    vec2 endPos = lp - vec2(-side * 0.055, -0.01);
                    float endSdf = length(endPos * vec2(1.15, 1.0)) - 0.042;

                    if (bodySdf < 0.004) {
                        float cylShade = sqrt(clamp(1.0 - pow(rlp.y / 0.042, 2.0), 0.0, 1.0));
                        vec3 bark = vec3(0.38, 0.20, 0.12) * (0.45 + 0.65 * cylShade);
                        // Brasa incandescente naranja en el lado que mira al fuego
                        float fireSide = clamp(-side * rlp.x * 8.0 + 0.4, 0.0, 1.0);
                        vec3 emberGlow = vec3(1.0, 0.38, 0.05) * pow(fireSide, 1.8) * (0.9 + 0.4 * sin(uTime * 7.0 + idx) + uBass);
                        bark += emberGlow;
                        col = mix(col, bark, smoothstep(0.004, -0.004, bodySdf));
                    }
                    if (endSdf < 0.003) {
                        float rings = sin(length(endPos) * 180.0) * 0.08;
                        vec3 woodCut = mix(vec3(0.85, 0.55, 0.30) + rings, vec3(1.0, 0.48, 0.12), clamp(1.0 - side * 0.5, 0.0, 1.0) * 0.5);
                        woodCut = mix(vec3(0.28, 0.14, 0.08), woodCut, smoothstep(-0.002, -0.010, endSdf));
                        col = mix(col, woodCut, smoothstep(0.003, -0.003, endSdf));
                    }
                }
            }

            // =========================================================================
            // 4. PERSONAJE DE FUEGO KAWAII 3D (SILUETA DE LLAMA + BRAZOS + PATITAS)
            // =========================================================================
            vec2 fp = uv - vec2(0.0, -0.35);
            fp.y /= (1.0 + bounce * 1.4);

            // Halo cálido detrás del personaje
            float aura = length((uv - vec2(0.0, -0.15)) * vec2(0.9, 0.75));
            col += uGlowColor * exp(-aura * 4.2) * (0.55 + uBass * 0.45);

            // Silueta exterior de 3 puntas + bracitos y patitas redondeadas
            float sway = sin(uTime * 4.5) * 0.025;
            float mainLobe  = sdFlameShape(fp, 0.21, 0.48, sway);
            float leftLobe  = sdFlameShape(fp - vec2(-0.08, 0.03), 0.12, 0.34, sway - 0.03);
            float rightLobe = sdFlameShape(fp - vec2(0.08, 0.02), 0.12, 0.32, sway + 0.03);
            float outerFlame = smin(mainLobe, smin(leftLobe, rightLobe, 0.06), 0.06);

            // Bracitos saludando al ritmo de la música
            float armWave = sin(uTime * 6.0) * 0.015 + uBass * 0.03;
            float armL = sdSegment(fp, vec2(-0.14, 0.14), vec2(-0.22, 0.17 + armWave)) - 0.024;
            float armR = sdSegment(fp, vec2(0.14, 0.14), vec2(0.22, 0.19 - armWave)) - 0.024;
            // Patitas kawaii sobre las losas
            float footL = sdBox2D(fp - vec2(-0.065, -0.015), vec2(0.026, 0.035), 0.022);
            float footR = sdBox2D(fp - vec2(0.065, -0.015), vec2(0.026, 0.035), 0.022);

            outerFlame = smin(outerFlame, min(min(armL, armR), min(footL, footR)), 0.025);

            // Capa media (naranja melocotón) y capa interior (crema brillante)
            float midFlame  = smin(
                sdFlameShape(fp - vec2(0.0, 0.01), 0.165, 0.38, sway * 0.8),
                min(sdFlameShape(fp - vec2(-0.06, 0.02), 0.09, 0.25, -0.02),
                    sdFlameShape(fp - vec2(0.06, 0.02), 0.09, 0.24, 0.02)),
                0.05
            );
            float coreFlame = sdFlameShape(fp - vec2(0.0, 0.015), 0.125, 0.27, sway * 0.5);

            // Renderizar las 3 capas con volumen 3D suave (Clay bevel)
            if (outerFlame < 0.004) {
                vec3 cOuter = shadeClay(uFlameOuter, outerFlame, fp - vec2(0.0, 0.18), vec2(0.0, 0.5), 0.045);
                col = mix(col, cOuter, smoothstep(0.004, -0.004, outerFlame));
            }
            if (midFlame < 0.004) {
                vec3 cMid = shadeClay(uFlameMid, midFlame, fp - vec2(0.0, 0.15), vec2(0.0, 0.5), 0.035);
                col = mix(col, cMid, smoothstep(0.004, -0.004, midFlame));
            }
            if (coreFlame < 0.004) {
                vec3 cCore = shadeClay(uFlameCore, coreFlame, fp - vec2(0.0, 0.12), vec2(0.0, 0.5), 0.03);
                col = mix(col, cCore, smoothstep(0.004, -0.004, coreFlame));
            }

            // Carita Kawaii feliz (ojitos arqueados ^ ^ y boca abierta sonriente como en la referencia)
            vec2 faceP = fp - vec2(0.0, 0.13);
            vec2 symFace = vec2(abs(faceP.x) - 0.052, faceP.y - 0.015);
            // Arco de ojo feliz ^
            float eyeArc = abs(length(symFace - vec2(0.0, -0.012)) - 0.020) - 0.0055;
            eyeArc = max(eyeArc, -(symFace.y + 0.010));
            // Mejillas rosadas
            float cheek = length(( symFace - vec2(0.012, -0.026) ) * vec2(0.8, 1.3)) - 0.016;
            // Boca feliz en 'D' invertida con lengüita rosa coral
            vec2 mP = faceP - vec2(0.0, -0.018);
            float mouthRadius = 0.024 + uBass * 0.015;
            float mouthSdf = max(length(mP) - mouthRadius, mP.y);

            if (cheek < 0.003) {
                col = mix(col, vec3(0.96, 0.42, 0.48), smoothstep(0.003, -0.003, cheek));
            }
            if (eyeArc < 0.002) {
                col = mix(col, vec3(0.24, 0.08, 0.08), smoothstep(0.002, -0.002, eyeArc));
            }
            if (mouthSdf < 0.002) {
                float tongue = length(mP - vec2(0.0, -mouthRadius * 0.85)) - mouthRadius * 0.75;
                vec3 inMouth = mix(vec3(0.32, 0.07, 0.09), vec3(0.94, 0.38, 0.42), smoothstep(0.002, -0.002, tongue));
                col = mix(col, inMouth, smoothstep(0.002, -0.002, mouthSdf));
            }

            // =========================================================================
            // 5. ESTRELLAS DE 4 PUNTAS Y CHISPAS MÁGICAS
            // =========================================================================
            for (float i = 1.0; i <= 9.0; i += 1.0) {
                float seed = hash12(vec2(i * 37.1, i * 19.7));
                float t = fract(uTime * (0.18 + seed * 0.12) + seed);
                vec2 starPos = vec2(
                    sin(i * 2.4 + t * 4.0) * (0.14 + 0.08 * seed),
                    -0.08 + t * 0.36
                );
                float sz = (0.012 + 0.012 * fract(seed * 7.3)) * sin(t * 3.14159) * (1.0 + uTreble * 0.6);
                float dStar = (mod(i, 2.0) < 0.5)
                    ? sdStar4(uv - starPos, sz)
                    : length(uv - starPos) - sz * 0.45;
                if (dStar < 0.003) {
                    col = mix(col, vec3(1.0, 0.96, 0.72), smoothstep(0.003, -0.002, dStar));
                }
            }

            // =========================================================================
            // 6. GORRO DE PAPÁ NOEL 3D Y CALCETINES COLGANTES CON VOLUMEN
            // =========================================================================
            // Gorro de Navidad apoyado sobre el pilar izquierdo
            vec2 hatP = uv - vec2(-0.46, 0.06);
            float hatBody = sdFlameShape((hatP - vec2(-0.02, -0.02)) * vec2(1.0, 1.2), 0.11, 0.18, -0.09);
            float hatBrim = sdBox2D(hatP - vec2(0.0, -0.025), vec2(0.105, 0.026), 0.022);
            float hatPom  = length(hatP - vec2(-0.115, 0.045)) - 0.034;

            if (hatBody < 0.004) {
                vec3 redCloth = shadeClay(vec3(0.84, 0.12, 0.16), hatBody, hatP, vec2(0.5, 0.5), 0.035);
                col = mix(col, redCloth, smoothstep(0.004, -0.004, hatBody));
            }
            float whiteFluff = min(hatBrim, hatPom);
            if (whiteFluff < 0.004) {
                vec3 fluffCol = shadeClay(vec3(0.95, 0.95, 0.98), whiteFluff, hatP, vec2(0.4, 0.6), 0.025);
                col = mix(col, fluffCol, smoothstep(0.004, -0.004, whiteFluff));
            }

            // Dos calcetines navideños 3D colgando a la derecha
            for (float k = 0.0; k < 2.0; k += 1.0) {
                vec2 sAnchor = vec2(0.18 + k * 0.22, 0.33 - k * 0.02);
                vec2 sp = uv - sAnchor;
                float swingAng = -0.15 + sin(uTime * 2.5 + k * 1.5) * 0.06;
                sp = mat2(cos(swingAng), -sin(swingAng), sin(swingAng), cos(swingAng)) * sp;

                float sCuff = sdBox2D(sp - vec2(0.0, 0.0), vec2(0.038, 0.018), 0.012);
                float sLeg  = sdBox2D(sp - vec2(0.0, -0.055), vec2(0.032, 0.052), 0.022);
                float sFoot = sdBox2D(sp - vec2(0.022, -0.095), vec2(0.048, 0.026), 0.024);
                float sBody = smin(sLeg, sFoot, 0.015);

                if (sBody < 0.004) {
                    vec3 baseSock = (k < 0.5) ? vec3(0.82, 0.14, 0.18) : vec3(0.20, 0.46, 0.22);
                    // Franja decorativa amarilla en el calcetín verde
                    if (k > 0.5 && abs(sp.y + 0.045) < 0.012) baseSock = vec3(0.88, 0.66, 0.18);
                    vec3 sockLit = shadeClay(baseSock, sBody, sp, vec2(-0.5, 0.5), 0.025);
                    // Puntera blanca en el calcetín rojo
                    if (k < 0.5 && length(sp - vec2(0.048, -0.098)) < 0.028) {
                        sockLit = shadeClay(vec3(0.95, 0.94, 0.96), sBody, sp, vec2(-0.5, 0.5), 0.02);
                    }
                    col = mix(col, sockLit, smoothstep(0.004, -0.004, sBody));
                }
                if (sCuff < 0.004) {
                    vec3 cuffLit = shadeClay(vec3(0.94, 0.94, 0.97), sCuff, sp, vec2(0.0, 0.7), 0.015);
                    col = mix(col, cuffLit, smoothstep(0.004, -0.004, sCuff));
                }
            }

            // =========================================================================
            // 7. GUIRNALDA NAVIDEÑA TUPIDA CON ESFERAS 3D Y CAMPANAS DORADAS
            // =========================================================================
            // Curva colgante de la guirnalda en la parte superior
            float garCurveY = 0.42 - 0.10 * cos(clamp(uv.x * 2.4, -3.14159, 3.14159));
            float garDist = length(vec2(uv.x, uv.y - garCurveY) * vec2(0.35, 1.0));
            if (abs(uv.x) < 0.68 && uv.y > 0.24) {
                float leafClusters = sin(uv.x * 38.0) * 0.015 + cos(uv.x * 75.0) * 0.008;
                float garSDF = (abs(uv.y - garCurveY) + leafClusters) - 0.052;
                if (garSDF < 0.005) {
                    vec3 pineCol = shadeClay(vec3(0.14, 0.42, 0.20), garSDF, vec2(0.0, uv.y - garCurveY), vec2(0.0, -0.6), 0.04);
                    col = mix(col, pineCol, smoothstep(0.005, -0.005, garSDF));
                }

                // Esferas rojas y doradas con brillo especular 3D
                for (float b = -5.0; b <= 5.0; b += 1.0) {
                    if (abs(b) < 0.5) continue;
                    float bx = b * 0.11;
                    float by = 0.42 - 0.10 * cos(clamp(bx * 2.4, -3.14159, 3.14159)) - 0.035;
                    vec2 bp = uv - vec2(bx, by);
                    float bSdf = length(bp) - 0.024;
                    if (bSdf < 0.003) {
                        vec3 bBase = (mod(abs(b), 2.0) < 0.5) ? vec3(0.88, 0.12, 0.16) : vec3(0.92, 0.68, 0.16);
                        vec3 bLit = shadeClay(bBase, bSdf, bp, vec2(-0.4, 0.6), 0.022);
                        col = mix(col, bLit, smoothstep(0.003, -0.003, bSdf));
                    }
                }
            }

            // Dos campanas doradas centrales arriba del todo
            for (float c = -1.0; c <= 1.0; c += 2.0) {
                vec2 bellP = uv - vec2(c * 0.038, 0.46);
                float bAng = c * 0.35;
                bellP = mat2(cos(bAng), -sin(bAng), sin(bAng), cos(bAng)) * bellP;
                float bellSdf = sdBox2D(bellP, vec2(0.032 - bellP.y * 0.3, 0.038), 0.016);
                if (bellSdf < 0.003) {
                    vec3 goldBell = shadeClay(vec3(0.95, 0.72, 0.22), bellSdf, bellP, vec2(0.0, -0.8), 0.025);
                    col = mix(col, goldBell, smoothstep(0.003, -0.003, bellSdf));
                }
            }

            // Corrección de color suave y viñeteado cinematográfico
            float vig = dot(uv, uv);
            col *= clamp(1.05 - vig * 0.28, 0.0, 1.0);
            col = pow(max(col, vec3(0.0)), vec3(0.92));

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
