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
package net.ccbluex.liquidbounce.utils.combat

import net.ccbluex.liquidbounce.utils.math.sq
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TargetPriorityTest {

    @Test
    fun `health distance prefers nearby healthy target over far low health target`() {
        val nearbyHealthy = targetHealthDistancePriorityScore(actualHealth = 20f, squaredDistanceBlocks = 3.0.sq())
        val farLowHealth = targetHealthDistancePriorityScore(actualHealth = 1f, squaredDistanceBlocks = 30.0.sq())

        assertTrue(nearbyHealthy < farLowHealth)
    }

    @Test
    fun `health distance still allows mid range low health cleanup`() {
        val nearbyHealthy = targetHealthDistancePriorityScore(actualHealth = 20f, squaredDistanceBlocks = 3.0.sq())
        val midRangeLowHealth = targetHealthDistancePriorityScore(actualHealth = 2f, squaredDistanceBlocks = 10.0.sq())

        assertTrue(midRangeLowHealth < nearbyHealthy)
    }

    @Test
    fun `health distance prefers lower health at equal distance`() {
        val lowerHealth = targetHealthDistancePriorityScore(actualHealth = 5f, squaredDistanceBlocks = 8.0.sq())
        val higherHealth = targetHealthDistancePriorityScore(actualHealth = 10f, squaredDistanceBlocks = 8.0.sq())

        assertTrue(lowerHealth < higherHealth)
    }

    @Test
    fun `health distance prefers closer target at equal health`() {
        val closer = targetHealthDistancePriorityScore(actualHealth = 10f, squaredDistanceBlocks = 5.0.sq())
        val farther = targetHealthDistancePriorityScore(actualHealth = 10f, squaredDistanceBlocks = 12.0.sq())

        assertTrue(closer < farther)
    }

}
