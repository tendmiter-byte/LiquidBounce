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
package net.ccbluex.liquidbounce.features.module.modules.movement.speed

import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.entity.SimulatedPlayer
import net.ccbluex.liquidbounce.utils.entity.set
import net.ccbluex.liquidbounce.utils.entity.moving
import net.ccbluex.liquidbounce.utils.math.anyNotEmpty
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput

/**
 * Prevents jumps that would cause the player's head to collide with a ceiling/block.
 */
object SpeedAvoidHeadCollision : MinecraftShortcuts {
    fun shouldDelayJump(): Boolean {
        if (!player.moving) {
            return false
        }

        val input = SimulatedPlayer.SimulatedPlayerInput.fromClientPlayer(DirectionalInput(player.input))
        input.set(jump = true)

        val simulatedPlayer = SimulatedPlayer.fromClientPlayer(input)

        // Simulate the jump
        for (tickIdx in 0..15) {
            simulatedPlayer.tick()

            // If we land on the ground, the jump has finished
            if (simulatedPlayer.onGround && tickIdx > 0) {
                break
            }

            // Check if player's head collides with a block's hitbox above them.
            val isHeadBumping = (simulatedPlayer.verticalCollision && !simulatedPlayer.onGround) ||
                    world.getBlockCollisions(player, simulatedPlayer.boundingBox.move(0.0, 0.2, 0.0)).anyNotEmpty()

            if (isHeadBumping) {
                return true
            }
        }

        return false
    }
}
