package com.example.ui.gl

import android.opengl.GLES20
import android.util.Log

private const val TAG = "ShaderUtils"

object ShaderUtils {

    const val CORE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uModelMatrix;
        uniform mediump float uTime;
        uniform mediump float uAudioLevel;
        uniform float uDisplaceScale;

        attribute vec3 aPosition;
        attribute vec3 aNormal;

        varying mediump vec3 vNormal;
        varying mediump vec3 vWorldPos;
        varying mediump float vWave;

        void main() {
            mediump float wave1 = sin(aPosition.x * 4.5 + uTime * 2.8) * cos(aPosition.y * 4.5 - uTime * 2.1);
            mediump float wave2 = sin(aPosition.z * 6.0 + uTime * 3.6);
            mediump float disp = (wave1 * 0.55 + wave2 * 0.45) * (0.04 + uAudioLevel * 0.22) * uDisplaceScale;
            vec3 displaced = aPosition + aNormal * disp;

            vec4 worldPos = uModelMatrix * vec4(displaced, 1.0);
            vWorldPos = worldPos.xyz;
            vNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);
            vWave = wave1 * 0.5 + 0.5;
            gl_Position = uMVPMatrix * vec4(displaced, 1.0);
        }
    """

    const val CORE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uPrimaryColor;
        uniform vec3 uSecondaryColor;
        uniform vec3 uCameraPos;
        uniform mediump float uTime;
        uniform mediump float uAudioLevel;
        uniform float uAlpha;

        varying mediump vec3 vNormal;
        varying mediump vec3 vWorldPos;
        varying mediump float vWave;

        void main() {
            vec3 N = normalize(vNormal);
            vec3 V = normalize(uCameraPos - vWorldPos);
            float fresnel = pow(1.0 - max(dot(N, V), 0.0), 2.1);
            float scanline = 0.86 + 0.14 * sin(vWorldPos.y * 26.0 - uTime * 5.5);

            vec3 base = mix(uSecondaryColor * 0.45, uPrimaryColor, clamp(vWave * 0.65 + fresnel * 0.75, 0.0, 1.0));
            vec3 glow = uPrimaryColor * (fresnel * (1.25 + uAudioLevel * 0.85)) * scanline;
            vec3 finalColor = base * 0.68 + glow;
            float alpha = clamp(uAlpha * (0.60 + fresnel * 0.52), 0.15, 0.96);
            gl_FragColor = vec4(finalColor, alpha);
        }
    """

    const val LINE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uModelMatrix;
        attribute vec3 aPosition;
        varying mediump vec3 vWorldPos;

        void main() {
            vec4 worldPos = uModelMatrix * vec4(aPosition, 1.0);
            vWorldPos = worldPos.xyz;
            gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
        }
    """

    const val LINE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uColor;
        uniform float uAlpha;
        uniform float uFadeRadius;
        varying mediump vec3 vWorldPos;

        void main() {
            float fade = 1.0;
            if (uFadeRadius > 0.0) {
                float d = length(vWorldPos.xz);
                fade = clamp(1.0 - (d / uFadeRadius), 0.0, 1.0);
                fade = fade * fade;
            }
            gl_FragColor = vec4(uColor, uAlpha * fade);
        }
    """

    const val PARTICLE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mediump float uTime;
        uniform mediump float uAudioLevel;
        attribute vec3 aPosition;
        attribute float aPhase;
        varying mediump float vAlpha;

        void main() {
            mediump float radiusBoost = 1.0 + uAudioLevel * 0.28 * sin(uTime * 3.0 + aPhase);
            vec3 pos = aPosition * radiusBoost;
            gl_Position = uMVPMatrix * vec4(pos, 1.0);
            gl_PointSize = 4.0 + 4.0 * (0.5 + 0.5 * sin(uTime * 2.5 + aPhase)) + uAudioLevel * 3.5;
            vAlpha = 0.35 + 0.65 * (0.5 + 0.5 * sin(uTime * 2.0 + aPhase));
        }
    """

    const val PARTICLE_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec3 uColor;
        uniform float uAlpha;
        varying mediump float vAlpha;

        void main() {
            vec2 coord = gl_PointCoord - vec2(0.5);
            float dist = length(coord);
            if (dist > 0.5) discard;
            float soft = smoothstep(0.5, 0.08, dist);
            gl_FragColor = vec4(uColor, soft * vAlpha * uAlpha);
        }
    """

    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
