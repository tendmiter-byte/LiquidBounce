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
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.RotationUpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.AimbotArtificialBoundingBox
import net.ccbluex.liquidbounce.features.module.modules.combat.aimbot.AimbotRequirements
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug.debugGeometry
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.aiming.data.RotationWithVector
import net.ccbluex.liquidbounce.utils.aiming.features.MovementCorrection
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.InterpolationAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.LinearAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.features.processors.anglesmooth.impl.SigmoidAngleSmooth
import net.ccbluex.liquidbounce.utils.aiming.point.PointTracker
import net.ccbluex.liquidbounce.utils.aiming.preference.LeastDifferencePreference
import net.ccbluex.liquidbounce.utils.aiming.utils.RotationUtil
import net.ccbluex.liquidbounce.utils.aiming.utils.raytraceBox
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.collection.itemSortedSetOf
import net.ccbluex.liquidbounce.utils.combat.TargetPriority
import net.ccbluex.liquidbounce.utils.combat.TargetTracker
import net.ccbluex.liquidbounce.utils.entity.PositionExtrapolation
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.entity.interpolateCurrentPosition
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.inventory.InventoryManager
import net.ccbluex.liquidbounce.utils.kotlin.Priority
import net.ccbluex.liquidbounce.utils.math.firstHit
import net.ccbluex.liquidbounce.utils.render.TargetRenderer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB

/**
 * Aimbot module
 *
 * Automatically faces selected entities around you.
 */
object ModuleAimbot : ClientModule("Aimbot", ModuleCategories.COMBAT, aliases = listOf("AimAssist", "AutoAim")) {

    private val activationMode by enumChoice("ActivationMode", ActivationMode.FOV)
    private val axis by multiEnumChoice<Axis>("Axis", Axis.HORIZONTAL, Axis.VERTICAL)

    private val range = float("Range", 4.2f, 1f..10f)

    val targetTracker = tree(
        TargetTracker(
            defaultPriority = TargetPriority.DIRECTION,
            range = range,
            defaultFov = 45f,
        )
    )

    private val artificialBoundingBox = tree(
        AimbotArtificialBoundingBox(
            owner = this,
            targets = targetTracker::targets,
            shouldDraw = { activationMode == ActivationMode.ARTIFICIAL_BOUNDING_BOX },
            getActivationBox = { entity, partialTicks ->
                applyArtificialExpansion(getRenderAimBox(entity, partialTicks))
            },
        )
    )

    private val pointTracker = tree(PointTracker(this))

    private val requires by multiEnumChoice<AimbotRequirements>("Requires")
    private val attackItems by items("Items", itemSortedSetOf())

    private val requirementsMet
        get() = (IgnoreOpened.SCREEN in ignores || mc.gui.screen() == null) &&
            requires.all { it.asBoolean } &&
            isAllowedAttackItem(player.mainHandItem)

    private val movementCorrection by enumChoice("MovementCorrection", MovementCorrection.SILENT)

    private var angleSmooth = modes(this, "AngleSmooth") {
        arrayOf(
            InterpolationAngleSmooth(it),
            SigmoidAngleSmooth(it),
            LinearAngleSmooth(it)
        )
    }

    private val ignores by multiEnumChoice<IgnoreOpened>("Ignore")

    init {
        tree(TargetRenderer(this, targetTracker))
    }

    @Suppress("unused", "ComplexCondition")
    private val tickHandler = handler<RotationUpdateEvent> { _ ->
        if (!requirementsMet) {
            targetTracker.reset()
            return@handler
        }

        val nextTargetRotation = findNextTargetRotation()

        nextTargetRotation?.let { (target, rotation) ->
            val currentRotation = player.rotation
            val filteredRotation = Rotation(
                yaw = if (Axis.HORIZONTAL in axis) rotation.rotation.yaw else currentRotation.yaw,
                pitch = if (Axis.VERTICAL in axis) rotation.rotation.pitch else currentRotation.pitch,
            )

            RotationManager.setRotationTarget(
                RotationTarget(
                    rotation = filteredRotation,
                    entity = target,
                    processors = listOf(angleSmooth.activeMode),
                    ticksUntilReset = 1,
                    resetThreshold = 1f,
                    considerInventory = IgnoreOpened.CONTAINER !in ignores,
                    movementCorrection = movementCorrection
                ),
                Priority.IMPORTANT_FOR_USAGE_1,
                this
            )
        }

        // Update Auto Weapon
        ModuleAutoWeapon.onTarget(targetTracker.target)
    }

    override fun onDisabled() {
        targetTracker.reset()
    }



    private fun findNextTargetRotation(): Pair<Entity, RotationWithVector>? {
        for (entity in targetTracker.targets()) {
            val eyes = player.eyePosition
            val point = pointTracker.findPoint(eyes, entity)

            if (!shouldActivateOn(point.box)) {
                continue
            }

            debugGeometry("Box") { ModuleDebug.DebuggedBox(point.box, Color4b.ORANGE.with(a = 90)) }
            debugGeometry("Point") { ModuleDebug.DebuggedPoint(point.pos, Color4b.WHITE, size = 0.1) }

            val rotationPreference = LeastDifferencePreference.leastDifferenceToLastPoint(eyes, point.pos)
            val rotation = raytraceBox(
                eyes = eyes,
                box = point.box,
                range = targetTracker.maxRange.toDouble(),
                wallsRange = 0.0,
                rotationPreference = rotationPreference
            ) ?: continue

            targetTracker.target = entity
            return entity to rotation
        }

        targetTracker.reset()
        return null
    }

    private fun shouldActivateOn(aimBox: AABB): Boolean {
        return when (activationMode) {
            ActivationMode.ARTIFICIAL_BOUNDING_BOX -> isClientAimingAt(applyArtificialExpansion(aimBox))

            ActivationMode.FOV -> {
                val rotationToEntity = Rotation.lookingAt(aimBox.center, player.eyePosition)
                targetTracker.isAngleWithinFov(player.rotation.angleTo(rotationToEntity))
            }
        }
    }

    private fun applyArtificialExpansion(box: AABB): AABB {
        val expansion = artificialBoundingBox.size.toDouble()

        return when {
            Axis.HORIZONTAL in axis && Axis.VERTICAL in axis -> box.inflate(expansion)
            Axis.HORIZONTAL in axis -> box.inflate(expansion, 0.0, expansion)
            Axis.VERTICAL in axis -> box.inflate(0.0, expansion, 0.0)
            else -> box
        }
    }

    private fun getRenderAimBox(entity: Entity, partialTicks: Float): AABB {
        val extrapolatedPos = PositionExtrapolation.getBestForEntity(entity).getPositionInTicks(0.0)
        val interpolationOffset = entity.interpolateCurrentPosition(partialTicks)
            .subtract(entity.position())

        return entity.getBoundingBoxAt(extrapolatedPos.add(interpolationOffset))
            .inflate(entity.pickRadius.toDouble())
    }

    private fun isClientAimingAt(box: AABB): Boolean {
        val eyes = player.eyePosition
        val direction = player.rotation.directionVector
        val end = eyes.add(direction.scale(targetTracker.maxRange.toDouble()))

        return box.firstHit(eyes, end) != null
    }

    internal fun isAllowedAttackItem(itemStack: ItemStack): Boolean {
        if (itemStack.isEmpty && AimbotRequirements.EMPTY_HAND in requires) {
            return true
        }

        return attackItems.isEmpty() || itemStack.item in attackItems
    }

    private enum class IgnoreOpened(
        override val tag: String
    ) : Tagged {
        SCREEN("Screen"),
        CONTAINER("Container")
    }

    private enum class Axis(override val tag: String) : Tagged {
        HORIZONTAL("Horizontal"),
        VERTICAL("Vertical")
    }

    private enum class ActivationMode(override val tag: String) : Tagged {
        ARTIFICIAL_BOUNDING_BOX("ArtificialBoundingBox"),
        FOV("FOV")
    }
}
