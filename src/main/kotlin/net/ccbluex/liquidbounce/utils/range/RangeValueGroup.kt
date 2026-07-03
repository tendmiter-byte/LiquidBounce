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

    /**
     * @see net.minecraft.world.entity.player.Player.entityInteractionRange
     */
    val interactionRange: Float
        get() = baseRange + maxRangeIncrease

    /**
     * Returns the range to use for vanilla entity detection (i.e. what gets returned by
     * [net.minecraft.world.entity.player.Player.entityInteractionRange]).
     *
     * We return [interactionRange] globally even in [ReachMode.PROGRESSIVE].
     * The actual hit check in startAttack uses adjustAttackRange, which gates the
     * extra reach behind the combo/delay requirements via [getInteractionRangeFor].
     * If we didn't extend the picking range upfront, the vanilla raytracer would cap
     * at baseRange, and we could never hit the entity even if we had the combo.
     */
    val effectiveInteractionRange: Float
        get() = interactionRange

    val interactionThroughWallsRange
        get() = throughWallsRange

    enum class ReachMode(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        STATIC("Static"),
        PROGRESSIVE("Progressive")
    }

    protected val reachMode by enumChoice("ReachMode", ReachMode.STATIC)

    /**
     * Increases the attack max-range.
     *
     * When min-range is introduced, rename from "RangeIncrease" to "MaxRangeIncrease"
     * and add "RangeIncrease" as an alias.
     */
    protected var maxRangeIncrease by float(
        "RangeIncrease",
        maxRangeIncrease,
        0.0f..5f,
        "blocks"
    )

    /**
     * This will use only this value for non-visible entities. Originally, we could never attack through walls,
     * so this makes sense to keep starting from 0.0.
     */
    protected var throughWallsRange by float(
        "ThroughWallsRange",
        throughWallsRange,
        0f..8f,
        "blocks"
    ).onChange {
        min(interactionRange, it)
    }

    protected var comboHits by int(
        "ComboHits",
        1,
        1..10,
        "hits"
    )

    protected var maxExtendedHits by int(
        "MaxExtendedHits",
        0,
        0..10,
        "hits"
    )

    /**
     * How much range to add per extended hit beyond the combo threshold.
     * When 0, the full [maxRangeIncrease] is granted immediately upon unlocking.
     */
    protected var rangePerHit by float(
        "RangePerHit",
        0.0f,
        0.0f..5f,
        "blocks"
    )

    protected var reachResetDelay by int(
        "ReachResetDelay",
        1000,
        0..2000,
        "ms"
    )

    /**
     * Cooldown in milliseconds after [maxExtendedHits] is exhausted before the player
     * can start building a new combo. Only applies when [maxExtendedHits] > 0.
     * When 0, no cooldown is applied and the next combo cycle starts immediately.
     */
    protected var extendedReachCooldown by int(
        "ExtendedReachCooldown",
        0,
        0..5000,
        "ms"
    )

    private val baseRange: Float
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

        // Avoid sqrt: distance > allowedRange  ⟺  distanceSqr > allowedRange²
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
     *
     * When [rangePerHit] is 0, the full [maxRangeIncrease] is granted once the combo threshold
     * is met (instant unlock). When [rangePerHit] > 0, each extended hit adds [rangePerHit]
     * blocks, capped at [maxRangeIncrease].
     */
    private fun getProgressiveIncrease(entity: Entity): Float {
        val tracker = comboTrackers.get(entity.id) ?: return 0f

        if (tracker.hits < comboHits) return 0f
        if (tracker.timer.hasElapsed(reachResetDelay.toLong())) return 0f

        val extendedHits = tracker.hits - comboHits
        if (maxExtendedHits > 0 && extendedHits >= maxExtendedHits) return 0f

        // Safety: should not happen since hits is 0 during cooldown, but guard anyway
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

        if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            return defaultRange
        }

        return base + getProgressiveIncrease(entity)
    }

    fun getThroughWallsRangeFor(entity: Entity?): Float {
        if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            return throughWallsRange
        }

        val cappedWallsRange = minOf(3.0f, throughWallsRange)
        return if (getProgressiveIncrease(entity) > 0f) throughWallsRange else cappedWallsRange
    }

    open fun getScanRangeFor(entity: Entity?): Float {
        val base = baseRange
        val defaultRange = base + maxRangeIncrease
        val cappedWallsRange = minOf(3.0f, throughWallsRange)

        if (reachMode != ReachMode.PROGRESSIVE || entity == null) {
            return maxOf(defaultRange, throughWallsRange)
        }

        return if (getProgressiveIncrease(entity) > 0f) maxOf(defaultRange, throughWallsRange)
            else maxOf(base, cappedWallsRange)
    }

    /**
     * Decreases the attack min-range.
     *
     * This is a placeholder until required.
     * There is no vanilla item that is making use of this for now.
     *
     * The spear is executed on the server-side and only uses min range as a visual indicator.
     * @see net.minecraft.client.Minecraft.startAttack
     * @see net.minecraft.client.multiplayer.MultiPlayerGameMode.piercingAttack
     */
    // private val minRangeDecrease by float("MinRangeDecrease", 0f, 0f..2f, "blocks")

    fun adjustAttackRange(attackRange: AttackRange = AttackRange.defaultFor(player)): AttackRange {
        val base = baseRange
        val defaultRange = base + maxRangeIncrease
        val baseInteractionRange = base + this@RangeValueGroup.maxRangeIncrease
        val isDefaultReach = Math.abs(attackRange.maxReach - baseInteractionRange) < 0.01f
        val targetReach = if (isDefaultReach) {
            defaultRange
        } else {
            attackRange.maxReach + this@RangeValueGroup.maxRangeIncrease
        }
        val creativeOffset = attackRange.maxCreativeReach - attackRange.maxReach
        return AttackRange(
            max(0f, attackRange.minReach/* - minRangeDecrease*/),
            targetReach,
            max(0f, attackRange.minCreativeReach/* - minRangeDecrease*/),
            targetReach + creativeOffset,
            attackRange.hitboxMargin,
            attackRange.mobFactor
        )
    }

    /**
     * Entity-aware variant that respects [ReachMode.PROGRESSIVE].
     * The increase applied is derived from [getInteractionRangeFor] rather than the
     * raw [maxRangeIncrease], so the progressive combo-tracker is honoured.
     */
    fun adjustAttackRange(attackRange: AttackRange, entity: Entity): AttackRange {
        val base = baseRange
        val dynamicRange = getInteractionRangeFor(entity)
        val increase = max(0f, dynamicRange - base)
        val baseInteractionRange = base + this@RangeValueGroup.maxRangeIncrease
        val isDefaultReach = Math.abs(attackRange.maxReach - baseInteractionRange) < 0.01f
        val targetReach = if (isDefaultReach) {
            dynamicRange
        } else {
            attackRange.maxReach + increase
        }
        val creativeOffset = attackRange.maxCreativeReach - attackRange.maxReach
        return AttackRange(
            max(0f, attackRange.minReach/* - minRangeDecrease*/),
            targetReach,
            max(0f, attackRange.minCreativeReach/* - minRangeDecrease*/),
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
