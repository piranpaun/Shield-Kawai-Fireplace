package com.shield.kawaiinavidrome

import android.content.Context
import android.graphics.*
import android.opengl.GLES32
import android.opengl.GLUtils
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

object TextureGenerator {

    fun loadOrGenerateTexture(
        context: Context,
        customResName: String,
        generator: () -> Bitmap,
        repeatWrap: Boolean
    ): Int {
        val resId = context.resources.getIdentifier(customResName, "drawable", context.packageName)
        val bitmap = if (resId != 0) {
            BitmapFactory.decodeResource(context.resources, resId)
        } else {
            generator()
        }

        val texIds = IntArray(1)
        GLES32.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texId)
        val wrapMode = if (repeatWrap) GLES32.GL_REPEAT else GLES32.GL_CLAMP_TO_EDGE
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, wrapMode)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, wrapMode)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR_MIPMAP_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)

        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES32.glGenerateMipmap(GLES32.GL_TEXTURE_2D)
        bitmap.recycle()

        return texId
    }

    // Textura de madera de nogal oscura para la repisa de la chimenea (1024x256)
    fun createMantelWoodBitmap(): Bitmap {
        val w = 1024
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rng = Random(42)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.rgb(78, 43, 24),
                Color.rgb(58, 30, 16),
                Color.rgb(44, 22, 11)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), basePaint)

        // Vetas longitudinales de nogal
        val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        for (i in 0 until 260) {
            val yBase = rng.nextFloat() * h
            val amp = 3f + rng.nextFloat() * 10f
            val freq = 0.004f + rng.nextFloat() * 0.012f
            val phase = rng.nextFloat() * 6.28f
            val dark = rng.nextBoolean()
            grainPaint.color = if (dark) {
                Color.argb(25 + rng.nextInt(35), 24, 10, 4)
            } else {
                Color.argb(18 + rng.nextInt(25), 118, 68, 38)
            }
            grainPaint.strokeWidth = 1f + rng.nextFloat() * 2.5f

            val path = Path()
            path.moveTo(0f, yBase)
            var x = 0f
            while (x <= w) {
                val y = yBase + sin(x * freq + phase) * amp
                path.lineTo(x, y)
                x += 16f
            }
            canvas.drawPath(path, grainPaint)
        }

        // Nudos orgánicos de madera
        for (k in 0 until 3) {
            val kx = 180f + k * 330f
            val ky = 70f + rng.nextFloat() * 110f
            for (ring in 1..10) {
                grainPaint.color = Color.argb(32 - ring * 2, 22, 9, 4)
                grainPaint.strokeWidth = 1.8f
                val oval = RectF(
                    kx - ring * 22f,
                    ky - ring * 4.5f,
                    kx + ring * 22f,
                    ky + ring * 4.5f
                )
                canvas.drawOval(oval, grainPaint)
            }
        }

        // Bisel superior e inferior de la viga
        val bevelPaint = Paint()
        bevelPaint.shader = LinearGradient(
            0f, 0f, 0f, 18f,
            Color.argb(75, 210, 155, 110),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), 18f, bevelPaint)

        return bmp
    }

    // Textura de tarima de madera para el suelo (1024x512)
    fun createFloorWoodBitmap(): Bitmap {
        val w = 1024
        val h = 512
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rng = Random(108)

        val rows = 6
        val plankH = h.toFloat() / rows
        val plankW = 341.33f

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 10, 5)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(40, 165, 105, 62)
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }

        for (r in 0 until rows) {
            val y0 = r * plankH
            val y1 = y0 + plankH
            val offset = if (r % 2 == 0) 0f else -plankW * 0.5f

            for (c in -1..3) {
                val x0 = c * plankW + offset
                val x1 = x0 + plankW

                val toneShift = rng.nextInt(-12, 14)
                val rCol = (68 + toneShift).coerceIn(35, 110)
                val gCol = (38 + toneShift / 2).coerceIn(18, 65)
                val bCol = (20 + toneShift / 3).coerceIn(10, 38)

                p.style = Paint.Style.FILL
                p.shader = LinearGradient(
                    x0, y0, x1, y1,
                    Color.rgb(rCol + 8, gCol + 5, bCol + 3),
                    Color.rgb(rCol - 8, gCol - 4, bCol - 2),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(x0, y0, x1, y1, p)

                // Vetas dentro de cada lama
                p.shader = null
                p.style = Paint.Style.STROKE
                for (g in 0 until 24) {
                    val gy = y0 + 4f + rng.nextFloat() * (plankH - 8f)
                    p.color = Color.argb(24 + rng.nextInt(25), 25, 11, 5)
                    p.strokeWidth = 1f + rng.nextFloat() * 1.6f
                    val path = Path()
                    path.moveTo(x0, gy)
                    var gx = x0
                    while (gx <= x1) {
                        path.lineTo(gx, gy + sin(gx * 0.015f + g) * 2.2f)
                        gx += 16f
                    }
                    canvas.drawPath(path, p)
                }

                // Junta biselada entre tablas
                canvas.drawRect(x0, y0, x1, y1, seamPaint)
                canvas.drawLine(x0 + 2f, y0 + 2.5f, x1 - 2f, y0 + 2.5f, highlightPaint)
            }
        }
        return bmp
    }

    // Textura transparente de guirnalda de muérdago, acebo, bayas y lazos rojos (1024x320)
    fun createMistletoeGarlandBitmap(): Bitmap {
        val w = 1024
        val h = 320
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rng = Random(777)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Función auxiliar para calcular la curva suave de la guirnalda
        fun garlandY(x: Float): Float {
            val nx = (x - w * 0.5f) / (w * 0.46f)
            return 165f + 36f * sin(nx * 3.14159f * 1.5f).let { it * it }
        }

        // 1. Sombra suave proyectada hacia abajo
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 56f
        paint.color = Color.argb(95, 10, 5, 4)
        val shadowPath = Path()
        for (i in 0..64) {
            val x = 50f + i * ((w - 100f) / 64f)
            val y = garlandY(x) + 18f
            if (i == 0) shadowPath.moveTo(x, y) else shadowPath.lineTo(x, y)
        }
        canvas.drawPath(shadowPath, paint)

        // 2. Ramas de pino y agujas tupidas
        for (i in 0..220) {
            val t = i / 220f
            val cx = 56f + t * (w - 112f)
            val cy = garlandY(cx) + rng.nextFloat() * 18f - 9f
            val angle = (rng.nextFloat() - 0.5f) * 2.4f
            val len = 36f + rng.nextFloat() * 28f

            val ex = cx + cos(angle) * len * (if (rng.nextBoolean()) 1f else -1f)
            val ey = cy + sin(angle) * len * 0.75f

            paint.strokeWidth = 4.2f
            paint.color = when (i % 3) {
                0 -> Color.rgb(22, 68, 34)
                1 -> Color.rgb(34, 92, 46)
                else -> Color.rgb(16, 52, 26)
            }
            canvas.drawLine(cx, cy, ex, ey, paint)
        }

        // 3. Hojas de muérdago ovaladas (verde salvia / oliva luminoso) y hojas de acebo
        for (i in 0..58) {
            val t = i / 58f
            val cx = 68f + t * (w - 136f)
            val cy = garlandY(cx) + (if (i % 2 == 0) -14f else 14f)
            val rotDeg = (if (i % 2 == 0) -28f else 28f) + (rng.nextFloat() - 0.5f) * 35f

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotDeg)

            if (i % 2 == 0) {
                // Hoja doble de muérdago (V-shape característica)
                for (dir in listOf(-1f, 1f)) {
                    canvas.save()
                    canvas.rotate(dir * 22f)
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(
                        0f, 0f, 44f, 0f,
                        Color.rgb(54, 118, 62),
                        Color.rgb(98, 162, 92),
                        Shader.TileMode.CLAMP
                    )
                    val leafOval = RectF(0f, -11f, 46f, 11f)
                    canvas.drawOval(leafOval, paint)
                    paint.shader = null
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.6f
                    paint.color = Color.rgb(145, 198, 130)
                    canvas.drawLine(4f, 0f, 40f, 0f, paint)
                    canvas.restore()
                }
            } else {
                // Hoja de acebo dentada
                val hollyPath = Path().apply {
                    moveTo(0f, 0f)
                    quadTo(14f, -18f, 26f, -10f)
                    quadTo(36f, -19f, 48f, -8f)
                    quadTo(56f, -12f, 62f, 0f)
                    quadTo(56f, 12f, 48f, 8f)
                    quadTo(36f, 19f, 26f, 10f)
                    quadTo(14f, 18f, 0f, 0f)
                    close()
                }
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    0f, -15f, 60f, 15f,
                    Color.rgb(18, 78, 36),
                    Color.rgb(38, 124, 58),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(hollyPath, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.8f
                paint.color = Color.rgb(90, 165, 95)
                canvas.drawLine(4f, 0f, 56f, 0f, paint)
            }
            canvas.restore()
        }

        // 4. Racimos de bayas blancas perladas de muérdago y bayas rojas
        for (i in 0..24) {
            val t = i / 24f
            val cx = 82f + t * (w - 164f)
            val cy = garlandY(cx) + (if (i % 2 == 0) 8f else -8f)
            val isWhiteMistletoe = (i % 2 == 0)

            val offsets = listOf(
                PointF(-7f, -5f),
                PointF(7f, -4f),
                PointF(0f, 7f)
            )
            for (off in offsets) {
                val bx = cx + off.x
                val by = cy + off.y
                val r = if (isWhiteMistletoe) 7.5f else 6.8f
                paint.style = Paint.Style.FILL
                paint.shader = if (isWhiteMistletoe) {
                    RadialGradient(
                        bx - 2.5f, by - 2.5f, r * 1.4f,
                        intArrayOf(Color.rgb(255, 255, 252), Color.rgb(225, 232, 218), Color.rgb(165, 178, 158)),
                        floatArrayOf(0f, 0.65f, 1f),
                        Shader.TileMode.CLAMP
                    )
                } else {
                    RadialGradient(
                        bx - 2.2f, by - 2.2f, r * 1.4f,
                        intArrayOf(Color.rgb(255, 110, 110), Color.rgb(210, 22, 32), Color.rgb(125, 10, 16)),
                        floatArrayOf(0f, 0.55f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(bx, by, r, paint)
                paint.shader = null
            }
        }

        // 5. Tres lazos de terciopelo carmesí con borde dorado (izquierda, centro, derecha)
        val bowPositions = listOf(130f, w * 0.5f, w - 130f)
        for (bx in bowPositions) {
            val by = garlandY(bx) + 6f
            // Cintas colgantes
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(175, 18, 28)
            val leftTail = Path().apply {
                moveTo(bx - 6f, by)
                lineTo(bx - 34f, by + 68f)
                lineTo(bx - 20f, by + 60f)
                lineTo(bx - 10f, by + 72f)
                lineTo(bx + 2f, by + 6f)
                close()
            }
            val rightTail = Path().apply {
                moveTo(bx + 6f, by)
                lineTo(bx + 34f, by + 68f)
                lineTo(bx + 20f, by + 60f)
                lineTo(bx + 10f, by + 72f)
                lineTo(bx - 2f, by + 6f)
                close()
            }
            canvas.drawPath(leftTail, paint)
            canvas.drawPath(rightTail, paint)

            // Lazadas izquierda y derecha
            for (dir in listOf(-1f, 1f)) {
                val loopPath = Path().apply {
                    moveTo(bx, by)
                    cubicTo(
                        bx + dir * 48f, by - 38f,
                        bx + dir * 56f, by + 28f,
                        bx, by + 4f
                    )
                    close()
                }
                paint.shader = LinearGradient(
                    bx, by - 25f, bx + dir * 45f, by + 20f,
                    Color.rgb(225, 34, 46),
                    Color.rgb(142, 12, 20),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(loopPath, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.rgb(235, 190, 85)
                canvas.drawPath(loopPath, paint)
                paint.style = Paint.Style.FILL
            }

            // Nudo central del lazo
            paint.color = Color.rgb(210, 26, 38)
            canvas.drawRoundRect(RectF(bx - 11f, by - 10f, bx + 11f, by + 11f), 5f, 5f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(240, 198, 92)
            canvas.drawRoundRect(RectF(bx - 11f, by - 10f, bx + 11f, by + 11f), 5f, 5f, paint)
            paint.style = Paint.Style.FILL
        }

        return bmp
    }
}
