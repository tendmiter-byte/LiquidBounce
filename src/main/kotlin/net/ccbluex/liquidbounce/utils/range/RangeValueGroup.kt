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
import net.ccbluex.liquidbounce.features.module.MinecraftShortcuts
import net.minecraft.world.entity.ai.attributes.Attributes
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

}
