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

package net.ccbluex.liquidbounce.features.module.modules.player.autobuff

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Drink
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Gapple
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Head
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Pot
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Refill
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features.Soup
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.combat.CombatManager
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.findClosestSlot
import net.ccbluex.liquidbounce.utils.inventory.isInInventoryScreen

object ModuleAutoBuff : ClientModule(
    name = "AutoBuff",
    category = ModuleCategories.PLAYER,
    aliases = listOf("AutoPot", "AutoGapple", "AutoSoup")
) {

    /**
     * All buff features
     */
    private val features = arrayOf(
        Soup,
        Head,
        Pot,
        Drink,
        Gapple
    )

    init {
        // Register features to configurable
        features.forEach(this::tree)
    }

    /**
     * Auto Swap will automatically swap your selected slot to the best item for the situation.
     * For example, if you're low on health, it will swap to the next health pot.
     *
     * It also allows to customize the delay between each swap.
     */
    internal object AutoSwap : ToggleableValueGroup(ModuleAutoBuff, "AutoSwap", true) {

        /**
         * How long should we wait after swapping to the item?
         */
        val delayIn by intRange("DelayIn", 1..1, 0..20, "ticks")

        /**
         * How long should we wait after using the item?
         */
        val delayOut by intRange("DelayOut", 1..1, 0..20, "ticks")

    }

    init {
        tree(AutoSwap)
        tree(Refill)
    }

    /**
     * Rotation Configurable for every feature that depends on rotation change
     */
    internal object Rotations : RotationsValueGroup(this) {

        val rotationTiming by enumChoice("RotationTiming", RotationTimingMode.NORMAL)

        enum class RotationTimingMode(override val tag: String) : Tagged {
            NORMAL("Normal"),
            ON_TICK("OnTick"),
            ON_USE("OnUse")
        }

    }

    init {
        tree(Rotations)
    }

    internal val combatPauseTime by int("CombatPauseTime", 0, 0..40, "ticks")
    private val notDuringCombat by boolean("NotDuringCombat", false)

    internal var isBuffing = false
        private set

    internal val isBuffingOrRefilling: Boolean
        get() = isBuffing || (Refill.enabled && Refill.hasPendingRefill())

    internal fun refreshCombatPause() {
        CombatManager.pauseCombatForAtLeast(combatPauseTime.coerceAtLeast(1))
    }

    internal fun beginBuffing() {
        isBuffing = true
        refreshCombatPause()
    }

    internal fun endBuffing() {
        isBuffing = false
    }

    internal fun hasPendingBuffAction(): Boolean {
        if (isBuffing) {
            return true
        }

        return activeFeatures.any { feature ->
            feature.enabled && feature.passesRequirements &&
                Slots.OffhandWithHotbar.findClosestSlot { feature.isValidItem(it, true) } != null
        }
    }

    internal val activeFeatures
        get() = features.filter { it.enabled }

    @Suppress("unused")
    private val tickHandler = tickHandler {
        if (notDuringCombat && CombatManager.isInCombat) {
            return@tickHandler
        }

        if (player.isDeadOrDying || player.isCreative || player.isSpectator || player.tickCount < 20) {
            return@tickHandler
        }

        for (feature in activeFeatures) {
            with(feature) {
                if (runIfPossible()) {
                    return@tickHandler
                }
            }
        }
    }

    @Suppress("unused")
    private val refiller = handler<ScheduleInventoryActionEvent> {
        if (!Refill.enabled || hasPendingBuffAction()) {
            return@handler
        }

        // Do not silently open the player inventory while a container screen is visible.
        // That desyncs containerMenu from the chest screen and breaks ChestStealer auto close.
        if (InventoryManager.isHandledScreenOpen && !isInInventoryScreen) {
            return@handler
        }

        Refill.execute(it)
    }

    override fun onDisabled() {
        endBuffing()
        SilentHotbar.resetSlot(ModuleAutoBuff)
        super.onDisabled()
    }

}
