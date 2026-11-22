package com.shield.kawaiinavidrome

import android.content.Context
import android.graphics.*
import android.opengl.GLES32
import android.opengl.GLUtils

object TextureGenerator {
    fun loadFireplaceBaseTexture(context: Context): Int {
        val opts = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val resId = context.resources.getIdentifier("fireplace_base", "drawable", context.packageName)
        val bitmap = if (resId != 0) {
            BitmapFactory.decodeResource(context.resources, resId, opts)
        } else {
            // Fallback warm canvas if fireplace_base.png has not been copied yet
            Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawColor(Color.rgb(45, 22, 16))
            }
        }

        val texIds = IntArray(1)
        GLES32.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texId)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_S, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_WRAP_T, GLES32.GL_CLAMP_TO_EDGE)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MIN_FILTER, GLES32.GL_LINEAR)
        GLES32.glTexParameteri(GLES32.GL_TEXTURE_2D, GLES32.GL_TEXTURE_MAG_FILTER, GLES32.GL_LINEAR)

        GLUtils.texImage2D(GLES32.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()
        return texId
    }
}
