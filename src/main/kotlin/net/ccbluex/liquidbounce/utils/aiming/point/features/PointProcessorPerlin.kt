/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */

package net.ccbluex.liquidbounce.utils.aiming.point.features

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.utils.aiming.point.PointInsideBox
import net.ccbluex.liquidbounce.utils.entity.horizontalSpeed
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.security.SecureRandom

internal class PointProcessorPerlin(parent: EventListener) : PointProcessor(parent, "Perlin", false) {

    companion object {
        private val random = SecureRandom()

        // Permutation table for Perlin Noise
        private val PERMUTATION = intArrayOf(
            151,160,137,91,90,15,
            131,13,201,95,96,53,194,233, 7,225,140,36,103,30,69,142, 8,99,37,240,21,10,23,
            190, 6,148,247,120,234,75,  0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,
            88,237,149,56,87,174,20,125,136,171,168, 68,175,74,165,71,134,139,48,27,166,
            77,146,158,231,83,111,229,122, 60,211,133,230,220,105,92,41,55,46,245,40,244,
            102,143,54, 65,25,63,161, 1,216,80,73,209,76,132,187,208, 89,18,169,200,196,
            135,130,116,188,189,134,246,129,211,114,120,224,252,142,203, 49,155,112, 21,115,
            170,172,186, 52,252,223,205,141,128,146, 19,138, 24,140,226,152, 34,181,120,242,
            210,190, 24, 42,243,153, 41, 85,233, 5,193, 2,139,239,141,142,148,130,135, 78,
            196,147, 85, 30,207,246, 55,252,142,244,228, 4, 34,242,193,147, 90,144, 11,115,
            224, 14, 54,223,209, 25, 65,163,225,224, 21,110,174,129,172, 44,254, 78,  4,180,
            110, 72,125,26, 90,  9,223, 78,104, 76, 53, 84,186,190,139,152,129,233,189,213,
            103,117,141, 72,155,159,135, 47,178,  5,169, 47,137,111,250,140,254,201,218, 97,
            228,251, 34,242,193,238,210,144, 12,191,179,162,241, 81, 51,145,235,249, 14,239,
            107, 49,192,214, 31,181,243, 72,135,141,142,146,190,219, 92,223,129,101,155,111,
            84, 37,240,21,10,23,190, 6,148,247,120,234,75, 0,26,197,62,94,252,219,203,117,
            35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168, 68,175,74,165,71,
            134,139,48,27,166,77,146,158,231,83,111,229,122, 60,211,133,230,220,105,92,41,
            55,46,245,40,244,102,143,54, 65,25,63,161, 1,216,80,73,209,76,132,187,208, 89,
            18,169,200,196,135,130,116,188,189,134,246,129,211,114,120,224,252,142,203, 49,
            155,112, 21,115,170,172,186, 52,252,223,205,141,128,146, 19,138, 24,140,226,152,
            34,181,120,242,210,190, 24, 42,243,153, 41, 85,233, 5,193, 2,139,239,141,142,
            148,130,135, 78,196,147, 85, 30,207,246, 55,252,142,244,228, 4, 34,242,193,147,
            90,144, 11,115,224, 14, 54,223,209, 25, 65,163,225,224, 21,110,174,129,172, 44,
            254, 78, 4,180,110, 72,125,26, 90, 9,223, 78,104, 76, 53, 84,186,190,139,152,
            129,233,189,213,103,117,141, 72,155,159,135, 47,178, 5,169, 47,137,111,250,140,
            254,201,218, 97,228,251,34,242,193,238,210,144,12,191,179,162,241, 81, 51,145,
            235,249, 14,239,107, 49,192,214, 31,181,243, 72,135,141,142,146,190,219, 92,223,
            129,101,155,111,84, 37,240,21,10,23,190, 6,148,247,120,234,75, 0,26,197,62,94,
            252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,
            175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122, 60,211,133,230,
            220,105,92,41,55,46,245,40,244,102,143,54, 65,25,63,161, 1,216,80,73,209,76,
            132,187,208, 89,18,169,200,196,135,130,116,188,189,134,246,129,211,114,120,224,
            252,142,203, 49,155,112, 21,115,170,172,186, 52,252,223,205,141,128,146, 19,138,
            24,140,226,152, 34,181,120,242,210,190, 24, 42,243,153, 41, 85,233, 5,193, 2,
            139,239,141,142,148,130,135, 78,196,147, 85, 30,207,246, 55,252,142,244,228, 4,
            34,242,193,147, 90,144, 11,115,224, 14, 54,223,209, 25, 65,163,225,224, 21,110,
            174,129,172, 44,254, 78, 4,180,110, 72,125,26, 90, 9,223, 78,104, 76, 53, 84,
            186,190,139,152,129,233,189,213,103,117,141, 72,155,159,135, 47,178, 5,169, 47,
            137,111,250,140,254,201,218, 97,228,251,34,242,193,238,210,144,12,191,179,162,
            241, 81, 51,145,235,249, 14,239,107, 49,192,214, 31,181,243, 72,135,141,142,146,
            190,219, 92,223,129,101,155,111,84, 37,240,21,10,23,190, 6,148,247,120,234,75,
            0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,
            125,136,171,168, 68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,
            122, 60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54, 65,25,63,161,
            1,216,80,73,209,76,132,187,208, 89,18,169,200,196,135,130,116,188,189,134,246,
            129,211,114,120,224,252,142,203, 49,155,112, 21,115,170,172,186, 52,252,223,205,
            141,128,146, 19,138, 24,140,226,152, 34,181,120,242,210,190, 24, 42,243,153, 41,
            85,233, 5,193, 2,139,239,141,142,148,130,135, 78,196,147, 85, 30,207,246, 55,
            252,142,244,228, 4, 34,242,193,147, 90,144, 11,115,224, 14, 54,223,209, 25, 65,
            163,225,224, 21,110,174,129,172, 44,254, 78, 4,180,110, 72,125,26, 90, 9,223,
            78,104, 76, 53, 84,186,190,139,152,129,233,189,213,103,117,141, 72,155,159,135,
            47,178, 5,169, 47,137,111,250,140,254,201,218, 97,228,251, 34,242,193,238,210,
            144,12,191,179,162,241, 81, 51,145,235,249, 14,239,107, 49,192,214, 31,181,243,
            72,135,141,142,146,190,219, 92,223,129,101,155,111,84, 37,240,21,10,23,190,
            6,148,247,120,234,75, 0,26,197,62,94,252,219,203,117,35,11,32,57,177,33, 88
        )

        private val P = IntArray(512).also { p ->
            for (i in 0..255) {
                p[i] = PERMUTATION[i]
                p[256 + i] = PERMUTATION[i]
            }
        }

        private fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

        private fun lerp(t: Double, a: Double, b: Double): Double = a + t * (b - a)

        private fun grad(hash: Int, x: Double, y: Double, z: Double): Double {
            val h = hash and 15
            val u = if (h < 8) x else y
            val v = if (h < 4) y else if (h == 12 || h == 14) x else z
            return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
        }

        fun noise(x: Double, y: Double, z: Double): Double {
            val xi = Math.floor(x).toInt() and 255
            val yi = Math.floor(y).toInt() and 255
            val zi = Math.floor(z).toInt() and 255

            val xf = x - Math.floor(x)
            val yf = y - Math.floor(y)
            val zf = z - Math.floor(z)

            val u = fade(xf)
            val v = fade(yf)
            val w = fade(zf)

            val aaa = P[P[P[xi] + yi] + zi]
            val aba = P[P[P[xi] + yi + 1] + zi]
            val aab = P[P[P[xi] + yi] + zi + 1]
            val abb = P[P[P[xi] + yi + 1] + zi + 1]
            val baa = P[P[P[xi + 1] + yi] + zi]
            val bba = P[P[P[xi + 1] + yi + 1] + zi]
            val bab = P[P[P[xi + 1] + yi] + zi + 1]
            val bbb = P[P[P[xi + 1] + yi + 1] + zi + 1]

            val x1 = lerp(u, grad(aaa, xf, yf, zf), grad(baa, xf - 1, yf, zf))
            val x2 = lerp(u, grad(aba, xf, yf - 1, zf), grad(bba, xf - 1, yf - 1, zf))
            val x3 = lerp(u, grad(aab, xf, yf, zf - 1), grad(bab, xf - 1, yf, zf - 1))
            val x4 = lerp(u, grad(abb, xf, yf - 1, zf - 1), grad(bbb, xf - 1, yf - 1, zf - 1))

            val y1 = lerp(v, x1, x2)
            val y2 = lerp(v, x3, x4)

            return lerp(w, y1, y2)
        }
    }

    private var time: Double = random.nextDouble() * 10000.0
    private var currentOffset: Vec3 = Vec3.ZERO

    private val yawFactor by floatRange("YawOffset", 0f..0f, 0.0f..1.0f)
    private val pitchFactor by floatRange("PitchOffset", 0f..0f, 0.0f..1.0f)
    private val speed by floatRange("Speed", 0.1f..0.2f, 0.01f..1f)

    private inner class Dynamic : ToggleableValueGroup(this, "Dynamic", false) {
        val hurtTime by int("HurtTime", 10, 0..10)
        val yawFactor by float("YawFactor", 0f, 0f..10f, "x")
        val pitchFactor by float("PitchFactor", 0f, 0f..10f, "x")
        val speed by floatRange("Speed", 0.5f..0.75f, 0.01f..1f)
    }

    private val dynamic = tree(Dynamic())

    fun updatePerlinOffset(entity: Any?) {
        val dynamicCheck = dynamic.enabled && entity is LivingEntity && entity.hurtTime >= dynamic.hurtTime

        val yawFactor =
            if (dynamicCheck && dynamic.yawFactor > 0f) {
                (yawFactor.random() + player.horizontalSpeed * dynamic.yawFactor)
            } else {
                yawFactor.random()
            }.toDouble()

        val pitchFactor =
            if (dynamicCheck && dynamic.pitchFactor > 0f) {
                (pitchFactor.random() + player.horizontalSpeed * dynamic.pitchFactor)
            } else {
                pitchFactor.random()
            }.toDouble()

        val currentSpeed =
            if (dynamicCheck) {
                dynamic.speed.random().toDouble()
            } else {
                speed.random().toDouble()
            }

        time += currentSpeed

        val offsetX = noise(time, 17.15, 42.84) * yawFactor
        val offsetY = noise(time + 100.0, 55.32, 12.98) * pitchFactor
        val offsetZ = noise(time + 200.0, 93.11, 74.45) * yawFactor

        currentOffset = Vec3(offsetX, offsetY, offsetZ)
    }

    override fun process(point: PointInsideBox): PointInsideBox {
        if (yawFactor.random() > 0.0f || pitchFactor.random() > 0.0f) {
            updatePerlinOffset(point)
        }

        return point + currentOffset
    }

}
