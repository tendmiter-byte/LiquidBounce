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

package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.inventory.GhostItemFix
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket

object ModuleGhostItemFix : ClientModule(
    "GhostItemFix",
    ModuleCategories.MISC,
    aliases = listOf("AntiDesync", "GhostBowlFix")
) {

    override val baseKey: String
        get() = "${ConfigSystem.KEY_PREFIX}.module.ghostItemFix"

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        if (event.origin == TransferOrigin.OUTGOING) {
            val packet = event.packet
            if (packet is ServerboundPlayerActionPacket &&
                (packet.action == ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS ||
                 packet.action == ServerboundPlayerActionPacket.Action.DROP_ITEM)
            ) {
                GhostItemFix.onDropPacketSent()
            }
        }
    }
}
