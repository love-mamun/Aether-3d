package com.example.ui.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class MeshBuffer(
    val buffer: FloatBuffer,
    val vertexCount: Int
)

object GeometryBuilder {

    /**
     * Sculpted 3D Cyber-Humanoid Head / Cranium Mesh (position + normal interleaved, 6 floats/vertex).
     */
    fun buildAvatarHeadMesh(subdivisions: Int = 2): MeshBuffer {
        val sphere = buildIcosphereRaw(subdivisions)
        val deformedVerts = sphere.vertices.map { v ->
            sculptHeadPoint(v[0], v[1], v[2])
        }
        return packTrianglesWithFaceNormals(deformedVerts, sphere.faces)
    }

    /**
     * Holographic wireframe lattice fitted slightly outside the sculpted 3D avatar cranium.
     */
    fun buildAvatarHeadWireframe(subdivisions: Int = 2): MeshBuffer {
        val sphere = buildIcosphereRaw(subdivisions)
        val deformedVerts = sphere.vertices.map { v ->
            val p = sculptHeadPoint(v[0], v[1], v[2])
            floatArrayOf(p[0] * 1.032f, p[1] * 1.032f, p[2] * 1.032f)
        }
        val edgeSet = LinkedHashSet<Long>()
        val floats = ArrayList<Float>(sphere.faces.size * 12)
        for ((a, b, c) in sphere.faces) {
            addUniqueEdge(a, b, deformedVerts, edgeSet, floats)
            addUniqueEdge(b, c, deformedVerts, edgeSet, floats)
            addUniqueEdge(c, a, deformedVerts, edgeSet, floats)
        }
        return MeshBuffer(toFloatBuffer(floats.toFloatArray()), floats.size / 3)
    }

    /**
     * Articulated 3D Lower Jaw / Chin Plate that pivots with voice amplitude.
     * Local origin sits near the jaw hinge (y = 0) so rotating around X opens the mouth naturally.
     */
    fun buildAvatarJawMesh(): MeshBuffer {
        val jawVerts = listOf(
            // Top lip / cheek hinge row
            floatArrayOf(-0.26f, -0.02f, 0.24f), // 0 L-hinge
            floatArrayOf(-0.18f, -0.06f, 0.44f), // 1 L-upper mouth
            floatArrayOf(0.0f,   -0.05f, 0.50f), // 2 Center upper lip
            floatArrayOf(0.18f,  -0.06f, 0.44f), // 3 R-upper mouth
            floatArrayOf(0.26f,  -0.02f, 0.24f), // 4 R-hinge
            // Bottom chin armor row
            floatArrayOf(-0.20f, -0.18f, 0.20f), // 5 L-jaw angle
            floatArrayOf(-0.11f, -0.27f, 0.40f), // 6 L-chin
            floatArrayOf(0.0f,   -0.29f, 0.46f), // 7 Chin tip
            floatArrayOf(0.11f,  -0.27f, 0.40f), // 8 R-chin
            floatArrayOf(0.20f,  -0.18f, 0.20f), // 9 R-jaw angle
            // Under-chin bevel back
            floatArrayOf(0.0f,   -0.22f, 0.16f)  // 10 Under chin
        )
        val faces = listOf(
            Triple(0, 5, 1), Triple(1, 5, 6),
            Triple(1, 6, 2), Triple(2, 6, 7),
            Triple(2, 7, 3), Triple(3, 7, 8),
            Triple(3, 8, 4), Triple(4, 8, 9),
            Triple(5, 10, 6), Triple(6, 10, 7),
            Triple(7, 10, 8), Triple(8, 10, 9),
            Triple(0, 1, 2), Triple(2, 3, 4)
        )
        return packTrianglesWithFaceNormals(jawVerts, faces)
    }

    /**
     * Dual glowing 3D cybernetic visor eyes contoured to the avatar brow sockets.
     */
    fun buildAvatarVisorEyesMesh(): MeshBuffer {
        val verts = listOf(
            // Left Visor Lens (0..3)
            floatArrayOf(-0.29f, 0.13f, 0.43f),
            floatArrayOf(-0.08f, 0.08f, 0.52f),
            floatArrayOf(-0.10f, 0.01f, 0.52f),
            floatArrayOf(-0.27f, 0.05f, 0.44f),
            // Right Visor Lens (4..7)
            floatArrayOf(0.08f,  0.08f, 0.52f),
            floatArrayOf(0.29f,  0.13f, 0.43f),
            floatArrayOf(0.27f,  0.05f, 0.44f),
            floatArrayOf(0.10f,  0.01f, 0.52f),
            // Forehead Sensor Crest (8..11)
            floatArrayOf(-0.06f, 0.34f, 0.48f),
            floatArrayOf(0.06f,  0.34f, 0.48f),
            floatArrayOf(0.03f,  0.22f, 0.53f),
            floatArrayOf(-0.03f, 0.22f, 0.53f)
        )
        val faces = listOf(
            Triple(0, 3, 2), Triple(0, 2, 1),
            Triple(4, 7, 6), Triple(4, 6, 5),
            Triple(8, 11, 10), Triple(8, 10, 9)
        )
        return packTrianglesWithFaceNormals(verts, faces)
    }

    /**
     * Sculpted 3D Neck Column + Cybernetic Collar & Shoulders Bust.
     * Positioned below the head (around y = -0.35..-1.05).
     */
    fun buildAvatarTorsoMesh(): MeshBuffer {
        val verts = ArrayList<FloatArray>()
        val faces = ArrayList<Triple<Int, Int, Int>>()

        // 1. Segmented Cyber Neck Column (top ring + bottom ring)
        val neckSegs = 12
        val neckTopStart = verts.size
        for (i in 0 until neckSegs) {
            val a = (i.toFloat() / neckSegs) * (2f * PI.toFloat())
            verts.add(floatArrayOf(cos(a) * 0.19f, -0.32f, sin(a) * 0.20f))
        }
        val neckBotStart = verts.size
        for (i in 0 until neckSegs) {
            val a = (i.toFloat() / neckSegs) * (2f * PI.toFloat())
            verts.add(floatArrayOf(cos(a) * 0.25f, -0.54f, sin(a) * 0.24f))
        }
        for (i in 0 until neckSegs) {
            val next = (i + 1) % neckSegs
            val t0 = neckTopStart + i
            val t1 = neckTopStart + next
            val b0 = neckBotStart + i
            val b1 = neckBotStart + next
            faces.add(Triple(t0, b0, b1))
            faces.add(Triple(t0, b1, t1))
        }

        // 2. Angular Holographic Shoulders & Armored Chest Bust
        val bustStart = verts.size
        val bustVerts = listOf(
            // Collar ring (0..4)
            floatArrayOf(-0.42f, -0.52f,  0.14f), // 0 L-collar front
            floatArrayOf(0.0f,   -0.56f,  0.30f), // 1 Center sternum top
            floatArrayOf(0.42f,  -0.52f,  0.14f), // 2 R-collar front
            floatArrayOf(0.38f,  -0.50f, -0.24f), // 3 R-collar back
            floatArrayOf(-0.38f, -0.50f, -0.24f), // 4 L-collar back
            // Shoulder pauldrons & chest ridge (5..10)
            floatArrayOf(-0.92f, -0.66f,  0.04f), // 5 L-shoulder outer
            floatArrayOf(-0.48f, -0.68f,  0.36f), // 6 L-pec
            floatArrayOf(0.0f,   -0.72f,  0.42f), // 7 Chest center
            floatArrayOf(0.48f,  -0.68f,  0.36f), // 8 R-pec
            floatArrayOf(0.92f,  -0.66f,  0.04f), // 9 R-shoulder outer
            floatArrayOf(0.0f,   -0.64f, -0.34f), // 10 Back spine ridge
            // Lower bust taper (11..15)
            floatArrayOf(-0.74f, -0.96f,  0.02f), // 11 L-lower shoulder
            floatArrayOf(-0.38f, -1.04f,  0.28f), // 12 L-lower chest
            floatArrayOf(0.0f,   -1.06f,  0.32f), // 13 Lower sternum
            floatArrayOf(0.38f,  -1.04f,  0.28f), // 14 R-lower chest
            floatArrayOf(0.74f,  -0.96f,  0.02f), // 15 R-lower shoulder
            floatArrayOf(0.0f,   -1.02f, -0.28f)  // 16 Lower back
        )
        verts.addAll(bustVerts)
        val b = bustStart
        val bustFaces = listOf(
            // Front collar -> chest
            Triple(b + 0, b + 5, b + 6),
            Triple(b + 0, b + 6, b + 1),
            Triple(b + 1, b + 6, b + 7),
            Triple(b + 1, b + 7, b + 8),
            Triple(b + 1, b + 8, b + 2),
            Triple(b + 2, b + 8, b + 9),
            // Back collar -> spine
            Triple(b + 0, b + 4, b + 5),
            Triple(b + 4, b + 10, b + 5),
            Triple(b + 4, b + 3, b + 10),
            Triple(b + 3, b + 2, b + 9),
            Triple(b + 3, b + 9, b + 10),
            // Mid chest -> lower bust
            Triple(b + 5, b + 11, b + 12),
            Triple(b + 5, b + 12, b + 6),
            Triple(b + 6, b + 12, b + 13),
            Triple(b + 6, b + 13, b + 7),
            Triple(b + 7, b + 13, b + 14),
            Triple(b + 7, b + 14, b + 8),
            Triple(b + 8, b + 14, b + 15),
            Triple(b + 8, b + 15, b + 9),
            // Back lower
            Triple(b + 5, b + 10, b + 16),
            Triple(b + 5, b + 16, b + 11),
            Triple(b + 9, b + 15, b + 16),
            Triple(b + 9, b + 16, b + 10)
        )
        faces.addAll(bustFaces)

        return packTrianglesWithFaceNormals(verts, faces)
    }

    /**
     * Glowing Arc Reactor emblem embedded in the 3D Avatar chest.
     */
    fun buildAvatarChestCoreMesh(subdivisions: Int = 1, radius: Float = 0.13f): MeshBuffer {
        val sphere = buildIcosphereRaw(subdivisions)
        val verts = sphere.vertices.map { v ->
            floatArrayOf(v[0] * radius, v[1] * radius, v[2] * radius * 0.65f)
        }
        return packTrianglesWithFaceNormals(verts, sphere.faces)
    }

    fun buildIcosphereTriangles(subdivisions: Int = 2, radius: Float = 0.72f): MeshBuffer {
        val sphere = buildIcosphereRaw(subdivisions)
        val floats = FloatArray(sphere.faces.size * 3 * 6)
        var ptr = 0
        for ((a, b, c) in sphere.faces) {
            for (idx in intArrayOf(a, b, c)) {
                val n = sphere.vertices[idx]
                floats[ptr++] = n[0] * radius
                floats[ptr++] = n[1] * radius
                floats[ptr++] = n[2] * radius
                floats[ptr++] = n[0]
                floats[ptr++] = n[1]
                floats[ptr++] = n[2]
            }
        }
        return MeshBuffer(toFloatBuffer(floats), sphere.faces.size * 3)
    }

    fun buildSegmentedArcRing(
        radius: Float,
        segments: Int = 64,
        gapEvery: Int = 6
    ): MeshBuffer {
        val floats = ArrayList<Float>()
        val twoPi = (2.0 * PI).toFloat()
        for (i in 0 until segments) {
            if (gapEvery > 0 && i % gapEvery == 0) continue
            val a0 = (i.toFloat() / segments) * twoPi
            val a1 = ((i + 1).toFloat() / segments) * twoPi
            floats.add(cos(a0) * radius)
            floats.add(0f)
            floats.add(sin(a0) * radius)
            floats.add(cos(a1) * radius)
            floats.add(0f)
            floats.add(sin(a1) * radius)
        }
        return MeshBuffer(toFloatBuffer(floats.toFloatArray()), floats.size / 3)
    }

    fun buildUnitPrismTriangles(): MeshBuffer {
        val w = 0.022f
        val d = 0.028f
        val v = floatArrayOf(
            -w, 0f, d,  w, 0f, d,  w, 1f, d,
            -w, 0f, d,  w, 1f, d, -w, 1f, d,
            w, 0f, -d, -w, 0f, -d, -w, 1f, -d,
            w, 0f, -d, -w, 1f, -d,  w, 1f, -d,
            -w, 0f, -d, -w, 0f, d, -w, 1f, d,
            -w, 0f, -d, -w, 1f, d, -w, 1f, -d,
            w, 0f, d,  w, 0f, -d,  w, 1f, -d,
            w, 0f, d,  w, 1f, -d,  w, 1f, d,
            -w, 1f, d,  w, 1f, d,  w, 1f, -d,
            -w, 1f, d,  w, 1f, -d, -w, 1f, -d
        )
        return MeshBuffer(toFloatBuffer(v), v.size / 3)
    }

    fun buildPerspectiveFloorGrid(
        halfExtent: Float = 4.2f,
        step: Float = 0.7f,
        yOffset: Float = -1.35f
    ): MeshBuffer {
        val floats = ArrayList<Float>()
        var v = -halfExtent
        while (v <= halfExtent + 0.001f) {
            floats.addAll(listOf(v, yOffset, -halfExtent, v, yOffset, halfExtent))
            floats.addAll(listOf(-halfExtent, yOffset, v, halfExtent, yOffset, v))
            v += step
        }
        return MeshBuffer(toFloatBuffer(floats.toFloatArray()), floats.size / 3)
    }

    fun buildOrbitalParticles(count: Int = 140): MeshBuffer {
        val rng = Random(2026)
        val floats = FloatArray(count * 4)
        var ptr = 0
        for (i in 0 until count) {
            val theta = rng.nextFloat() * (2f * PI.toFloat())
            val cosPhi = rng.nextFloat() * 2f - 1f
            val sinPhi = sqrt((1f - cosPhi * cosPhi).coerceAtLeast(0f))
            val radius = 1.25f + rng.nextFloat() * 1.15f
            floats[ptr++] = radius * sinPhi * cos(theta)
            floats[ptr++] = radius * cosPhi
            floats[ptr++] = radius * sinPhi * sin(theta)
            floats[ptr++] = rng.nextFloat() * 6.283f
        }
        return MeshBuffer(toFloatBuffer(floats), count)
    }

    private fun sculptHeadPoint(nx: Float, ny: Float, nz: Float): FloatArray {
        var x = nx * 0.44f
        var y = ny * 0.56f
        var z = nz * 0.48f

        // Taper lower face toward jawline and broaden cranium slightly
        if (ny < 0f) {
            val lowerFactor = (-ny).coerceIn(0f, 1f)
            x *= (1.0f - 0.34f * lowerFactor)
            z *= (1.0f - 0.20f * lowerFactor)
        } else {
            val upperFactor = ny.coerceIn(0f, 1f)
            x *= (1.0f - 0.08f * upperFactor)
        }

        // Flatten cheeks / side temples slightly for sleek cyber-helm silhouette
        if (abs(nx) > 0.55f) {
            x *= 0.93f
        }

        // Sculpt forward face plane, subtle brow ridge, and recessed visor sockets
        if (nz > 0.25f) {
            // Subtle nose/brow prominence along center vertical axis
            if (abs(nx) < 0.14f && ny in -0.15f..0.38f) {
                z += 0.035f
            }
            // Brow ridge
            if (ny in 0.22f..0.42f) {
                z += 0.03f
            }
            // Recessed eye visor sockets
            if (abs(nx) in 0.16f..0.52f && ny in 0.02f..0.22f) {
                z -= 0.045f
            }
        }
        return floatArrayOf(x, y, z)
    }

    private data class RawIcosphere(
        val vertices: List<FloatArray>,
        val faces: List<Triple<Int, Int, Int>>
    )

    private fun buildIcosphereRaw(subdivisions: Int): RawIcosphere {
        val t = (1f + sqrt(5f)) / 2f
        val baseVerts = listOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f),
            floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t),
            floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f),
            floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f)
        ).map { normalize(it) }

        var faces = listOf(
            Triple(0, 11, 5), Triple(0, 5, 1), Triple(0, 1, 7), Triple(0, 7, 10), Triple(0, 10, 11),
            Triple(1, 5, 9), Triple(5, 11, 4), Triple(11, 10, 2), Triple(10, 7, 6), Triple(7, 1, 8),
            Triple(3, 9, 4), Triple(3, 4, 2), Triple(3, 2, 6), Triple(3, 6, 8), Triple(3, 8, 9),
            Triple(4, 9, 5), Triple(2, 4, 11), Triple(6, 2, 10), Triple(8, 6, 7), Triple(9, 8, 1)
        )

        val vertices = baseVerts.toMutableList()
        val midpointCache = HashMap<Long, Int>()

        fun getMidpoint(i1: Int, i2: Int): Int {
            val min = minOf(i1, i2).toLong()
            val max = maxOf(i1, i2).toLong()
            val key = (min shl 32) + max
            midpointCache[key]?.let { return it }
            val v1 = vertices[i1]
            val v2 = vertices[i2]
            val mid = normalize(
                floatArrayOf((v1[0] + v2[0]) * 0.5f, (v1[1] + v2[1]) * 0.5f, (v1[2] + v2[2]) * 0.5f)
            )
            val idx = vertices.size
            vertices.add(mid)
            midpointCache[key] = idx
            return idx
        }

        repeat(subdivisions) {
            val next = ArrayList<Triple<Int, Int, Int>>(faces.size * 4)
            for ((a, b, c) in faces) {
                val ab = getMidpoint(a, b)
                val bc = getMidpoint(b, c)
                val ca = getMidpoint(c, a)
                next.add(Triple(a, ab, ca))
                next.add(Triple(b, bc, ab))
                next.add(Triple(c, ca, bc))
                next.add(Triple(ab, bc, ca))
            }
            faces = next
        }
        return RawIcosphere(vertices, faces)
    }

    private fun packTrianglesWithFaceNormals(
        vertices: List<FloatArray>,
        faces: List<Triple<Int, Int, Int>>
    ): MeshBuffer {
        val floats = FloatArray(faces.size * 3 * 6)
        var ptr = 0
        for ((a, b, c) in faces) {
            val p0 = vertices[a]
            val p1 = vertices[b]
            val p2 = vertices[c]
            val ux = p1[0] - p0[0]
            val uy = p1[1] - p0[1]
            val uz = p1[2] - p0[2]
            val vx = p2[0] - p0[0]
            val vy = p2[1] - p0[1]
            val vz = p2[2] - p0[2]
            val fn = normalize(
                floatArrayOf(
                    uy * vz - uz * vy,
                    uz * vx - ux * vz,
                    ux * vy - uy * vx
                )
            )
            for (p in arrayOf(p0, p1, p2)) {
                floats[ptr++] = p[0]
                floats[ptr++] = p[1]
                floats[ptr++] = p[2]
                floats[ptr++] = fn[0]
                floats[ptr++] = fn[1]
                floats[ptr++] = fn[2]
            }
        }
        return MeshBuffer(toFloatBuffer(floats), faces.size * 3)
    }

    private fun addUniqueEdge(
        i0: Int,
        i1: Int,
        verts: List<FloatArray>,
        edgeSet: MutableSet<Long>,
        out: MutableList<Float>
    ) {
        val min = minOf(i0, i1).toLong()
        val max = maxOf(i0, i1).toLong()
        val key = (min shl 32) + max
        if (!edgeSet.add(key)) return
        val a = verts[i0]
        val b = verts[i1]
        out.add(a[0]); out.add(a[1]); out.add(a[2])
        out.add(b[0]); out.add(b[1]); out.add(b[2])
    }

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    private fun toFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(array)
                position(0)
            }
    }
}
