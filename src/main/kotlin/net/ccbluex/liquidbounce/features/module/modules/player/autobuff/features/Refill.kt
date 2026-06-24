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
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.ScheduleInventoryActionEvent
import net.ccbluex.liquidbounce.features.module.modules.player.autobuff.ModuleAutoBuff
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.inventory.HotbarItemSlot
import net.ccbluex.liquidbounce.utils.inventory.InventoryAction
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.inventory.PlayerInventoryConstraints
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.isInInventoryScreen
import net.ccbluex.liquidbounce.utils.inventory.mergeableCapacityFor
import net.ccbluex.liquidbounce.utils.item.isMergeable
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.min

object Refill : ToggleableValueGroup(ModuleAutoBuff, "Refill", true) {

    private val inventoryConstraints = tree(PlayerInventoryConstraints())
    private val moveMode by enumChoice("MoveMode", MoveMode.QUICK_MOVE)

    fun hasPendingRefill(): Boolean {
        if (!enabled || ModuleAutoBuff.hasPendingBuffAction()) {
            return false
        }

        // Do not silently open the player inventory while a container screen is visible.
        if (InventoryManager.isHandledScreenOpen && !isInInventoryScreen) {
            return false
        }

        val validFeatures = ModuleAutoBuff.activeFeatures

        val validItems = Slots.Inventory.filter {
            val itemStack = it.itemStack
            validFeatures.any { f -> f.isValidItem(itemStack, false) }
        }

        if (validItems.isEmpty()) {
            return false
        }

        val emptySlots = Slots.OffhandWithHotbar
            .filter { it.itemStack.isEmpty && it != HotbarItemSlot.OFFHAND }

        return when (moveMode) {
            MoveMode.BULK_MOVE -> {
                val referenceStack = validItems.first().itemStack
                val remainingCapacity = Slots.Hotbar.mergeableCapacityFor(referenceStack)
                if (remainingCapacity <= 0) {
                    return false
                }

                val identicalSlots = validItems.filter {
                    it.itemStack.isMergeable(referenceStack)
                }

                identicalSlots.isNotEmpty()
            }

            MoveMode.QUICK_MOVE -> {
                emptySlots.isNotEmpty()
            }

            MoveMode.DRAG_AND_DROP -> {
                emptySlots.isNotEmpty()
            }
        }
    }

    fun execute(event: ScheduleInventoryActionEvent) {
        val validFeatures = ModuleAutoBuff.activeFeatures

        val validItems = Slots.Inventory.filter {
            val itemStack = it.itemStack
            validFeatures.any { f -> f.isValidItem(itemStack, false) }
        }

        if (validItems.isEmpty()) {
            return
        }

        val emptySlots = Slots.OffhandWithHotbar
            .filter { it.itemStack.isEmpty && it != HotbarItemSlot.OFFHAND }
            .toMutableList()

        when (moveMode) {
            MoveMode.BULK_MOVE -> {
                val referenceStack = validItems.first().itemStack
                var remainingCapacity = Slots.Hotbar.mergeableCapacityFor(referenceStack)
                if (remainingCapacity <= 0) {
                    return
                }

                val identicalSlots = buildList {
                    for (slot in validItems) {
                        if (remainingCapacity <= 0) {
                            break
                        }

                        val stack = slot.itemStack
                        if (!stack.isMergeable(referenceStack)) {
                            continue
                        }

                        add(slot)
                        remainingCapacity -= min(stack.count, remainingCapacity)
                    }
                }

                if (identicalSlots.isEmpty()) {
                    return
                }

                val screen = mc.gui.screen() as? AbstractContainerScreen<*>
                val bulkScreen = if (screen != null && isInInventoryScreen) screen else null

                event.schedule(
                    inventoryConstraints,
                    listOf(InventoryAction.BulkQuickMove.performBulkQuickMove(bulkScreen, identicalSlots)),
                    Priority.IMPORTANT_FOR_USAGE_1,
                )
            }

            MoveMode.QUICK_MOVE -> {
                if (emptySlots.isEmpty()) {
                    return
                }

                val itemsToRefill = validItems.take(emptySlots.size)

                for (itemSlot in itemsToRefill) {
                    event.schedule(
                        inventoryConstraints,
                        listOf(InventoryAction.Click.performQuickMove(slot = itemSlot)),
                        Priority.IMPORTANT_FOR_USAGE_1,
                    )
                }
            }

            MoveMode.DRAG_AND_DROP -> {
                if (emptySlots.isEmpty()) {
                    return
                }

                val itemsToRefill = validItems.take(emptySlots.size)

                for (itemSlot in itemsToRefill) {
                    val targetSlot = emptySlots.removeAt(0)
                    event.schedule(
                        inventoryConstraints,
                        listOf(
                            InventoryAction.Click.performPickup(slot = itemSlot),
                            InventoryAction.Click.performPickupAll(slot = itemSlot),
                            InventoryAction.Click.performPickup(slot = targetSlot),
                        ),
                        Priority.IMPORTANT_FOR_USAGE_1,
                    )
                }
            }
        }
    }

    private enum class MoveMode(
        override val tag: String,
        override val tagAliases: List<String> = emptyList(),
    ) : Tagged {
        QUICK_MOVE("QuickMove"),
        BULK_MOVE("BulkMove", listOf("MassMoveIdenticalItems")),
        DRAG_AND_DROP("DragAndDrop", listOf("PickupAll")),
    }

}
