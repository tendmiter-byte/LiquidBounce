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

import net.ccbluex.fastutil.objectDoubleHashMapOf
import net.ccbluex.fastutil.objectDoubleMapOf
import net.ccbluex.liquidbounce.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GraphSearchTest {

    @Test
    fun `finds optimal path in weighted graph`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 2.0), WeightedEdge("B", 1.0)),
            "A" to listOf(WeightedEdge("G", 2.0)),
            "B" to listOf(WeightedEdge("C", 1.0)),
            "C" to listOf(WeightedEdge("G", 10.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "A", "G"), path.nodes)
        assertEquals(4.0, path.totalCost)
    }

    @Test
    fun `returns null when goal cannot be reached`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 1.0)),
            "A" to listOf(WeightedEdge("B", 1.0)),
            "B" to emptyList(),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
        )

        assertNull(result)
    }

    @Test
    fun `returns start-only path when start already satisfies goal`() {
        val result = aStarShortestPath(
            start = 42,
            isGoal = { it == 42 },
            neighbors = { emptyList() },
            heuristic = objectDoubleMapOf(),
        )

        val path = assertNotNull(result)
        assertEquals(listOf(42), path.nodes)
        assertEquals(0.0, path.totalCost)
    }

    @Test
    fun `respects maxIterations and can stop early`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 1.0)),
            "A" to listOf(WeightedEdge("B", 1.0)),
            "B" to listOf(WeightedEdge("C", 1.0)),
            "C" to listOf(WeightedEdge("G", 1.0)),
        )

        val limited = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxIterations = 3,
        )
        assertNull(limited)

        val full = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxIterations = 10,
        )
        assertEquals(listOf("S", "A", "B", "C", "G"), assertNotNull(full).nodes)
    }

    @Test
    fun `respects maxCost and prunes expensive routes`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 2.0)),
            "A" to listOf(WeightedEdge("G", 2.0)),
        )

        val tooLow = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxCost = 3.0,
        )
        assertNull(tooLow)

        val exact = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxCost = 4.0,
        )
        val path = assertNotNull(exact)
        assertEquals(listOf("S", "A", "G"), path.nodes)
        assertEquals(4.0, path.totalCost)
    }

    @Test
    fun `finds improved route when better path to known node appears later`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 10.0), WeightedEdge("B", 1.0)),
            "B" to listOf(WeightedEdge("A", 1.0)),
            "A" to listOf(WeightedEdge("G", 1.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "B", "A", "G"), path.nodes)
        assertEquals(3.0, path.totalCost)
    }

    @Test
    fun `works with admissible heuristic`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 1.0), WeightedEdge("B", 4.0)),
            "A" to listOf(WeightedEdge("C", 1.0)),
            "B" to listOf(WeightedEdge("C", 1.0)),
            "C" to listOf(WeightedEdge("G", 1.0)),
        )
        val heuristic = objectDoubleHashMapOf(
            "S", 3.0,
            "A", 2.0,
            "B", 1.0,
            "C", 1.0,
            "G", 0.0,
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = heuristic,
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "A", "C", "G"), path.nodes)
        assertEquals(3.0, path.totalCost)
    }

    @Test
    fun `handles cycles without infinite looping`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 1.0)),
            "A" to listOf(WeightedEdge("B", 1.0)),
            "B" to listOf(WeightedEdge("A", 1.0), WeightedEdge("G", 1.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxIterations = 50,
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "A", "B", "G"), path.nodes)
        assertEquals(3.0, path.totalCost)
    }

    @Test
    fun `selects cheapest among duplicate neighbor edges`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 5.0), WeightedEdge("A", 1.0)),
            "A" to listOf(WeightedEdge("G", 1.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "A", "G"), path.nodes)
        assertEquals(2.0, path.totalCost)
    }

    @Test
    fun `supports zero-cost edges and exact zero maxCost`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 0.0)),
            "A" to listOf(WeightedEdge("G", 0.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxCost = 0.0,
        )

        val path = assertNotNull(result)
        assertEquals(listOf("S", "A", "G"), path.nodes)
        assertEquals(0.0, path.totalCost)
    }

    @Test
    fun `dijkstra wrapper remains equivalent to zero-heuristic a-star`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("A", 2.0), WeightedEdge("B", 1.0)),
            "A" to listOf(WeightedEdge("G", 2.0)),
            "B" to listOf(WeightedEdge("G", 10.0)),
        )

        val viaDijkstra = dijkstraShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            maxIterations = 100,
            maxCost = 10.0,
        )

        val viaAStar = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxIterations = 100,
            maxCost = 10.0,
        )

        val nonNullAStar = assertNotNull(viaAStar)
        val nonNullDijkstra = assertNotNull(viaDijkstra)

        assertEquals(nonNullAStar.nodes, nonNullDijkstra.nodes)
        assertEquals(nonNullAStar.totalCost, nonNullDijkstra.totalCost)
    }

    @Test
    fun `negative maxCost yields no path when goal is not start`() {
        val graph = mapOf(
            "S" to listOf(WeightedEdge("G", 0.0)),
        )

        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "G" },
            neighbors = { graph[it].orEmpty() },
            heuristic = objectDoubleMapOf(),
            maxCost = -0.1,
        )

        assertNull(result)
    }

    @Test
    fun `negative maxCost rejects even immediate start-goal match`() {
        val result = aStarShortestPath(
            start = "S",
            isGoal = { it == "S" },
            neighbors = { emptyList() },
            heuristic = objectDoubleMapOf(),
            maxCost = -0.1,
        )

        assertNull(result)
    }

    @Test
    fun `grid search routes around short wall`() {
        val start = GridCell(1, 0)
        val goal = GridCell(1, 2)
        val blocked = setOf(GridCell(0, 1), GridCell(1, 1), GridCell(2, 1))

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.walkableNeighbors(blocked) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(start, path.nodes.first())
        assertEquals(goal, path.nodes.last())
        assertNull(path.nodes.firstOrNull { it in blocked })
        assertEquals(7, path.nodes.size)
    }

    @Test
    fun `grid search returns null when wall blocks every route`() {
        val start = GridCell(1, 0)
        val goal = GridCell(1, 2)
        val blocked = (-2..4).mapTo(hashSetOf()) { x -> GridCell(x, 1) }

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.walkableNeighbors(blocked) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertNull(result)
    }

    @Test
    fun `grid search chooses reachable nearest goal among multiple goals`() {
        val start = GridCell(1, 0)
        val goals = setOf(GridCell(4, 2), GridCell(1, 2))

        val result = aStarShortestPath(
            start = start,
            isGoal = { it in goals },
            neighbors = { cell -> cell.walkableNeighbors(blocked = emptySet()) },
            heuristic = { cell -> goals.minOf { goal -> cell.manhattanDistance(goal).toDouble() } },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(start, path.nodes.first())
        assertEquals(GridCell(1, 2), path.nodes.last())
    }

    @Test
    fun `grid search returns null once when no goals are reachable`() {
        val start = GridCell(1, 0)
        val goals = setOf(GridCell(1, 2), GridCell(2, 2))
        val blocked = (-2..4).mapTo(hashSetOf()) { x -> GridCell(x, 1) }

        val result = aStarShortestPath(
            start = start,
            isGoal = { it in goals },
            neighbors = { cell -> cell.walkableNeighbors(blocked) },
            heuristic = { cell -> goals.minOf { goal -> cell.manhattanDistance(goal).toDouble() } },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertNull(result)
    }

    @Test
    fun `single goal grid search still reaches requested goal`() {
        val start = GridCell(1, 0)
        val goal = GridCell(3, 2)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.walkableNeighbors(blocked = emptySet()) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertEquals(goal, assertNotNull(result).nodes.last())
    }

    @Test
    fun `grid search reaches elevated target through climbable column`() {
        val start = ClimbGridCell(0, 0, 0)
        val goal = ClimbGridCell(2, 3, 0)
        val standable = setOf(start, goal)
        val climbable = setOf(
            ClimbGridCell(1, 0, 0),
            ClimbGridCell(1, 1, 0),
            ClimbGridCell(1, 2, 0),
            ClimbGridCell(1, 3, 0),
        )

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.climbableNeighbors(standable, climbable) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(start, path.nodes.first())
        assertEquals(goal, path.nodes.last())
        assertEquals(
            listOf(
                ClimbGridCell(1, 0, 0),
                ClimbGridCell(1, 1, 0),
                ClimbGridCell(1, 2, 0),
                ClimbGridCell(1, 3, 0),
            ),
            path.nodes.filter { it in climbable }
        )
    }

    @Test
    fun `grid search cannot reach elevated target without climbable column`() {
        val start = ClimbGridCell(0, 0, 0)
        val goal = ClimbGridCell(2, 3, 0)
        val standable = setOf(start, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.climbableNeighbors(standable, climbable = emptySet()) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertNull(result)
    }

    @Test
    fun `grid search reaches elevated target through one block step ups`() {
        val start = StepGridCell(0, 0, 0)
        val goal = StepGridCell(3, 3, 0)
        val standable = setOf(
            start,
            StepGridCell(1, 1, 0),
            StepGridCell(2, 2, 0),
            goal,
        )

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.stepNeighbors(standable) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(listOf(0, 1, 2, 3), path.nodes.map { it.y })
        assertEquals(goal, path.nodes.last())
    }

    @Test
    fun `grid search rejects two block cliff without intermediate step`() {
        val start = StepGridCell(0, 0, 0)
        val goal = StepGridCell(1, 2, 0)
        val standable = setOf(start, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.stepNeighbors(standable) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertNull(result)
    }

    @Test
    fun `parkour disabled does not route across one missing block`() {
        val start = ParkourGridCell(0, 0, 0)
        val goal = ParkourGridCell(2, 0, 0)
        val standable = setOf(start, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.parkourNeighbors(standable, parkourEnabled = false) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        assertNull(result)
    }

    @Test
    fun `parkour enabled routes across one missing cardinal block`() {
        val start = ParkourGridCell(0, 0, 0)
        val goal = ParkourGridCell(2, 1, 0)
        val standable = setOf(start, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.parkourNeighbors(standable, parkourEnabled = true) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(listOf(start, goal), path.nodes)
    }

    @Test
    fun `parkour enabled routes through multi step raised missing-block course`() {
        val start = ParkourGridCell(0, 0, 0)
        val firstLanding = ParkourGridCell(2, 1, 0)
        val secondLanding = ParkourGridCell(4, 2, 0)
        val goal = ParkourGridCell(6, 3, 0)
        val standable = setOf(start, firstLanding, secondLanding, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.parkourNeighbors(standable, parkourEnabled = true) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 40.0,
        )

        val path = assertNotNull(result)
        assertEquals(listOf(start, firstLanding, secondLanding, goal), path.nodes)
    }

    @Test
    fun `parkour enabled can jump over lower walkable ground to raised landing`() {
        val start = ParkourGridCell(0, 0, 0)
        val lowerGround = ParkourGridCell(1, 0, 0)
        val goal = ParkourGridCell(2, 1, 0)
        val standable = setOf(start, lowerGround, goal)

        val result = aStarShortestPath(
            start = start,
            isGoal = { it == goal },
            neighbors = { cell -> cell.parkourNeighbors(standable, parkourEnabled = true) },
            heuristic = { cell -> cell.manhattanDistance(goal).toDouble() },
            maxIterations = 100,
            maxCost = 20.0,
        )

        val path = assertNotNull(result)
        assertEquals(listOf(start, goal), path.nodes)
    }

    @Test
    fun `parkour rejects long diagonal high hazardous and blocked landings`() {
        val start = ParkourGridCell(0, 0, 0)
        val standable = setOf(
            start,
            ParkourGridCell(3, 0, 0),
            ParkourGridCell(2, 0, 2),
            ParkourGridCell(2, 2, 0),
            ParkourGridCell(2, 0, 0),
            ParkourGridCell(0, 0, 2),
        )
        val hazardous = setOf(ParkourGridCell(2, 0, 0))
        val blockedHeadroom = setOf(ParkourGridCell(0, 0, 2))

        val neighbors = start.parkourNeighbors(
            standable = standable,
            parkourEnabled = true,
            hazardous = hazardous,
            blockedHeadroom = blockedHeadroom,
        )

        assertEquals(emptyList<WeightedEdge<ParkourGridCell>>(), neighbors)
    }

    @Test
    fun `parkour rejects blocked transit body space`() {
        val start = ParkourGridCell(0, 0, 0)
        val goal = ParkourGridCell(2, 0, 0)
        val standable = setOf(start, goal)

        val neighbors = start.parkourNeighbors(
            standable = standable,
            parkourEnabled = true,
            blockedTransit = setOf(ParkourGridCell(1, 0, 0)),
        )

        assertEquals(emptyList<WeightedEdge<ParkourGridCell>>(), neighbors)
    }

    private data class GridCell(val x: Int, val z: Int) {

        fun manhattanDistance(other: GridCell): Int {
            return abs(x - other.x) + abs(z - other.z)
        }

        fun walkableNeighbors(blocked: Set<GridCell>): List<WeightedEdge<GridCell>> {
            return listOf(
                GridCell(x - 1, z),
                GridCell(x + 1, z),
                GridCell(x, z - 1),
                GridCell(x, z + 1),
            )
                .asSequence()
                .filter { it.x in -2..4 && it.z in 0..2 && it !in blocked }
                .map { WeightedEdge(it, 1.0) }
                .toList()
        }
    }

    private data class ClimbGridCell(val x: Int, val y: Int, val z: Int) {

        fun manhattanDistance(other: ClimbGridCell): Int {
            return abs(x - other.x) + abs(y - other.y) + abs(z - other.z)
        }

        fun climbableNeighbors(
            standable: Set<ClimbGridCell>,
            climbable: Set<ClimbGridCell>
        ): List<WeightedEdge<ClimbGridCell>> {
            val horizontal = listOf(
                copy(x = x - 1),
                copy(x = x + 1),
                copy(z = z - 1),
                copy(z = z + 1),
            )
                .filter { it in standable || it in climbable }
                .map { WeightedEdge(it, 1.0) }

            if (this !in climbable) {
                return horizontal
            }

            val vertical = listOf(
                copy(y = y + 1),
                copy(y = y - 1),
            )
                .filter { it in standable || it in climbable }
                .map { WeightedEdge(it, 1.2) }

            return horizontal + vertical
        }
    }

    private data class StepGridCell(val x: Int, val y: Int, val z: Int) {

        fun manhattanDistance(other: StepGridCell): Int {
            return abs(x - other.x) + abs(y - other.y) + abs(z - other.z)
        }

        fun stepNeighbors(standable: Set<StepGridCell>): List<WeightedEdge<StepGridCell>> {
            return listOf(
                copy(x = x - 1, y = y),
                copy(x = x - 1, y = y + 1),
                copy(x = x + 1, y = y),
                copy(x = x + 1, y = y + 1),
                copy(z = z - 1, y = y),
                copy(z = z - 1, y = y + 1),
                copy(z = z + 1, y = y),
                copy(z = z + 1, y = y + 1),
            )
                .filter { it in standable }
                .map { neighbor ->
                    WeightedEdge(
                        node = neighbor,
                        cost = 1.0 + if (neighbor.y > y) 0.5 else 0.0
                    )
                }
        }
    }

    private data class ParkourGridCell(val x: Int, val y: Int, val z: Int) {

        fun manhattanDistance(other: ParkourGridCell): Int {
            return abs(x - other.x) + abs(y - other.y) + abs(z - other.z)
        }

        fun parkourNeighbors(
            standable: Set<ParkourGridCell>,
            parkourEnabled: Boolean,
            hazardous: Set<ParkourGridCell> = emptySet(),
            blockedHeadroom: Set<ParkourGridCell> = emptySet(),
            blockedTransit: Set<ParkourGridCell> = emptySet(),
        ): List<WeightedEdge<ParkourGridCell>> {
            val walkNeighbors = listOf(
                copy(x = x - 1),
                copy(x = x + 1),
                copy(z = z - 1),
                copy(z = z + 1),
            )
                .filter { it in standable && it !in hazardous && it !in blockedHeadroom }
                .map { WeightedEdge(it, 1.0) }

            if (!parkourEnabled || this !in standable) {
                return walkNeighbors
            }

            val jumpNeighbors = listOf(
                copy(x = x - 2, y = y),
                copy(x = x - 2, y = y + 1),
                copy(x = x + 2, y = y),
                copy(x = x + 2, y = y + 1),
                copy(z = z - 2, y = y),
                copy(z = z - 2, y = y + 1),
                copy(z = z + 2, y = y),
                copy(z = z + 2, y = y + 1),
            )
                .filter { landing ->
                    val transit = ParkourGridCell((x + landing.x) / 2, y, (z + landing.z) / 2)
                    landing in standable &&
                        landing !in hazardous &&
                        landing !in blockedHeadroom &&
                        transit !in blockedTransit &&
                        isConservativeParkourJumpEdge(
                            previous = net.minecraft.core.BlockPos(x, y, z),
                            next = net.minecraft.core.BlockPos(landing.x, landing.y, landing.z),
                        )
                }
                .map { WeightedEdge(it, 9.0) }

            return walkNeighbors + jumpNeighbors
        }
    }
}
