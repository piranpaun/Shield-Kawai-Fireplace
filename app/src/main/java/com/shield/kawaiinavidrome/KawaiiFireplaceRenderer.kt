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
    private var sceneModeLocation = 0

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
    @Volatile var sceneMode: SceneTheme = SceneTheme.REALISTIC
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
        uniform float uSceneMode; // 0.0 = Kawaii, 1.0 = Realistic 3D, 2.0 = Studio Ghibli

        uniform vec3 uBgColor;
        uniform vec3 uBrickColor;
        uniform vec3 uFlameOuter;
        uniform vec3 uFlameMid;
        uniform vec3 uFlameCore;
        uniform vec3 uGlowColor;

        out vec4 fragColor;

        // =========================================================================
        // CORE NOISE & MATH
        // =========================================================================
        float hash12(vec2 p) {
            vec3 p3  = fract(vec3(p.xyx) * 0.1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
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
                p = rot * p * 2.03 + vec2(17.1, 31.7);
                a *= 0.5;
            }
            return v;
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
            vec2 pa = p - a, ba = b - a;
            float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
            return length(pa - ba * h);
        }

        float sdStar4(vec2 p, float r) {
            p = abs(p);
            return (p.x + p.y + abs(p.x - p.y) * 0.55) - r;
        }

        // =========================================================================
        // 5x7 STITCHED GLYPH RENDERER FOR STOCKING NAMES:
        // 0: Papa | 1: Mama | 2: Eva | 3: Carmelo | 4: Ginger
        // =========================================================================
        int getGlyphRow(int ch, int row) {
            // 'P' (80)
            if (ch == 80) {
                if (row == 0 || row == 3) return 30;
                if (row == 1 || row == 2) return 17;
                return 16;
            }
            // 'a' (97)
            if (ch == 97) {
                if (row == 2) return 14;
                if (row == 3) return 1;
                if (row == 4 || row == 6) return 15;
                if (row == 5) return 17;
                return 0;
            }
            // 'p' (112)
            if (ch == 112) {
                if (row == 2 || row == 4) return 30;
                if (row == 3) return 17;
                if (row == 5 || row == 6) return 16;
                return 0;
            }
            // 'M' (77)
            if (ch == 77) {
                if (row == 1) return 27;
                if (row == 2 || row == 3) return 21;
                return 17;
            }
            // 'm' (109)
            if (ch == 109) {
                if (row == 2) return 26;
                if (row >= 3) return 21;
                return 0;
            }
            // 'E' (69)
            if (ch == 69) {
                if (row == 0 || row == 6) return 31;
                if (row == 3) return 30;
                return 16;
            }
            // 'v' (118)
            if (ch == 118) {
                if (row >= 2 && row <= 4) return 17;
                if (row == 5) return 10;
                if (row == 6) return 4;
                return 0;
            }
            // 'C' (67)
            if (ch == 67) {
                if (row == 0 || row == 6) return 14;
                if (row == 1 || row == 5) return 17;
                return 16;
            }
            // 'r' (114)
            if (ch == 114) {
                if (row == 2) return 22;
                if (row == 3) return 25;
                if (row >= 4) return 16;
                return 0;
            }
            // 'e' (101)
            if (ch == 101) {
                if (row == 2 || row == 6) return 14;
                if (row == 3) return 17;
                if (row == 4) return 31;
                if (row == 5) return 16;
                return 0;
            }
            // 'l' (108)
            if (ch == 108) {
                if (row == 0) return 12;
                if (row == 6) return 14;
                return 4;
            }
            // 'o' (111)
            if (ch == 111) {
                if (row == 2 || row == 6) return 14;
                if (row >= 3 && row <= 5) return 17;
                return 0;
            }
            // 'G' (71)
            if (ch == 71) {
                if (row == 0 || row == 6) return 14;
                if (row == 1 || row == 4 || row == 5) return 17;
                if (row == 2) return 16;
                return 23;
            }
            // 'i' (105)
            if (ch == 105) {
                if (row == 0) return 4;
                if (row == 1) return 0;
                if (row == 2) return 12;
                if (row == 6) return 14;
                return 4;
            }
            // 'n' (110)
            if (ch == 110) {
                if (row == 2) return 22;
                if (row == 3) return 25;
                if (row >= 4) return 17;
                return 0;
            }
            // 'g' (103)
            if (ch == 103) {
                if (row == 2 || row == 4) return 15;
                if (row == 3) return 17;
                if (row == 5) return 1;
                if (row == 6) return 14;
                return 0;
            }
            return 0;
        }

        float sampleStockingName(int sockIdx, vec2 localUv) {
            int len = 4;
            if (sockIdx == 2) len = 3;
            else if (sockIdx == 3) len = 7;
            else if (sockIdx == 4) len = 6;

            float charAspect = 0.72;
            float spacing = 1.22;
            float totalW = float(len) * spacing - 0.22;
            // Scale text cleanly inside cuff bounds (halfW = 0.095, halfH = 0.023)
            float scaleY = 0.029;
            float scaleX = min(scaleY * charAspect, 0.168 / totalW);
            scaleY = scaleX / charAspect;

            vec2 p = localUv / vec2(scaleX, scaleY);
            p.x += totalW * 0.5;
            p.y = 0.5 - p.y; // 0 at top, 1 at bottom

            if (p.x < 0.0 || p.x > totalW || p.y < 0.0 || p.y > 1.0) return 0.0;

            int charSlot = int(floor(p.x / spacing));
            float charLocalX = fract(p.x / spacing) * spacing;
            if (charLocalX > 1.0 || charSlot < 0 || charSlot >= len) return 0.0;

            int ch = 0;
            if (sockIdx == 0) { // "Papa"
                if (charSlot == 0) ch = 80;
                else if (charSlot == 1) ch = 97;
                else if (charSlot == 2) ch = 112;
                else ch = 97;
            } else if (sockIdx == 1) { // "Mama"
                if (charSlot == 0) ch = 77;
                else if (charSlot == 1) ch = 97;
                else if (charSlot == 2) ch = 109;
                else ch = 97;
            } else if (sockIdx == 2) { // "Eva"
                if (charSlot == 0) ch = 69;
                else if (charSlot == 1) ch = 118;
                else ch = 97;
            } else if (sockIdx == 3) { // "Carmelo"
                if (charSlot == 0) ch = 67;
                else if (charSlot == 1) ch = 97;
                else if (charSlot == 2) ch = 114;
                else if (charSlot == 3) ch = 109;
                else if (charSlot == 4) ch = 101;
                else if (charSlot == 5) ch = 108;
                else ch = 111;
            } else { // "Ginger"
                if (charSlot == 0) ch = 71;
                else if (charSlot == 1) ch = 105;
                else if (charSlot == 2) ch = 110;
                else if (charSlot == 3) ch = 103;
                else if (charSlot == 4) ch = 101;
                else ch = 114;
            }

            vec2 grid = vec2(charLocalX * 5.0, p.y * 7.0);
            int gx = clamp(int(floor(grid.x)), 0, 4);
            int gy = clamp(int(floor(grid.y)), 0, 6);
            int bits = getGlyphRow(ch, gy);
            int bit = (bits >> (4 - gx)) & 1;
            if (bit == 0) return 0.0;

            // Soft stitched thread rounding per dot
            vec2 cellF = fract(grid) - 0.5;
            return smoothstep(0.62, 0.25, length(cellF));
        }

        // =========================================================================
        // THEME 1 & 2: REALISTIC 3D & STUDIO GHIBLI COZY FIREPLACE (3/4 SCREEN FIRE)
        // =========================================================================
        vec3 blackbodyFlame(float temp, bool isGhibli) {
            if (isGhibli) {
                // Studio Ghibli vibrant cel-shaded & painted fire palette
                vec3 c1 = vec3(0.86, 0.16, 0.12); // Deep vermilion
                vec3 c2 = vec3(0.98, 0.45, 0.12); // Warm tangerine
                vec3 c3 = vec3(1.00, 0.80, 0.22); // Golden butter
                vec3 c4 = vec3(1.00, 0.97, 0.82); // Luminous cream
                float stepped = mix(temp, floor(temp * 4.5) / 4.5, 0.55);
                vec3 col = mix(c1, c2, smoothstep(0.15, 0.42, stepped));
                col = mix(col, c3, smoothstep(0.42, 0.72, stepped));
                col = mix(col, c4, smoothstep(0.72, 0.95, stepped));
                return col;
            } else {
                // Realistic physical fire temperature gradient
                vec3 c1 = vec3(0.65, 0.08, 0.01);
                vec3 c2 = vec3(0.98, 0.32, 0.03);
                vec3 c3 = vec3(1.00, 0.72, 0.20);
                vec3 c4 = vec3(1.00, 0.97, 0.88);
                vec3 col = mix(c1, c2, smoothstep(0.10, 0.40, temp));
                col = mix(col, c3, smoothstep(0.40, 0.75, temp));
                col = mix(col, c4, smoothstep(0.75, 0.98, temp));
                return col;
            }
        }

        vec3 renderRealisticOrGhibli(vec2 uv, bool isGhibli) {
            // Subtle painterly brush warp for Studio Ghibli
            vec2 p = uv;
            if (isGhibli) {
                vec2 brush = vec2(fbm(uv * 14.0), fbm(uv * 14.0 + 9.2)) - 0.5;
                p += brush * 0.0045;
            }

            // Flickering fire intensity driven by time + bass
            float flicker = 0.88 + 0.12 * sin(uTime * 11.3) * cos(uTime * 7.1) + uBass * 0.45;
            vec3 fireLightColor = isGhibli ? vec3(1.0, 0.58, 0.22) : vec3(1.0, 0.48, 0.14);

            // -----------------------------------------------------------------
            // A. MINIMALIST COZY ROOM WALL & WOOD PANELING
            // -----------------------------------------------------------------
            float wallWoodGrain = fbm(vec2(p.x * 22.0, p.y * 2.5));
            vec3 baseWall = isGhibli
                ? vec3(0.16, 0.13, 0.18) + wallWoodGrain * 0.04
                : vec3(0.10, 0.08, 0.07) + wallWoodGrain * 0.025;

            float distToHearth = length((p - vec2(0.0, -0.10)) * vec2(0.75, 1.0));
            float roomGlow = exp(-distToHearth * 1.9) * flicker;
            vec3 col = baseWall + fireLightColor * roomGlow * (isGhibli ? 0.38 : 0.30);

            // -----------------------------------------------------------------
            // B. LEFT WINDOW: SNOWING OUTSIDE INTO SERENE NIGHT
            // -----------------------------------------------------------------
            vec2 winCenter = vec2(-0.73, 0.04);
            float winFrameOuter = sdBox2D(p - winCenter, vec2(0.135, 0.34), 0.008);
            float winGlass = sdBox2D(p - winCenter, vec2(0.112, 0.315), 0.004);

            if (winFrameOuter < 0.004) {
                // Night sky & distant snowy hills/pines
                vec2 wp = p - winCenter;
                vec3 nightSky = isGhibli
                    ? mix(vec3(0.08, 0.16, 0.34), vec3(0.03, 0.07, 0.18), smoothstep(-0.3, 0.3, wp.y))
                    : mix(vec3(0.04, 0.07, 0.14), vec3(0.01, 0.02, 0.06), smoothstep(-0.3, 0.3, wp.y));

                // Soft moonlit snow hill at bottom of window
                float hill = wp.y - (-0.23 + 0.025 * sin(wp.x * 14.0 + 1.0));
                if (hill < 0.0) {
                    nightSky = isGhibli ? vec3(0.58, 0.68, 0.85) : vec3(0.45, 0.52, 0.64);
                }

                // Multi-depth falling snow outside
                for (float layer = 1.0; layer <= 3.0; layer += 1.0) {
                    vec2 sUv = wp * (9.0 + layer * 6.0);
                    sUv.y += uTime * (0.42 / layer);
                    sUv.x += sin(uTime * 0.5 + layer * 1.7 + wp.y * 4.0) * 0.25;
                    vec2 sId = floor(sUv);
                    vec2 sF = fract(sUv) - 0.5;
                    vec2 jitter = (vec2(hash12(sId), hash12(sId + 31.4)) - 0.5) * 0.55;
                    float dFlake = length(sF - jitter);
                    float rFlake = (0.11 / layer) * step(0.62, hash12(sId + 73.1));
                    float alphaFlake = smoothstep(rFlake, rFlake * 0.2, dFlake) * (0.95 / layer);
                    nightSky = mix(nightSky, vec3(0.92, 0.96, 1.0), alphaFlake);
                }

                // Subtle warm fire reflection on glass & snow on mullions
                nightSky += fireLightColor * 0.06 * smoothstep(0.15, -0.15, wp.x);

                // Minimalist dark walnut window frame + crossbars
                float mullion = min(abs(wp.x) - 0.011, abs(wp.y) - 0.011);
                float isWood = max(-winGlass, -mullion);
                float wGrain = fbm(vec2(wp.x * 12.0, wp.y * 45.0));
                vec3 walnutFrame = (isGhibli ? vec3(0.26, 0.15, 0.11) : vec3(0.16, 0.09, 0.06)) * (0.85 + 0.3 * wGrain);
                walnutFrame += fireLightColor * 0.14 * smoothstep(0.15, -0.15, wp.x) * flicker;

                // Minimalist wooden windowsill at bottom
                float sill = sdBox2D(wp - vec2(0.0, -0.345), vec2(0.155, 0.018), 0.005);
                vec3 winOut = mix(walnutFrame, nightSky, smoothstep(-0.003, 0.003, isWood));
                if (sill < 0.003) {
                    winOut = mix(winOut, walnutFrame * 1.15, smoothstep(0.003, -0.003, sill));
                }
                col = mix(col, winOut, smoothstep(0.004, -0.004, min(winFrameOuter, sill)));
            }

            // -----------------------------------------------------------------
            // C. RIGHT SIDE: PARTIAL OUT-OF-FOCUS CHRISTMAS TREE WITH BOKEH BALLS
            // -----------------------------------------------------------------
            vec2 treeOrigin = vec2(0.84, -0.48);
            vec2 tp = p - treeOrigin;
            if (p.x > 0.52) {
                // Soft out-of-focus conical pine silhouette
                float treeH = clamp(tp.y / 0.96, 0.0, 1.0);
                float branchWave = sin(tp.y * 36.0) * 0.035 + fbm(tp * 9.0) * 0.045;
                float treeWidth = (1.0 - treeH * 0.88) * 0.34 + branchWave;
                float treeMask = smoothstep(0.06, -0.06, abs(tp.x) - treeWidth) * step(tp.y, 0.94);

                if (treeMask > 0.001) {
                    float depthNoise = fbm(tp * 7.5);
                    vec3 pineDark = isGhibli ? vec3(0.06, 0.22, 0.14) : vec3(0.03, 0.11, 0.06);
                    vec3 pineLit  = isGhibli ? vec3(0.15, 0.38, 0.22) : vec3(0.08, 0.22, 0.11);
                    vec3 treeCol = mix(pineDark, pineLit, depthNoise);
                    // Warm firelight rim on left edge of tree
                    treeCol += fireLightColor * 0.22 * smoothstep(0.82, 0.54, p.x) * flicker;
                    col = mix(col, treeCol, treeMask * 0.92);
                }

                // Out-of-focus glowing Christmas ornaments & fairy lights (Optical Bokeh)
                for (float b = 0.0; b < 14.0; b += 1.0) {
                    float s1 = hash12(vec2(b * 13.7, 4.1));
                    float s2 = hash12(vec2(b * 29.3, 8.7));
                    float by = -0.36 + s1 * 0.76;
                    float maxR = (1.0 - clamp((by + 0.48) / 0.96, 0.0, 1.0) * 0.85) * 0.27;
                    float bx = 0.82 - s2 * maxR;
                    vec2 bPos = vec2(bx, by);
                    float dBokeh = length(p - bPos);

                    // Large soft out-of-focus bokeh circle with bright optical rim
                    bool isGold = mod(b, 3.0) < 1.5;
                    float bokehRadius = isGold ? (0.024 + 0.006 * uTreble) : 0.034;
                    if (dBokeh < bokehRadius * 1.4) {
                        float pulse = 0.78 + 0.22 * sin(uTime * (2.2 + s1 * 2.0) + b) + uTreble * 0.35;
                        vec3 orbCol = isGold
                            ? vec3(1.0, 0.82, 0.38)
                            : (mod(b, 2.0) < 0.5 ? vec3(0.95, 0.22, 0.25) : vec3(0.96, 0.72, 0.32));

                        // Soft optical bokeh profile (bright ring + creamy interior + wide halo)
                        float disc = smoothstep(bokehRadius, bokehRadius * 0.72, dBokeh);
                        float ring = smoothstep(bokehRadius * 0.45, bokehRadius * 0.92, dBokeh) * disc;
                        float halo = exp(-dBokeh * 42.0) * 0.55;
                        float intensity = (disc * 0.45 + ring * 0.40 + halo) * pulse;
                        col += orbCol * intensity * (isGhibli ? 0.85 : 0.75);
                    }
                }
            }

            // -----------------------------------------------------------------
            // D. MINIMALISTIC ARCHITECTURAL HEARTH & WOODEN FLOOR / FURNITURE
            // -----------------------------------------------------------------
            // Clean dark walnut wood floor at bottom
            if (p.y < -0.39) {
                float plank = fract(p.x * 3.5 + floor((p.y + 0.45) * 18.0) * 0.37);
                float plankSeam = smoothstep(0.0, 0.015, plank) * smoothstep(1.0, 0.985, plank);
                float fGrain = fbm(vec2(p.x * 35.0, p.y * 8.0));
                vec3 woodFloor = (isGhibli ? vec3(0.24, 0.14, 0.09) : vec3(0.13, 0.07, 0.04)) * (0.75 + 0.4 * fGrain) * (0.6 + 0.4 * plankSeam);
                float floorRefl = exp(-length(vec2(p.x * 0.9, (p.y + 0.42) * 3.0)) * 2.6) * flicker;
                woodFloor += fireLightColor * floorRefl * 0.65;
                col = woodFloor;
            }

            // Minimalistic low wooden side console on far left (subtle, uncluttered)
            float sideTable = sdBox2D(p - vec2(-0.75, -0.35), vec2(0.13, 0.065), 0.008);
            if (sideTable < 0.003) {
                float tGrain = fbm(vec2(p.x * 40.0, p.y * 12.0));
                vec3 tableCol = (isGhibli ? vec3(0.32, 0.18, 0.11) : vec3(0.18, 0.10, 0.06)) * (0.8 + 0.35 * tGrain);
                tableCol += fireLightColor * 0.22 * smoothstep(-0.88, -0.62, p.x) * flicker;
                col = mix(col, tableCol, smoothstep(0.003, -0.003, sideTable));
            }

            // Grand Minimalist Fireplace Surround (Honed dark stone/slate, clean proportions)
            // Outer hearth frame: spans x in [-0.57, 0.57], y in [-0.41, 0.26]
            float outerHearth = sdBox2D(p - vec2(0.0, -0.075), vec2(0.57, 0.335), 0.014);
            // Firebox opening: 3/4 visual dominance! x in [-0.50, 0.50], y in [-0.38, 0.17]
            float firebox = sdBox2D(p - vec2(0.0, -0.105), vec2(0.50, 0.275), 0.012);

            if (outerHearth < 0.004) {
                float stoneTex = fbm(p * 28.0);
                vec3 slateBase = isGhibli
                    ? vec3(0.24, 0.22, 0.25) + stoneTex * 0.05
                    : vec3(0.14, 0.13, 0.14) + stoneTex * 0.035;
                // Inner bevel highlight catching the firelight
                float innerRim = smoothstep(0.045, 0.0, firebox);
                slateBase += fireLightColor * innerRim * 0.55 * flicker;
                col = mix(col, slateBase, smoothstep(0.004, -0.004, outerHearth));
            }

            // -----------------------------------------------------------------
            // E. INTERIOR OF FIREBOX: 3D RAYMARCHED LOGS, EMBERS & 3/4 ROARING FIRE
            // -----------------------------------------------------------------
            if (firebox < 0.003) {
                // Dark soot-lined brick backwall inside the firebox
                vec2 bUv = p * vec2(7.0, 15.0);
                bUv.x += mod(floor(bUv.y), 2.0) * 0.5;
                vec2 bF = fract(bUv) - 0.5;
                float bMask = smoothstep(0.48, 0.42, abs(bF.x)) * smoothstep(0.46, 0.38, abs(bF.y));
                vec3 backWall = (isGhibli ? vec3(0.11, 0.07, 0.08) : vec3(0.04, 0.03, 0.03)) * (0.5 + 0.5 * bMask);

                // Warm glow on the back of the firebox
                float backGlow = exp(-length((p - vec2(0.0, -0.14)) * vec2(1.1, 1.4)) * 2.4) * flicker;
                vec3 boxCol = backWall + fireLightColor * backGlow * (isGhibli ? 0.85 : 0.72);

                // Glowing ember bed across the bottom of the firebox
                if (p.y < -0.27) {
                    float emberY = smoothstep(-0.27, -0.36, p.y);
                    float coals = fbm(vec2(p.x * 26.0, p.y * 45.0) + vec2(0.0, uTime * 0.15));
                    float cracks = smoothstep(0.42, 0.72, coals) * emberY;
                    vec3 coalCol = mix(vec3(0.05, 0.03, 0.03), blackbodyFlame(cracks * (0.85 + 0.3 * uBass), isGhibli) * 1.35, cracks);
                    boxCol = mix(boxCol, coalCol, smoothstep(-0.27, -0.29, p.y));
                }

                // 3D Cylindrical Firewood Logs stacked in the center
                for (int i = 0; i < 3; i++) {
                    float fi = float(i);
                    vec2 logPos = (i == 0) ? vec2(-0.14, -0.31)
                                : (i == 1) ? vec2( 0.14, -0.31)
                                           : vec2( 0.00, -0.255);
                    float ang = (i == 0) ? 0.16 : (i == 1) ? -0.18 : -0.05;
                    vec2 lp = p - logPos;
                    mat2 rot = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
                    vec2 rlp = rot * lp;

                    float halfLen = (i == 2) ? 0.21 : 0.20;
                    float rad = 0.042;
                    float logSdf = sdBox2D(rlp, vec2(halfLen, rad), rad * 0.88);
                    if (logSdf < 0.003) {
                        float cylZ = sqrt(clamp(1.0 - pow(rlp.y / rad, 2.0), 0.05, 1.0));
                        float barkNoise = fbm(vec2(rlp.x * 28.0, rlp.y * 55.0));
                        vec3 bark = (isGhibli ? vec3(0.24, 0.13, 0.09) : vec3(0.12, 0.06, 0.04)) * (0.35 + 0.65 * cylZ) * (0.7 + 0.5 * barkNoise);

                        // Glowing charred cracks on the underside/center facing the fire
                        float charZone = smoothstep(0.02, -0.035, rlp.y) * smoothstep(halfLen, halfLen * 0.3, abs(rlp.x));
                        float emberCrack = smoothstep(0.52, 0.78, barkNoise) * charZone;
                        bark += blackbodyFlame(0.45 + 0.45 * emberCrack + uBass * 0.2, isGhibli) * emberCrack * 1.4;

                        boxCol = mix(boxCol, bark, smoothstep(0.003, -0.003, logSdf));
                    }
                }

                // ROARING VOLUMETRIC FIRE (fills the 3/4 firebox with rich, organic tongues)
                vec2 fUv = (p - vec2(0.0, -0.34)) / vec2(0.44, 0.48 + uBass * 0.08);
                if (fUv.y > -0.05 && fUv.y < 1.15 && abs(fUv.x) < 1.15) {
                    float yH = clamp(fUv.y, 0.0, 1.0);
                    // Domain-warped upward turbulent flow
                    vec2 flow1 = vec2(fUv.x * 3.2, fUv.y * 2.4 - uTime * 2.6);
                    vec2 flow2 = vec2(fUv.x * 6.5 + 4.1, fUv.y * 5.0 - uTime * 4.2);
                    float turb = (fbm(flow1) - 0.5) * 0.55 + (fbm(flow2) - 0.5) * 0.25;

                    // Taper into lively flame tongues toward the top
                    float lateralSpread = (1.0 - pow(yH, 1.35) * 0.78);
                    float warpedX = (fUv.x + turb * (0.25 + yH * 0.95)) / max(lateralSpread, 0.14);

                    // Multi-tongue density field
                    float tongues = sin(warpedX * 5.5 + fbm(flow1 * 1.5) * 4.0) * 0.22 * yH;
                    float coreDist = length(vec2(warpedX * 1.05, max(0.0, yH - 0.18) * 1.15));
                    float density = (1.0 - coreDist + tongues + (fbm(flow2) - 0.45) * 0.45) * smoothstep(1.05, 0.12, yH);
                    density *= smoothstep(-0.04, 0.08, fUv.y);

                    if (density > 0.02) {
                        float temp = clamp(density * (0.95 + uBass * 0.35), 0.0, 1.0);
                        vec3 fCol = blackbodyFlame(temp, isGhibli);
                        float alpha = isGhibli
                            ? smoothstep(0.04, 0.22, density)
                            : smoothstep(0.02, 0.38, density);
                        // Additive + alpha blend for luminous fire
                        boxCol = mix(boxCol, fCol, clamp(alpha, 0.0, 1.0));
                        boxCol += fCol * alpha * 0.28;
                    }
                }

                // Rising glowing sparks & embers inside the firebox
                for (float k = 1.0; k <= 16.0; k += 1.0) {
                    float sd = hash12(vec2(k * 51.7, 11.3));
                    float life = fract(uTime * (0.25 + sd * 0.22) + sd);
                    vec2 spPos = vec2(
                        (sd - 0.5) * 0.76 + sin(life * 9.0 + k) * 0.05,
                        -0.30 + life * 0.46
                    );
                    float dSp = length(p - spPos);
                    float rSp = (0.0025 + 0.0025 * fract(sd * 9.1)) * (1.0 - life) * (1.0 + uTreble * 0.5);
                    if (dSp < rSp * 2.5) {
                        float spGlow = smoothstep(rSp * 2.5, rSp * 0.2, dSp) * (1.0 - life * 0.6);
                        boxCol += vec3(1.0, 0.78, 0.30) * spGlow * 1.4;
                    }
                }

                col = mix(col, boxCol, smoothstep(0.003, -0.003, firebox));
            }

            // -----------------------------------------------------------------
            // F. SOLID WALNUT WOOD MANTEL BEAM (TOP OF FIREPLACE)
            // -----------------------------------------------------------------
            vec2 mantelCenter = vec2(0.0, 0.255);
            float mantelSdf = sdBox2D(p - mantelCenter, vec2(0.64, 0.032), 0.008);
            if (mantelSdf < 0.015) {
                // Subtle drop shadow cast onto the stone below the mantel
                float underShadow = smoothstep(0.015, 0.0, mantelSdf) * step(p.y, mantelCenter.y);
                col *= (1.0 - underShadow * 0.45);
            }
            if (mantelSdf < 0.003) {
                float mGrain = fbm(vec2(p.x * 16.0, p.y * 95.0));
                float bevelY = clamp((p.y - (mantelCenter.y - 0.032)) / 0.064, 0.0, 1.0);
                vec3 walnut = (isGhibli ? vec3(0.36, 0.20, 0.12) : vec3(0.22, 0.12, 0.07)) * (0.75 + 0.45 * mGrain);
                // Warm firelight reflecting off the bottom bevel of the wooden mantel
                walnut += fireLightColor * (1.0 - bevelY) * 0.45 * flicker;
                // Soft top highlight
                walnut += vec3(0.08, 0.07, 0.06) * smoothstep(0.75, 1.0, bevelY);
                col = mix(col, walnut, smoothstep(0.003, -0.003, mantelSdf));
            }

            // -----------------------------------------------------------------
            // G. 5 HANGING STOCKINGS: PAPA, MAMA, EVA, CARMELO, GINGER
            // -----------------------------------------------------------------
            for (int s = 0; s < 5; s++) {
                float fs = float(s);
                float anchorX = -0.48 + fs * 0.24;
                vec2 anchor = vec2(anchorX, 0.232);
                vec2 sp = p - anchor;

                // Gentle swaying motion from warm fireplace convection + music bass
                float swayAngle = sin(uTime * 1.8 + fs * 1.35) * (0.032 + uBass * 0.025);
                float ca = cos(swayAngle), sa = sin(swayAngle);
                sp = mat2(ca, -sa, sa, ca) * sp;

                // Stocking geometry: cuff at top, knitted leg, angled foot
                float cuffSdf = sdBox2D(sp - vec2(0.0, -0.024), vec2(0.095, 0.023), 0.007);
                float legSdf  = sdBox2D(sp - vec2(0.0, -0.078), vec2(0.044, 0.042), 0.016);
                vec2 footP = sp - vec2(0.022, -0.118);
                float footSdf = sdBox2D(footP, vec2(0.062, 0.025), 0.022);
                float bodySdf = smin(legSdf, footSdf, 0.012);

                // Small brass hanger loop above cuff
                float hookSdf = abs(length(sp - vec2(0.0, 0.004)) - 0.009) - 0.0025;
                if (hookSdf < 0.002) {
                    col = mix(col, vec3(0.85, 0.68, 0.28), smoothstep(0.002, -0.002, hookSdf));
                }

                // Soft shadow behind stocking
                float sockShadow = min(cuffSdf, bodySdf);
                if (sockShadow < 0.012) {
                    col *= (1.0 - smoothstep(0.012, 0.0, sockShadow) * 0.35);
                }

                // Knitted stocking body
                if (bodySdf < 0.003) {
                    // Elegant, harmonious Christmas colors for each family member
                    vec3 sockCol = vec3(0.62, 0.12, 0.15); // Papa: Deep Burgundy
                    if (s == 1) sockCol = vec3(0.14, 0.36, 0.22); // Mama: Forest Pine
                    else if (s == 2) sockCol = vec3(0.72, 0.22, 0.24); // Eva: Cranberry Red
                    else if (s == 3) sockCol = vec3(0.18, 0.28, 0.42); // Carmelo: Winter Slate Blue
                    else if (s == 4) sockCol = vec3(0.68, 0.34, 0.14); // Ginger: Warm Cinnamon

                    if (isGhibli) sockCol *= 1.18;

                    // Subtle cable-knit ribbing + warm firelight from below
                    float knit = sin(sp.x * 190.0) * sin(sp.y * 190.0) * 0.08;
                    float round3D = sqrt(clamp(1.0 - pow(sp.x / 0.065, 2.0), 0.15, 1.0));
                    vec3 litSock = sockCol * (0.58 + 0.42 * round3D + knit);
                    litSock += fireLightColor * smoothstep(-0.04, -0.14, sp.y) * 0.38 * flicker;

                    // Cream heel & toe accents
                    float heel = length(sp - vec2(-0.036, -0.112)) - 0.024;
                    float toe  = length(sp - vec2(0.064, -0.118)) - 0.025;
                    if (min(heel, toe) < 0.0) {
                        litSock = mix(litSock, vec3(0.88, 0.84, 0.78) * (0.65 + 0.35 * round3D), 0.85);
                    }

                    col = mix(col, litSock, smoothstep(0.003, -0.003, bodySdf));
                }

                // Plush cream cuff with embroidered name
                if (cuffSdf < 0.003) {
                    float cuffRound = sqrt(clamp(1.0 - pow(sp.x / 0.105, 2.0), 0.2, 1.0));
                    float fleece = fbm(sp * 95.0) * 0.08;
                    vec3 cuffCol = (isGhibli ? vec3(0.95, 0.91, 0.84) : vec3(0.88, 0.84, 0.78)) * (0.68 + 0.32 * cuffRound + fleece);
                    cuffCol += fireLightColor * 0.22 * flicker;

                    // Render stitched name ("Papa", "Mama", "Eva", "Carmelo", "Ginger")
                    float textMask = sampleStockingName(s, sp - vec2(0.0, -0.024));
                    vec3 stitchCol = isGhibli ? vec3(0.58, 0.10, 0.14) : vec3(0.48, 0.08, 0.10);
                    cuffCol = mix(cuffCol, stitchCol, textMask * 0.95);

                    col = mix(col, cuffCol, smoothstep(0.003, -0.003, cuffSdf));
                }
            }

            // -----------------------------------------------------------------
            // H. CINEMATIC POST-PROCESSING & FILM / PAINTERLY GRAIN
            // -----------------------------------------------------------------
            float vig = dot(uv * vec2(0.85, 1.0), uv * vec2(0.85, 1.0));
            col *= clamp(1.04 - vig * 0.34, 0.0, 1.0);
            if (isGhibli) {
                // Subtle watercolor paper texture
                col += (fbm(uv * 110.0) - 0.5) * 0.018;
                col = pow(max(col, vec3(0.0)), vec3(0.90));
            } else {
                col = pow(max(col, vec3(0.0)), vec3(0.93));
            }
            return col;
        }

        // =========================================================================
        // THEME 0: KAWAII CHRISTMAS 3D CLAY SCENE
        // =========================================================================
        vec3 shadeClay(vec3 baseCol, float sdf, vec2 grad, vec2 lightDir, float bevel) {
            float z = sqrt(clamp(1.0 - pow(clamp(1.0 + sdf / bevel, 0.0, 1.0), 2.0), 0.01, 1.0));
            vec3 normal = normalize(vec3(grad * (1.0 - z), z));
            vec3 lDir = normalize(vec3(lightDir, 0.65));
            float diff = clamp(dot(normal, lDir), 0.0, 1.0);
            float rim = pow(1.0 - normal.z, 2.2) * 0.35;
            float spec = pow(clamp(dot(reflect(-lDir, normal), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 12.0) * 0.25;
            return baseCol * (0.42 + 0.68 * diff) + baseCol * rim + vec3(spec);
        }

        vec3 renderBrickWallKawaii(vec2 p, vec2 scale, vec2 firePos, float fireBoost) {
            vec2 st = p * scale;
            float row = floor(st.y);
            st.x += mod(row, 2.0) * 0.5;
            vec2 id = floor(st);
            vec2 f = fract(st) - 0.5;

            vec2 d2 = abs(f) - vec2(0.45, 0.41);
            float bSdf = min(max(d2.x, d2.y), 0.0) + length(max(d2, 0.0)) - 0.04;

            float h = hash12(id);
            vec3 brickTone = uBrickColor * (0.88 + 0.22 * h);
            brickTone.r += 0.04 * (h - 0.5);

            vec2 localNorm = f * vec2(1.4, 1.8);
            float bevel = smoothstep(0.0, -0.14, bSdf);
            vec3 norm = normalize(vec3(-localNorm * (1.0 - bevel * 0.65), 0.55 + bevel * 0.45));

            vec3 toFire = normalize(vec3(firePos - p, 0.28));
            float fireDiff = max(dot(norm, toFire), 0.0);
            float topDiff = max(dot(norm, normalize(vec3(0.0, 0.6, 0.8))), 0.0);
            float distToFire = length(firePos - p);
            float atten = 1.0 / (1.0 + distToFire * distToFire * 2.4);

            vec3 litBrick = brickTone * (0.35 + 0.45 * topDiff);
            litBrick += (uGlowColor * 1.1 + vec3(0.3, 0.1, 0.0)) * fireDiff * atten * (1.3 + fireBoost);

            vec3 mortarCol = uBrickColor * 0.25;
            float mask = smoothstep(0.01, -0.02, bSdf);
            return mix(mortarCol, litBrick, mask);
        }

        float sdFlameShape(vec2 p, float w, float h, float sway) {
            p.y += h * 0.45;
            float yNorm = clamp(p.y / h, 0.0, 1.0);
            p.x -= sway * pow(yNorm, 1.6);
            float r = w * sin(pow(yNorm, 0.65) * 3.14159) * (1.0 - 0.35 * yNorm);
            vec2 d = vec2(abs(p.x) - r, abs(p.y - h * 0.5) - h * 0.5);
            return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
        }

        vec3 renderKawaii(vec2 uv) {
            vec2 fireCenter = vec2(0.0, -0.14);
            float bounce = sin(uTime * 6.0) * 0.012 + uBass * 0.045;
            vec3 col = uBgColor * 0.8;

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                vec2 wPos = uv - vec2(side * 0.72, 0.12);
                float winOuter = sdBox2D(wPos, vec2(0.22, 0.36), 0.01);
                float winPane  = sdBox2D(wPos, vec2(0.18, 0.32), 0.008);
                if (winOuter < 0.01) {
                    vec3 sky = mix(vec3(0.05, 0.07, 0.16), vec3(0.10, 0.14, 0.28), uv.y + 0.5);
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
                    float crossBars = min(abs(wPos.x) - 0.016, abs(wPos.y + 0.02) - 0.016);
                    float isWood = max(-winPane, -crossBars);
                    vec3 woodCol = vec3(0.46, 0.26, 0.16) * (0.8 + 0.3 * (1.0 - abs(wPos.x) * 2.0)) + uGlowColor * 0.18;
                    vec3 winFinal = mix(woodCol, sky, smoothstep(-0.004, 0.004, isWood));
                    col = mix(col, winFinal, smoothstep(0.005, -0.005, winOuter));
                }
            }

            float mainWall = sdBox2D(uv - vec2(0.0, 0.02), vec2(0.54, 0.42), 0.02);
            if (mainWall < 0.005) {
                vec3 wallBricks = renderBrickWallKawaii(uv, vec2(4.5, 9.5), fireCenter, uBass);
                float hearthShadow = smoothstep(0.32, 0.05, length((uv - vec2(0.0, -0.18)) * vec2(0.85, 1.1)));
                wallBricks = mix(wallBricks, wallBricks * 0.45 + uGlowColor * 0.25, hearthShadow * 0.5);
                col = mix(col, wallBricks, smoothstep(0.005, -0.005, mainWall));
            }

            for (float s = -1.0; s <= 1.0; s += 2.0) {
                vec2 pillarPos = uv - vec2(s * 0.46, -0.20);
                float pillarSDF = sdBox2D(pillarPos, vec2(0.15, 0.22), 0.025);
                if (pillarSDF < 0.015) col *= smoothstep(0.0, 0.015, pillarSDF) * 0.4 + 0.6;
                if (pillarSDF < 0.004) {
                    vec3 pBricks = renderBrickWallKawaii(uv + vec2(0.12 * s, 0.03), vec2(4.2, 8.8), fireCenter, uBass) * 1.08;
                    col = mix(col, pBricks, smoothstep(0.004, -0.004, pillarSDF));
                }
            }

            float floorSDF = sdBox2D(uv - vec2(0.0, -0.44), vec2(0.76, 0.055), 0.025);
            if (floorSDF < 0.004) {
                vec2 tileUv = vec2(uv.x * 5.0, (uv.y + 0.44) * 9.0);
                vec2 tileF = fract(tileUv) - 0.5;
                float tileSdf = sdBox2D(tileF, vec2(0.46, 0.42), 0.08);
                vec3 tileCol = uBrickColor * 0.92 * (0.75 + 0.35 * smoothstep(0.02, -0.12, tileSdf));
                tileCol += uGlowColor * (1.0 / (1.0 + abs(uv.x) * 3.5)) * (0.45 + uBass * 0.3);
                tileCol = mix(uBrickColor * 0.25, tileCol, smoothstep(0.02, -0.02, tileSdf));
                col = mix(col, tileCol, smoothstep(0.004, -0.004, floorSDF));
            }

            for (float side = -1.0; side <= 1.0; side += 2.0) {
                for (float idx = 0.0; idx < 2.0; idx += 1.0) {
                    vec2 logCenter = vec2(side * (0.21 + idx * 0.07), -0.33 + idx * 0.045);
                    vec2 lp = uv - logCenter;
                    float ang = side * (-0.22 + idx * 0.12);
                    mat2 rot = mat2(cos(ang), -sin(ang), sin(ang), cos(ang));
                    vec2 rlp = rot * lp;
                    float bodySdf = sdBox2D(rlp, vec2(0.09, 0.042), 0.038);
                    vec2 endPos = lp - vec2(-side * 0.055, -0.01);
                    float endSdf = length(endPos * vec2(1.15, 1.0)) - 0.042;
                    if (bodySdf < 0.004) {
                        float cylShade = sqrt(clamp(1.0 - pow(rlp.y / 0.042, 2.0), 0.0, 1.0));
                        vec3 bark = vec3(0.38, 0.20, 0.12) * (0.45 + 0.65 * cylShade);
                        float fireSide = clamp(-side * rlp.x * 8.0 + 0.4, 0.0, 1.0);
                        bark += vec3(1.0, 0.38, 0.05) * pow(fireSide, 1.8) * (0.9 + 0.4 * sin(uTime * 7.0 + idx) + uBass);
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

            vec2 fp = uv - vec2(0.0, -0.35);
            fp.y /= (1.0 + bounce * 1.4);
            float aura = length((uv - vec2(0.0, -0.15)) * vec2(0.9, 0.75));
            col += uGlowColor * exp(-aura * 4.2) * (0.55 + uBass * 0.45);

            float sway = sin(uTime * 4.5) * 0.025;
            float outerFlame = smin(
                sdFlameShape(fp, 0.21, 0.48, sway),
                smin(sdFlameShape(fp - vec2(-0.08, 0.03), 0.12, 0.34, sway - 0.03),
                     sdFlameShape(fp - vec2(0.08, 0.02), 0.12, 0.32, sway + 0.03), 0.06),
                0.06
            );
            float armWave = sin(uTime * 6.0) * 0.015 + uBass * 0.03;
            float armL = sdSegment(fp, vec2(-0.14, 0.14), vec2(-0.22, 0.17 + armWave)) - 0.024;
            float armR = sdSegment(fp, vec2(0.14, 0.14), vec2(0.22, 0.19 - armWave)) - 0.024;
            float footL = sdBox2D(fp - vec2(-0.065, -0.015), vec2(0.026, 0.035), 0.022);
            float footR = sdBox2D(fp - vec2(0.065, -0.015), vec2(0.026, 0.035), 0.022);
            outerFlame = smin(outerFlame, min(min(armL, armR), min(footL, footR)), 0.025);

            float midFlame = smin(
                sdFlameShape(fp - vec2(0.0, 0.01), 0.165, 0.38, sway * 0.8),
                min(sdFlameShape(fp - vec2(-0.06, 0.02), 0.09, 0.25, -0.02),
                    sdFlameShape(fp - vec2(0.06, 0.02), 0.09, 0.24, 0.02)),
                0.05
            );
            float coreFlame = sdFlameShape(fp - vec2(0.0, 0.015), 0.125, 0.27, sway * 0.5);

            if (outerFlame < 0.004) col = mix(col, shadeClay(uFlameOuter, outerFlame, fp - vec2(0.0, 0.18), vec2(0.0, 0.5), 0.045), smoothstep(0.004, -0.004, outerFlame));
            if (midFlame < 0.004) col = mix(col, shadeClay(uFlameMid, midFlame, fp - vec2(0.0, 0.15), vec2(0.0, 0.5), 0.035), smoothstep(0.004, -0.004, midFlame));
            if (coreFlame < 0.004) col = mix(col, shadeClay(uFlameCore, coreFlame, fp - vec2(0.0, 0.12), vec2(0.0, 0.5), 0.03), smoothstep(0.004, -0.004, coreFlame));

            vec2 faceP = fp - vec2(0.0, 0.13);
            vec2 symFace = vec2(abs(faceP.x) - 0.052, faceP.y - 0.015);
            float eyeArc = max(abs(length(symFace - vec2(0.0, -0.012)) - 0.020) - 0.0055, -(symFace.y + 0.010));
            float cheek = length((symFace - vec2(0.012, -0.026)) * vec2(0.8, 1.3)) - 0.016;
            vec2 mP = faceP - vec2(0.0, -0.018);
            float mouthRadius = 0.024 + uBass * 0.015;
            float mouthSdf = max(length(mP) - mouthRadius, mP.y);

            if (cheek < 0.003) col = mix(col, vec3(0.96, 0.42, 0.48), smoothstep(0.003, -0.003, cheek));
            if (eyeArc < 0.002) col = mix(col, vec3(0.24, 0.08, 0.08), smoothstep(0.002, -0.002, eyeArc));
            if (mouthSdf < 0.002) {
                float tongue = length(mP - vec2(0.0, -mouthRadius * 0.85)) - mouthRadius * 0.75;
                col = mix(col, mix(vec3(0.32, 0.07, 0.09), vec3(0.94, 0.38, 0.42), smoothstep(0.002, -0.002, tongue)), smoothstep(0.002, -0.002, mouthSdf));
            }

            for (float i = 1.0; i <= 9.0; i += 1.0) {
                float seed = hash12(vec2(i * 37.1, i * 19.7));
                float t = fract(uTime * (0.18 + seed * 0.12) + seed);
                vec2 starPos = vec2(sin(i * 2.4 + t * 4.0) * (0.14 + 0.08 * seed), -0.08 + t * 0.36);
                float sz = (0.012 + 0.012 * fract(seed * 7.3)) * sin(t * 3.14159) * (1.0 + uTreble * 0.6);
                float dStar = (mod(i, 2.0) < 0.5) ? sdStar4(uv - starPos, sz) : length(uv - starPos) - sz * 0.45;
                if (dStar < 0.003) col = mix(col, vec3(1.0, 0.96, 0.72), smoothstep(0.003, -0.002, dStar));
            }

            vec2 hatP = uv - vec2(-0.46, 0.06);
            float hatBody = sdFlameShape((hatP - vec2(-0.02, -0.02)) * vec2(1.0, 1.2), 0.11, 0.18, -0.09);
            float whiteFluff = min(sdBox2D(hatP - vec2(0.0, -0.025), vec2(0.105, 0.026), 0.022), length(hatP - vec2(-0.115, 0.045)) - 0.034);
            if (hatBody < 0.004) col = mix(col, shadeClay(vec3(0.84, 0.12, 0.16), hatBody, hatP, vec2(0.5, 0.5), 0.035), smoothstep(0.004, -0.004, hatBody));
            if (whiteFluff < 0.004) col = mix(col, shadeClay(vec3(0.95, 0.95, 0.98), whiteFluff, hatP, vec2(0.4, 0.6), 0.025), smoothstep(0.004, -0.004, whiteFluff));

            float garCurveY = 0.42 - 0.10 * cos(clamp(uv.x * 2.4, -3.14159, 3.14159));
            if (abs(uv.x) < 0.68 && uv.y > 0.24) {
                float leafClusters = sin(uv.x * 38.0) * 0.015 + cos(uv.x * 75.0) * 0.008;
                float garSDF = (abs(uv.y - garCurveY) + leafClusters) - 0.052;
                if (garSDF < 0.005) col = mix(col, shadeClay(vec3(0.14, 0.42, 0.20), garSDF, vec2(0.0, uv.y - garCurveY), vec2(0.0, -0.6), 0.04), smoothstep(0.005, -0.005, garSDF));
                for (float b = -5.0; b <= 5.0; b += 1.0) {
                    if (abs(b) < 0.5) continue;
                    float bx = b * 0.11;
                    float by = 0.42 - 0.10 * cos(clamp(bx * 2.4, -3.14159, 3.14159)) - 0.035;
                    vec2 bp = uv - vec2(bx, by);
                    float bSdf = length(bp) - 0.024;
                    if (bSdf < 0.003) {
                        vec3 bBase = (mod(abs(b), 2.0) < 0.5) ? vec3(0.88, 0.12, 0.16) : vec3(0.92, 0.68, 0.16);
                        col = mix(col, shadeClay(bBase, bSdf, bp, vec2(-0.4, 0.6), 0.022), smoothstep(0.003, -0.003, bSdf));
                    }
                }
            }

            float vig = dot(uv, uv);
            col *= clamp(1.05 - vig * 0.28, 0.0, 1.0);
            return pow(max(col, vec3(0.0)), vec3(0.92));
        }

        void main() {
            vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution.xy) / uResolution.y;
            vec3 finalColor;
            if (uSceneMode < 0.5) {
                finalColor = renderKawaii(uv);
            } else if (uSceneMode < 1.5) {
                finalColor = renderRealisticOrGhibli(uv, false);
            } else {
                finalColor = renderRealisticOrGhibli(uv, true);
            }
            fragColor = vec4(finalColor, 1.0);
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
        sceneModeLocation = GLES32.glGetUniformLocation(program, "uSceneMode")

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
        GLES32.glUniform1f(sceneModeLocation, sceneMode.id.toFloat())

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
