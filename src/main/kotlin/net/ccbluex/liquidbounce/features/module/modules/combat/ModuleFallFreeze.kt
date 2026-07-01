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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerMoveEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.entity.movementForward
import net.ccbluex.liquidbounce.utils.entity.movementSideways
import net.ccbluex.liquidbounce.utils.entity.set
import net.ccbluex.liquidbounce.utils.network.sendPacketSilently
import net.ccbluex.liquidbounce.utils.raytracing.clip
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3

/**
 * FallFreeze module
 *
 * Freezes the player's position when they are close to the ground while falling,
 * keeping them suspended mid-air. This causes KillAura attacks to register as
 * critical hits since the server sees the player as airborne (onGround = false)
 * with positive fallDistance.
 *
 * Unlike a raw height offset, this module measures the actual distance from the
 * solid ground directly beneath the player via a downward block raytrace.
 */
object ModuleFallFreeze : ClientModule("FallFreeze", ModuleCategories.COMBAT, disableOnQuit = true) {

    /**
     * How many blocks above the ground the player must be (or less) when falling
     * for the freeze to activate. Range 0.1–1.0 blocks.
     */
    private val distanceFromGround by float("DistanceFromGround", 0.42f, 0.1f..1.0f)
    private val disableOnFlag by boolean("DisableOnFlag", true)

    var isFrozen = false
        private set

    private var freezePos: Vec3? = null

    override fun onEnabled() {
        isFrozen = false
        freezePos = null
        super.onEnabled()
    }

    override fun onDisabled() {
        isFrozen = false
        freezePos = null
        super.onDisabled()
    }

    /**
     * Returns the distance in blocks between the player's feet and the highest
     * solid block directly below them, searched up to [maxDist] blocks down.
     * If no block is found within that range, returns [maxDist].
     */
    private fun getDistanceToGround(maxDist: Double): Double {
        val from = Vec3(player.x, player.y, player.z)
        val to   = Vec3(player.x, player.y - maxDist, player.z)
        val hit  = player.level().clip(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)
        return player.y - hit.location.y
    }

    @Suppress("unused")
    private val tickHandler = handler<PlayerTickEvent> {
        // Reset when touching the ground
        if (player.onGround()) {
            if (isFrozen) {
                isFrozen = false
                freezePos = null
            }
            return@handler
        }

        // Activate freeze only while the player is falling downward
        if (!isFrozen && player.deltaMovement.y < 0.0) {
            val threshold = distanceFromGround.toDouble()
            val maxFallDist = -player.deltaMovement.y
            // Raytrace far enough to see the ground even when falling very fast
            val dist = getDistanceToGround(Math.max(10.0, maxFallDist + threshold + 1.0))
            
            if (dist <= threshold || dist - maxFallDist <= threshold) {
                isFrozen = true
                val targetY = if (dist <= threshold) player.y else player.y - dist + threshold
                freezePos = Vec3(player.x, targetY, player.z)
            }
        }

        if (isFrozen) {
            // Lock position client-side
            freezePos?.let { player.setPos(it) }

            // Set a small downward velocity so CriticalsJump.shouldWaitForCrit()
            // sees deltaMovement.y < -0.08 and does NOT block KillAura attacks.
            player.deltaMovement = Vec3(0.0, -0.1, 0.0)

            // Maintain positive fallDistance so wouldDoCriticalHit() returns true
            player.fallDistance = 0.5

            // Clear movement input to avoid FOV changes and unwanted momentum
            player.input.movementForward = 0f
            player.input.movementSideways = 0f
            player.input.set(
                forward = false,
                backward = false,
                left = false,
                right = false,
                jump = false,
                sneak = false,
                sprint = false
            )
            player.isSprinting = false
        }
    }

    @Suppress("unused")
    private val moveHandler = handler<PlayerMoveEvent> { event ->
        if (isFrozen) {
            event.movement = Vec3.ZERO
        }
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        val packet = event.packet

        if (packet is ClientboundPlayerPositionPacket) {
            isFrozen = false
            freezePos = null
            if (disableOnFlag) {
                net.ccbluex.liquidbounce.utils.client.notification(
                    this.name,
                    message("disabledOnFlag"),
                    net.ccbluex.liquidbounce.event.events.NotificationEvent.Severity.INFO
                )
                enabled = false
            }
            return@handler
        }

        if (isFrozen && event.origin == TransferOrigin.OUTGOING && packet is ServerboundMovePlayerPacket) {
            if (packet is ServerboundMovePlayerPacket.PosRot) {
                event.cancelEvent()
                // Send rotation-only so KillAura can still aim and attack while
                // position stays locked. onGround = false → server registers crits.
                sendPacketSilently(
                    ServerboundMovePlayerPacket.Rot(
                        packet.getYRot(player.yRot),
                        packet.getXRot(player.xRot),
                        false, // onGround = false for critical hits
                        player.horizontalCollision
                    )
                )
            } else if (packet is ServerboundMovePlayerPacket.Pos) {
                event.cancelEvent()
            }
        }
    }

}
