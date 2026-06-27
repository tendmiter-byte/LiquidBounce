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

package net.ccbluex.liquidbounce.features.module.modules.player

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleTeams
import net.ccbluex.liquidbounce.utils.aiming.RotationManager
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.max

object ModuleMultiRaycast : ClientModule("MultiRaycast", ModuleCategories.PLAYER) {

    private val throughEntities by boolean("ThroughEntities", true)
    private val ignoreTeammates by boolean("IgnoreTeammates", true)
    private val ignoreArmorStands by boolean("IgnoreArmorStands", true)
    private val ignorePets by boolean("IgnorePets", true)

    private val throughBlocks by boolean("ThroughBlocks", false)
    private val blockFilter by enumChoice("BlockFilter", BlockFilter.NONE)

    enum class BlockFilter(override val tag: String) : Tagged {
        NONE("None"),
        TRANSPARENT("Transparent"),
        ALL("All")
    }

    fun runRaycast(
        camera: Entity,
        blockInteractionRange: Double,
        entityInteractionRange: Double,
        tickDelta: Float
    ): HitResult {
        val cameraRotation = Rotation(camera.getViewYRot(tickDelta), camera.getViewXRot(tickDelta), true)
        val rotation = (RotationManager.currentRotation ?: cameraRotation).normalize()
        val start = camera.getEyePosition(tickDelta)
        val direction = rotation.directionVector
        val maxRange = max(blockInteractionRange, entityInteractionRange)
        val end = start.add(direction.x * maxRange, direction.y * maxRange, direction.z * maxRange)

        // Clip block
        val blockHit = if (throughBlocks) {
            clipThroughBlocks(camera.level(), start, start.add(direction.x * blockInteractionRange, direction.y * blockInteractionRange, direction.z * blockInteractionRange), camera) { state ->
                shouldBypassBlock(state)
            }
        } else {
            camera.level().clip(
                ClipContext(
                    start,
                    start.add(direction.x * blockInteractionRange, direction.y * blockInteractionRange, direction.z * blockInteractionRange),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    camera
                )
            )
        }

        // Get entities along ray
        val entityHits = getEntitiesAlongRay(camera, start, end, entityInteractionRange)

        // Filter entities
        val filteredEntities = entityHits.filter { hit ->
            val entity = hit.entity
            if (throughEntities) {
                if (ignoreTeammates && entity is Player) {
                    if (net.ccbluex.liquidbounce.features.misc.FriendManager.isFriend(entity.gameProfile.name)) {
                        return@filter false
                    }
                    if (ModuleTeams.running && ModuleTeams.isInClientPlayersTeam(entity)) {
                        return@filter false
                    }
                }
                if (ignoreArmorStands && entity is ArmorStand) {
                    return@filter false
                }
                if (ignorePets && entity is OwnableEntity && entity.ownerUUID != null) {
                    return@filter false
                }
            }
            true
        }

        val closestEntityHit = filteredEntities.firstOrNull()

        if (throughBlocks) {
            if (closestEntityHit != null) {
                return closestEntityHit
            }
            return blockHit
        } else {
            if (blockHit.type != HitResult.Type.MISS) {
                val blockDist = start.distanceTo(blockHit.location)
                if (closestEntityHit != null) {
                    val entityDist = start.distanceTo(closestEntityHit.location)
                    if (entityDist < blockDist) {
                        return closestEntityHit
                    }
                }
                return blockHit
            } else {
                if (closestEntityHit != null) {
                    return closestEntityHit
                }
                return blockHit
            }
        }
    }

    private fun clipThroughBlocks(
        level: net.minecraft.world.level.Level,
        start: Vec3,
        end: Vec3,
        camera: Entity,
        shouldBypass: (BlockState) -> Boolean
    ): BlockHitResult {
        var currentStart = start
        var iterations = 0
        val dir = end.subtract(start).normalize()
        while (iterations < 10) {
            val hit = level.clip(
                ClipContext(
                    currentStart,
                    end,
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    camera
                )
            )
            if (hit.type == HitResult.Type.MISS) {
                return hit
            }
            val state = level.getBlockState(hit.blockPos)
            if (shouldBypass(state)) {
                currentStart = hit.location.add(dir.x * 0.001, dir.y * 0.001, dir.z * 0.001)
                iterations++
            } else {
                return hit
            }
        }
        return BlockHitResult.miss(end, Direction.UP, BlockPos.ZERO)
    }

    private fun shouldBypassBlock(state: BlockState): Boolean {
        if (state.isAir) return true
        return when (blockFilter) {
            BlockFilter.NONE -> false
            BlockFilter.TRANSPARENT -> !state.isSolid || !state.canOcclude()
            BlockFilter.ALL -> true
        }
    }

    private fun getEntitiesAlongRay(
        camera: Entity,
        start: Vec3,
        end: Vec3,
        range: Double
    ): List<EntityHitResult> {
        val box = camera.boundingBox.expandTowards(end.subtract(start)).inflate(1.0, 1.0, 1.0)
        val entities = camera.level().getEntities(camera, box) { entity ->
            entity != player && !entity.isSpectator && entity.isPickable
        }
        val hits = mutableListOf<EntityHitResult>()
        for (entity in entities) {
            val pickRadius = entity.pickRadius.toDouble()
            val entityBox = entity.boundingBox.inflate(pickRadius)
            val clipResult = entityBox.clip(start, end)
            if (clipResult.isPresent) {
                val hitPos = clipResult.get()
                val distSqr = start.distanceToSqr(hitPos)
                if (distSqr <= range * range) {
                    hits.add(EntityHitResult(entity, hitPos))
                }
            }
        }
        hits.sortBy { start.distanceToSqr(it.location) }
        return hits
    }

}
