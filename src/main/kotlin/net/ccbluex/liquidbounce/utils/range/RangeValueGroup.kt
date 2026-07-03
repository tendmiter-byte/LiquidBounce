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
import net.ccbluex.liquidbounce.utils.kotlin.random
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

    /**
     * @see net.minecraft.world.entity.player.Player.entityInteractionRange
     */
    val interactionRange: Float
        get() = (mc.player?.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)?.toFloat()
            ?: 3.0F) + maxRangeIncrease

    val interactionThroughWallsRange
        get() = throughWallsRange

    enum class ReachMode(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        NORMAL("Normal"),
        COMBO("Combo")
    }

    protected val reachMode by enumChoice("ReachMode", ReachMode.NORMAL)

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

    protected var comboHits by float(
        "ComboHits",
        1f,
        1f..10f,
        "hits"
    )

    protected var reachResetDelay by float(
        "ReachResetDelay",
        1000f,
        0f..2000f,
        "ms"
    )

    protected var maxGainedReach by float(
        "MaxGainedReach",
        1.5f,
        1f..5f,
        "blocks"
    )

    class ReachComboTracker {
        var hits: Int = 0
        val timer = Chronometer()
    }

    val comboTrackers = it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ReachComboTracker>()

    @Suppress("unused")
    private val postAttackHandler = handler<PostAttackEntityEvent> { event ->
        if (reachMode != ReachMode.COMBO) return@handler
        val entityId = event.entity.id
        var tracker = comboTrackers.get(entityId)
        if (tracker == null) {
            tracker = ReachComboTracker()
            comboTrackers.put(entityId, tracker)
        }

        val baseRange = (mc.player?.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)?.toFloat() ?: 3.0F)
        val distance = player.distanceTo(event.entity)
        val gainedReach = distance - baseRange

        if (gainedReach > maxGainedReach) {
            tracker.hits = 0
            tracker.timer.reset(0L)
            return@handler
        }

        if (tracker.hits > 0 && !tracker.timer.hasElapsed(reachResetDelay.toLong())) {
            tracker.hits = (tracker.hits + 1).coerceAtMost(100)
        } else {
            tracker.hits = 1
        }
        tracker.timer.reset()
    }

    fun getInteractionRangeFor(entity: Entity?): Float {
        val baseRange = (mc.player?.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)?.toFloat() ?: 3.0F)
        val defaultRange = baseRange + maxRangeIncrease

        if (reachMode != ReachMode.COMBO || entity == null) {
            return defaultRange
        }

        val distance = player.distanceTo(entity)
        val gainedReach = distance - baseRange

        if (gainedReach > maxGainedReach) {
            val tracker = comboTrackers.get(entity.id)
            if (tracker != null) {
                tracker.hits = 0
                tracker.timer.reset(0L)
            }
            return baseRange
        }

        var tracker = comboTrackers.get(entity.id)
        if (tracker == null) {
            tracker = ReachComboTracker()
            comboTrackers.put(entity.id, tracker)
        }

        if (tracker.hits > 0 && tracker.timer.hasElapsed(reachResetDelay.toLong())) {
            tracker.hits = 0
            return baseRange
        }

        if (tracker.hits >= comboHits) {
            return defaultRange
        }

        return baseRange
    }

    fun getThroughWallsRangeFor(entity: Entity?): Float {
        if (reachMode != ReachMode.COMBO || entity == null) {
            return throughWallsRange
        }

        val tracker = comboTrackers.get(entity.id) ?: return minOf(3.0f, throughWallsRange)

        if (tracker.hits > 0 && tracker.timer.hasElapsed(reachResetDelay.toLong())) {
            tracker.hits = 0
            return minOf(3.0f, throughWallsRange)
        }

        if (tracker.hits >= comboHits) {
            return throughWallsRange
        }

        return minOf(3.0f, throughWallsRange)
    }

    open fun getScanRangeFor(entity: Entity?): Float {
        val range = getInteractionRangeFor(entity)
        val wallsRange = getThroughWallsRangeFor(entity)
        return maxOf(range, wallsRange)
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

    fun adjustAttackRange(attackRange: AttackRange = AttackRange.defaultFor(player)) =
        AttackRange(
            max(0f, attackRange.minReach/* - minRangeDecrease*/),
            attackRange.maxReach + this@RangeValueGroup.maxRangeIncrease,
            max(0f, attackRange.minCreativeReach/* - minRangeDecrease*/),
            attackRange.maxCreativeReach + this@RangeValueGroup.maxRangeIncrease,
            attackRange.hitboxMargin,
            attackRange.mobFactor
        )

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
