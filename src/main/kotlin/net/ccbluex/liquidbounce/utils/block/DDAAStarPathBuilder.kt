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

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap
import net.ccbluex.liquidbounce.utils.client.player
import net.ccbluex.liquidbounce.utils.client.world
import net.ccbluex.liquidbounce.utils.entity.getBoundingBoxAt
import net.ccbluex.liquidbounce.utils.math.allEmpty
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.BaseFireBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CactusBlock
import net.minecraft.world.level.block.CampfireBlock
import net.minecraft.world.level.block.LadderBlock
import net.minecraft.world.level.block.MagmaBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.WitherRoseBlock
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

// Thread-local caches to store results of expensive collision and state checks during a search.
private val standableCache = ThreadLocal.withInitial { Long2BooleanOpenHashMap() }
private val climbableCache = ThreadLocal.withInitial { Long2BooleanOpenHashMap() }
private val bodyPassableCache = ThreadLocal.withInitial { Long2BooleanOpenHashMap() }
private val floorCache = ThreadLocal.withInitial { Long2BooleanOpenHashMap() }
private val hazardCache = ThreadLocal.withInitial { Long2BooleanOpenHashMap() }

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
    CLIMB,
    PARKOUR_JUMP
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

@JvmRecord
data class DetailedBlockPathSearchResult(
    val path: DetailedBlockPathResult?,
    val stats: PathSearchStats,
    val bounds: BlockPathSearchBounds?
)

data class BlockPathSearchBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    fun contains(position: Vec3i): Boolean {
        return position.x in minX..maxX &&
            position.y in minY..maxY &&
            position.z in minZ..maxZ
    }

    companion object {
        fun around(
            positions: Collection<Vec3i>,
            horizontalPadding: Int,
            maxStepUp: Int,
            maxDropDown: Int,
        ): BlockPathSearchBounds? {
            if (positions.isEmpty()) {
                return null
            }

            return BlockPathSearchBounds(
                minX = positions.minOf { it.x } - horizontalPadding,
                maxX = positions.maxOf { it.x } + horizontalPadding,
                minY = positions.minOf { it.y } - maxDropDown - 1,
                maxY = positions.maxOf { it.y } + maxStepUp + 2,
                minZ = positions.minOf { it.z } - horizontalPadding,
                maxZ = positions.maxOf { it.z } + horizontalPadding,
            )
        }
    }
}

internal fun classifyBlockPathNodeKind(
    previous: Vec3i,
    next: Vec3i,
    isClimbable: (Vec3i) -> Boolean,
    isParkourJump: (Vec3i, Vec3i) -> Boolean = { _, _ -> false },
): BlockPathNodeKind {
    return when {
        isClimbable(previous) || isClimbable(next) -> BlockPathNodeKind.CLIMB
        isParkourJump(previous, next) -> BlockPathNodeKind.PARKOUR_JUMP
        next.y > previous.y -> BlockPathNodeKind.STEP_UP
        next.y < previous.y -> BlockPathNodeKind.DROP_DOWN
        else -> BlockPathNodeKind.WALK
    }
}

internal fun createBlockPathSteps(
    start: Vec3i,
    nodes: List<Vec3i>,
    isClimbable: (Vec3i) -> Boolean,
    isParkourJump: (Vec3i, Vec3i) -> Boolean = { _, _ -> false },
): List<BlockPathNode> {
    var previous = start

    return nodes.map { node ->
        BlockPathNode(
            position = node,
            kind = classifyBlockPathNodeKind(previous, node, isClimbable, isParkourJump)
        ).also {
            previous = node
        }
    }
}

internal fun isConservativeParkourJumpEdge(previous: Vec3i, next: Vec3i, maxStepUp: Int = 1): Boolean {
    val dx = abs(next.x - previous.x)
    val dz = abs(next.z - previous.z)
    val dy = next.y - previous.y

    return dy in 0..maxStepUp && (dx == PARKOUR_JUMP_BLOCK_DISTANCE && dz == 0 ||
        dx == 0 && dz == PARKOUR_JUMP_BLOCK_DISTANCE)
}

private val cardinalDirections = arrayOf(
    Vec3i(0, 0, -1), // front
    Vec3i(0, 0, 1), // back
    Vec3i(-1, 0, 0), // left
    Vec3i(1, 0, 0) // right
)

private val diagonalDirections = arrayOf(
    Vec3i(-1, 0, -1), // left front
    Vec3i(1, 0, -1), // right front
    Vec3i(-1, 0, 1), // left back
    Vec3i(1, 0, 1) // right back
)

interface DDAAStarPathBuilder {

    val allowDiagonal: Boolean

    val allowClimbableNavigation: Boolean get() = false

    val allowParkourNavigation: Boolean get() = false

    val allowParkourSprint: Boolean get() = false

    val maxIterations: Int get() = 500

    val stopRange: Double get() = 2.0

    val maxStepUp: Int get() = 1

    val maxDropDown: Int get() = 3

    private fun isHazardous(pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val block = state.block

        val fluid = world.getFluidState(pos)
        if (fluid.`is`(Fluids.LAVA) ||
            fluid.`is`(Fluids.FLOWING_LAVA)
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

    private fun isHazardousCached(pos: BlockPos): Boolean {
        val packed = pos.asLong()
        val cache = hazardCache.get()
        if (cache.containsKey(packed)) {
            return cache.get(packed)
        }
        val result = isHazardous(pos)
        cache.put(packed, result)
        return result
    }

    private fun hasFluid(pos: BlockPos): Boolean {
        return !world.getFluidState(pos).isEmpty
    }

    private fun Vec3i.isBodyPassable(): Boolean {
        val box = player.getBoundingBoxAt(Vec3(x + 0.5, y.toDouble(), z + 0.5))

        return world.getBlockCollisions(player, box).allEmpty()
    }

    private fun Vec3i.isBodyPassableCached(): Boolean {
        val packed = BlockPos.asLong(x, y, z)
        val cache = bodyPassableCache.get()
        if (cache.containsKey(packed)) {
            return cache.get(packed)
        }
        val result = isBodyPassable()
        cache.put(packed, result)
        return result
    }

    private fun Vec3i.hasFloor(): Boolean {
        val floorBox = AABB(x.toDouble(), y - FLOOR_CHECK_DEPTH, z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)

        return !world.getBlockCollisions(player, floorBox).allEmpty()
    }

    private fun Vec3i.hasFloorCached(): Boolean {
        val packed = BlockPos.asLong(x, y, z)
        val cache = floorCache.get()
        if (cache.containsKey(packed)) {
            return cache.get(packed)
        }
        val result = hasFloor()
        cache.put(packed, result)
        return result
    }

    private fun Vec3i.hasSafeBodyBlocks(checkFloor: Boolean): Boolean {
        val mutablePos = BlockPos.MutableBlockPos()
        if (isHazardousCached(mutablePos.set(x, y, z)) ||
            isHazardousCached(mutablePos.set(x, y + 1, z))
        ) {
            return false
        }

        if (checkFloor && isHazardousCached(mutablePos.set(x, y - 1, z))) {
            return false
        }

        return true
    }

    private fun Vec3i.hasParkourSafeBodyBlocks(): Boolean {
        val mutablePos = BlockPos.MutableBlockPos()
        return hasSafeBodyBlocks(checkFloor = true) &&
            !hasFluid(mutablePos.set(x, y, z)) &&
            !hasFluid(mutablePos.set(x, y + 1, z)) &&
            !hasFluid(mutablePos.set(x, y - 1, z))
    }

    private val Vec3i.isStandable: Boolean
        get() {
            val packed = BlockPos.asLong(x, y, z)
            val cache = standableCache.get()
            if (cache.containsKey(packed)) {
                return cache.get(packed)
            }
            val result = isBodyPassableCached() && hasFloorCached() && hasSafeBodyBlocks(checkFloor = true)
            cache.put(packed, result)
            return result
        }

    private val Vec3i.isClimbableNode: Boolean
        get() {
            val packed = BlockPos.asLong(x, y, z)
            val cache = climbableCache.get()
            if (cache.containsKey(packed)) {
                return cache.get(packed)
            }
            val result = allowClimbableNavigation &&
                isClimbableBlock(this) &&
                isBodyPassableCached() &&
                hasSafeBodyBlocks(checkFloor = false)
            cache.put(packed, result)
            return result
        }

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

    fun recordParkourEdgeCandidate(start: Vec3i, landing: Vec3i) {}

    fun recordParkourEdgeAccepted(start: Vec3i, landing: Vec3i) {}

    fun recordParkourEdgeRejected(reason: String) {}

    fun getParkourEdgeExtraCost(start: Vec3i, landing: Vec3i): Double = 0.0

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
        maxCost: Int,
        bounds: BlockPathSearchBounds? = null,
    ): DetailedBlockPathResult? {
        return findPathToAnyDetailedSearchResult(start, goals, maxCost, bounds).path
    }

    fun findPathToAnyDetailedSearchResult(
        start: Vec3i,
        goals: Collection<Vec3i>,
        maxCost: Int,
        bounds: BlockPathSearchBounds? = null,
    ): DetailedBlockPathSearchResult {
        val goalList = goals.distinct()
        if (goalList.isEmpty()) {
            return DetailedBlockPathSearchResult(
                path = null,
                stats = PathSearchStats(PathSearchExitReason.EXHAUSTED, 0, 0, 0),
                bounds = bounds
            )
        }

        goalList.minByOrNull { it.distSqr(start) }
            ?.takeIf { it.closerThan(start, stopRange) }
            ?.let {
                return DetailedBlockPathSearchResult(
                    path = DetailedBlockPathResult(it, emptyList(), emptyList(), 0.0),
                    stats = PathSearchStats(PathSearchExitReason.GOAL_REACHED, 0, 0, 1),
                    bounds = bounds
                )
            }

        // Clear thread-local search caches to ensure a fresh context for this search query.
        standableCache.get().clear()
        climbableCache.get().clear()
        bodyPassableCache.get().clear()
        floorCache.get().clear()
        hazardCache.get().clear()

        val searchResult = aStarShortestPathResult(
            start = start,
            isGoal = { position -> goalList.any { goal -> position.closerThan(goal, stopRange) } },
            neighbors = { position -> getAdjacentEdges(position, bounds) },
            heuristic = { position -> goalList.minOf { goal -> sqrt(position.distSqr(goal)) } },
            maxIterations = maxIterations,
            maxCost = maxCost.toDouble(),
        )
        val shortestPath = searchResult.path ?: return DetailedBlockPathSearchResult(
            path = null,
            stats = searchResult.stats,
            bounds = bounds
        )

        val reachedNode = shortestPath.nodes.lastOrNull() ?: return DetailedBlockPathSearchResult(
            path = null,
            stats = searchResult.stats,
            bounds = bounds
        )
        val reachedGoal = goalList.minByOrNull { it.distSqr(reachedNode) } ?: return DetailedBlockPathSearchResult(
            path = null,
            stats = searchResult.stats,
            bounds = bounds
        )

        // Exclude start node to preserve the original API contract.
        val nodes = shortestPath.nodes.drop(1)
        return DetailedBlockPathSearchResult(
            path = DetailedBlockPathResult(
                reachedGoal = reachedGoal,
                nodes = nodes,
                steps = createBlockPathSteps(start, nodes, ::isClimbablePathNode, ::isParkourJumpEdge),
                totalCost = shortestPath.totalCost
            ),
            stats = searchResult.stats,
            bounds = bounds
        )
    }

    private fun getAdjacentEdges(position: Vec3i, bounds: BlockPathSearchBounds?): List<WeightedEdge<Vec3i>> = buildList {
        getAdjacentNodesDirect(position, bounds)
        getAdjacentNodesClimbable(position, bounds)
        getAdjacentNodesParkour(position, bounds)
        if (allowDiagonal) {
            getAdjacentNodesDiagonal(position, bounds)
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDirect(
        position: Vec3i,
        bounds: BlockPathSearchBounds?
    ) {
        for (direction in cardinalDirections) {
            val adjacentPosition = resolveNavigableNeighbor(position, direction, allowClimbable = true, bounds = bounds)
            if (adjacentPosition != null) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesClimbable(
        position: Vec3i,
        bounds: BlockPathSearchBounds?
    ) {
        if (!allowClimbableNavigation || !position.isClimbableNode) {
            return
        }

        val above = BlockPos(position.x, position.y + 1, position.z)
        if (bounds.containsOrUnbounded(above) && (above.isClimbableNode || above.isStandable)) {
            add(WeightedEdge(above, CLIMB_UP_COST))
        }

        val below = BlockPos(position.x, position.y - 1, position.z)
        if (bounds.containsOrUnbounded(below) && (below.isClimbableNode || below.isStandable)) {
            add(WeightedEdge(below, CLIMB_DOWN_COST))
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesParkour(
        position: Vec3i,
        bounds: BlockPathSearchBounds?
    ) {
        if (!allowParkourNavigation || !position.isStandable || !position.hasParkourSafeBodyBlocks()) {
            return
        }

        for (direction in cardinalDirections) {
            val gap = BlockPos(
                position.x + direction.x,
                position.y,
                position.z + direction.z
            )
            if (!bounds.containsOrUnbounded(gap)) {
                continue
            }
            if (isWalkableParkourMidpoint(position, direction, bounds)) {
                recordParkourEdgeRejected("walkableMidpoint")
                continue
            }
            if (!gap.isBodyPassableCached() || !gap.hasParkourSafeBodyBlocks()) {
                recordParkourEdgeRejected("transit")
                continue
            }

            for (offsetY in 0..maxStepUp) {
                val landing = BlockPos(
                    position.x + direction.x * PARKOUR_JUMP_BLOCK_DISTANCE,
                    position.y + offsetY,
                    position.z + direction.z * PARKOUR_JUMP_BLOCK_DISTANCE
                )
                if (!bounds.containsOrUnbounded(landing)) {
                    continue
                }
                if (!landing.isStandable || !landing.hasParkourSafeBodyBlocks()) {
                    recordParkourEdgeRejected("landing")
                    continue
                }

                recordParkourEdgeCandidate(position, landing)
                recordParkourEdgeAccepted(position, landing)
                add(
                    WeightedEdge(
                        landing,
                        position.parkourCostTo(landing) + getParkourEdgeExtraCost(position, landing)
                    )
                )
            }
        }
    }

    private fun MutableList<WeightedEdge<Vec3i>>.getAdjacentNodesDiagonal(
        position: Vec3i,
        bounds: BlockPathSearchBounds?
    ) {
        val pos = BlockPos.MutableBlockPos()
        for (direction in diagonalDirections) {
            val adjacentPosition = resolveNavigableNeighbor(position, direction, allowClimbable = false, bounds = bounds)
            if (adjacentPosition != null &&
                pos.set(position.x + direction.x, adjacentPosition.y, position.z).isBodyPassableCached() &&
                pos.set(position.x, adjacentPosition.y, position.z + direction.z).isBodyPassableCached() &&
                isDiagonalStepUpAllowed(position, direction, adjacentPosition, bounds)
            ) {
                add(WeightedEdge(adjacentPosition, position.walkCostTo(adjacentPosition)))
            }
        }
    }

    private fun resolveNavigableNeighbor(
        position: Vec3i,
        direction: Vec3i,
        allowClimbable: Boolean,
        bounds: BlockPathSearchBounds?
    ): BlockPos? {
        val pos = BlockPos.MutableBlockPos()
        for (offsetY in verticalNeighborOffsets()) {
            val adjacentPosition = pos.set(position.x + direction.x, position.y + offsetY, position.z + direction.z)
            if (!bounds.containsOrUnbounded(adjacentPosition)) {
                continue
            }
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

    private fun isDiagonalStepUpAllowed(
        position: Vec3i,
        direction: Vec3i,
        adjacentPosition: Vec3i,
        bounds: BlockPathSearchBounds?
    ): Boolean {
        if (adjacentPosition.y <= position.y) {
            return true
        }

        val xAdjacent = resolveNavigableNeighbor(
            position,
            Vec3i(direction.x, 0, 0),
            allowClimbable = false,
            bounds = bounds
        )
        val zAdjacent = resolveNavigableNeighbor(
            position,
            Vec3i(0, 0, direction.z),
            allowClimbable = false,
            bounds = bounds
        )

        return xAdjacent?.y == adjacentPosition.y && zAdjacent?.y == adjacentPosition.y
    }

    private fun isWalkableParkourMidpoint(
        position: Vec3i,
        direction: Vec3i,
        bounds: BlockPathSearchBounds?
    ): Boolean {
        val pos = BlockPos.MutableBlockPos()
        for (offsetY in 0..maxStepUp) {
            val midpoint = pos.set(position.x + direction.x, position.y + offsetY, position.z + direction.z)
            if (bounds.containsOrUnbounded(midpoint) && midpoint.isStandable) {
                return true
            }
        }

        return false
    }

    private fun BlockPathSearchBounds?.containsOrUnbounded(position: Vec3i): Boolean {
        return this == null || contains(position)
    }

    private fun isParkourJumpEdge(previous: Vec3i, next: Vec3i): Boolean {
        return allowParkourNavigation && isConservativeParkourJumpEdge(previous, next, maxStepUp)
    }

    private fun Vec3i.walkCostTo(other: Vec3i): Double {
        val verticalPenalty = when {
            other.y > y -> STEP_UP_COST
            other.y < y -> DROP_DOWN_COST * (y - other.y)
            else -> 0.0
        }

        return sqrt(distSqr(other)) + verticalPenalty
    }

    private fun Vec3i.parkourCostTo(other: Vec3i): Double {
        return walkCostTo(other) + PARKOUR_JUMP_COST
    }

    companion object {
        private const val FLOOR_CHECK_DEPTH = 0.125
        private const val STEP_UP_COST = 0.5
        private const val DROP_DOWN_COST = 0.2
        private const val CLIMB_UP_COST = 1.2
        private const val CLIMB_DOWN_COST = 1.0
        private const val PARKOUR_JUMP_COST = 8.0
    }
}

private const val PARKOUR_JUMP_BLOCK_DISTANCE = 2
