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
 */

package net.ccbluex.liquidbounce.utils.raytracing

import net.minecraft.core.Direction

/**
 * Mutable raycast hit result container for pathfinder checks.
 */
class PathfinderHitResult {
    var isHit: Boolean = false
        private set

    var hitX: Int = 0
        private set

    var hitY: Int = 0
        private set

    var hitZ: Int = 0
        private set

    var hitSide: Direction? = null
        private set

    var distanceSq: Double = 0.0
        private set

    fun set(hitX: Int, hitY: Int, hitZ: Int, hitSide: Direction?, distanceSq: Double) {
        this.isHit = true
        this.hitX = hitX
        this.hitY = hitY
        this.hitZ = hitZ
        this.hitSide = hitSide
        this.distanceSq = distanceSq
    }

    fun reset() {
        this.isHit = false
        this.hitX = 0
        this.hitY = 0
        this.hitZ = 0
        this.hitSide = null
        this.distanceSq = 0.0
    }
}
