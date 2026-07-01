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
package net.ccbluex.liquidbounce.features.module.modules.combat.backtrack

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.BlinkPacketEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.TickPacketProcessEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.blink.BlinkManager
import net.ccbluex.liquidbounce.features.blink.TrackedEntityPosition
import net.ccbluex.liquidbounce.features.blink.esp.BlinkEspBox
import net.ccbluex.liquidbounce.features.blink.esp.BlinkEspData
import net.ccbluex.liquidbounce.features.blink.esp.BlinkEspModel
import net.ccbluex.liquidbounce.features.blink.esp.BlinkEspNone
import net.ccbluex.liquidbounce.features.blink.esp.BlinkEspWireframe
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.velocity.mode.VelocityReduce
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.client.inGame
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.combat.findEnemy
import net.ccbluex.liquidbounce.utils.combat.shouldBeAttacked
import net.ccbluex.liquidbounce.utils.entity.boxedDistanceTo
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squareBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.kotlin.random
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

object ModuleBacktrack : ClientModule("Backtrack", ModuleCategories.COMBAT) {

    // Quake Pro vertical FOV (110°), flat-ground walk/sprint speeds in blocks/s
    private const val QUAKE_PRO_FOV = 110f
    private const val WALK_SPEED = 4.317
    private const val SPRINT_SPEED = 5.612

    private val range by floatRange("Range", 1f..3f, 0f..10f)
    val delay by intRange("Delay", 100..150, 0..1000, "ms")
    private val delayMode by enumChoice("DelayMode", DelayMode.DYNAMIC)
    private val nextBacktrackDelay by intRange("NextBacktrackDelay", 0..10, 0..5000, "ms")
    private val trackingBuffer by int("TrackingBuffer", 500, 0..5000, "ms")
    private val hitDistance by floatRange("HitDistance", 0f..4.5f, 0f..10f)
    private val maxQueuedPackets by int("MaxQueuedPackets", 0, 0..1000)
    private val chance by float("Chance", 50f, 0f..100f, "%")
    private var currentChance = (0..100).random()

    private val requires by multiEnumChoice<BacktrackRequirements>("Requires")
    private val attackItems by items("Items", itemSortedSetOf())

    private val requirementsMet
        get() = requires.all { it.asBoolean }

    private object SmartRelease : ToggleableValueGroup(this, "SmartRelease", true) {
        val distanceGain by floatRange("DistanceGain", 0.03f..0.08f, 0f..1f)
    }

    private val smartRelease = tree(SmartRelease)

    private object PauseOnHurtTime : ToggleableValueGroup(this, "PauseOnHurtTime", false) {
        val hurtTime by int("HurtTime", 3, 0..10)
    }

    private val pauseOnHurtTime = tree(PauseOnHurtTime)

    private val targetMode by enumChoice("TargetMode", Mode.ATTACK)
    private val lastAttackTimeToWork by int("LastAttackTimeToWork", 1000, 0..5000)

    enum class Mode(override val tag: String) : Tagged {
        ATTACK("Attack"),
        RANGE("Range")
    }

    private enum class DelayMode(
        override val tag: String,
        override val tagAliases: List<String> = emptyList(),
    ) : Tagged {
        DYNAMIC("Dynamic", listOf("Constant")),
        REVERSE_DYNAMIC("ReverseDynamic"),
    }

    private val espMode = choices("Esp", 2) {
        arrayOf(
            BlinkEspBox(it, ::getEspData),
            BlinkEspModel(it, getEspData = ::getEspData),
            BlinkEspWireframe(it, ::getEspData),
            BlinkEspNone(it),
        )
    }.apply {
        doNotIncludeAlways()
    }

    private val chronometer = Chronometer()
    private val trackingBufferChronometer = Chronometer()
    private val attackChronometer = Chronometer()

    private var shouldPause = false

    private var target: Entity? = null
    private val position = TrackedEntityPosition()

    private var lastTargetDistance = Double.NaN
    private var lastDistanceSampleTime = 0L
    private var currentDistanceRate = 0.0
    private var currentSmartReleaseGain = SmartRelease.distanceGain.random().toDouble()

    var currentDelay = delay.random()

    @Suppress("unused")
    private val queuePacketHandler = handler<BlinkPacketEvent> { event ->
        if (event.origin != TransferOrigin.INCOMING) {
            return@handler
        }

        if (VelocityReduce.ownsIncomingBlinkQueue) {
            return@handler
        }

        val packet = event.packet
        val shouldCancel = shouldCancelPackets()
        val incomingQueueSize = BlinkManager.queuedPacketCount(
            TransferOrigin.INCOMING,
            limit = maxQueuedPackets.takeIf { it > 0 } ?: 1
        )
        val hasQueuedIncoming = incomingQueueSize > 0

        if (packet == null) {
            if (shouldCancel || hasQueuedIncoming) {
                event.action = BlinkManager.Action.PASS
            }
            return@handler
        }

        if (!hasQueuedIncoming && !shouldCancel) {
            return@handler
        }

        if (maxQueuedPackets > 0 && incomingQueueSize >= maxQueuedPackets) {
            event.action = BlinkManager.Action.FLUSH
            return@handler
        }

        when (packet) {
            // Ignore message-related packets
            is ServerboundChatPacket, is ClientboundSystemChatPacket, is ServerboundChatCommandPacket -> {
                event.action = BlinkManager.Action.PASS
                return@handler
            }

            // Flush on teleport or disconnect
            is ClientboundPlayerPositionPacket, is ClientboundDisconnectPacket -> {
                clear(true)
                return@handler
            }

            // Ignore own hurt sounds
            is ClientboundSoundPacket -> {
                if (packet.sound.value() == SoundEvents.PLAYER_HURT) {
                    event.action = BlinkManager.Action.PASS
                    return@handler
                }
            }

            // Flush on own death
            is ClientboundSetHealthPacket -> {
                if (packet.health <= 0) {
                    clear(true)
                    return@handler
                }
            }
        }

        // Update box position with these packets
        val target = target ?: return@handler
        val pos = position.handlePacket(packet, world, target)
        if (pos != null) {
            val liveDistance = boxedDistanceToPlayer(target, pos)
            val delayedDistance = boxedDistanceToPlayer(target)

            if (isTrackedPositionOutsideDistance(liveDistance) ||
                shouldSmartRelease(liveDistance, delayedDistance)
            ) {
                // Process all packets when the live server position is already the better hit.
                event.action = BlinkManager.Action.FLUSH
                // And stop right here. No need to cancel further packets.
                return@handler
            }
        }

        event.action = BlinkManager.Action.QUEUE
    }

    private fun getEspData(): BlinkEspData? {
        val entity = target ?: return null
        val pos = position.base
        val rotation = entity.rotation

        return BlinkEspData(entity, pos, rotation)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> { event ->
        // Clear packets on disconnect only
        if (event.world == null) {
            clear(clearOnly = true)
        }
    }

    @Suppress("unused")
    private val tickPacketProcessHandler = handler<TickPacketProcessEvent> {
        if (!inGame) {
            clear(clearOnly = true)
            return@handler
        }

        if (VelocityReduce.ownsIncomingBlinkQueue) {
            return@handler
        }

        val target = target
        if (target != null) {
            if (target.isRemoved || !target.isAlive) {
                clear()
            } else {
                updateDistanceRate(target)
            }
        } else {
            resetDistanceTracking()
        }

        val hadQueuedIncoming = hasQueuedIncoming()

        if (shouldCancelPackets()) {
            val now = System.currentTimeMillis()
            BlinkManager.flush { snapshot ->
                snapshot.origin == TransferOrigin.INCOMING && snapshot.timestamp <= now - currentDelay
            }
        } else if (hadQueuedIncoming) {
            BlinkManager.flush(TransferOrigin.INCOMING)
            clear()
        }

        if (!hasQueuedIncoming()) {
            currentDelay = calculateDelay(target)
            currentSmartReleaseGain = SmartRelease.distanceGain.random().toDouble()
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEntityEvent> { event ->
        attackChronometer.reset() // Update the last attack time
        currentChance = (0..100).random()

        if (targetMode != Mode.ATTACK) {
            return@handler
        }

        val enemy = event.entity
        processTarget(enemy)
    }

    @Suppress("unused")
    private val rangeTargetHandler = handler<GameTickEvent> {
        if (targetMode != Mode.RANGE) return@handler

        val currentTarget = target
        if (currentTarget != null && shouldBacktrack(currentTarget)) {
            processTarget(currentTarget)
            return@handler
        }

        val enemy = world.findEnemy(range)

        if (enemy == null) {
            clear()
            return@handler
        }

        processTarget(enemy)
    }

    private fun processTarget(enemy: Entity) {
        shouldPause = enemy is LivingEntity && enemy.hurtTime >= PauseOnHurtTime.hurtTime

        // Reset on enemy change
        if (enemy != target) {
            clear(resetChronometer = false)

            // Instantly set new position, so it does not look like the box was created with delay
            position.setBaseFrom(enemy)
            currentChance = (0..100).random()
        }

        if (!shouldBacktrack(enemy)) {
            clear()
            return
        }

        target = enemy
        currentDelay = calculateDelay(enemy)
    }

    override fun onEnabled() {
        clear(false)
    }

    override fun onDisabled() {
        clear(true)
    }

    private fun clear(handlePackets: Boolean = true, clearOnly: Boolean = false, resetChronometer: Boolean = true) {
        if (handlePackets && !clearOnly) {
            BlinkManager.flush(TransferOrigin.INCOMING)
        } else if (clearOnly) {
            BlinkManager.clear(TransferOrigin.INCOMING)
        }

        if (target != null && resetChronometer) {
            chronometer.waitForAtLeast(nextBacktrackDelay.random().toLong())
        }

        target = null
        position.base = Vec3.ZERO
        resetDistanceTracking()
        currentSmartReleaseGain = SmartRelease.distanceGain.random().toDouble()
    }

    private fun resetDistanceTracking() {
        lastTargetDistance = Double.NaN
        lastDistanceSampleTime = 0L
        currentDistanceRate = 0.0
    }

    private fun shouldBacktrack(target: Entity): Boolean {
        if (target.isRemoved) return false
        val inRange = target.boxedDistanceTo(player) in range

        if (inRange) {
            trackingBufferChronometer.reset()
        }

        return (inRange || !trackingBufferChronometer.hasElapsed(trackingBuffer.toLong())) &&
            target.shouldBeAttacked() &&
            player.tickCount > 10 &&
            currentChance < chance &&
            chronometer.hasElapsed() &&
            requirementsMet &&
            isAllowedAttackItem(player.mainHandItem) &&
            !shouldPause() &&
            isTrackedPositionUseful(target) &&
            !attackChronometer.hasElapsed(lastAttackTimeToWork.toLong()) &&
            !VelocityReduce.backtrackBlocked
    }

    private fun calculateDelay(target: Entity?): Int {
        val minDelay = delay.start
        val maxDelay = delay.endInclusive

        if (target == null) {
            return minDelay
        }

        val factor = when (delayMode) {
            DelayMode.DYNAMIC -> calculateDynamicDelayFactor(target)
            DelayMode.REVERSE_DYNAMIC -> 1.0 - calculateDynamicDelayFactor(target)
        }

        return (minDelay + (maxDelay - minDelay) * factor).toInt().coerceIn(minDelay, maxDelay)
    }

    private fun calculateDynamicDelayFactor(target: Entity): Double {
        val hiddenFromTarget = !isPlayerInTargetFov(target)
        val distancingFactor = getDistancingFactor(target)

        return (if (hiddenFromTarget) 1.0 else 0.0) * distancingFactor
    }

    private fun isPlayerInTargetFov(target: Entity): Boolean {
        if (target !is LivingEntity) {
            return true
        }

        val maxAngle = QUAKE_PRO_FOV / 2f
        val eyes = target.eyePosition
        val rotationToPlayer = Rotation.lookingAt(eyes, player.eyePosition)

        if (target.rotation.angleTo(rotationToPlayer) > maxAngle) {
            return false
        }

        val distance = target.boxedDistanceTo(player)
        return isLookingAtEntity(
            fromEntity = target,
            toEntity = player,
            rotation = target.rotation,
            range = distance + 1.0,
            throughWallsRange = 0.0,
        ) != null
    }

    private fun getDistancingFactor(target: Entity): Double {
        if (currentDistanceRate <= 0.0) {
            return 0.0
        }

        return (currentDistanceRate / getExpectedDistancingSpeed(target)).coerceIn(0.0, 1.0)
    }

    private fun updateDistanceRate(target: Entity) {
        val distance = target.boxedDistanceTo(player)
        val now = System.currentTimeMillis()

        if (lastTargetDistance.isNaN()) {
            lastTargetDistance = distance
            lastDistanceSampleTime = now
            currentDistanceRate = 0.0
            return
        }

        val elapsedMs = (now - lastDistanceSampleTime).coerceAtLeast(1L)
        if (elapsedMs >= 50) {
            val rawRate = (distance - lastTargetDistance) / elapsedMs * 1000.0
            currentDistanceRate = 0.3 * rawRate + 0.7 * currentDistanceRate
            lastTargetDistance = distance
            lastDistanceSampleTime = now
        }
    }

    private fun getExpectedDistancingSpeed(target: Entity): Double {
        val sprinting = target is LivingEntity && target.isSprinting
        return if (sprinting) SPRINT_SPEED else WALK_SPEED
    }

    private fun isTrackedPositionUseful(target: Entity): Boolean {
        val trackedPos = position.base

        if (trackedPos == Vec3.ZERO) {
            return true
        }

        val liveDistance = boxedDistanceToPlayer(target, trackedPos)

        if (isTrackedPositionOutsideDistance(liveDistance)) {
            return false
        }

        if (!hasQueuedIncoming()) {
            return true
        }

        return !shouldSmartRelease(liveDistance, boxedDistanceToPlayer(target))
    }

    private fun boxedDistanceToPlayer(target: Entity, targetPos: Vec3 = target.position()): Double {
        return sqrt(target.squareBoxedDistanceTo(player, targetPos))
    }

    private fun isTrackedPositionOutsideDistance(liveDistance: Double): Boolean {
        val minDistance = hitDistance.start
        val maxDistance = hitDistance.endInclusive

        return liveDistance < minDistance || liveDistance > maxDistance
    }

    private fun shouldSmartRelease(liveDistance: Double, delayedDistance: Double): Boolean {
        if (!smartRelease.enabled) {
            return false
        }

        return liveDistance + currentSmartReleaseGain < delayedDistance
    }

    internal fun isAllowedAttackItem(itemStack: ItemStack): Boolean {
        if (itemStack.isEmpty && BacktrackRequirements.EMPTY_HAND in requires) {
            return true
        }

        return attackItems.isEmpty() || itemStack.item in attackItems
    }

    fun isLagging() = running && hasQueuedIncoming()

    private fun shouldPause() = pauseOnHurtTime.enabled && shouldPause

    fun shouldCancelPackets() =
        target?.let { target -> target.isAlive && !target.isRemoved && shouldBacktrack(target) } == true

    private fun hasQueuedIncoming() =
        BlinkManager.hasQueuedPackets(TransferOrigin.INCOMING)

}
