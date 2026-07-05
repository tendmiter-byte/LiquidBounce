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

package net.ccbluex.liquidbounce.utils.block

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.entity.set
import net.ccbluex.liquidbounce.utils.math.allEmpty
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.WitherRoseBlock
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

object PathfinderSimulation {

    enum class MovementMode {
        WALK,
        SPRINT,
        BHOP
    }

    data class TransitionKey(val from: Vec3i, val to: Vec3i, val mode: MovementMode)

    // Thread-local caches to store simulation results within a single search query.
    private val moveCache = ThreadLocal.withInitial { HashMap<TransitionKey, Boolean>() }
    private val parkourCache = ThreadLocal.withInitial { HashMap<TransitionKey, Boolean>() }

    private var lastTickCount = -1
    private var airborneTicks = 0

    /**
     * Clears all cached simulation results for a fresh search query.
     */
    fun clearCache() {
        moveCache.get().clear()
        parkourCache.get().clear()
    }

    /**
     * Checks if a fluid is lava.
     */
    private fun isLava(level: Level, pos: BlockPos): Boolean {
        val fluid = level.getFluidState(pos)
        return fluid.`is`(Fluids.LAVA) || fluid.`is`(Fluids.FLOWING_LAVA)
    }

    /**
     * Identifies if a block is hazardous to touch or stand on.
     */
    private fun isHazardous(level: Level, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        val block = state.block

        if (isLava(level, pos)) {
            return true
        }

        return when (block) {
            is BaseFireBlock,
            is CampfireBlock,
            is CactusBlock,
            is MagmaBlock,
            is SweetBerryBushBlock,
            is WitherRoseBlock -> true
            else -> false
        }
    }

    /**
     * Scans the given bounding box and checks if it intersects any hazardous blocks.
     */
    private fun isPlayerInHazard(level: Level, box: AABB): Boolean {
        val minX = floor(box.minX).toInt()
        val maxX = floor(box.maxX).toInt()
        val minY = floor(box.minY).toInt()
        val maxY = floor(box.maxY).toInt()
        val minZ = floor(box.minZ).toInt()
        val maxZ = floor(box.maxZ).toInt()

        val pos = BlockPos.MutableBlockPos()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    pos.set(x, y, z)
                    if (isHazardous(level, pos)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Updates the historical count of ticks the player has been airborne in the game world.
     */
    private fun updateAirborneState() {
        val currentTick = player.tickCount
        if (currentTick != lastTickCount) {
            lastTickCount = currentTick
            if (player.onGround()) {
                airborneTicks = 0
            } else {
                airborneTicks++
            }
        }
    }

    /**
     * Calculates the estimated number of ticks it will take for the player to hit the ground from their current state.
     */
    fun ticksToHitGround(): Int {
        if (player.onGround()) return 0
        
        var y = player.y
        var deltaY = player.deltaMovement.y
        var ticks = 0
        val box = player.boundingBox
        val level = player.level()
        
        while (ticks < 40) {
            deltaY -= 0.08 // Gravity
            deltaY *= 0.98 // Drag
            
            y += deltaY
            ticks++
            
            val testBox = box.move(0.0, y - player.y, 0.0)
            if (!level.getBlockCollisions(player, testBox).allEmpty()) {
                return ticks
            }
        }
        return ticks
    }

    /**
     * Checks whether the player is bhopping by analyzing their airborne timeline.
     */
    fun isBhopping(): Boolean {
        if (player.onGround()) {
            return false
        }
        
        updateAirborneState()
        val totalAirTime = airborneTicks + ticksToHitGround()
        
        // A typical Minecraft jump has an air time of ~10-12 ticks. We allow 8 to 18 ticks to account for Jump Boost.
        return player.isSprinting && totalAirTime in 8..18
    }

    /**
     * Determines the active movement mode of the player based purely on player attributes and jump airtime.
     */
    fun getActiveMovementMode(): MovementMode {
        val sprinting = player.isSprinting
        val bhopping = isBhopping()
        return when {
            bhopping -> MovementMode.BHOP
            sprinting -> MovementMode.SPRINT
            else -> MovementMode.WALK
        }
    }

    /**
     * Estimates the horizontal movement speed based on MCPK recurrence relations.
     * Returns the speed BEFORE the sprint-jump boost is applied (since the simulation
     * itself will apply the jump boost on the first tick if jumping).
     */
    fun getMovementSpeed(mode: MovementMode): Double {
        val groundRet = 0.546
        val currentXZSpeed = sqrt(player.deltaMovement.x * player.deltaMovement.x + player.deltaMovement.z * player.deltaMovement.z)
        
        // If the player is currently airborne, their current velocity already has the jump boost.
        // We deduct it to get their base ground speed before the simulation's first jump.
        val hasJumpBoost = mode != MovementMode.WALK && !player.onGround()
        val adjustedXZSpeed = if (hasJumpBoost) currentXZSpeed - 0.2 else currentXZSpeed

        val defaultSpeed = when (mode) {
            MovementMode.WALK -> {
                0.1 / (1.0 - groundRet)
            }
            MovementMode.SPRINT -> {
                0.13 / (1.0 - groundRet)
            }
            MovementMode.BHOP -> {
                // Solve MCPK jump recurrence to find steady-state pre-jump speed.
                var airRet = 1.0
                var airAccelSum = 0.0
                for (i in 0 until 11) {
                    airAccelSum = airAccelSum * 0.91 + 0.026
                    airRet *= 0.91
                }
                val peakSpeed = (airAccelSum * 0.546 + 0.33) / (1.0 - airRet * groundRet)
                peakSpeed - 0.2 // Deduct jump boost since tick() will apply it
            }
        }
        return max(defaultSpeed, adjustedXZSpeed)
    }

    /**
     * Simulates movement from one node to an adjacent node to verify if the transition is physically passable.
     * Utilizes a thread-local cache to prevent redundant simulations during the A* search.
     */
    fun simulateMove(from: Vec3i, to: Vec3i): Boolean {
        if (from == to) return true
        val mode = getActiveMovementMode()
        val key = TransitionKey(from, to, mode)
        val cache = moveCache.get()
        if (cache.containsKey(key)) {
            return cache.get(key)!!
        }
        val result = simulateMoveInternal(from, to, mode)
        cache.put(key, result)
        return result
    }

    private fun simulateMoveInternal(from: Vec3i, to: Vec3i, mode: MovementMode): Boolean {
        val startPos = Vec3(from.x + 0.5, from.y.toDouble(), from.z + 0.5)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val distanceXZ = sqrt((dx * dx + dz * dz).toDouble())
        val yaw = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()

        val shouldJump = when (mode) {
            MovementMode.BHOP -> true
            else -> to.y > from.y
        }

        val speed = getMovementSpeed(mode)

        // Initialize XZ velocity based on the current movement speed
        val initialDelta = if (distanceXZ > 0.0) {
            Vec3(dx / distanceXZ * speed, 0.0, dz / distanceXZ * speed)
        } else {
            Vec3.ZERO
        }

        val input = SimulatedPlayer.SimulatedPlayerInput(
            directionalInput = DirectionalInput.FORWARDS,
            jumping = shouldJump,
            sprinting = mode != MovementMode.WALK,
            sneaking = false
        )

        val level = player.level()
        val simPlayer = SimulatedPlayer(
            player = player,
            input = input,
            pos = startPos,
            deltaMovement = initialDelta,
            boundingBox = player.getBoundingBoxAt(startPos),
            yRot = yaw,
            xRot = 0f,
            isSprinting = mode != MovementMode.WALK,
            fallDistance = 0.0,
            jumpTriggerTime = 0,
            jumping = shouldJump,
            fallFlying = false,
            onGround = true,
            horizontalCollision = false,
            verticalCollision = false,
            wasTouchingWater = false,
            isSwimming = false,
            wasUnderwater = false,
            fluidInteraction = player.fluidInteraction
        )

        var lastPos = startPos
        for (tick in 1..12) {
            if (tick > 1) {
                // If bhopping, keep jumping. Otherwise, only jump if stepping up is still needed.
                val keepJumping = if (mode == MovementMode.BHOP) true else to.y > from.y && simPlayer.pos.y < to.y
                input.set(jump = keepJumping)
            }

            simPlayer.tick()

            // Obstacle/Hazard avoidance check
            if (isPlayerInHazard(level, simPlayer.boundingBox)) {
                return false
            }

            // Wall avoidance check: colliding with walls blocks movement.
            // Allow initial tick collision for step-ups to resolve.
            val isStepUp = to.y > from.y
            if (simPlayer.horizontalCollision) {
                if (!isStepUp || tick > 2) {
                    return false
                }
            }

            if (simPlayer.onGround) {
                val currentX = floor(simPlayer.pos.x).toInt()
                val currentZ = floor(simPlayer.pos.z).toInt()
                if (currentX == to.x && currentZ == to.z && abs(simPlayer.pos.y - to.y) < 0.5) {
                    return true
                }
            }

            if (simPlayer.pos.y < to.y - 1.5) {
                return false
            }

            // Early abort if the player is stuck and not moving.
            if (simPlayer.pos.distanceToSqr(lastPos) < 0.001) {
                return false
            }
            lastPos = simPlayer.pos
        }

        return false
    }

    /**
     * Simulates a parkour jump from one standable block to a landing block.
     * Utilizes a thread-local cache to prevent redundant simulations during the A* search.
     */
    fun simulateParkourJump(start: Vec3i, landing: Vec3i): Boolean {
        if (start == landing) return true
        val mode = getActiveMovementMode()
        val key = TransitionKey(start, landing, mode)
        val cache = parkourCache.get()
        if (cache.containsKey(key)) {
            return cache.get(key)!!
        }
        val result = simulateParkourJumpInternal(start, landing, mode)
        cache.put(key, result)
        return result
    }

    private fun simulateParkourJumpInternal(start: Vec3i, landing: Vec3i, mode: MovementMode): Boolean {
        val startPos = Vec3(start.x + 0.5, start.y.toDouble(), start.z + 0.5)
        val dx = landing.x - start.x
        val dz = landing.z - start.z
        val distanceXZ = sqrt((dx * dx + dz * dz).toDouble())
        val yaw = Math.toDegrees(Math.atan2(-dx.toDouble(), dz.toDouble())).toFloat()

        val speed = getMovementSpeed(mode)

        // Initial XZ momentum for a running jump
        val initialDelta = if (distanceXZ > 0.0) {
            Vec3(dx / distanceXZ * speed, 0.0, dz / distanceXZ * speed)
        } else {
            Vec3.ZERO
        }

        val input = SimulatedPlayer.SimulatedPlayerInput(
            directionalInput = DirectionalInput.FORWARDS,
            jumping = true,
            sprinting = mode != MovementMode.WALK,
            sneaking = false
        )

        val level = player.level()
        val simPlayer = SimulatedPlayer(
            player = player,
            input = input,
            pos = startPos,
            deltaMovement = initialDelta,
            boundingBox = player.getBoundingBoxAt(startPos),
            yRot = yaw,
            xRot = 0f,
            isSprinting = mode != MovementMode.WALK,
            fallDistance = 0.0,
            jumpTriggerTime = 0,
            jumping = true,
            fallFlying = false,
            onGround = true,
            horizontalCollision = false,
            verticalCollision = false,
            wasTouchingWater = false,
            isSwimming = false,
            wasUnderwater = false,
            fluidInteraction = player.fluidInteraction
        )

        var lastPos = startPos
        for (tick in 1..20) {
            if (tick > 1) {
                val keepJumping = mode == MovementMode.BHOP
                input.set(jump = keepJumping)
            }

            simPlayer.tick()

            // Obstacle/Hazard avoidance check
            if (isPlayerInHazard(level, simPlayer.boundingBox)) {
                return false
            }

            // Wall avoidance check: hitting any wall mid-jump fails the jump immediately.
            if (simPlayer.horizontalCollision) {
                return false
            }

            if (simPlayer.onGround) {
                val currentX = floor(simPlayer.pos.x).toInt()
                val currentZ = floor(simPlayer.pos.z).toInt()
                if (currentX == landing.x && currentZ == landing.z && abs(simPlayer.pos.y - landing.y) < 0.5) {
                    return true
                }
                // Abort jump if landed early elsewhere and ceased movement.
                if (tick > 2 && simPlayer.pos.distanceToSqr(lastPos) < 0.001) {
                    return false
                }
            }

            if (simPlayer.pos.y < landing.y - 1.5) {
                return false
            }

            lastPos = simPlayer.pos
        }

        return false
    }
}
