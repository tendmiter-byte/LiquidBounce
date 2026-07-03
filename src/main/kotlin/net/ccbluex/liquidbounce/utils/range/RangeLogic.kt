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
package net.ccbluex.liquidbounce.utils.range

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PostAttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.AttackRange
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

class RangeLogic(val config: RangeValueGroup) : EventListener, MinecraftShortcuts {

    override fun parent(): EventListener = config

    class ReachComboTracker {
        var hits: Int = 0
        val timer = Chronometer()
        val cooldownTimer = Chronometer()
    }

    private val comboTrackers = it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ReachComboTracker>()

    private fun getOrCreateTracker(entityId: Int): ReachComboTracker {
        var tracker = comboTrackers.get(entityId)
        if (tracker == null) {
            tracker = ReachComboTracker()
            comboTrackers.put(entityId, tracker)
        }
        return tracker
    }

    @Suppress("unused")
    private val postAttackHandler = handler<PostAttackEntityEvent> { event ->
        if (config.reachMode != RangeValueGroup.ReachMode.PROGRESSIVE) return@handler

        val tracker = getOrCreateTracker(event.entity.id)

        // Don't count hits during cooldown
        if (config.extendedReachCooldown > 0
            && !tracker.cooldownTimer.hasElapsed(config.extendedReachCooldown.toLong())) {
            return@handler
        }

        val allowedRange = if (player.hasLineOfSight(event.entity)) {
            getInteractionRangeFor(event.entity)
        } else {
            getThroughWallsRangeFor(event.entity)
        }

        // Avoid sqrt: distance > allowedRange  ⟺  distanceSqr > allowedRange²
        if (event.entity.squaredBoxedDistanceTo(player) > allowedRange * allowedRange) {
            tracker.hits = 0
            tracker.timer.reset(0L)
            return@handler
        }

        if (tracker.hits > 0 && !tracker.timer.hasElapsed(config.reachResetDelay.toLong())) {
            if (config.maxExtendedHits > 0 && (tracker.hits - config.comboHits) >= config.maxExtendedHits) {
                if (config.extendedReachCooldown > 0) {
                    tracker.hits = 0
                    tracker.timer.reset(0L)
                    tracker.cooldownTimer.reset()
                    return@handler
                } else {
                    tracker.hits = 1
                }
            } else {
                tracker.hits++
            }
        } else {
            tracker.hits = 1
        }
        tracker.timer.reset()
    }

    /**
     * Returns the progressive range increase for the given entity (0 to [maxRangeIncrease]).
     */
    private fun getProgressiveIncrease(entity: Entity): Float {
        val tracker = comboTrackers.get(entity.id) ?: return 0f

        if (tracker.hits < config.comboHits) return 0f
        if (tracker.timer.hasElapsed(config.reachResetDelay.toLong())) return 0f

        val extendedHits = tracker.hits - config.comboHits
        if (config.maxExtendedHits > 0 && extendedHits >= config.maxExtendedHits) return 0f

        // Safety: should not happen since hits is 0 during cooldown, but guard anyway
        if (config.extendedReachCooldown > 0
            && !tracker.cooldownTimer.hasElapsed(config.extendedReachCooldown.toLong())) {
            return 0f
        }

        if (config.rangePerHit <= 0f) return config.maxRangeIncrease // instant full reach

        return min(config.maxRangeIncrease, config.rangePerHit * (extendedHits + 1))
    }

    fun getInteractionRangeFor(entity: Entity?): Float {
        val base = config.baseRange
        val defaultRange = base + config.maxRangeIncrease

        if (config.reachMode != RangeValueGroup.ReachMode.PROGRESSIVE || entity == null) {
            return defaultRange
        }

        return base + getProgressiveIncrease(entity)
    }

    fun getThroughWallsRangeFor(entity: Entity?): Float {
        if (config.reachMode != RangeValueGroup.ReachMode.PROGRESSIVE || entity == null) {
            return config.throughWallsRange
        }

        val cappedWallsRange = minOf(3.0f, config.throughWallsRange)
        return if (getProgressiveIncrease(entity) > 0f) config.throughWallsRange else cappedWallsRange
    }

    fun getScanRangeFor(entity: Entity?): Float {
        val base = config.baseRange
        val defaultRange = base + config.maxRangeIncrease
        val cappedWallsRange = minOf(3.0f, config.throughWallsRange)

        if (config.reachMode != RangeValueGroup.ReachMode.PROGRESSIVE || entity == null) {
            return maxOf(defaultRange, config.throughWallsRange)
        }

        return if (getProgressiveIncrease(entity) > 0f) maxOf(defaultRange, config.throughWallsRange)
            else maxOf(base, cappedWallsRange)
    }

    fun adjustAttackRange(attackRange: AttackRange = AttackRange.defaultFor(player)): AttackRange {
        val base = config.baseRange
        val defaultRange = base + config.maxRangeIncrease
        val baseInteractionRange = base + config.maxRangeIncrease
        val isDefaultReach = Math.abs(attackRange.maxReach - baseInteractionRange) < 0.01f
        val targetReach = if (isDefaultReach) {
            defaultRange
        } else {
            attackRange.maxReach + config.maxRangeIncrease
        }
        val creativeOffset = attackRange.maxCreativeReach - attackRange.maxReach
        return AttackRange(
            max(0f, attackRange.minReach),
            targetReach,
            max(0f, attackRange.minCreativeReach),
            targetReach + creativeOffset,
            attackRange.hitboxMargin,
            attackRange.mobFactor
        )
    }

    fun adjustAttackRange(attackRange: AttackRange, entity: Entity): AttackRange {
        val base = config.baseRange
        val dynamicRange = getInteractionRangeFor(entity)
        val increase = max(0f, dynamicRange - base)
        val baseInteractionRange = base + config.maxRangeIncrease
        val isDefaultReach = Math.abs(attackRange.maxReach - baseInteractionRange) < 0.01f
        val targetReach = if (isDefaultReach) {
            dynamicRange
        } else {
            attackRange.maxReach + increase
        }
        val creativeOffset = attackRange.maxCreativeReach - attackRange.maxReach
        return AttackRange(
            max(0f, attackRange.minReach),
            targetReach,
            max(0f, attackRange.minCreativeReach),
            targetReach + creativeOffset,
            attackRange.hitboxMargin,
            attackRange.mobFactor
        )
    }

    fun getAttackRange(itemStack: ItemStack = player.getItemInHand(InteractionHand.MAIN_HAND)) = adjustAttackRange(
        itemStack.get(DataComponents.ATTACK_RANGE) ?: AttackRange.defaultFor(player)
    )

    fun isInRange(itemStack: ItemStack = player.getItemInHand(InteractionHand.MAIN_HAND), pos: Vec3) =
        getAttackRange(itemStack).isInRange(player, pos)

    fun isInRange(entity: Entity, pos: Vec3): Boolean {
        val dynamicRange = getInteractionRangeFor(entity)
        return player.distanceToSqr(pos) <= dynamicRange * dynamicRange
    }
}
