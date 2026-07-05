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

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import java.util.PriorityQueue
import java.util.function.Predicate
import java.util.function.ToDoubleFunction

@JvmRecord
data class WeightedEdge<T>(
    val node: T,
    val cost: Double,
)

@JvmRecord
data class ShortestPath<T>(
    val nodes: List<T>,
    val totalCost: Double,
)

enum class PathSearchExitReason {
    GOAL_REACHED,
    MAX_ITERATIONS,
    EXHAUSTED
}

@JvmRecord
data class PathSearchStats(
    val exitReason: PathSearchExitReason,
    val iterations: Int,
    val visitedNodes: Int,
    val queuePeak: Int,
)

@JvmRecord
data class PathSearchResult<T>(
    val path: ShortestPath<T>?,
    val stats: PathSearchStats,
)

@JvmRecord
private data class QueueEntry<T>(
    val node: T,
    val gScore: Double,
    val fScore: Double,
)

/**
 * Finds the shortest path using A* search.
 *
 * Set [heuristic] to zero to get Dijkstra behavior.
 */
fun <T> aStarShortestPath(
    start: T,
    isGoal: Predicate<T>,
    neighbors: (T) -> Iterable<WeightedEdge<T>>,
    heuristic: ToDoubleFunction<T>,
    maxIterations: Int = Int.MAX_VALUE,
    maxCost: Double = Double.POSITIVE_INFINITY,
): ShortestPath<T>? = aStarShortestPathResult(
    start = start,
    isGoal = isGoal,
    neighbors = neighbors,
    heuristic = heuristic,
    maxIterations = maxIterations,
    maxCost = maxCost,
).path

fun <T> aStarShortestPathResult(
    start: T,
    isGoal: Predicate<T>,
    neighbors: (T) -> Iterable<WeightedEdge<T>>,
    heuristic: ToDoubleFunction<T>,
    maxIterations: Int = Int.MAX_VALUE,
    maxCost: Double = Double.POSITIVE_INFINITY,
): PathSearchResult<T> {
    require(maxIterations > 0) { "maxIterations must be positive." }

    val gScores = Object2DoubleOpenHashMap<T>().apply {
        defaultReturnValue(Double.POSITIVE_INFINITY)
        put(start, 0.0)
    }
    val previous = Object2ObjectOpenHashMap<T, T>()

    val queue = PriorityQueue(Comparator.comparingDouble(ToDoubleFunction(QueueEntry<T>::fScore)))
    queue.add(QueueEntry(start, 0.0, heuristic.applyAsDouble(start)))

    var iterations = 0
    var visitedNodes = 0
    var queuePeak = queue.size
    while (queue.isNotEmpty()) {
        if (iterations >= maxIterations) {
            return PathSearchResult(
                path = null,
                stats = PathSearchStats(
                    exitReason = PathSearchExitReason.MAX_ITERATIONS,
                    iterations = iterations,
                    visitedNodes = visitedNodes,
                    queuePeak = queuePeak
                )
            )
        }

        iterations++
        val current = queue.poll()
        val bestCurrentG = gScores.getDouble(current.node)
        if (current.gScore > bestCurrentG || current.gScore > maxCost) {
            continue
        }

        visitedNodes++
        if (isGoal.test(current.node)) {
            return PathSearchResult(
                path = ShortestPath(reconstructPath(start, current.node, previous), current.gScore),
                stats = PathSearchStats(
                    exitReason = PathSearchExitReason.GOAL_REACHED,
                    iterations = iterations,
                    visitedNodes = visitedNodes,
                    queuePeak = queuePeak
                )
            )
        }

        for (edge in neighbors(current.node)) {
            require(edge.cost >= 0.0) { "Path search edge costs must be non-negative." }

            val candidateG = current.gScore + edge.cost
            if (candidateG > maxCost) {
                continue
            }

            val knownG = gScores.getDouble(edge.node)
            if (candidateG >= knownG) {
                continue
            }

            gScores.put(edge.node, candidateG)
            previous.put(edge.node, current.node)

            val fScore = candidateG + heuristic.applyAsDouble(edge.node)
            queue.add(QueueEntry(edge.node, candidateG, fScore))
            if (queue.size > queuePeak) {
                queuePeak = queue.size
            }
        }
    }

    return PathSearchResult(
        path = null,
        stats = PathSearchStats(
            exitReason = PathSearchExitReason.EXHAUSTED,
            iterations = iterations,
            visitedNodes = visitedNodes,
            queuePeak = queuePeak
        )
    )
}

private fun <T> reconstructPath(
    start: T,
    goal: T,
    previous: Object2ObjectOpenHashMap<T, T>,
): List<T> {
    val reversedPath = mutableListOf<T>()

    var current = goal
    reversedPath.add(current)

    while (current != start) {
        val parent = previous[current]
            ?: return emptyList()
        current = parent
        reversedPath.add(current)
    }

    reversedPath.reverse()
    return reversedPath
}
