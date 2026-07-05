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

import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.PostAttackEntityEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.ccbluex.liquidbounce.utils.client.Chronometer
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.math.RANGE_PRECISION_EPSILON
import net.minecraft.core.component.DataComponents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.AttackRange
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.min

/**
 * Allows adjusting your attack range and scan range.
 */
open class RangeValueGroup(
    name: String,
    maxRangeIncrease: Float,
    throughWallsRange: Float
) : ValueGroup(name), MinecraftShortcuts, EventListener {

    override fun parent(): EventListener? = base as? EventListener

    val interactionRange: Float
        get() = baseRange + maxRangeIncrease + RANGE_PRECISION_EPSILON

    val effectiveInteractionRange: Float
        get() = interactionRange

    val interactionThroughWallsRange: Float
        get() = throughWallsRange + RANGE_PRECISION_EPSILON

    enum class ReachMode(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        STATIC("Static"),
        PROGRESSIVE("Progressive")
    }

    internal val reachMode by enumChoice("ReachMode", ReachMode.STATIC)

    /**
     * Increases the attack max-range.
     */
    internal var maxRangeIncrease by float(
        "RangeIncrease",
        maxRangeIncrease,
        0.0f..5f,
        "blocks"
    )

    /**
     * This will use only this value for non-visible entities.
     */
    internal var throughWallsRange by float(
        "ThroughWallsRange",
        throughWallsRange,
        0f..8f,
        "blocks"
    ).onChange {
        min(baseRange + maxRangeIncrease, it)
    }

    internal var comboHits by int(
        "RequiredComboCountForActuation",
        1,
        1..10,
        "hits"
    )

    internal var maxExtendedHits by int(
        "MaxExtendedComboUntilReset",
        0,
        0..10,
        "hits"
    )

    /**
     * How much range to add per extended hit beyond the combo threshold.
     */
    internal var rangePerHit by float(
        "RangeIncreasePerHit",
        0.0f,
        0.0f..5f,
        "blocks"
    )

    internal var reachResetDelay by int(
        "ExtendedReachResetDelay",
        1000,
        0..2000,
        "ms"
    )

    /**
     * Cooldown in milliseconds after [maxExtendedHits] is exhausted before the player
     * can start building a new combo.
     */
    internal var extendedReachCooldown by int(
        "ExtendedReachCooldown",
        0,
        0..5000,
        "ms"
    )

    internal val baseRange: Float
        get() = mc.player?.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)?.toFloat() ?: 3.0F

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
        if (reachMode != ReachMode.PROGRESSIVE) return@handler

        val tracker = getOrCreateTracker(event.entity.id)

        // Don't count hits during cooldown
        if (extendedReachCooldown > 0
            && !tracker.cooldownTimer.hasElapsed(extendedReachCooldown.toLong())) {
            return@handler
        }

        val allowedRange = if (player.hasLineOfSight(event.entity)) {
            getInteractionRangeFor(event.entity)
        } else {
            getThroughWallsRangeFor(event.entity)
        }

        // Avoid sqrt
        if (event.entity.squaredBoxedDistanceTo(player) > allowedRange * allowedRange) {
            tracker.hits = 0
            tracker.timer.reset(0L)
            return@handler
        }

        if (tracker.hits > 0 && !tracker.timer.hasElapsed(reachResetDelay.toLong())) {
            if (maxExtendedHits > 0 && (tracker.hits - comboHits) >= maxExtendedHits) {
                if (extendedReachCooldown > 0) {
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

        if (tracker.hits < comboHits) return 0f
        if (tracker.timer.hasElapsed(reachResetDelay.toLong())) return 0f

        val extendedHits = tracker.hits - comboHits
        if (maxExtendedHits > 0 && extendedHits >= maxExtendedHits) return 0f

        if (extendedReachCooldown > 0
            && !tracker.cooldownTimer.hasElapsed(extendedReachCooldown.toLong())) {
            return 0f
        }

        if (rangePerHit <= 0f) return maxRangeIncrease // instant full reach

        return min(maxRangeIncrease, rangePerHit * (extendedHits + 1))
    }

    fun getInteractionRangeFor(entity: Entity?): Float {
        val base = baseRange
        val defaultRange = base + maxRangeIncrease

        val r = if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            defaultRange
        } else {
            base + getProgressiveIncrease(entity)
        }
        return r + RANGE_PRECISION_EPSILON
    }

    fun getThroughWallsRangeFor(entity: Entity?): Float {
        val r = if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            throughWallsRange
        } else {
            val cappedWallsRange = minOf(3.0f, throughWallsRange)
            if (getProgressiveIncrease(entity) > 0f) throughWallsRange else cappedWallsRange
        }
        return r + RANGE_PRECISION_EPSILON
    }

    open fun getScanRangeFor(entity: Entity?): Float {
        val base = baseRange
        val defaultRange = base + maxRangeIncrease
        val cappedWallsRange = minOf(3.0f, throughWallsRange)

        val r = if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            maxOf(defaultRange, throughWallsRange)
        } else {
            if (getProgressiveIncrease(entity) > 0f) maxOf(defaultRange, throughWallsRange)
            else maxOf(base, cappedWallsRange)
        }
        return r + RANGE_PRECISION_EPSILON
    }

    fun adjustAttackRange(attackRange: AttackRange = AttackRange.defaultFor(player)): AttackRange {
        val base = baseRange
        val defaultRange = base + maxRangeIncrease
        val baseInteractionRange = base + maxRangeIncrease
        val isDefaultReach = Math.abs(attackRange.maxReach - baseInteractionRange) < 0.01f
        val targetReach = if (isDefaultReach) {
            defaultRange
        } else {
            attackRange.maxReach + maxRangeIncrease
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

    /**
     * Entity-aware variant that respects [ReachMode.PROGRESSIVE].
     */
    fun adjustAttackRange(attackRange: AttackRange, entity: Entity): AttackRange {
        val base = baseRange
        val dynamicRange = getInteractionRangeFor(entity)
        val increase = max(0f, dynamicRange - base)
        val baseInteractionRange = base + maxRangeIncrease
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
        return player.eyePosition.distanceToSqr(pos) <= dynamicRange * dynamicRange
    }

}
