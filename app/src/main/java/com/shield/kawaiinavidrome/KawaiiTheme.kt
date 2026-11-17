package com.shield.kawaiinavidrome

data class KawaiiTheme(
    val name: String,
    val bgColor: FloatArray,
    val brickColor: FloatArray,
    val flameOuter: FloatArray,
    val flameMid: FloatArray,
    val flameCore: FloatArray,
    val glowColor: FloatArray,
    val hudAccentHex: Int
)

object KawaiiThemes {
    val list = listOf(
        KawaiiTheme(
            name = "Navidad Clásica Pastel",
            bgColor = floatArrayOf(0.12f, 0.07f, 0.18f),
            brickColor = floatArrayOf(0.85f, 0.45f, 0.48f),
            flameOuter = floatArrayOf(1.00f, 0.42f, 0.35f),
            flameMid = floatArrayOf(1.00f, 0.68f, 0.25f),
            flameCore = floatArrayOf(1.00f, 0.92f, 0.55f),
            glowColor = floatArrayOf(0.80f, 0.30f, 0.10f),
            hudAccentHex = 0xFFFF8FA3.toInt()
        ),
        KawaiiTheme(
            name = "Sakura Mágico",
            bgColor = floatArrayOf(0.16f, 0.08f, 0.22f),
            brickColor = floatArrayOf(0.75f, 0.52f, 0.82f),
            flameOuter = floatArrayOf(0.95f, 0.30f, 0.65f),
            flameMid = floatArrayOf(1.00f, 0.58f, 0.82f),
            flameCore = floatArrayOf(1.00f, 0.90f, 0.98f),
            glowColor = floatArrayOf(0.85f, 0.20f, 0.60f),
            hudAccentHex = 0xFFFF99C8.toInt()
        ),
        KawaiiTheme(
            name = "Hielo Ártico",
            bgColor = floatArrayOf(0.05f, 0.12f, 0.20f),
            brickColor = floatArrayOf(0.45f, 0.68f, 0.80f),
            flameOuter = floatArrayOf(0.15f, 0.65f, 0.95f),
            flameMid = floatArrayOf(0.35f, 0.92f, 0.85f),
            flameCore = floatArrayOf(0.88f, 1.00f, 0.98f),
            glowColor = floatArrayOf(0.10f, 0.55f, 0.85f),
            hudAccentHex = 0xFF72EFDD.toInt()
        ),
        KawaiiTheme(
            name = "Matcha & Cacao",
            bgColor = floatArrayOf(0.10f, 0.14f, 0.09f),
            brickColor = floatArrayOf(0.68f, 0.50f, 0.38f),
            flameOuter = floatArrayOf(0.42f, 0.78f, 0.35f),
            flameMid = floatArrayOf(0.75f, 0.90f, 0.40f),
            flameCore = floatArrayOf(0.98f, 0.98f, 0.72f),
            glowColor = floatArrayOf(0.45f, 0.70f, 0.20f),
            hudAccentHex = 0xFFA7C957.toInt()
        )
    )
}
