package com.kyant.backdrop

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import org.intellij.lang.annotations.Language

interface RuntimeShader {
    fun setFloatUniform(name: String, value: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float)
    fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float)
    fun setFloatUniform(name: String, values: FloatArray)

    fun setIntUniform(name: String, value: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int)
    fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int)
    fun setIntUniform(name: String, values: IntArray)

    fun setColorUniform(name: String, color: Color)
}

fun RuntimeShader(@Language("AGSL") shaderString: String): RuntimeShader {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AndroidRuntimeShaderCreator.create(shaderString)
    } else {
        FallbackRuntimeShader()
    }
}

fun RuntimeShader.asAndroidRuntimeShader(): android.graphics.RuntimeShader {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && this is AndroidRuntimeShader) {
        this.shader
    } else {
        throw UnsupportedOperationException("RuntimeShader is only supported on Android 13 (API 33)+")
    }
}

fun RuntimeShader.asComposeShader(): Shader {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && this is AndroidRuntimeShader) {
        this.shader
    } else {
        throw UnsupportedOperationException("RuntimeShader is only supported on Android 13 (API 33)+")
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object AndroidRuntimeShaderCreator {
    fun create(shaderString: String): RuntimeShader {
        return AndroidRuntimeShader(android.graphics.RuntimeShader(shaderString))
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AndroidRuntimeShader(val shader: android.graphics.RuntimeShader) : RuntimeShader {
    override fun setFloatUniform(name: String, value: Float) { shader.setFloatUniform(name, value) }
    override fun setFloatUniform(name: String, value1: Float, value2: Float) { shader.setFloatUniform(name, value1, value2) }
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) { shader.setFloatUniform(name, value1, value2, value3) }
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) { shader.setFloatUniform(name, value1, value2, value3, value4) }
    override fun setFloatUniform(name: String, values: FloatArray) { shader.setFloatUniform(name, values) }
    override fun setIntUniform(name: String, value: Int) { shader.setIntUniform(name, value) }
    override fun setIntUniform(name: String, value1: Int, value2: Int) { shader.setIntUniform(name, value1, value2) }
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) { shader.setIntUniform(name, value1, value2, value3) }
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) { shader.setIntUniform(name, value1, value2, value3, value4) }
    override fun setIntUniform(name: String, values: IntArray) { shader.setIntUniform(name, values) }
    override fun setColorUniform(name: String, color: Color) { shader.setColorUniform(name, color.toArgb()) }
}

internal class FallbackRuntimeShader : RuntimeShader {
    override fun setFloatUniform(name: String, value: Float) {}
    override fun setFloatUniform(name: String, value1: Float, value2: Float) {}
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) {}
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) {}
    override fun setFloatUniform(name: String, values: FloatArray) {}
    override fun setIntUniform(name: String, value: Int) {}
    override fun setIntUniform(name: String, value1: Int, value2: Int) {}
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int) {}
    override fun setIntUniform(name: String, value1: Int, value2: Int, value3: Int, value4: Int) {}
    override fun setIntUniform(name: String, values: IntArray) {}
    override fun setColorUniform(name: String, color: Color) {}
}
