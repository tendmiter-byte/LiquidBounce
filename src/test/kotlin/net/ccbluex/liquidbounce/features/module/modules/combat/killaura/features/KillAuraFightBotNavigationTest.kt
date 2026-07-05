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
package net.ccbluex.liquidbounce.features.module.modules.combat.killaura.features

import net.ccbluex.liquidbounce.utils.block.BlockPathNode
import net.ccbluex.liquidbounce.utils.block.BlockPathNodeKind
import net.ccbluex.liquidbounce.utils.block.createBlockPathSteps
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertIterableEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KillAuraFightBotNavigationTest {

    @Test
    fun `climbable waypoint segment is not skippable`() {
        val climbable = BlockPos(0, 1, 0)
        val nodes = listOf(
            BlockPos(0, 0, 0),
            climbable,
            BlockPos(0, 2, 0),
        )

        assertTrue(
            containsClimbableWaypoint(
                nodes = nodes,
                fromIndex = 0,
                toIndex = 2,
                isClimbable = { it == climbable }
            )
        )
    }

    @Test
    fun `ordinary waypoint segment is skippable`() {
        val nodes = listOf(
            BlockPos(0, 0, 0),
            BlockPos(1, 0, 0),
            BlockPos(2, 0, 0),
        )

        assertFalse(
            containsClimbableWaypoint(
                nodes = nodes,
                fromIndex = 0,
                toIndex = 2,
                isClimbable = { false }
            )
        )
    }

    @Test
    fun `step up waypoint segment is not skippable`() {
        val steps = listOf(
            BlockPathNode(BlockPos(0, 0, 0), BlockPathNodeKind.WALK),
            BlockPathNode(BlockPos(1, 1, 0), BlockPathNodeKind.STEP_UP),
            BlockPathNode(BlockPos(2, 1, 0), BlockPathNodeKind.WALK),
        )

        assertTrue(
            containsRequiredFightBotWaypoint(
                steps = steps,
                fromIndex = 0,
                toIndex = 2,
            )
        )
    }

    @Test
    fun `flat waypoint segment remains skippable`() {
        val steps = listOf(
            BlockPathNode(BlockPos(0, 0, 0), BlockPathNodeKind.WALK),
            BlockPathNode(BlockPos(1, 0, 0), BlockPathNodeKind.WALK),
            BlockPathNode(BlockPos(2, 0, 0), BlockPathNodeKind.DROP_DOWN),
        )

        assertFalse(
            containsRequiredFightBotWaypoint(
                steps = steps,
                fromIndex = 0,
                toIndex = 2,
            )
        )
    }

    @Test
    fun `logged ascending path classifies repeated one block steps`() {
        val start = BlockPos(21, -60, -7)
        val nodes = listOf(
            BlockPos(21, -59, -8),
            BlockPos(20, -58, -8),
            BlockPos(19, -58, -8),
            BlockPos(18, -57, -9),
            BlockPos(19, -56, -10),
        )

        val steps = createBlockPathSteps(start, nodes) { false }

        assertIterableEquals(
            listOf(
                BlockPathNodeKind.STEP_UP,
                BlockPathNodeKind.STEP_UP,
                BlockPathNodeKind.WALK,
                BlockPathNodeKind.STEP_UP,
                BlockPathNodeKind.STEP_UP,
            ),
            steps.map { it.kind }
        )
        assertTrue(containsRequiredFightBotWaypoint(steps, 0, steps.lastIndex))
    }

    @Test
    fun `step up jump waits for close grounded approach`() {
        assertFalse(
            shouldJumpForFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.0,
                horizontalDistanceSq = 4.0,
                onGround = true,
                isStepUpWaypoint = true,
                jumpTicks = 0,
            )
        )

        assertTrue(
            shouldJumpForFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.0,
                horizontalDistanceSq = 1.0,
                onGround = true,
                isStepUpWaypoint = true,
                jumpTicks = 0,
            )
        )
    }

    @Test
    fun `step up jump hold is bounded`() {
        assertTrue(
            shouldJumpForFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.2,
                horizontalDistanceSq = 1.0,
                onGround = false,
                isStepUpWaypoint = true,
                jumpTicks = 1,
            )
        )

        assertFalse(
            shouldJumpForFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.2,
                horizontalDistanceSq = 1.0,
                onGround = false,
                isStepUpWaypoint = true,
                jumpTicks = 5,
            )
        )
    }

    @Test
    fun `step up waypoint does not advance before feet clear target height`() {
        assertFalse(
            hasClearedFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.8,
                waypointKind = BlockPathNodeKind.STEP_UP,
            )
        )

        assertTrue(
            hasClearedFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.96,
                waypointKind = BlockPathNodeKind.STEP_UP,
            )
        )

        assertTrue(
            hasClearedFightBotStepUpWaypoint(
                waypointY = 1.0,
                playerY = 0.0,
                waypointKind = BlockPathNodeKind.WALK,
            )
        )
    }

    @Test
    fun `step up vertical progress prevents immediate stuck replans`() {
        assertTrue(hasFightBotStepUpVerticalProgress(playerY = -59.58, bestWaypointY = -60.0))
        assertFalse(hasFightBotStepUpVerticalProgress(playerY = -59.57, bestWaypointY = -59.58))
    }

    @Test
    fun `climb ascent clears horizontal input when attached`() {
        val input = climbDirectionalInputForFightBotWaypoint(
            waypointY = 3.0,
            playerY = 2.0,
            onClimbable = true,
            isClimbableWaypoint = true
        )

        assertEquals(DirectionalInput.NONE, input)
        assertFalse(input?.isMoving == true)
    }

    @Test
    fun `climb ascent requests jump only when attached`() {
        assertTrue(
            shouldJumpForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = true,
                isClimbableWaypoint = true
            )
        )

        assertFalse(
            shouldJumpForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = false,
                isClimbableWaypoint = true
            )
        )
    }

    @Test
    fun `off ladder climb waypoint recovers without climb input or jump`() {
        assertNull(
            climbDirectionalInputForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = false,
                isClimbableWaypoint = true
            )
        )
        assertFalse(
            shouldJumpForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = false,
                isClimbableWaypoint = true
            )
        )
    }

    @Test
    fun `non climb waypoint does not request climb input or jump`() {
        assertNull(
            climbDirectionalInputForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = true,
                isClimbableWaypoint = false
            )
        )
        assertFalse(
            shouldJumpForFightBotWaypoint(
                waypointY = 3.0,
                playerY = 2.0,
                onClimbable = true,
                isClimbableWaypoint = false
            )
        )
    }

    @Test
    fun `unreachable cooldown backs off to max`() {
        val targetBlock = BlockPos(20, -56, -15)
        val playerBlock = BlockPos(26, -60, -13)

        val first = nextFightBotUnreachableRoute(
            previous = null,
            targetId = 1,
            targetBlock = targetBlock,
            playerBlock = playerBlock,
            currentTick = 100,
        )
        assertEquals(1, first.failures)
        assertEquals(140, first.retryTick)

        val second = nextFightBotUnreachableRoute(first, 1, targetBlock, playerBlock, currentTick = 140)
        assertEquals(2, second.failures)
        assertEquals(220, second.retryTick)

        val third = nextFightBotUnreachableRoute(second, 1, targetBlock, playerBlock, currentTick = 220)
        assertEquals(3, third.failures)
        assertEquals(380, third.retryTick)

        val fourth = nextFightBotUnreachableRoute(third, 1, targetBlock, playerBlock, currentTick = 380)
        assertEquals(4, fourth.failures)
        assertEquals(540, fourth.retryTick)
    }

    @Test
    fun `cached unreachable target cools down without retry`() {
        val targetBlock = BlockPos(20, -56, -15)
        val playerBlock = BlockPos(26, -60, -13)
        val route = FightBotUnreachableRoute(
            targetId = 1,
            targetBlock = targetBlock,
            playerYBand = fightBotUnreachableYBand(playerBlock),
            originBlock = playerBlock,
            failures = 2,
            retryTick = 220,
        )

        assertTrue(
            isFightBotUnreachableRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                currentTick = 219,
            )
        )
        assertFalse(
            isFightBotUnreachableRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                currentTick = 220,
            )
        )
    }

    @Test
    fun `unreachable cache invalidates when target height band or origin changes`() {
        val targetBlock = BlockPos(20, -56, -15)
        val playerBlock = BlockPos(26, -60, -13)
        val route = FightBotUnreachableRoute(
            targetId = 1,
            targetBlock = targetBlock,
            playerYBand = fightBotUnreachableYBand(playerBlock),
            originBlock = playerBlock,
            failures = 1,
            retryTick = 140,
        )

        assertFalse(shouldInvalidateFightBotUnreachableRoute(route, 1, targetBlock, BlockPos(28, -60, -10)))
        assertTrue(shouldInvalidateFightBotUnreachableRoute(route, 1, BlockPos(20, -55, -15), playerBlock))
        assertTrue(shouldInvalidateFightBotUnreachableRoute(route, 1, targetBlock, BlockPos(26, -56, -13)))
        assertTrue(shouldInvalidateFightBotUnreachableRoute(route, 1, targetBlock, BlockPos(40, -60, -13)))
    }
}
