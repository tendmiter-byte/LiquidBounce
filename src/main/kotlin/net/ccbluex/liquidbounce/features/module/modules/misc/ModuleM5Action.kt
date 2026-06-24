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
package net.ccbluex.liquidbounce.features.module.modules.misc

import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.features.misc.PearlThrowSettings
import net.ccbluex.liquidbounce.features.misc.PlayerClickRaycastSettings
import net.ccbluex.liquidbounce.features.misc.TickPearlMode
import net.ccbluex.liquidbounce.features.misc.TickPlayerClickMode
import net.ccbluex.liquidbounce.features.misc.toggleTarget
import net.ccbluex.liquidbounce.features.misc.resetPearlMode
import net.ccbluex.liquidbounce.features.misc.toggleFriend
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.minecraft.world.entity.player.Player
import org.lwjgl.glfw.GLFW

/**
 * Allows you to perform actions with the fifth mouse button.
 */
object ModuleM5Action : ClientModule("M5Action", ModuleCategories.MISC) {

    init {
        doNotIncludeAlways()
    }

    private val mode = modes(this, "Mode", FriendClicker, arrayOf(FriendClicker, TargetLock, Pearl))

    override fun onDisabled() {
        resetPearlMode(Pearl)
    }

    object FriendClicker : TickPlayerClickMode(GLFW.GLFW_MOUSE_BUTTON_5, "FriendClicker") {
        override val raycast = tree(PlayerClickRaycastSettings())

        override fun onPlayerClick(player: Player) = toggleFriend(player)

        override val parent: ModeValueGroup<*>
            get() = mode
    }

    object TargetLock : TickPlayerClickMode(GLFW.GLFW_MOUSE_BUTTON_5, "TargetLock", listOf("Target")) {
        override val raycast = tree(PlayerClickRaycastSettings())

        override fun onPlayerClick(player: Player) = toggleTarget(player)

        override val parent: ModeValueGroup<*>
            get() = mode
    }

    object Pearl : TickPearlMode(GLFW.GLFW_MOUSE_BUTTON_5) {
        override val settings = tree(PearlThrowSettings())

        override val parent: ModeValueGroup<*>
            get() = mode
    }

}
