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
        get() = baseRange + maxRangeIncrease + 0.005f

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
        get() = throughWallsRange + 0.005f

    enum class ReachMode(override val tag: String) : net.ccbluex.liquidbounce.config.types.list.Tagged {
        STATIC("Static"),
        PROGRESSIVE("Progressive")
    }

    internal val reachMode by enumChoice("ReachMode", ReachMode.STATIC)

    /**
     * Increases the attack max-range.
     *
     * When min-range is introduced, rename from "RangeIncrease" to "MaxRangeIncrease"
     * and add "RangeIncrease" as an alias.
     */
    internal var maxRangeIncrease by float(
        "RangeIncrease",
        maxRangeIncrease,
        0.0f..5f,
        "blocks"
    )

    /**
     * This will use only this value for non-visible entities. Originally, we could never attack through walls,
     * so this makes sense to keep starting from 0.0.
     */
    internal var throughWallsRange by float(
        "ThroughWallsRange",
        throughWallsRange,
        0f..8f,
        "blocks"
    ).onChange {
        min(interactionRange, it)
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
     * When 0, the full [maxRangeIncrease] is granted immediately upon unlocking.
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
     * can start building a new combo. Only applies when [maxExtendedHits] > 0.
     * When 0, no cooldown is applied and the next combo cycle starts immediately.
     */
    internal var extendedReachCooldown by int(
        "ExtendedReachCooldown",
        0,
        0..5000,
        "ms"
    )

    internal val baseRange: Float
        get() = mc.player?.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)?.toFloat() ?: 3.0F

    val logic = RangeLogic(this)

    override fun children(): List<EventListener> = listOf(logic)

    fun getInteractionRangeFor(entity: Entity?): Float = logic.getInteractionRangeFor(entity)

    fun getThroughWallsRangeFor(entity: Entity?): Float = logic.getThroughWallsRangeFor(entity)

    open fun getScanRangeFor(entity: Entity?): Float = logic.getScanRangeFor(entity)

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

    fun adjustAttackRange(attackRange: AttackRange = AttackRange.defaultFor(player)): AttackRange =
        logic.adjustAttackRange(attackRange)

    /**
     * Entity-aware variant that respects [ReachMode.PROGRESSIVE].
     * The increase applied is derived from [getInteractionRangeFor] rather than the
     * raw [maxRangeIncrease], so the progressive combo-tracker is honoured.
     */
    fun adjustAttackRange(attackRange: AttackRange, entity: Entity): AttackRange =
        logic.adjustAttackRange(attackRange, entity)

    fun getAttackRange(itemStack: ItemStack = player.getItemInHand(InteractionHand.MAIN_HAND)) =
        logic.getAttackRange(itemStack)

    fun isInRange(itemStack: ItemStack = player.getItemInHand(InteractionHand.MAIN_HAND), pos: Vec3) =
        logic.isInRange(itemStack, pos)

    fun isInRange(entity: Entity, pos: Vec3): Boolean =
        logic.isInRange(entity, pos)

}
