/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package net.ccbluex.liquidbounce.utils.inventory

import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleGhostItemFix
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.world.item.Items
import java.util.concurrent.ConcurrentHashMap

object GhostItemFix {
    private val recentlyDropped = ConcurrentHashMap<Int, Long>()

    @JvmStatic
    fun onDropPacketSent() {
        val player = mc.player ?: return
        val slot = if (SilentHotbar.isSlotModified()) {
            SilentHotbar.serversideSlot
        } else {
            player.inventory.selectedSlot
        }
        recentlyDropped[slot] = System.currentTimeMillis()
    }

    @JvmStatic
    fun shouldIgnoreSetSlot(containerId: Int, slotId: Int, itemStack: net.minecraft.world.item.ItemStack): Boolean {
        if (!ModuleGhostItemFix.running) {
            return false
        }
        if (containerId != 0) {
            return false
        }
        // Hotbar slots are 36..44
        if (slotId !in 36..44) {
            return false
        }
        val hotbarIndex = slotId - 36
        val dropTime = recentlyDropped[hotbarIndex] ?: return false
        if (System.currentTimeMillis() - dropTime > 500) {
            recentlyDropped.remove(hotbarIndex)
            return false
        }
        val item = itemStack.item
        return item == Items.BOWL || item == Items.GLASS_BOTTLE
    }
}
