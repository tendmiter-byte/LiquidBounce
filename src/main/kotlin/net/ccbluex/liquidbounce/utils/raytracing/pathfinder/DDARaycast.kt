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

@file:Suppress("NOTHING_TO_INLINE")

package net.ccbluex.liquidbounce.utils.raytracing.pathfinder

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor

/**
 * Thread-local mutable block position helper to avoid object allocation in hot paths.
 */
val threadLocalPos: ThreadLocal<BlockPos.MutableBlockPos> = ThreadLocal.withInitial { BlockPos.MutableBlockPos() }

/**
 * An optimized block-only raycaster used for pathfinding. Uses a fast 3D Digital Differential Analysis (DDA) algorithm.
 */
object DDARaycast {

    /**
     * Performs a voxel-based raycast within the specified world border boundaries.
     *
     * @param minBorderX Minimum X boundary of the world border.
     * @param maxBorderX Maximum X boundary of the world border.
     * @param minBorderZ Minimum Z boundary of the world border.
     * @param maxBorderZ Maximum Z boundary of the world border.
     * @param startX Starting X coordinate of the ray.
     * @param startY Starting Y coordinate of the ray.
     * @param startZ Starting Z coordinate of the ray.
     * @param endX Ending X coordinate of the ray.
     * @param endY Ending Y coordinate of the ray.
     * @param endZ Ending Z coordinate of the ray.
     * @param allowStartInside If true, ignores solidity checks on the voxel containing the starting point.
     * @param isSolid A lambda that receives a packed block position (as Long) and returns whether that block is solid.
     * @param hitResult A mutable [DDAHitResult] where results of a successful hit will be populated.
     * @return true if the ray hit a solid block or exited the world border, false if the ray completed without hits.
     */
    inline fun raycastVoxel(
        minBorderX: Double, maxBorderX: Double,
        minBorderZ: Double, maxBorderZ: Double,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean,
        hitResult: DDAHitResult
    ): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        val dz = endZ - startZ
        val lenSq = dx * dx + dy * dy + dz * dz

        // Guard against zero-length or extremely small rays
        if (lenSq < 1e-9) {
            hitResult.reset()
            return false
        }

        // Initialize starting voxel coordinates (block grid)
        var x = floor(startX).toInt()
        var y = floor(startY).toInt()
        var z = floor(startZ).toInt()

        // Determine ending voxel coordinates
        val endVoxelX = floor(endX).toInt()
        val endVoxelY = floor(endY).toInt()
        val endVoxelZ = floor(endZ).toInt()

        // Determine step direction for each axis (+1, -1, or 0)
        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val stepZ = if (dz > 0) 1 else if (dz < 0) -1 else 0

        // tDelta represents the distance the ray travels along each axis to move 1 voxel unit
        val tDeltaX = if (stepX != 0) abs(1.0 / dx) else Double.MAX_VALUE
        val tDeltaY = if (stepY != 0) abs(1.0 / dy) else Double.MAX_VALUE
        val tDeltaZ = if (stepZ != 0) abs(1.0 / dz) else Double.MAX_VALUE

        // tMax represents the ray's initial travel distance to cross the first voxel boundary along each axis
        var tMaxX = if (stepX > 0) (x + 1.0 - startX) * tDeltaX else if (stepX < 0) (startX - x) * tDeltaX else Double.MAX_VALUE
        var tMaxY = if (stepY > 0) (y + 1.0 - startY) * tDeltaY else if (stepY < 0) (startY - y) * tDeltaY else Double.MAX_VALUE
        var tMaxZ = if (stepZ > 0) (z + 1.0 - startZ) * tDeltaZ else if (stepZ < 0) (startZ - z) * tDeltaZ else Double.MAX_VALUE

        var tLast = 0.0
        var hitSide: Direction? = null
        var isFirstStep = true

        var steps = 0
        val maxSteps = 256

        // Traverse the voxel grid (3D DDA loop)
        while (steps < maxSteps) {
            val xDouble = x.toDouble()
            val zDouble = z.toDouble()
            
            // Check if the current voxel has exited the specified world border boundaries
            if (xDouble + 1.0 <= minBorderX || xDouble >= maxBorderX || zDouble + 1.0 <= minBorderZ || zDouble >= maxBorderZ) {
                val distSq = tLast * tLast * lenSq
                hitResult.set(x, y, z, hitSide, distSq)
                return true
            }

            // Check if the current voxel is solid (skip start voxel if allowStartInside is true)
            if (!isFirstStep || !allowStartInside) {
                val packedPos = BlockPos.asLong(x, y, z)
                if (isSolid(packedPos)) {
                    val distSq = tLast * tLast * lenSq
                    hitResult.set(x, y, z, hitSide, distSq)
                    return true
                }
            }
            isFirstStep = false

            // Stop if we have reached the destination voxel
            if (x == endVoxelX && y == endVoxelY && z == endVoxelZ) {
                break
            }

            // Increment the ray along the axis with the smallest tMax to step into the next voxel
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    tLast = tMaxX
                    tMaxX += tDeltaX
                    x += stepX
                    hitSide = if (stepX > 0) Direction.WEST else Direction.EAST
                } else {
                    tLast = tMaxZ
                    tMaxZ += tDeltaZ
                    z += stepZ
                    hitSide = if (stepZ > 0) Direction.NORTH else Direction.SOUTH
                }
            } else {
                if (tMaxY < tMaxZ) {
                    tLast = tMaxY
                    tMaxY += tDeltaY
                    y += stepY
                    hitSide = if (stepY > 0) Direction.DOWN else Direction.UP
                } else {
                    tLast = tMaxZ
                    tMaxZ += tDeltaZ
                    z += stepZ
                    hitSide = if (stepZ > 0) Direction.NORTH else Direction.SOUTH
                }
            }
            steps++
        }

        hitResult.reset()
        return false
    }

    /**
     * Checks if there is an unobstructed line of sight (no solid blocks) between two coordinates.
     *
     * @return true if no solid blocks are hit and the ray stays within world borders, false otherwise.
     */
    inline fun hasLineOfSightVoxel(
        minBorderX: Double, maxBorderX: Double,
        minBorderZ: Double, maxBorderZ: Double,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean
    ): Boolean {
        val dx = endX - startX
        val dy = endY - startY
        val dz = endZ - startZ
        val lenSq = dx * dx + dy * dy + dz * dz

        if (lenSq < 1e-9) {
            return true
        }

        var x = floor(startX).toInt()
        var y = floor(startY).toInt()
        var z = floor(startZ).toInt()

        val endVoxelX = floor(endX).toInt()
        val endVoxelY = floor(endY).toInt()
        val endVoxelZ = floor(endZ).toInt()

        val stepX = if (dx > 0) 1 else if (dx < 0) -1 else 0
        val stepY = if (dy > 0) 1 else if (dy < 0) -1 else 0
        val stepZ = if (dz > 0) 1 else if (dz < 0) -1 else 0

        val tDeltaX = if (stepX != 0) abs(1.0 / dx) else Double.MAX_VALUE
        val tDeltaY = if (stepY != 0) abs(1.0 / dy) else Double.MAX_VALUE
        val tDeltaZ = if (stepZ != 0) abs(1.0 / dz) else Double.MAX_VALUE

        var tMaxX = if (stepX > 0) (x + 1.0 - startX) * tDeltaX else if (stepX < 0) (startX - x) * tDeltaX else Double.MAX_VALUE
        var tMaxY = if (stepY > 0) (y + 1.0 - startY) * tDeltaY else if (stepY < 0) (startY - y) * tDeltaY else Double.MAX_VALUE
        var tMaxZ = if (stepZ > 0) (z + 1.0 - startZ) * tDeltaZ else if (stepZ < 0) (startZ - z) * tDeltaZ else Double.MAX_VALUE

        var steps = 0
        val maxSteps = 256
        var isFirstStep = true

        while (steps < maxSteps) {
            val xDouble = x.toDouble()
            val zDouble = z.toDouble()
            if (xDouble + 1.0 <= minBorderX || xDouble >= maxBorderX || zDouble + 1.0 <= minBorderZ || zDouble >= maxBorderZ) {
                return false
            }

            if (!isFirstStep || !allowStartInside) {
                val packedPos = BlockPos.asLong(x, y, z)
                if (isSolid(packedPos)) {
                    return false
                }
            }
            isFirstStep = false

            if (x == endVoxelX && y == endVoxelY && z == endVoxelZ) {
                break
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    tMaxX += tDeltaX
                    x += stepX
                } else {
                    tMaxZ += tDeltaZ
                    z += stepZ
                }
            } else {
                if (tMaxY < tMaxZ) {
                    tMaxY += tDeltaY
                    y += stepY
                } else {
                    tMaxZ += tDeltaZ
                    z += stepZ
                }
            }
            steps++
        }

        return true
    }

    /**
     * Performs a 3D DDA raycast using double coordinates. Respects the Level's world border.
     */
    inline fun raycast(
        level: Level,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean,
        hitResult: DDAHitResult
    ): Boolean {
        val border = level.worldBorder
        return raycastVoxel(
            border.minX, border.maxX, border.minZ, border.maxZ,
            startX, startY, startZ, endX, endY, endZ,
            allowStartInside, isSolid, hitResult
        )
    }

    /**
     * Performs a 3D DDA raycast using Vec3 vectors. Respects the Level's world border.
     */
    inline fun raycast(
        level: Level,
        start: Vec3,
        end: Vec3,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean,
        hitResult: DDAHitResult
    ): Boolean {
        return raycast(level, start.x, start.y, start.z, end.x, end.y, end.z, allowStartInside, isSolid, hitResult)
    }

    /**
     * Performs a 3D DDA raycast using packed BlockPos coordinates (Long). Respects the Level's world border.
     */
    inline fun raycast(
        level: Level,
        startPacked: Long,
        endPacked: Long,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean,
        hitResult: DDAHitResult
    ): Boolean {
        val sx = BlockPos.getX(startPacked) + 0.5
        val sy = BlockPos.getY(startPacked) + 0.5
        val sz = BlockPos.getZ(startPacked) + 0.5
        val ex = BlockPos.getX(endPacked) + 0.5
        val ey = BlockPos.getY(endPacked) + 0.5
        val ez = BlockPos.getZ(endPacked) + 0.5
        return raycast(level, sx, sy, sz, ex, ey, ez, allowStartInside, isSolid, hitResult)
    }

    /**
     * Checks line of sight between double coordinates. Respects the Level's world border.
     */
    inline fun hasLineOfSight(
        level: Level,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean
    ): Boolean {
        val border = level.worldBorder
        return hasLineOfSightVoxel(
            border.minX, border.maxX, border.minZ, border.maxZ,
            startX, startY, startZ, endX, endY, endZ,
            allowStartInside, isSolid
        )
    }

    /**
     * Checks line of sight between Vec3 vectors. Respects the Level's world border.
     */
    inline fun hasLineOfSight(
        level: Level,
        start: Vec3,
        end: Vec3,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean
    ): Boolean {
        return hasLineOfSight(level, start.x, start.y, start.z, end.x, end.y, end.z, allowStartInside, isSolid)
    }

    /**
     * Checks line of sight between packed BlockPos coordinates (Long). Respects the Level's world border.
     */
    inline fun hasLineOfSight(
        level: Level,
        startPacked: Long,
        endPacked: Long,
        allowStartInside: Boolean,
        isSolid: (Long) -> Boolean
    ): Boolean {
        val sx = BlockPos.getX(startPacked) + 0.5
        val sy = BlockPos.getY(startPacked) + 0.5
        val sz = BlockPos.getZ(startPacked) + 0.5
        val ex = BlockPos.getX(endPacked) + 0.5
        val ey = BlockPos.getY(endPacked) + 0.5
        val ez = BlockPos.getZ(endPacked) + 0.5
        return hasLineOfSight(level, sx, sy, sz, ex, ey, ez, allowStartInside, isSolid)
    }

    /**
     * Raycasts against BlockStates retrieved dynamically from the Level.
     */
    @JvmName("raycastBlockState")
    inline fun raycast(
        level: Level,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        crossinline isSolid: (BlockState) -> Boolean,
        hitResult: DDAHitResult
    ): Boolean {
        val mutablePos = threadLocalPos.get()
        return raycast(
            level, startX, startY, startZ, endX, endY, endZ, allowStartInside,
            { packed: Long ->
                val px = BlockPos.getX(packed)
                val py = BlockPos.getY(packed)
                val pz = BlockPos.getZ(packed)
                val state = level.getBlockState(mutablePos.set(px, py, pz))
                isSolid(state)
            },
            hitResult
        )
    }

    /**
     * Checks line of sight against BlockStates retrieved dynamically from the Level.
     */
    @JvmName("hasLineOfSightBlockState")
    inline fun hasLineOfSight(
        level: Level,
        startX: Double, startY: Double, startZ: Double,
        endX: Double, endY: Double, endZ: Double,
        allowStartInside: Boolean,
        crossinline isSolid: (BlockState) -> Boolean
    ): Boolean {
        val mutablePos = threadLocalPos.get()
        return hasLineOfSight(
            level, startX, startY, startZ, endX, endY, endZ, allowStartInside,
            { packed: Long ->
                val px = BlockPos.getX(packed)
                val py = BlockPos.getY(packed)
                val pz = BlockPos.getZ(packed)
                val state = level.getBlockState(mutablePos.set(px, py, pz))
                isSolid(state)
            }
        )
    }
}
