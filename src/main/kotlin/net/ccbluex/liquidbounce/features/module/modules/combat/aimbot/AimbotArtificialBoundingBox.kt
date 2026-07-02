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
package net.ccbluex.liquidbounce.features.module.modules.combat.aimbot

import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.render.drawBox
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.render.renderEnvironment
import net.ccbluex.liquidbounce.render.withPositionRelativeToCamera
import net.ccbluex.liquidbounce.utils.math.worldToLocal
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB

/**
 * Artificial bounding box settings and visualization for [net.ccbluex.liquidbounce.features.module.modules.combat.ModuleAimbot].
 */
class AimbotArtificialBoundingBox(
    private val owner: EventListener,
    private val targets: () -> Iterable<Entity>,
    private val shouldDraw: () -> Boolean,
    private val getActivationBox: (Entity, Float) -> AABB,
) : ValueGroup("ArtificialBoundingBox") {

    val size by float("Size", 0.3f, 0f..1f, aliases = listOf("ArtificialBoundingBox"))

    init {
        tree(Draw())
    }

    private inner class Draw : ToggleableValueGroup(owner, "Draw", false) {

        private val color by color("Color", Color4b.CYAN.with(a = 50))
        private val outlineColor by color("OutlineColor", Color4b.CYAN.with(a = 150))

        private val renderHandler = handler<WorldRenderEvent> { event ->
            if (!enabled || !shouldDraw()) {
                return@handler
            }

            event.renderEnvironment {
                val partialTicks = event.partialTicks

                for (entity in targets()) {
                    val activationBox = getActivationBox(entity, partialTicks)
                    val (origin, localBox) = activationBox.worldToLocal()

                    withPositionRelativeToCamera(origin) {
                        drawBox(
                            localBox,
                            color,
                            outlineColor,
                        )
                    }
                }
            }
        }
    }
}
