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
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.math.allEmpty
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WitherRoseBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

data class BlockPath(
    val nodes: List<Vec3i>,
    val totalCost: Double,
)

data class BlockPathResult(
    val reachedGoal: Vec3i,
    val nodes: List<Vec3i>,
    val totalCost: Double,
)

enum class BlockPathNodeKind {
    WALK,
    STEP_UP,
    DROP_DOWN,
    CLIMB
}

data class BlockPathNode(
    val position: Vec3i,
    val kind: BlockPathNodeKind,
)

data class DetailedBlockPath(
    val nodes: List<Vec3i>,
    val steps: List<BlockPathNode>,
    val totalCost: Double,
)

data class DetailedBlockPathResult(
    val reachedGoal: Vec3i,
    val nodes: List<Vec3i>,
    val steps: List<BlockPathNode>,
    val totalCost: Double,
)

internal fun classifyBlockPathNodeKind(
    previous: Vec3i,
    next: Vec3i,
    isClimbable: (Vec3i) -> Boolean,
): BlockPathNodeKind {
    return when {
        isClimbable(previous) || isClimbable(next) -> BlockPathNodeKind.CLIMB
        next.y > previous.y -> BlockPathNodeKind.STEP_UP
        next.y < previous.y -> BlockPathNodeKind.DROP_DOWN
        else -> BlockPathNodeKind.WALK
    }
}

internal fun createBlockPathSteps(
    start: Vec3i,
    nodes: List<Vec3i>,
    isClimbable: (Vec3i) -> Boolean,
): List<BlockPathNode> {
    var previous = start

    return nodes.map { node ->
        BlockPathNode(
            position = node,
            kind = classifyBlockPathNodeKind(previous, node, isClimbable)
        ).also {
            previous = node
        }
    }
}

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

    val allowClimbableNavigation: Boolean get() = false

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
        val box = player.getBoundingBoxAt(Vec3(x + 0.5, y.toDouble(), z + 0.5))

        return world.getBlockCollisions(player, box).allEmpty()
    }

    private fun Vec3i.hasFloor(): Boolean {
        val floorBox = AABB(x.toDouble(), y - FLOOR_CHECK_DEPTH, z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)

        return !world.getBlockCollisions(player, floorBox).allEmpty()
    }

    private fun Vec3i.hasSafeBodyBlocks(checkFloor: Boolean): Boolean {
        val mutablePos = BlockPos.MutableBlockPos()
        if (isHazardous(mutablePos.set(x, y, z)) ||
            isHazardous(mutablePos.set(x, y + 1, z))
        ) {
            return false
        }

        if (checkFloor && isHazardous(mutablePos.set(x, y - 1, z))) {
            return false
        }

        return true
    }

    private val Vec3i.isStandable: Boolean
        get() = isBodyPassable() && hasFloor() && hasSafeBodyBlocks(checkFloor = true)

    private val Vec3i.isClimbableNode: Boolean
        get() = allowClimbableNavigation &&
            isClimbableBlock(this) &&
            isBodyPassable() &&
            hasSafeBodyBlocks(checkFloor = false)

    fun isClimbablePathNode(position: Vec3i): Boolean {
        return position.isClimbableNode
    }

    fun isClimbableBlock(position: Vec3i): Boolean {
        val pos = BlockPos.MutableBlockPos(position.x, position.y, position.z)
        val state = world.getBlockState(pos)

        if (state.`is`(BlockTags.CLIMBABLE)) {
            return true
        }

        if (state.block !is TrapDoorBlock || !state.getValue(TrapDoorBlock.OPEN)) {
            return false
        }

        val belowState = world.getBlockState(pos.below())
        return belowState.`is`(Blocks.LADDER) &&
            belowState.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING)
    }

    fun findPath(start: Vec3i, end: Vec3i, maxCost: Int): List<Vec3i> {
        return findPathResult(start, end, maxCost)?.nodes ?: emptyList()
    }

    fun findPathResult(start: Vec3i, end: Vec3i, maxCost: Int): BlockPath? {
        return findPathToAnyDetailedResult(start, listOf(end), maxCost)?.let { result ->
            BlockPath(result.nodes, result.totalCost)
        }
    }

    fun findPathToAnyResult(start: Vec3i, goals: Collection<Vec3i>, maxCost: Int): BlockPathResult? {
        return findPathToAnyDetailedResult(start, goals, maxCost)?.let { result ->
            BlockPathResult(result.reachedGoal, result.nodes, result.totalCost)
        }
    }

    fun findPathDetailedResult(start: Vec3i, end: Vec3i, maxCost: Int): DetailedBlockPath? {
        return findPathToAnyDetailedResult(start, listOf(end), maxCost)?.let { result ->
            DetailedBlockPath(result.nodes, result.steps, result.totalCost)
        }
    }

    fun findPathToAnyDetailedResult(
        start: Vec3i,
        goals: Collection<Vec3i>,
        maxCost: Int
    ): DetailedBlockPathResult? {
        val goalList = goals.distinct()
        if (goalList.isEmpty()) {
            return null
        }

        goalList.minByOrNull { it.distSqr(start) }
            ?.takeIf { it.closerThan(start, stopRange) }
            ?.let { return DetailedBlockPathResult(it, emptyList(), emptyList(), 0.0) }

        val shortestPath = aStarShortestPath(
            start = start,
            isGoal = { position -> goalList.any { goal -> position.closerThan(goal, stopRange) } },
            neighbors = ::getAdjacentEdges,
            heuristic = { position -> goalList.minOf { goal -> sqrt(position.distSqr(goal)) } },
            maxIterations = maxIterations,
            maxCost = maxCost.toDouble(),
        ) ?: return null

        val reachedNode = shortestPath.nodes.lastOrNull() ?: return null
        val reachedGoal = goalList.minByOrNull { it.distSqr(reachedNode) } ?: return null

        // Exclude start node to preserve the original API contract.
        val nodes = shortestPath.nodes.drop(1)
        return DetailedBlockPathResult(
            reachedGoal = reachedGoal,
            nodes = nodes,
            steps = createBlockPathSteps(start, nodes, ::isClimbablePathNode),
            totalCost = shortestPath.totalCost
        )
    }

    private fun getAdjacentEdges(position: Vec3i): List<WeightedEdge<Vec3i>> = buildList {
        getAdjacentNodesDirect(position)
        getAdjacentNodesClimbable(position)
        if (allowDiagonal) {
            getAdjacentNodesDiagonal(position)
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDirect(position: Vec3i) {
        for (direction in cardinalDirections) {
            val adjacentPosition = resolveNavigableNeighbor(position, direction, allowClimbable = true)
            if (adjacentPosition != null) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesClimbable(position: Vec3i) {
        if (!allowClimbableNavigation || !position.isClimbableNode) {
            return
        }

        val above = BlockPos(position.x, position.y + 1, position.z)
        if (above.isClimbableNode || above.isStandable) {
            add(WeightedEdge(above, CLIMB_UP_COST))
        }

        val below = BlockPos(position.x, position.y - 1, position.z)
        if (below.isClimbableNode || below.isStandable) {
            add(WeightedEdge(below, CLIMB_DOWN_COST))
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDiagonal(position: Vec3i) {
        val pos = BlockPos.MutableBlockPos()
        for (direction in diagonalDirections) {
            val adjacentPosition = resolveNavigableNeighbor(position, direction, allowClimbable = false)
            if (adjacentPosition != null &&
                pos.set(position.x + direction.x, adjacentPosition.y, position.z).isBodyPassable() &&
                pos.set(position.x, adjacentPosition.y, position.z + direction.z).isBodyPassable() &&
                isDiagonalStepUpAllowed(position, direction, adjacentPosition)
            ) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun resolveNavigableNeighbor(position: Vec3i, direction: Vec3i, allowClimbable: Boolean): BlockPos? {
        val pos = BlockPos.MutableBlockPos()
        for (offsetY in verticalNeighborOffsets()) {
            val adjacentPosition = pos.set(position.x + direction.x, position.y + offsetY, position.z + direction.z)
            if (adjacentPosition.isStandable || allowClimbable && adjacentPosition.isClimbableNode) {
                return adjacentPosition.immutable()
            }
        }

        return null
    }

    private fun verticalNeighborOffsets(): List<Int> = buildList {
        add(0)
        for (offset in 1..maxStepUp) {
            add(offset)
        }
        for (offset in -1 downTo -maxDropDown) {
            add(offset)
        }
    }

    private fun isDiagonalStepUpAllowed(position: Vec3i, direction: Vec3i, adjacentPosition: Vec3i): Boolean {
        if (adjacentPosition.y <= position.y) {
            return true
        }

        val xAdjacent = resolveNavigableNeighbor(position, Vec3i(direction.x, 0, 0), allowClimbable = false)
        val zAdjacent = resolveNavigableNeighbor(position, Vec3i(0, 0, direction.z), allowClimbable = false)

        return xAdjacent?.y == adjacentPosition.y && zAdjacent?.y == adjacentPosition.y
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
        private const val CLIMB_UP_COST = 1.2
        private const val CLIMB_DOWN_COST = 1.0
    }
}
