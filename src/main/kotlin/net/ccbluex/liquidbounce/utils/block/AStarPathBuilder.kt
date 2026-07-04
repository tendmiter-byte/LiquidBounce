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

package net.ccbluex.liquidbounce.utils.block

import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.math.allEmpty
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.WitherRoseBlock
import net.minecraft.world.phys.AABB
import kotlin.math.sqrt

data class BlockPath(
    val nodes: List<Vec3i>,
    val totalCost: Double,
)

private val cardinalDirections = arrayOf(
    Vec3i(-1, 0, 0), // left
    Vec3i(1, 0, 0), // right
    Vec3i(0, 0, -1), // front
    Vec3i(0, 0, 1), // back
)

private val diagonalDirections = arrayOf(
    Vec3i(-1, 0, -1), // left front
    Vec3i(1, 0, -1), // right front
    Vec3i(-1, 0, 1), // left back
    Vec3i(1, 0, 1) // right back
)

interface AStarPathBuilder {

    val allowDiagonal: Boolean

    val maxIterations: Int get() = 500

    val stopRange: Double get() = 2.0

    val maxStepUp: Int get() = 1

    val maxDropDown: Int get() = 3

    private fun isHazardous(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val block = state.block

        val fluid = world.getFluidState(pos)
        if (fluid.`is`(net.minecraft.world.level.material.Fluids.LAVA) ||
            fluid.`is`(net.minecraft.world.level.material.Fluids.FLOWING_LAVA)
        ) {
            return true
        }

        return when (block) {
            is BaseFireBlock,
            is CampfireBlock,
            is CactusBlock,
            is MagmaBlock,
            is SweetBerryBushBlock,
            is WitherRoseBlock -> true
            else -> false
        }
    }

    private fun Vec3i.isBodyPassable(): Boolean {
        val box = AABB(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 2.0, z + 1.0)

        return world.getBlockCollisions(player, box).allEmpty()
    }

    private fun Vec3i.hasFloor(): Boolean {
        val floorBox = AABB(x.toDouble(), y - FLOOR_CHECK_DEPTH, z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)

        return !world.getBlockCollisions(player, floorBox).allEmpty()
    }

    private val Vec3i.isStandable: Boolean
        get() {
            if (!isBodyPassable() || !hasFloor()) {
                return false
            }

            val mutablePos = BlockPos.MutableBlockPos()
            if (isHazardous(mutablePos.set(x, y, z)) ||
                isHazardous(mutablePos.set(x, y + 1, z)) ||
                isHazardous(mutablePos.set(x, y - 1, z))
            ) {
                return false
            }

            return true
        }

    fun findPath(start: Vec3i, end: Vec3i, maxCost: Int): List<Vec3i> {
        return findPathResult(start, end, maxCost)?.nodes ?: emptyList()
    }

    fun findPathResult(start: Vec3i, end: Vec3i, maxCost: Int): BlockPath? {
        if (end.closerThan(start, stopRange)) {
            return BlockPath(emptyList(), 0.0)
        }

        val shortestPath = aStarShortestPath(
            start = start,
            isGoal = { it.closerThan(end, stopRange) },
            neighbors = ::getAdjacentEdges,
            heuristic = { sqrt(it.distSqr(end)) },
            maxIterations = maxIterations,
            maxCost = maxCost.toDouble(),
        ) ?: return null

        // Exclude start node to preserve the original API contract.
        return BlockPath(shortestPath.nodes.drop(1), shortestPath.totalCost)
    }

    private fun getAdjacentEdges(position: Vec3i): List<WeightedEdge<Vec3i>> = buildList {
        getAdjacentNodesDirect(position)
        if (allowDiagonal) {
            getAdjacentNodesDiagonal(position)
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDirect(position: Vec3i) {
        for (direction in cardinalDirections) {
            val adjacentPosition = resolveWalkableNeighbor(position, direction)
            if (adjacentPosition != null) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDiagonal(position: Vec3i) {
        val pos = BlockPos.MutableBlockPos()
        for (direction in diagonalDirections) {
            val adjacentPosition = resolveWalkableNeighbor(position, direction)
            if (adjacentPosition != null &&
                pos.set(position.x + direction.x, adjacentPosition.y, position.z).isBodyPassable() &&
                pos.set(position.x, adjacentPosition.y, position.z + direction.z).isBodyPassable()
            ) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun resolveWalkableNeighbor(position: Vec3i, direction: Vec3i): BlockPos? {
        val pos = BlockPos.MutableBlockPos()
        for (offsetY in maxStepUp downTo -maxDropDown) {
            val adjacentPosition = pos.set(position.x + direction.x, position.y + offsetY, position.z + direction.z)
            if (adjacentPosition.isStandable) {
                return adjacentPosition.immutable()
            }
        }

        return null
    }

    private fun Vec3i.walkCostTo(other: Vec3i): Double {
        val verticalPenalty = when {
            other.y > y -> STEP_UP_COST
            other.y < y -> DROP_DOWN_COST * (y - other.y)
            else -> 0.0
        }

        return sqrt(distSqr(other)) + verticalPenalty
    }

    companion object {
        private const val FLOOR_CHECK_DEPTH = 0.125
        private const val STEP_UP_COST = 0.5
        private const val DROP_DOWN_COST = 0.2
    }
}
