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

package net.ccbluex.liquidbounce.utils.raytracing.pathfinder

import net.minecraft.core.Direction

/**
 * A mutable container holding the result of a 3D DDA voxel raycast operation.
 */
class DDAHitResult {
    /**
     * Whether the raycast hit a voxel that satisfied the solid criteria.
     */
    var isHit: Boolean = false
        private set

    /**
     * The X coordinate of the hit voxel.
     */
    var hitX: Int = 0
        private set

    /**
     * The Y coordinate of the hit voxel.
     */
    var hitY: Int = 0
        private set

    /**
     * The Z coordinate of the hit voxel.
     */
    var hitZ: Int = 0
        private set

    /**
     * The face of the voxel that the ray hit.
     */
    var hitSide: Direction? = null
        private set

    /**
     * The squared distance from the starting position of the raycast to the hit position.
     */
    var distanceSq: Double = 0.0
        private set

    /**
     * Sets the state of this hit result.
     */
    fun set(hitX: Int, hitY: Int, hitZ: Int, hitSide: Direction?, distanceSq: Double) {
        this.isHit = true
        this.hitX = hitX
        this.hitY = hitY
        this.hitZ = hitZ
        this.hitSide = hitSide
        this.distanceSq = distanceSq
    }

    /**
     * Resets this hit result to its default empty state.
     */
    fun reset() {
        this.isHit = false
        this.hitX = 0
        this.hitY = 0
        this.hitZ = 0
        this.hitSide = null
        this.distanceSq = 0.0
    }
}
