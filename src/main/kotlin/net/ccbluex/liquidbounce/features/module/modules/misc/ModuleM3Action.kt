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

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.misc.MiddleClickPearlMode
import net.ccbluex.liquidbounce.features.misc.MiddleClickPlayerClickMode
import net.ccbluex.liquidbounce.features.misc.PearlThrowSettings
import net.ccbluex.liquidbounce.features.misc.PlayerClickRaycastSettings
import net.ccbluex.liquidbounce.features.misc.toggleTarget
import net.ccbluex.liquidbounce.features.misc.resetPearlMode
import net.ccbluex.liquidbounce.features.misc.toggleFriend
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items

/**
 * Allows you to perform actions with the middle mouse button.
 */
object ModuleM3Action : ClientModule(
    "M3Action",
    ModuleCategories.MISC,
    aliases = listOf("FriendClicker", "MiddleClickPearl", "MiddleClickAction")
) {

    init {
        doNotIncludeAlways()
    }

    private val mode = modes(this, "Mode", FriendClicker, arrayOf(FriendClicker, TargetLock, Pearl))

    override fun onDisabled() {
        resetPearlMode(Pearl)
    }

    object FriendClicker : MiddleClickPlayerClickMode("FriendClicker") {
        override val raycast = tree(PlayerClickRaycastSettings())

        override fun onPlayerClick(player: Player) = toggleFriend(player)

        override val parent: ModeValueGroup<*>
            get() = mode
    }

    object TargetLock : MiddleClickPlayerClickMode("TargetLock", listOf("Target")) {
        override val raycast = tree(PlayerClickRaycastSettings())

        override fun onPlayerClick(player: Player) = toggleTarget(player)

        override val parent: ModeValueGroup<*>
            get() = mode
    }

    object Pearl : MiddleClickPearlMode() {
        override val settings = tree(PearlThrowSettings())

        override val parent: ModeValueGroup<*>
            get() = mode

        fun cancelPick(): Boolean {
            return ModuleM3Action.running &&
                mode.activeMode == this &&
                Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) != null
        }
    }

}
