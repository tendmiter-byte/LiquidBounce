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

package net.ccbluex.liquidbounce.features.module.modules.player.autobuff.features

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.waitTicks
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.Buff
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.ModuleAutoBuff
import net.ccbluex.liquidbounce.utils.entity.armorItems
import net.ccbluex.liquidbounce.utils.entity.getEffectiveDamage
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.item.armorToughness
import net.ccbluex.liquidbounce.utils.item.armorValue
import net.ccbluex.liquidbounce.utils.item.durability
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.max

internal object Soup : Buff("Soup") {

    private object Survival : ValueGroup("Survival") {
        val expectedHitDamage by float("ExpectedHitDamage", 7f, 1f..20f, "dmg")
        val requiredSurvival by float("RequiredSurvival", 2.25f, 0.5f..5f, "hits")
        val healAmount by float("HealAmount", 7f, 1f..20f, "heal")
        val allowedWaste by float("AllowedWaste", 1f, 0f..10f, "heal")
        val ignoreWasteBelowSurvival by float("IgnoreWasteBelowSurvival", 0.75f, 0.25f..2f, "hits")
    }

    private object ArmorBreakRisk : ValueGroup("ArmorBreakRisk") {
        val durability by int("Durability", 8, 0..100)
    }

    private object DropAfterUse : ToggleableValueGroup(this, "DropAfterUse", true) {
        val assumeEmptyBowl by boolean("AssumeEmptyBowl", true)
        val wait by intRange("Wait", 1..2, 1..20, "ticks")
    }

    init {
        tree(Survival)
        tree(ArmorBreakRisk)
        tree(DropAfterUse)
    }

    override val passesRequirements: Boolean
        get() = enabled && !InventoryManager.isInventoryOpen && passesSurvivalRequirements()

    override fun isValidItem(stack: ItemStack, forUse: Boolean): Boolean {
        return stack.`is`(Items.MUSHROOM_STEW)
    }

    override suspend fun execute(slot: HotbarItemSlot) {
        useHotbarSlotOrOffhand(slot)

        if (!DropAfterUse.enabled) {
            return
        }

        repeat(DropAfterUse.wait.random()) {
            waitTicks(1)
            ModuleAutoBuff.refreshCombatPause()
        }

        var shouldDropBowl = DropAfterUse.assumeEmptyBowl
        if (!shouldDropBowl) {
            for (i in 0 until 10) {
                if (slot.itemStack.`is`(Items.BOWL)) {
                    shouldDropBowl = true
                    break
                }
                waitTicks(1)
                ModuleAutoBuff.refreshCombatPause()
            }
            if (!shouldDropBowl && slot.itemStack.`is`(Items.BOWL)) {
                shouldDropBowl = true
            }
        }

        if (shouldDropBowl && slot != HotbarItemSlot.OFFHAND) {
            // Guarantee the correct slot is selected server-side before dropping.
            SilentHotbar.selectSlotSilently(ModuleAutoBuff, slot, 1)
            interaction.ensureHasSentCarriedItem()
            if (player.drop(true)) {
                player.swing(InteractionHand.MAIN_HAND)
                player.inventory.setItem(slot.inventorySlot, ItemStack.EMPTY)
            }
        }
    }

    private fun passesSurvivalRequirements(): Boolean {
        val missingHealth = (player.maxHealth - player.health).coerceAtLeast(0f)
        if (missingHealth <= 0f) {
            return false
        }

        val expectedDamage = expectedPostMitigationDamage()
        if (expectedDamage <= 0f) {
            return false
        }

        val availableHealth = player.health + player.absorptionAmount
        val currentSurvival = availableHealth / expectedDamage

        if (currentSurvival > Survival.requiredSurvival) {
            return false
        }

        val wastedHealing = (Survival.healAmount - missingHealth).coerceAtLeast(0f)
        return wastedHealing <= Survival.allowedWaste ||
            currentSurvival <= Survival.ignoreWasteBelowSurvival
    }

    private fun expectedPostMitigationDamage(): Float {
        val currentDamage = player.getEffectiveDamage(
            player.damageSources().playerAttack(player),
            Survival.expectedHitDamage,
            ignoreShield = true,
        )

        return max(currentDamage, projectedDamageWithoutFragileArmor())
    }

    private fun projectedDamageWithoutFragileArmor(): Float {
        var defensePoints = 0f
        var toughness = 0f

        for (stack in player.armorItems) {
            val armorIsFragile = stack.maxDamage > 0 && stack.durability <= ArmorBreakRisk.durability
            if (stack.isEmpty || armorIsFragile) {
                continue
            }

            defensePoints += stack.armorValue?.toFloat() ?: 0f
            toughness += stack.armorToughness?.toFloat() ?: 0f
        }

        return Survival.expectedHitDamage * armorDamageFactor(
            Survival.expectedHitDamage,
            defensePoints,
            toughness,
        )
    }

    /**
     * Mirrors [net.ccbluex.liquidbounce.utils.item.armor.ArmorComparator.getDamageFactor].
     */
    private fun armorDamageFactor(damage: Float, defensePoints: Float, toughness: Float): Float {
        val toughnessFactor = 2.0f + toughness / 4.0f
        val cappedDefense = (defensePoints - damage / toughnessFactor)
            .coerceIn(defensePoints * 0.2f, 20.0f)

        return 1.0f - cappedDefense / 25.0f
    }

}
