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
package net.ccbluex.liquidbounce.features.misc

import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleTargetLock
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.SilentHotbar
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.input.InputTracker.isMouseButtonPressed
import net.ccbluex.liquidbounce.utils.inventory.Slots
import net.ccbluex.liquidbounce.utils.inventory.useHotbarSlotOrOffhand
import net.ccbluex.liquidbounce.utils.raytracing.findEntityInCrosshair
import net.ccbluex.liquidbounce.utils.raytracing.isLookingAtEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items

class PlayerClickRaycastSettings : ValueGroup("Raycast") {
    val pickUpRange by float("PickUpRange", 3.0f, 1f..100f)
    val throughWallsRange by float("ThroughWallsRange", 0f, 0f..100f)
}

class PearlThrowSettings : ValueGroup("Throw") {
    val slotResetDelay by int("SlotResetDelay", 1, 0..10, "ticks")
    val stopOnSubmit by floatRange("StopOnSubmit", 85F..90F, 60F..90F, "Pitch")
}

internal fun findCrosshairPlayer(
    pickUpRange: Double,
    throughWallsRange: Double,
    rotation: Rotation = player.rotation,
): Player? {
    val entity = (findEntityInCrosshair(pickUpRange, rotation) { it is Player }
        ?: return null).entity as Player

    if (isLookingAtEntity(
            toEntity = entity,
            rotation = rotation,
            range = pickUpRange,
            throughWallsRange = throughWallsRange,
        ) == null
    ) {
        return null
    }

    return entity
}

internal fun ClientModule.toggleFriend(player: Player) {
    val playerName = player.gameProfile.name

    if (FriendManager.isFriend(playerName)) {
        FriendManager.friends.remove(FriendManager.Friend(playerName, null))
        notification(
            name,
            message("removedFriend", playerName),
            NotificationEvent.Severity.INFO
        )
    } else {
        FriendManager.friends.add(FriendManager.Friend(playerName, null))
        notification(
            name,
            message("addedFriend", playerName),
            NotificationEvent.Severity.INFO
        )
    }
}

internal fun ClientModule.toggleTarget(player: Player) {
    val playerName = player.gameProfile.name

    if (ModuleTargetLock.isListedUsername(playerName)) {
        ModuleTargetLock.removeListedUsername(playerName)
        notification(
            name,
            message("removedTarget", playerName),
            NotificationEvent.Severity.INFO
        )
    } else {
        ModuleTargetLock.addListedUsername(playerName)
        notification(
            name,
            message("addedTarget", playerName),
            NotificationEvent.Severity.INFO
        )
    }
}

abstract class TickPlayerClickMode(
    private val mouseButton: Int,
    name: String,
    aliases: List<String> = emptyList(),
) : Mode(name, aliases) {

    protected abstract val raycast: PlayerClickRaycastSettings

    private var clicked = false

    protected abstract fun onPlayerClick(player: Player)

    @Suppress("unused")
    val repeatable = handler<GameTickEvent> {
        val entity = findCrosshairPlayer(
            pickUpRange = raycast.pickUpRange.toDouble(),
            throughWallsRange = raycast.throughWallsRange.toDouble(),
        ) ?: return@handler

        val buttonDown = isMouseButtonPressed(mouseButton)

        if (buttonDown && !clicked) {
            onPlayerClick(entity)
        }

        clicked = buttonDown
    }
}

abstract class MiddleClickPlayerClickMode(
    name: String,
    aliases: List<String> = emptyList(),
) : Mode(name, aliases) {

    protected abstract val raycast: PlayerClickRaycastSettings

    private var clicked = false

    protected abstract fun onPlayerClick(player: Player)

    @Suppress("unused")
    val repeatable = handler<GameTickEvent> {
        val entity = findCrosshairPlayer(
            pickUpRange = raycast.pickUpRange.toDouble(),
            throughWallsRange = raycast.throughWallsRange.toDouble(),
        ) ?: return@handler

        val buttonDown = mc.options.keyPickItem.isDown

        if (buttonDown && !clicked) {
            onPlayerClick(entity)
        }

        clicked = buttonDown
    }
}

abstract class PearlMode : Mode("Pearl") {

    protected abstract val settings: PearlThrowSettings

    private var wasPressed = false

    protected abstract fun isButtonDown(): Boolean

    @Suppress("unused")
    val repeatable = handler<GameTickEvent> {
        if (mc.gui.screen() != null) {
            wasPressed = false
            return@handler
        }

        if (player.xRot in settings.stopOnSubmit) {
            wasPressed = false
            return@handler
        }

        val buttonDown = isButtonDown()

        if (buttonDown) {
            val slot = Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL) ?: return@handler
            SilentHotbar.selectSlotSilently(this, slot, settings.slotResetDelay)
            wasPressed = true
        } else if (wasPressed) {
            Slots.OffhandWithHotbar.findSlot(Items.ENDER_PEARL)?.let {
                useHotbarSlotOrOffhand(it, settings.slotResetDelay)
            }
            wasPressed = false
        }
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        wasPressed = false
    }

    override fun disable() {
        wasPressed = false
        SilentHotbar.resetSlot(this)
    }
}

abstract class TickPearlMode(
    private val mouseButton: Int,
) : PearlMode() {

    override fun isButtonDown(): Boolean = isMouseButtonPressed(mouseButton)
}

abstract class MiddleClickPearlMode : PearlMode() {

    override fun isButtonDown(): Boolean = mc.options.keyPickItem.isDown
}

internal fun resetPearlMode(pearl: PearlMode) {
    SilentHotbar.resetSlot(pearl)
    pearl.disable()
}
