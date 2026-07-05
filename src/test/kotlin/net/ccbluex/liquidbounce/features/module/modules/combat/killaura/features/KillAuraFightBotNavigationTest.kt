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
import net.minecraft.world.phys.Vec3
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
    fun `parkour waypoint segment is not skippable`() {
        val steps = listOf(
            BlockPathNode(BlockPos(0, 0, 0), BlockPathNodeKind.WALK),
            BlockPathNode(BlockPos(2, 0, 0), BlockPathNodeKind.PARKOUR_JUMP),
            BlockPathNode(BlockPos(3, 0, 0), BlockPathNodeKind.WALK),
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

        val steps = createBlockPathSteps(start, nodes, isClimbable = { false })

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
    fun `one missing block gap classifies as parkour jump when validated by path builder`() {
        val start = BlockPos(0, 0, 0)
        val nodes = listOf(BlockPos(2, 1, 0))

        val steps = createBlockPathSteps(
            start = start,
            nodes = nodes,
            isClimbable = { false },
            isParkourJump = { previous, next -> previous == start && next == nodes.single() },
        )

        assertEquals(BlockPathNodeKind.PARKOUR_JUMP, steps.single().kind)
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
    fun `step up waypoint does not complete while airborne beside landing block`() {
        assertFalse(
            hasLandedFightBotStepUpWaypoint(
                landingBlock = BlockPos(21, -59, -8),
                playerBlock = BlockPos(21, -60, -7),
                playerY = -59.58,
                onGround = false,
                waypointKind = BlockPathNodeKind.STEP_UP,
            )
        )
    }

    @Test
    fun `step up waypoint completes after landing on expected block`() {
        assertTrue(
            hasLandedFightBotStepUpWaypoint(
                landingBlock = BlockPos(21, -59, -8),
                playerBlock = BlockPos(21, -59, -8),
                playerY = -59.0,
                onGround = true,
                waypointKind = BlockPathNodeKind.STEP_UP,
            )
        )
    }

    @Test
    fun `ordinary waypoint does not require step up landing`() {
        assertTrue(
            hasLandedFightBotStepUpWaypoint(
                landingBlock = BlockPos(21, -59, -8),
                playerBlock = BlockPos(21, -60, -7),
                playerY = -59.58,
                onGround = false,
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
    fun `parkour jump is requested only from grounded launch edge`() {
        val launch = BlockPos(1, 0, 0)
        val landing = BlockPos(3, 0, 0)
        val launchPoint = fightBotParkourLaunchPoint(launch, landing)!!

        assertTrue(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = launch,
                playerPosition = launchPoint,
                onGround = true,
                isParkourWaypoint = true,
                airTicks = 0,
                jumpTicks = 0,
            )
        )

        assertFalse(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = launch,
                playerPosition = Vec3(1.5, 0.0, 0.5),
                onGround = true,
                isParkourWaypoint = true,
                airTicks = 0,
                jumpTicks = 0,
            )
        )
        assertFalse(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = BlockPos(0, 0, 0),
                playerPosition = Vec3(0.5, 0.0, 0.5),
                onGround = true,
                isParkourWaypoint = true,
                airTicks = 0,
                jumpTicks = 0,
            )
        )
        assertFalse(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = launch,
                playerPosition = launchPoint,
                onGround = false,
                isParkourWaypoint = true,
                airTicks = 1,
                jumpTicks = 0,
            )
        )
    }

    @Test
    fun `parkour jump hold is bounded after launch starts`() {
        val launch = BlockPos(1, 0, 0)
        val landing = BlockPos(3, 0, 0)
        val launchPoint = fightBotParkourLaunchPoint(launch, landing)!!

        assertTrue(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = launch,
                playerPosition = launchPoint,
                onGround = false,
                isParkourWaypoint = true,
                airTicks = 1,
                jumpTicks = 1,
            )
        )

        assertFalse(
            shouldJumpForFightBotParkourWaypoint(
                launchBlock = launch,
                landingBlock = landing,
                launchPoint = launchPoint,
                playerBlock = launch,
                playerPosition = launchPoint,
                onGround = false,
                isParkourWaypoint = true,
                airTicks = 4,
                jumpTicks = 5,
            )
        )
    }

    @Test
    fun `parkour waypoint advances only after expected landing`() {
        val landing = BlockPos(2, 1, 0)

        assertFalse(
            hasLandedFightBotParkourWaypoint(
                landingBlock = landing,
                playerBlock = BlockPos(2, 0, 0),
                onGround = true,
                waypointKind = BlockPathNodeKind.PARKOUR_JUMP,
            )
        )
        assertFalse(
            hasLandedFightBotParkourWaypoint(
                landingBlock = landing,
                playerBlock = landing,
                onGround = false,
                waypointKind = BlockPathNodeKind.PARKOUR_JUMP,
            )
        )
        assertTrue(
            hasLandedFightBotParkourWaypoint(
                landingBlock = landing,
                playerBlock = landing,
                onGround = true,
                waypointKind = BlockPathNodeKind.PARKOUR_JUMP,
            )
        )
    }

    @Test
    fun `parkour airborne ticks suppress normal stuck handling briefly`() {
        assertTrue(
            shouldSuppressFightBotParkourStuck(
                waypointKind = BlockPathNodeKind.PARKOUR_JUMP,
                onGround = false,
                airTicks = 8,
            )
        )
        assertFalse(
            shouldSuppressFightBotParkourStuck(
                waypointKind = BlockPathNodeKind.PARKOUR_JUMP,
                onGround = true,
                airTicks = 8,
            )
        )
        assertFalse(
            shouldSuppressFightBotParkourStuck(
                waypointKind = BlockPathNodeKind.STEP_UP,
                onGround = false,
                airTicks = 8,
            )
        )
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
            parkourEnabled = false,
            parkourSprintAllowed = false,
            currentTick = 100,
        )
        assertEquals(1, first.failures)
        assertEquals(140, first.retryTick)

        val second = nextFightBotUnreachableRoute(
            first,
            1,
            targetBlock,
            playerBlock,
            parkourEnabled = false,
            parkourSprintAllowed = false,
            currentTick = 140
        )
        assertEquals(2, second.failures)
        assertEquals(220, second.retryTick)

        val third = nextFightBotUnreachableRoute(
            second,
            1,
            targetBlock,
            playerBlock,
            parkourEnabled = false,
            parkourSprintAllowed = false,
            currentTick = 220
        )
        assertEquals(3, third.failures)
        assertEquals(380, third.retryTick)

        val fourth = nextFightBotUnreachableRoute(
            third,
            1,
            targetBlock,
            playerBlock,
            parkourEnabled = false,
            parkourSprintAllowed = false,
            currentTick = 380
        )
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
            parkourEnabled = false,
            parkourSprintAllowed = false,
            failures = 2,
            retryTick = 220,
        )

        assertTrue(
            isFightBotUnreachableRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = false,
                parkourSprintAllowed = false,
                currentTick = 219,
            )
        )
        assertFalse(
            isFightBotUnreachableRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = false,
                parkourSprintAllowed = false,
                currentTick = 220,
            )
        )
    }

    @Test
    fun `search limited cooldown uses exact player block and short retry`() {
        val targetBlock = BlockPos(20, -56, -15)
        val playerBlock = BlockPos(20, -60, -5)
        val route = nextFightBotSearchLimitedRoute(
            targetId = 1,
            targetBlock = targetBlock,
            playerBlock = playerBlock,
            parkourEnabled = true,
            parkourSprintAllowed = true,
            currentTick = 100,
        )

        assertEquals(110, route.retryTick)
        assertTrue(
            isFightBotSearchLimitedRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = true,
                parkourSprintAllowed = true,
                currentTick = 109,
            )
        )
        assertFalse(
            isFightBotSearchLimitedRouteCoolingDown(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = true,
                parkourSprintAllowed = true,
                currentTick = 110,
            )
        )
    }

    @Test
    fun `search limited cooldown invalidates when player block changes`() {
        val targetBlock = BlockPos(20, -56, -15)
        val playerBlock = BlockPos(20, -60, -5)
        val route = FightBotSearchLimitedRoute(
            targetId = 1,
            targetBlock = targetBlock,
            playerBlock = playerBlock,
            parkourEnabled = true,
            parkourSprintAllowed = true,
            retryTick = 110,
        )

        assertFalse(
            shouldInvalidateFightBotSearchLimitedRoute(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = true,
                parkourSprintAllowed = true,
            )
        )
        assertTrue(
            shouldInvalidateFightBotSearchLimitedRoute(
                route = route,
                targetId = 1,
                targetBlock = targetBlock,
                playerBlock = BlockPos(20, -60, -6),
                parkourEnabled = true,
                parkourSprintAllowed = true,
            )
        )
    }

    @Test
    fun `search bounds include start goals and vertical padding`() {
        val bounds = fightBotPathSearchBounds(
            start = BlockPos(20, -60, -5),
            goals = listOf(BlockPos(19, -56, -12), BlockPos(23, -56, -15)),
            horizontalPadding = 6,
            maxStepUp = 1,
            maxDropDown = 3,
        )

        assertEquals(BlockPos(13, -64, -21), BlockPos(bounds!!.minX, bounds.minY, bounds.minZ))
        assertEquals(BlockPos(29, -53, 1), BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ))
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
            parkourEnabled = false,
            parkourSprintAllowed = false,
            failures = 1,
            retryTick = 140,
        )

        assertFalse(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                targetBlock,
                BlockPos(28, -60, -10),
                parkourEnabled = false,
                parkourSprintAllowed = false
            )
        )
        assertTrue(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                BlockPos(20, -55, -15),
                playerBlock,
                parkourEnabled = false,
                parkourSprintAllowed = false
            )
        )
        assertTrue(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                targetBlock,
                BlockPos(26, -56, -13),
                parkourEnabled = false,
                parkourSprintAllowed = false
            )
        )
        assertTrue(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                targetBlock,
                BlockPos(40, -60, -13),
                parkourEnabled = false,
                parkourSprintAllowed = false
            )
        )
        assertTrue(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                targetBlock,
                playerBlock,
                parkourEnabled = true,
                parkourSprintAllowed = false
            )
        )
        assertTrue(
            shouldInvalidateFightBotUnreachableRoute(
                route,
                1,
                targetBlock,
                playerBlock,
                parkourEnabled = false,
                parkourSprintAllowed = true
            )
        )
    }
}
