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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.ccbluex.liquidbounce.config.types.group.ToggleableValueGroup
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.clicker
import net.ccbluex.liquidbounce.features.module.modules.combat.killaura.ModuleKillAura.targetTracker
import net.ccbluex.liquidbounce.features.module.modules.misc.debugrecorder.modes.GenericDebugRecorder
import net.ccbluex.liquidbounce.features.module.modules.render.ModuleDebug
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.block.DDAAStarPathBuilder
import net.ccbluex.liquidbounce.utils.block.BlockPathSearchBounds
import net.ccbluex.liquidbounce.utils.block.BlockPathNode
import net.ccbluex.liquidbounce.utils.block.BlockPathNodeKind
import net.ccbluex.liquidbounce.utils.block.DetailedBlockPath
import net.ccbluex.liquidbounce.utils.block.DetailedBlockPathResult
import net.ccbluex.liquidbounce.utils.block.PathSearchExitReason
import net.ccbluex.liquidbounce.utils.block.PathSearchStats
import net.ccbluex.liquidbounce.utils.block.isConservativeParkourJumpEdge
import net.ccbluex.liquidbounce.utils.client.logger
import net.ccbluex.liquidbounce.utils.entity.doesCollideAt
import net.ccbluex.liquidbounce.utils.entity.doesNotCollideBelow
import net.ccbluex.liquidbounce.utils.entity.rotation
import net.ccbluex.liquidbounce.utils.entity.squaredBoxedDistanceTo
import net.ccbluex.liquidbounce.utils.io.toJsonArray
import net.ccbluex.liquidbounce.utils.math.bottomCenter
import net.ccbluex.liquidbounce.utils.math.fma
import net.ccbluex.liquidbounce.utils.math.sq
import net.ccbluex.liquidbounce.utils.movement.DirectionalInput
import net.ccbluex.liquidbounce.utils.movement.getDegreesRelativeToView
import net.ccbluex.liquidbounce.utils.movement.getDirectionalInputForDegrees
import net.ccbluex.liquidbounce.utils.navigation.NavigationBaseValueGroup
import net.ccbluex.liquidbounce.utils.raytracing.pathfinder.DDARaycast
import net.ccbluex.liquidbounce.utils.raytracing.pathfinder.threadLocalPos
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Data class holding combat-related context
 */
data class CombatContext(
    val playerPosition: Vec3,
    val combatTarget: CombatTarget?
)

data class CombatTarget(
    val entity: Entity,
    val distance: Double,
    val range: Float,
    val outOfDistance: Boolean,
    val targetRotation: Rotation,
    val requiredTargetRotation: Rotation,
    val outOfDanger: Boolean
)

private data class AttackCandidate(
    val position: Vec3,
    val blockPos: BlockPos,
    val dangerous: Boolean,
    val targetLookDistanceSq: Double,
    val playerDistanceSq: Double,
)

private data class AttackRoute(
    val candidate: AttackCandidate,
    val path: DetailedBlockPath?,
    val cost: Double,
    val penalized: Boolean,
)

private data class CachedCombatPath(
    val targetId: Int,
    val targetBlock: BlockPos,
    val startBlock: BlockPos,
    val goalBlock: BlockPos,
    val destination: Vec3,
    val nodes: List<Vec3i>,
    val steps: List<BlockPathNode>,
    val createdTick: Int,
    var waypointIndex: Int = 0,
    var bestWaypointDistanceSq: Double = Double.POSITIVE_INFINITY,
    var bestWaypointY: Double = Double.NEGATIVE_INFINITY,
    var stagnantTicks: Int = 0,
)

internal data class FightBotUnreachableRoute(
    val targetId: Int,
    val targetBlock: BlockPos,
    val playerYBand: Int,
    val originBlock: BlockPos,
    val parkourEnabled: Boolean,
    val parkourSprintAllowed: Boolean,
    val failures: Int,
    val retryTick: Int,
)

internal data class FightBotSearchLimitedRoute(
    val targetId: Int,
    val targetBlock: BlockPos,
    val playerBlock: BlockPos,
    val parkourEnabled: Boolean,
    val parkourSprintAllowed: Boolean,
    val retryTick: Int,
)

private data class FightBotParkourEdge(
    val launchBlock: BlockPos,
    val landingBlock: BlockPos,
)

private data class FightBotPenalizedParkourEdge(
    val edge: FightBotParkourEdge,
    val untilTick: Int,
)

private enum class FightBotParkourPhase {
    NONE,
    APPROACH,
    JUMP,
    AIRBORNE,
    LANDED,
    MISSED
}

private class FightBotDiagnosticsTrace(
    val startedNs: Long = System.nanoTime(),
) {
    var totalNs = 0L
    var candidateScanNs = 0L
    var routeSelectionNs = 0L
    var pathfindingNs = 0L
    var raycastNs = 0L

    var candidateDuplicates = 0
    var candidateCollisions = 0
    var candidateAttackLosFailures = 0
    var acceptedCandidates = 0
    var routeCandidates = 0
    var directRoutes = 0
    var pathRequests = 0
    var pathSuccesses = 0
    var pathFailures = 0
    var raycasts = 0
    var cacheState = "none"
    var selectedMode = "none"
    var selectedCost = 0.0
    var selectedPathNodes = 0
    var selectedClimbablePathNodes = 0
    var noRouteCacheTicks = 0
    var unreachableCooldownTicks = 0
    var unreachableFailures = 0
    var pathGoalCount = 0
    var pathMaxCost = PATH_MAX_COST
    var pathSearchMode = "none"
    var pathExitReason = "none"
    var pathIterations = 0
    var pathVisitedNodes = 0
    var pathQueuePeak = 0
    var pathBounds: BlockPathSearchBounds? = null
    var parkourEnabled = false
    var parkourSprintAllowed = false
    var parkourEdgeCandidates = 0
    var parkourEdgeAccepted = 0
    var parkourTransitRejected = 0
    var parkourLandingRejected = 0
    var parkourWalkableMidpointRejected = 0
    var parkourSimulationRejected = 0
    var stuck = false
    var activeWaypointClimbable = false
    var activeWaypointKind = BlockPathNodeKind.WALK
    var selectedStepUpPathNodes = 0
    var selectedParkourPathNodes = 0
    var stepUpJumpTicks = 0
    var stepUpStuck = false
    var parkourAirTicks = 0
    var parkourValidated = false
    var parkourMiss = false
    var parkourPhase = FightBotParkourPhase.NONE
    var parkourLaunchPoint: Vec3? = null
    var parkourJumpReady = false
    var parkourPenaltyActive = false
    var selectedGoalBlock: BlockPos? = null
    var activeWaypoint: Vec3? = null
    var parkourLaunchBlock: Vec3i? = null
    var parkourLandingBlock: Vec3i? = null
    val acceptedCandidateBlocks = mutableListOf<BlockPos>()
    val climbablePathNodeBlocks = mutableListOf<Vec3i>()
    val stepUpPathNodeBlocks = mutableListOf<Vec3i>()
    val parkourPathNodeBlocks = mutableListOf<Vec3i>()
    val pathStepKinds = mutableListOf<BlockPathNodeKind>()
}

internal fun shouldJumpForFightBotWaypoint(
    waypointY: Double,
    playerY: Double,
    onClimbable: Boolean,
    isClimbableWaypoint: Boolean,
): Boolean {
    if (!isClimbableWaypoint || waypointY <= playerY + CLIMB_ASCEND_EPSILON) {
        return false
    }

    return onClimbable
}

internal fun climbDirectionalInputForFightBotWaypoint(
    waypointY: Double,
    playerY: Double,
    onClimbable: Boolean,
    isClimbableWaypoint: Boolean,
): DirectionalInput? {
    if (!isClimbableWaypoint ||
        waypointY <= playerY + CLIMB_ASCEND_EPSILON ||
        !onClimbable
    ) {
        return null
    }

    return DirectionalInput.NONE
}

internal fun containsClimbableWaypoint(
    nodes: List<Vec3i>,
    fromIndex: Int,
    toIndex: Int,
    isClimbable: (Vec3i) -> Boolean,
): Boolean {
    val start = fromIndex.coerceAtLeast(0)
    val end = toIndex.coerceAtMost(nodes.lastIndex)

    if (start > end) {
        return false
    }

    return (start..end).any { index -> isClimbable(nodes[index]) }
}

internal fun containsRequiredFightBotWaypoint(
    steps: List<BlockPathNode>,
    fromIndex: Int,
    toIndex: Int,
): Boolean {
    val start = fromIndex.coerceAtLeast(0)
    val end = toIndex.coerceAtMost(steps.lastIndex)

    if (start > end) {
        return false
    }

    return (start..end).any { index ->
        steps[index].kind == BlockPathNodeKind.CLIMB ||
            steps[index].kind == BlockPathNodeKind.STEP_UP ||
            steps[index].kind == BlockPathNodeKind.PARKOUR_JUMP
    }
}

internal fun shouldJumpForFightBotStepUpWaypoint(
    waypointY: Double,
    playerY: Double,
    horizontalDistanceSq: Double,
    onGround: Boolean,
    isStepUpWaypoint: Boolean,
    jumpTicks: Int,
): Boolean {
    if (!isStepUpWaypoint || waypointY <= playerY + STEP_UP_ASCEND_EPSILON) {
        return false
    }

    if (jumpTicks in 1 until STEP_UP_JUMP_HOLD_TICKS) {
        return true
    }

    return onGround && horizontalDistanceSq <= STEP_UP_JUMP_START_DISTANCE_SQ
}

internal fun hasClearedFightBotStepUpWaypoint(
    waypointY: Double,
    playerY: Double,
    waypointKind: BlockPathNodeKind,
): Boolean {
    return waypointKind != BlockPathNodeKind.STEP_UP || playerY >= waypointY - STEP_UP_CLEAR_EPSILON
}

internal fun hasLandedFightBotStepUpWaypoint(
    landingBlock: Vec3i,
    playerBlock: Vec3i,
    playerY: Double,
    onGround: Boolean,
    waypointKind: BlockPathNodeKind,
): Boolean {
    if (waypointKind != BlockPathNodeKind.STEP_UP) {
        return true
    }

    return onGround &&
        playerBlock.x == landingBlock.x &&
        playerBlock.z == landingBlock.z &&
        (playerBlock.y >= landingBlock.y || playerY >= landingBlock.y - STEP_UP_LANDING_EPSILON)
}

internal fun hasFightBotStepUpVerticalProgress(playerY: Double, bestWaypointY: Double): Boolean {
    return playerY > bestWaypointY + STEP_UP_PROGRESS_EPSILON
}

internal fun fightBotParkourLaunchPoint(launchBlock: Vec3i, landingBlock: Vec3i): Vec3? {
    if (!isConservativeParkourJumpEdge(launchBlock, landingBlock)) {
        return null
    }

    val directionX = (landingBlock.x - launchBlock.x).directionSign()
    val directionZ = (landingBlock.z - launchBlock.z).directionSign()

    return launchBlock.bottomCenter.add(
        directionX * PARKOUR_LAUNCH_EDGE_OFFSET,
        0.0,
        directionZ * PARKOUR_LAUNCH_EDGE_OFFSET
    )
}

internal fun shouldJumpForFightBotParkourWaypoint(
    launchBlock: Vec3i?,
    landingBlock: Vec3i?,
    launchPoint: Vec3?,
    playerBlock: Vec3i,
    playerPosition: Vec3,
    onGround: Boolean,
    isParkourWaypoint: Boolean,
    airTicks: Int,
    jumpTicks: Int,
): Boolean {
    if (!isParkourWaypoint ||
        launchBlock == null ||
        landingBlock == null ||
        launchPoint == null
    ) {
        return false
    }

    if (jumpTicks in 1 until PARKOUR_JUMP_HOLD_TICKS) {
        return true
    }

    if (!onGround || airTicks != 0 || launchBlock != playerBlock) {
        return false
    }

    return playerPosition.horizontalDistanceSq(launchPoint) <= PARKOUR_LAUNCH_POINT_REACHED_DISTANCE_SQ
}

internal fun hasLandedFightBotParkourWaypoint(
    landingBlock: Vec3i,
    playerBlock: Vec3i,
    onGround: Boolean,
    waypointKind: BlockPathNodeKind,
): Boolean {
    return waypointKind != BlockPathNodeKind.PARKOUR_JUMP || onGround && landingBlock == playerBlock
}

internal fun shouldSuppressFightBotParkourStuck(
    waypointKind: BlockPathNodeKind,
    onGround: Boolean,
    airTicks: Int,
): Boolean {
    return waypointKind == BlockPathNodeKind.PARKOUR_JUMP &&
        !onGround &&
        airTicks in 1..PARKOUR_MAX_AIR_TICKS
}

internal fun fightBotUnreachableCooldownTicks(failures: Int): Int {
    return when {
        failures <= 1 -> UNREACHABLE_FIRST_COOLDOWN_TICKS
        failures == 2 -> UNREACHABLE_SECOND_COOLDOWN_TICKS
        else -> UNREACHABLE_MAX_COOLDOWN_TICKS
    }
}

internal fun fightBotUnreachableYBand(playerBlock: Vec3i): Int {
    return Math.floorDiv(playerBlock.y, UNREACHABLE_Y_BAND_HEIGHT)
}

internal fun shouldInvalidateFightBotUnreachableRoute(
    route: FightBotUnreachableRoute,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
): Boolean {
    return route.targetId != targetId ||
        route.targetBlock != targetBlock ||
        route.parkourEnabled != parkourEnabled ||
        route.parkourSprintAllowed != parkourSprintAllowed ||
        route.playerYBand != fightBotUnreachableYBand(playerBlock) ||
        route.originBlock.horizontalBlockDistanceSq(playerBlock) > UNREACHABLE_ORIGIN_RETRY_DISTANCE_SQ
}

internal fun isFightBotUnreachableRouteCoolingDown(
    route: FightBotUnreachableRoute,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
    currentTick: Int,
): Boolean {
    if (shouldInvalidateFightBotUnreachableRoute(
            route,
            targetId,
            targetBlock,
            playerBlock,
            parkourEnabled,
            parkourSprintAllowed
        )
    ) {
        return false
    }

    return currentTick < route.retryTick
}

internal fun nextFightBotUnreachableRoute(
    previous: FightBotUnreachableRoute?,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
    currentTick: Int,
): FightBotUnreachableRoute {
    val canReusePrevious = previous != null &&
        !shouldInvalidateFightBotUnreachableRoute(
            previous,
            targetId,
            targetBlock,
            playerBlock,
            parkourEnabled,
            parkourSprintAllowed
        )
    val failures = if (canReusePrevious) previous.failures + 1 else 1
    val originBlock = if (canReusePrevious) previous.originBlock else playerBlock
    val cooldownTicks = fightBotUnreachableCooldownTicks(failures)

    return FightBotUnreachableRoute(
        targetId = targetId,
        targetBlock = targetBlock,
        playerYBand = fightBotUnreachableYBand(playerBlock),
        originBlock = originBlock,
        parkourEnabled = parkourEnabled,
        parkourSprintAllowed = parkourSprintAllowed,
        failures = failures,
        retryTick = currentTick + cooldownTicks,
    )
}

internal fun shouldInvalidateFightBotSearchLimitedRoute(
    route: FightBotSearchLimitedRoute,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
): Boolean {
    return route.targetId != targetId ||
        route.targetBlock != targetBlock ||
        route.playerBlock != playerBlock ||
        route.parkourEnabled != parkourEnabled ||
        route.parkourSprintAllowed != parkourSprintAllowed
}

internal fun isFightBotSearchLimitedRouteCoolingDown(
    route: FightBotSearchLimitedRoute,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
    currentTick: Int,
): Boolean {
    if (shouldInvalidateFightBotSearchLimitedRoute(
            route,
            targetId,
            targetBlock,
            playerBlock,
            parkourEnabled,
            parkourSprintAllowed
        )
    ) {
        return false
    }

    return currentTick < route.retryTick
}

internal fun nextFightBotSearchLimitedRoute(
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    parkourEnabled: Boolean,
    parkourSprintAllowed: Boolean,
    currentTick: Int,
): FightBotSearchLimitedRoute {
    return FightBotSearchLimitedRoute(
        targetId = targetId,
        targetBlock = targetBlock,
        playerBlock = playerBlock,
        parkourEnabled = parkourEnabled,
        parkourSprintAllowed = parkourSprintAllowed,
        retryTick = currentTick + SEARCH_LIMITED_COOLDOWN_TICKS,
    )
}

internal fun fightBotPathSearchBounds(
    start: Vec3i,
    goals: Collection<Vec3i>,
    horizontalPadding: Int,
    maxStepUp: Int,
    maxDropDown: Int,
): BlockPathSearchBounds? {
    return BlockPathSearchBounds.around(
        positions = goals + start,
        horizontalPadding = horizontalPadding,
        maxStepUp = maxStepUp,
        maxDropDown = maxDropDown,
    )
}

private fun Vec3i.horizontalBlockDistanceSq(other: Vec3i): Int {
    val x = this.x - other.x
    val z = this.z - other.z
    return x * x + z * z
}

private fun Vec3i.blockDistanceSq(other: Vec3i): Int {
    val x = this.x - other.x
    val y = this.y - other.y
    val z = this.z - other.z
    return x * x + y * y + z * z
}

private fun Vec3.horizontalDistanceSq(other: Vec3): Double {
    val x = this.x - other.x
    val z = this.z - other.z
    return x * x + z * z
}

private fun Int.directionSign(): Int {
    return when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}

private const val PATH_CACHE_TICKS = 40
private const val PATH_MAX_COST = 40
private const val PARKOUR_PATH_MAX_COST = 90
private const val MAX_PATHED_ATTACK_CANDIDATES = 8
private const val WAYPOINT_REACHED_DISTANCE_SQ = 0.55 * 0.55
private const val CLIMB_ASCEND_EPSILON = 0.1
private const val CLIMB_CLEAR_EPSILON = 0.1
private const val STEP_UP_ASCEND_EPSILON = 0.25
private const val STEP_UP_CLEAR_EPSILON = 0.05
private const val STEP_UP_LANDING_EPSILON = 0.02
private const val STEP_UP_PROGRESS_EPSILON = 0.05
private const val STEP_UP_JUMP_HOLD_TICKS = 5
private const val STEP_UP_JUMP_START_DISTANCE = 1.65
private const val STEP_UP_JUMP_START_DISTANCE_SQ =
    STEP_UP_JUMP_START_DISTANCE * STEP_UP_JUMP_START_DISTANCE
private const val PARKOUR_MAX_AIR_TICKS = 24
private const val PARKOUR_JUMP_HOLD_TICKS = 5
private const val PARKOUR_LAUNCH_EDGE_OFFSET = 0.42
private const val PARKOUR_LAUNCH_POINT_REACHED_DISTANCE = 0.32
private const val PARKOUR_LAUNCH_POINT_REACHED_DISTANCE_SQ =
    PARKOUR_LAUNCH_POINT_REACHED_DISTANCE * PARKOUR_LAUNCH_POINT_REACHED_DISTANCE
private const val STUCK_TICKS = 8
private const val STUCK_PROGRESS_EPSILON_SQ = 0.04
private const val BAD_GOAL_TICKS = 25
private const val BAD_GOAL_COST = 20.0
private const val BAD_PARKOUR_EDGE_TICKS = 40
private const val BAD_PARKOUR_EDGE_COST = 100.0
private const val PARKOUR_PENALTY_NO_ROUTE_COOLDOWN_TICKS = 10
private const val SEARCH_LIMITED_COOLDOWN_TICKS = 10
private const val PATH_BOUNDS_HORIZONTAL_PADDING = 6
private const val ATTACK_POSITION_STEP = 10
private const val MIN_ATTACK_SLOT_RADIUS = 1.0
private const val MAX_LOGGED_CANDIDATES = 32
private const val UNREACHABLE_FIRST_COOLDOWN_TICKS = 40
private const val UNREACHABLE_SECOND_COOLDOWN_TICKS = 80
private const val UNREACHABLE_MAX_COOLDOWN_TICKS = 160
private const val UNREACHABLE_Y_BAND_HEIGHT = 4
private const val UNREACHABLE_ORIGIN_RETRY_DISTANCE = 12
private const val UNREACHABLE_ORIGIN_RETRY_DISTANCE_SQ =
    UNREACHABLE_ORIGIN_RETRY_DISTANCE * UNREACHABLE_ORIGIN_RETRY_DISTANCE

/**
 * A fight bot that handles combat and movement automatically
 */
@Suppress("LargeClass", "TooManyFunctions")
object KillAuraFightBot : NavigationBaseValueGroup<CombatContext>(ModuleKillAura, "FightBot", false), DDAAStarPathBuilder {

    override val allowDiagonal: Boolean get() = true
    override val allowClimbableNavigation: Boolean get() = true
    override val allowParkourNavigation: Boolean get() = Parkour.running
    override val allowParkourSprint: Boolean get() = Parkour.running && autoSprintEnabled
    override val maxIterations: Int get() = 500
    override val stopRange: Double get() = 0.75

    private val combatPathMaxCost: Int
        get() = if (Parkour.running) PARKOUR_PATH_MAX_COST else PATH_MAX_COST

    override fun recordParkourEdgeCandidate(start: Vec3i, landing: Vec3i) {
        activeDiagnostics?.apply {
            parkourEdgeCandidates++
        }
    }

    override fun recordParkourEdgeAccepted(start: Vec3i, landing: Vec3i) {
        activeDiagnostics?.apply {
            parkourEdgeAccepted++
        }
    }

    override fun recordParkourEdgeRejected(reason: String) {
        activeDiagnostics?.apply {
            when (reason) {
                "transit" -> parkourTransitRejected++
                "landing" -> parkourLandingRejected++
                "walkableMidpoint" -> parkourWalkableMidpointRejected++
                "simulation" -> parkourSimulationRejected++
            }
        }
    }

    override fun getParkourEdgeExtraCost(start: Vec3i, landing: Vec3i): Double {
        return if (isParkourEdgePenalized(start, landing)) BAD_PARKOUR_EDGE_COST else 0.0
    }

    private val opponentRange by float("OpponentRange", 3f, 0.1f..10f)
    private val dangerousYawDiff by float("DangerousYaw", 55f, 0f..90f, suffix = "°")
    private val runawayOnCooldown by boolean("RunawayOnCooldown", true)
    private val pathfindingRange by float("PathfindingRange", 15f, 2f..50f)
    private val directRange by float("DirectRange", 4f, 1f..10f)

    private var cachedCombatPath: CachedCombatPath? = null
    private var unreachableCombatRoute: FightBotUnreachableRoute? = null
    private var searchLimitedCombatRoute: FightBotSearchLimitedRoute? = null
    private var activeWaypoint: Vec3? = null
    private var activeWaypointBlock: Vec3i? = null
    private var activeWaypointClimbable = false
    private var activeWaypointKind = BlockPathNodeKind.WALK
    private var activeStepUpJumpBlock: Vec3i? = null
    private var activeStepUpJumpTicks = 0
    private var activeParkourLaunchBlock: Vec3i? = null
    private var activeParkourLandingBlock: Vec3i? = null
    private var activeParkourLaunchPoint: Vec3? = null
    private var activeParkourPhase = FightBotParkourPhase.NONE
    private var activeParkourAirTicks = 0
    private var activeParkourJumpTicks = 0
    private var activeParkourMiss = false
    private var penalizedParkourEdge: FightBotPenalizedParkourEdge? = null
    private var parkourPenaltyNoRouteUntilTick = 0
    private var penalizedGoal: BlockPos? = null
    private var penalizedGoalUntilTick = 0
    private var activeDiagnostics: FightBotDiagnosticsTrace? = null

    internal object TargetFilter : ValueGroup("TargetFilter") {
        internal var range by float("Range", 50f, 10f..100f)
        internal var visibleOnly by boolean("VisibleOnly", true)
        internal var notWhenVoid by boolean("NotWhenVoid", true)
    }

    /**
     * Configuration for leader following functionality
     */
    internal object LeaderFollower : ToggleableValueGroup(this, "Leader", false) {
        internal val username by text("Username", "")
        internal val radius by float("Radius", 5f, 2f..10f)
    }

    private object Parkour : ToggleableValueGroup(this, "Parkour", false) {}

    private object Diagnostics : ToggleableValueGroup(this, "Diagnostics", false) {
        internal val intervalTicks by int("IntervalTicks", 10, 1..100, "ticks")
        internal val slowThresholdMs by int("SlowThreshold", 4, 1..100, "ms")
        internal val scanBlocks by boolean("ScanBlocks", true)
        internal val blockScanRange by int("BlockScanRange", 6, 1..16)
        internal val maxBlocks by int("MaxBlocks", 64, 1..256)
        internal val clientLog by boolean("ClientLog", false)
        internal var lastRecordTick = -1000
    }

    init {
        tree(TargetFilter)
        tree(LeaderFollower)
        tree(Parkour)
        tree(Diagnostics)
    }

    fun updateTarget() {
        targetTracker.select { entity ->
            if (player.squaredBoxedDistanceTo(entity) > TargetFilter.range.sq()) {
                return@select null
            }

            if (TargetFilter.visibleOnly && !player.hasLineOfSight(entity)) {
                return@select null
            }

            if (TargetFilter.notWhenVoid && entity.doesNotCollideBelow()) {
                return@select null
            }

            entity
        }
    }

    /**
     * Creates combat context
     */
    override fun createNavigationContext(): CombatContext {
        val playerPosition = player.position()

        val combatTarget = targetTracker.target?.let { entity ->
            val distance = playerPosition.distanceTo(entity.position())
            val range = min(ModuleKillAura.range.interactionRange, distance.toFloat())
            val outOfDistance = distance > opponentRange

            val targetRotation = entity.rotation.copy(pitch = 0.0f)
            val requiredTargetRotation = Rotation.lookingAt(playerPosition, entity.eyePosition).copy(pitch = 0.0f)
            val outOfDanger = targetRotation.angleTo(requiredTargetRotation) > dangerousYawDiff

            CombatTarget(entity, distance, range, outOfDistance, targetRotation, requiredTargetRotation, outOfDanger)
        }

        return CombatContext(
            playerPosition,
            combatTarget
        )
    }

    /**
     * Calculates the desired position to move towards
     *
     * @return Target position as Vec3d
     */
    override fun calculateGoalPosition(context: CombatContext): Vec3? {
        val diagnostics = if (Diagnostics.running) FightBotDiagnosticsTrace() else null
        activeDiagnostics = diagnostics

        var result: Vec3? = null
        try {
            result = calculateGoalPositionInternal(context)
            return result
        } finally {
            if (diagnostics != null) {
                diagnostics.totalNs = System.nanoTime() - diagnostics.startedNs
                diagnostics.pathMaxCost = combatPathMaxCost
                diagnostics.parkourEnabled = Parkour.running
                diagnostics.parkourSprintAllowed = allowParkourSprint
                diagnostics.activeWaypoint = activeWaypoint
                diagnostics.activeWaypointKind = activeWaypointKind
                diagnostics.activeWaypointClimbable = activeWaypointClimbable
                diagnostics.stepUpJumpTicks = activeStepUpJumpTicks
                diagnostics.parkourLaunchBlock = activeParkourLaunchBlock
                diagnostics.parkourLandingBlock = activeParkourLandingBlock
                diagnostics.parkourLaunchPoint = activeParkourLaunchPoint
                diagnostics.parkourAirTicks = activeParkourAirTicks
                diagnostics.parkourPhase = activeParkourPhase
                diagnostics.parkourJumpReady = isActiveParkourJumpReady()
                diagnostics.parkourMiss = activeParkourMiss
                diagnostics.parkourPenaltyActive = isAnyParkourPenaltyActive()
                publishDiagnostics(context, diagnostics, result)
            }
            activeDiagnostics = null
        }
    }

    private fun calculateGoalPositionInternal(context: CombatContext): Vec3? {
        activeWaypoint = null
        activeWaypointBlock = null
        activeWaypointClimbable = false
        activeWaypointKind = BlockPathNodeKind.WALK

        if (LeaderFollower.running && LeaderFollower.username.isNotEmpty()) {
            clearCombatPath()
            unreachableCombatRoute = null
            searchLimitedCombatRoute = null
            val leader = world.players().find { it.gameProfile.name == LeaderFollower.username }
            activeDiagnostics?.selectedMode = "leader"
            return leader?.let { calculateLeaderGoalPosition(it.position(), context.playerPosition) }
        }

        val combatTarget = context.combatTarget ?: run {
            clearCombatPath()
            unreachableCombatRoute = null
            searchLimitedCombatRoute = null
            activeDiagnostics?.selectedMode = "noTarget"
            return null
        }

        if (runawayOnCooldown && !clicker.willClickAt()) {
            clearCombatPath()
            activeDiagnostics?.selectedMode = "runaway"
            return calculateRoutedDestination(context, calculateRunawayPosition(context, combatTarget))
        }

        return calculateAttackGoalPosition(context, combatTarget)
    }

    /**
     * Handles additional movement mechanics like swimming and jumping
     *
     * @param event Movement input event to modify
     */
    override fun calculateDirectionalInput(currentInput: DirectionalInput, goal: Vec3): DirectionalInput {
        climbDirectionalInputForFightBotWaypoint(
            waypointY = goal.y,
            playerY = player.y,
            onClimbable = player.onClimbable(),
            isClimbableWaypoint = activeWaypointClimbable,
        )?.let { return it }

        val movementGoal = activeParkourLandingBlock
            ?.takeIf { shouldSteerToParkourLanding() }
            ?.bottomCenter
            ?: goal
        val degrees = getDegreesRelativeToView(movementGoal.subtract(player.position()), player.yRot)
        return getDirectionalInputForDegrees(DirectionalInput.NONE, degrees, deadAngle = 20.0F)
    }

    override fun handleMovementAssist(event: MovementInputEvent, context: CombatContext, goal: Vec3) {
        super.handleMovementAssist(event, context, goal)

        val waypoint = activeWaypoint ?: goal
        val isStepUpWaypoint = activeWaypointKind == BlockPathNodeKind.STEP_UP
        val isParkourWaypoint = activeWaypointKind == BlockPathNodeKind.PARKOUR_JUMP
        val stepUpBlock = activeWaypointBlock?.takeIf { isStepUpWaypoint }
        if (activeStepUpJumpBlock != stepUpBlock) {
            activeStepUpJumpBlock = stepUpBlock
            activeStepUpJumpTicks = 0
        }

        val needsStepUp = shouldJumpForFightBotStepUpWaypoint(
            waypointY = waypoint.y,
            playerY = player.y,
            horizontalDistanceSq = player.position().horizontalDistanceSq(waypoint),
            onGround = player.onGround(),
            isStepUpWaypoint = isStepUpWaypoint,
            jumpTicks = activeStepUpJumpTicks,
        )
        val needsClimb = shouldJumpForFightBotWaypoint(
            waypointY = waypoint.y,
            playerY = player.y,
            onClimbable = player.onClimbable(),
            isClimbableWaypoint = activeWaypointClimbable,
        )
        val needsParkour = shouldJumpForFightBotParkourWaypoint(
            launchBlock = activeParkourLaunchBlock,
            landingBlock = activeParkourLandingBlock,
            launchPoint = activeParkourLaunchPoint,
            playerBlock = player.blockPosition(),
            playerPosition = player.position(),
            onGround = player.onGround(),
            isParkourWaypoint = isParkourWaypoint,
            airTicks = activeParkourAirTicks,
            jumpTicks = activeParkourJumpTicks,
        )
        val blockedWhileMoving = !activeWaypointClimbable &&
            !isStepUpWaypoint &&
            !isParkourWaypoint &&
            player.horizontalCollision &&
            player.onGround() &&
            player.position().distanceToSqr(waypoint) > WAYPOINT_REACHED_DISTANCE_SQ

        if (needsStepUp || needsClimb || needsParkour || blockedWhileMoving) {
            event.jump = true
        }

        if (needsStepUp) {
            activeStepUpJumpTicks++
        } else if (isStepUpWaypoint && player.onGround()) {
            activeStepUpJumpTicks = 0
        }

        if (isParkourWaypoint) {
            if (needsParkour) {
                activeParkourPhase = FightBotParkourPhase.JUMP
                activeParkourJumpTicks++
            }

            val landedOnExpectedBlock = player.onGround() && player.blockPosition() == activeParkourLandingBlock
            if (!player.onGround()) {
                activeParkourAirTicks++
                activeParkourPhase = FightBotParkourPhase.AIRBORNE
            } else if (activeParkourAirTicks > 0 && landedOnExpectedBlock) {
                activeParkourPhase = FightBotParkourPhase.LANDED
            } else if (activeParkourAirTicks > 0) {
                activeParkourMiss = true
                activeParkourPhase = FightBotParkourPhase.MISSED
            } else if (
                activeParkourPhase == FightBotParkourPhase.JUMP &&
                activeParkourJumpTicks >= PARKOUR_JUMP_HOLD_TICKS
            ) {
                activeParkourMiss = true
                activeParkourPhase = FightBotParkourPhase.MISSED
            } else if (!needsParkour && activeParkourPhase == FightBotParkourPhase.NONE) {
                activeParkourPhase = FightBotParkourPhase.APPROACH
            }
        } else {
            activeParkourAirTicks = 0
            activeParkourJumpTicks = 0
            activeParkourPhase = FightBotParkourPhase.NONE
        }
    }

    /**
     * Gets rotation based on movement and target
     *
     * @return Movement rotation or null if no target
     */
    override fun getMovementRotation(): Rotation {
        val movementRotation = activeParkourLandingBlock
            ?.takeIf { activeWaypointKind == BlockPathNodeKind.PARKOUR_JUMP }
            ?.let { Rotation.lookingAt(point = it.bottomCenter, from = player.eyePosition).copy(pitch = 0.0f) }
            ?: super.getMovementRotation()
        val movementPitch = targetTracker.target?.let { entity ->
            Rotation.lookingAt(point = entity.boundingBox.center, from = player.eyePosition).pitch
        } ?: return movementRotation

        return movementRotation.copy(pitch = movementPitch)
    }

    private inline fun isBlockSolidOrHazardous(packed: Long): Boolean {
        val px = BlockPos.getX(packed)
        val py = BlockPos.getY(packed)
        val pz = BlockPos.getZ(packed)
        val p = threadLocalPos.get().set(px, py, pz)
        val state = world.getBlockState(p)
        val block = state.block

        val fluid = world.getFluidState(p)
        val isLava = fluid.`is`(net.minecraft.world.level.material.Fluids.LAVA) ||
                fluid.`is`(net.minecraft.world.level.material.Fluids.FLOWING_LAVA)

        val isHazard = block is net.minecraft.world.level.block.BaseFireBlock ||
                block is net.minecraft.world.level.block.CampfireBlock ||
                block is net.minecraft.world.level.block.CactusBlock ||
                block is net.minecraft.world.level.block.MagmaBlock ||
                block is net.minecraft.world.level.block.SweetBerryBushBlock ||
                block is net.minecraft.world.level.block.WitherRoseBlock

        return !state.getCollisionShape(world, p).isEmpty || isLava || isHazard
    }

    private fun calculateLeaderGoalPosition(leaderPosition: Vec3, playerPosition: Vec3): Vec3 {
        return (-180..180 step 45)
            .mapNotNull { yaw ->
                val rotation = Rotation(yaw = yaw.toFloat(), pitch = 0.0F)
                val position = leaderPosition.fma(LeaderFollower.radius.toDouble(), rotation.directionVector)

                val pathClear = DDARaycast.hasLineOfSight(
                    level = world,
                    startX = playerPosition.x, startY = playerPosition.y + 0.5, startZ = playerPosition.z,
                    endX = position.x, endY = position.y + 0.5, endZ = position.z,
                    allowStartInside = true,
                    isSolid = ::isBlockSolidOrHazardous
                )

                if (!pathClear) {
                    return@mapNotNull null
                }

                ModuleDebug.debugGeometry(
                    this,
                    "Possible Position $yaw",
                    ModuleDebug.DebuggedPoint(position, Color4b.MAGENTA)
                )
                position
            }
            .minByOrNull { it.distanceToSqr(playerPosition) } ?: leaderPosition
    }

    private fun calculateRunawayPosition(context: CombatContext, combatTarget: CombatTarget): Vec3 {
        return context.playerPosition.fma(
            combatTarget.range.toDouble(), combatTarget.requiredTargetRotation.directionVector
        )
    }

    private fun calculateAttackGoalPosition(context: CombatContext, combatTarget: CombatTarget): Vec3? {
        tryFollowCachedCombatPath(context, combatTarget)?.let {
            return it
        }

        if (tryUseUnreachableRouteCache(combatTarget)) {
            return null
        }

        if (tryUseSearchLimitedRouteCache(combatTarget)) {
            return null
        }

        if (tryUseParkourPenaltyCooldown(combatTarget)) {
            return null
        }

        findBestAttackRoute(context, combatTarget)?.let { route ->
            return activateAttackRoute(context, combatTarget, route)
        }

        clearCombatPath()
        if (activeDiagnostics?.pathExitReason == PathSearchExitReason.MAX_ITERATIONS.name) {
            cacheSearchLimitedRoute(combatTarget)
            activeDiagnostics?.apply {
                selectedMode = "attackSearchLimited"
                selectedGoalBlock = combatTarget.entity.blockPosition()
            }
            return null
        }

        if (isAnyParkourPenaltyActive()) {
            parkourPenaltyNoRouteUntilTick = player.tickCount + PARKOUR_PENALTY_NO_ROUTE_COOLDOWN_TICKS
            activeDiagnostics?.apply {
                selectedMode = "attackParkourRoutePenalty"
                selectedGoalBlock = combatTarget.entity.blockPosition()
                parkourPenaltyActive = true
            }
            return null
        }

        cacheUnreachableRoute(combatTarget)
        activeDiagnostics?.apply {
            selectedMode = "attackNoRoute"
            selectedGoalBlock = combatTarget.entity.blockPosition()
        }
        return null
    }

    private fun tryFollowCachedCombatPath(context: CombatContext, combatTarget: CombatTarget): Vec3? {
        val cachedPath = cachedCombatPath ?: run {
            activeDiagnostics?.cacheState = "miss"
            return null
        }
        if (!cachedPath.isReusableFor(combatTarget)) {
            clearCombatPath()
            activeDiagnostics?.cacheState = "invalid"
            return null
        }

        activeDiagnostics?.apply {
            cacheState = "hit"
            selectedMode = "cached"
            selectedGoalBlock = cachedPath.goalBlock
            selectedPathNodes = cachedPath.nodes.size
            val climbableNodes = cachedPath.nodes.filter(::isClimbablePathNode)
            val stepUpNodes = cachedPath.steps.filter { it.kind == BlockPathNodeKind.STEP_UP }
            val parkourNodes = cachedPath.steps.filter { it.kind == BlockPathNodeKind.PARKOUR_JUMP }
            selectedClimbablePathNodes = climbableNodes.size
            selectedStepUpPathNodes = stepUpNodes.size
            selectedParkourPathNodes = parkourNodes.size
            parkourValidated = parkourNodes.isNotEmpty()
            climbablePathNodeBlocks.clear()
            climbablePathNodeBlocks += climbableNodes.take(MAX_LOGGED_CANDIDATES)
            stepUpPathNodeBlocks.clear()
            stepUpPathNodeBlocks += stepUpNodes.map { it.position }.take(MAX_LOGGED_CANDIDATES)
            parkourPathNodeBlocks.clear()
            parkourPathNodeBlocks += parkourNodes.map { it.position }.take(MAX_LOGGED_CANDIDATES)
            pathStepKinds.clear()
            pathStepKinds += cachedPath.steps.map { it.kind }.take(MAX_LOGGED_CANDIDATES)
        }
        return followCachedCombatPath(cachedPath, context.playerPosition)
    }

    private fun tryUseUnreachableRouteCache(combatTarget: CombatTarget): Boolean {
        val unreachableRoute = unreachableCombatRoute ?: return false
        val targetBlock = combatTarget.entity.blockPosition()
        val playerBlock = player.blockPosition()

        if (shouldInvalidateFightBotUnreachableRoute(
                route = unreachableRoute,
                targetId = combatTarget.entity.id,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = Parkour.running,
                parkourSprintAllowed = allowParkourSprint,
            )
        ) {
            unreachableCombatRoute = null
            return false
        }

        val remainingTicks = unreachableRoute.retryTick - player.tickCount
        if (remainingTicks <= 0) {
            return false
        }

        clearCombatPath()
        activeDiagnostics?.apply {
            cacheState = "unreachable"
            selectedMode = "attackNoRouteCached"
            selectedGoalBlock = unreachableRoute.targetBlock
            noRouteCacheTicks = remainingTicks
            unreachableCooldownTicks = remainingTicks
            unreachableFailures = unreachableRoute.failures
        }
        return true
    }

    private fun tryUseSearchLimitedRouteCache(combatTarget: CombatTarget): Boolean {
        val limitedRoute = searchLimitedCombatRoute ?: return false
        val targetBlock = combatTarget.entity.blockPosition()
        val playerBlock = player.blockPosition()

        if (shouldInvalidateFightBotSearchLimitedRoute(
                route = limitedRoute,
                targetId = combatTarget.entity.id,
                targetBlock = targetBlock,
                playerBlock = playerBlock,
                parkourEnabled = Parkour.running,
                parkourSprintAllowed = allowParkourSprint,
            )
        ) {
            searchLimitedCombatRoute = null
            return false
        }

        val remainingTicks = limitedRoute.retryTick - player.tickCount
        if (remainingTicks <= 0) {
            return false
        }

        clearCombatPath()
        activeDiagnostics?.apply {
            cacheState = "searchLimited"
            selectedMode = "attackSearchLimitedCached"
            selectedGoalBlock = limitedRoute.targetBlock
            noRouteCacheTicks = remainingTicks
        }
        return true
    }

    private fun tryUseParkourPenaltyCooldown(combatTarget: CombatTarget): Boolean {
        if (!isAnyParkourPenaltyActive() || player.tickCount >= parkourPenaltyNoRouteUntilTick) {
            return false
        }

        clearCombatPath()
        activeDiagnostics?.apply {
            cacheState = "parkourPenalty"
            selectedMode = "attackParkourPenaltyCached"
            selectedGoalBlock = combatTarget.entity.blockPosition()
            parkourPenaltyActive = true
        }
        return true
    }

    private fun cacheSearchLimitedRoute(combatTarget: CombatTarget) {
        val route = nextFightBotSearchLimitedRoute(
            targetId = combatTarget.entity.id,
            targetBlock = combatTarget.entity.blockPosition(),
            playerBlock = player.blockPosition(),
            parkourEnabled = Parkour.running,
            parkourSprintAllowed = allowParkourSprint,
            currentTick = player.tickCount,
        )
        searchLimitedCombatRoute = route
        unreachableCombatRoute = null

        activeDiagnostics?.apply {
            noRouteCacheTicks = route.retryTick - player.tickCount
        }
    }

    private fun cacheUnreachableRoute(combatTarget: CombatTarget) {
        val route = nextFightBotUnreachableRoute(
            previous = unreachableCombatRoute,
            targetId = combatTarget.entity.id,
            targetBlock = combatTarget.entity.blockPosition(),
            playerBlock = player.blockPosition(),
            parkourEnabled = Parkour.running,
            parkourSprintAllowed = allowParkourSprint,
            currentTick = player.tickCount,
        )
        unreachableCombatRoute = route
        searchLimitedCombatRoute = null

        val cooldownTicks = route.retryTick - player.tickCount
        activeDiagnostics?.apply {
            noRouteCacheTicks = cooldownTicks
            unreachableCooldownTicks = cooldownTicks
            unreachableFailures = route.failures
        }
    }

    private fun findBestAttackRoute(context: CombatContext, combatTarget: CombatTarget): AttackRoute? {
        val diagnosticsStart = System.nanoTime()

        try {
            val candidates = generateAttackCandidates(context, combatTarget)
            activeDiagnostics?.routeCandidates = candidates.size

            candidates
                .asSequence()
                .mapNotNull { candidate -> createDirectRoute(context, candidate) }
                .minWithOrNull(attackRouteComparator())
                ?.let { return it }

            val pathCandidates = candidates
                .asSequence()
                .filter { it.playerDistanceSq <= pathfindingRange.sq() }
                .sortedWith(pathCandidateComparator())
                .take(MAX_PATHED_ATTACK_CANDIDATES)
                .toList()

            if (pathCandidates.isEmpty()) {
                return null
            }

            val pathResult = findMeasuredPathToAny(
                start = player.blockPosition(),
                goals = pathCandidates.map { it.blockPos }
            ) ?: return null

            if (pathResult.nodes.isEmpty()) {
                return null
            }

            val candidate = pathCandidates.firstOrNull {
                it.blockPos == pathResult.reachedGoal || it.blockPos.closerThan(pathResult.reachedGoal, stopRange)
            } ?: return null
            val path = DetailedBlockPath(pathResult.nodes, pathResult.steps, pathResult.totalCost)

            return AttackRoute(
                candidate = candidate,
                path = path,
                cost = path.totalCost,
                penalized = isGoalPenalized(candidate.blockPos)
            )
        } finally {
            activeDiagnostics?.routeSelectionNs =
                activeDiagnostics?.routeSelectionNs?.plus(System.nanoTime() - diagnosticsStart) ?: 0L
        }
    }

    private fun createDirectRoute(context: CombatContext, candidate: AttackCandidate): AttackRoute? {
        if (context.playerPosition.distanceTo(candidate.position) > directRange ||
            !hasStraightPath(context.playerPosition, candidate.position)
        ) {
            return null
        }

        activeDiagnostics?.directRoutes = activeDiagnostics?.directRoutes?.plus(1) ?: 0
        return AttackRoute(
            candidate = candidate,
            path = null,
            cost = context.playerPosition.distanceTo(candidate.position),
            penalized = isGoalPenalized(candidate.blockPos)
        )
    }

    private fun pathCandidateComparator(): Comparator<AttackCandidate> {
        return compareBy<AttackCandidate> { if (isGoalPenalized(it.blockPos)) 1 else 0 }
            .thenBy { if (it.dangerous) 1 else 0 }
            .thenBy { it.playerDistanceSq }
            .thenBy { it.targetLookDistanceSq }
    }

    private fun attackRouteComparator(): Comparator<AttackRoute> {
        return compareBy<AttackRoute> { it.cost + if (it.penalized) BAD_GOAL_COST else 0.0 }
            .thenBy { if (it.candidate.dangerous) 1 else 0 }
            .thenBy { it.candidate.targetLookDistanceSq }
            .thenBy { it.candidate.playerDistanceSq }
    }

    private fun generateAttackCandidates(context: CombatContext, combatTarget: CombatTarget): List<AttackCandidate> {
        val diagnosticsStart = System.nanoTime()
        val target = combatTarget.entity
        val attackRadius = getAttackSlotRadius(combatTarget)
        val targetLookPosition = calculateTargetLookPosition(combatTarget)
        val seenBlocks = hashSetOf<BlockPos>()

        val candidates = (-180..180 step ATTACK_POSITION_STEP)
            .mapNotNull { yaw ->
                val rotation = Rotation(yaw = yaw.toFloat(), pitch = 0.0F)
                val rawPosition = target.position().fma(attackRadius, rotation.directionVector)
                val blockPos = blockPosOf(rawPosition)
                val position = blockPos.bottomCenter

                if (!seenBlocks.add(blockPos)) {
                    activeDiagnostics?.candidateDuplicates =
                        activeDiagnostics?.candidateDuplicates?.plus(1) ?: 0
                    return@mapNotNull null
                }

                if (player.doesCollideAt(position)) {
                    activeDiagnostics?.candidateCollisions =
                        activeDiagnostics?.candidateCollisions?.plus(1) ?: 0
                    return@mapNotNull null
                }

                if (!canAttackFrom(position, target)) {
                    activeDiagnostics?.candidateAttackLosFailures =
                        activeDiagnostics?.candidateAttackLosFailures?.plus(1) ?: 0
                    return@mapNotNull null
                }

                val dangerous = rotation.angleTo(combatTarget.targetRotation) <= dangerousYawDiff
                ModuleDebug.debugGeometry(
                    this,
                    "Possible Position $yaw",
                    ModuleDebug.DebuggedPoint(position, if (!dangerous) Color4b.GREEN else Color4b.RED)
                )

                AttackCandidate(
                    position = position,
                    blockPos = blockPos,
                    dangerous = dangerous,
                    targetLookDistanceSq = position.distanceToSqr(targetLookPosition),
                    playerDistanceSq = position.distanceToSqr(context.playerPosition),
                )
            }

        activeDiagnostics?.apply {
            candidateScanNs += System.nanoTime() - diagnosticsStart
            acceptedCandidates = candidates.size
            acceptedCandidateBlocks.clear()
            acceptedCandidateBlocks += candidates.map { it.blockPos }.take(MAX_LOGGED_CANDIDATES)
        }

        return candidates
    }

    private fun activateAttackRoute(
        context: CombatContext,
        combatTarget: CombatTarget,
        route: AttackRoute
    ): Vec3 {
        val cachedPath = CachedCombatPath(
            targetId = combatTarget.entity.id,
            targetBlock = combatTarget.entity.blockPosition(),
            startBlock = player.blockPosition(),
            goalBlock = route.candidate.blockPos,
            destination = route.candidate.position,
            nodes = route.path?.nodes.orEmpty(),
            steps = route.path?.steps.orEmpty(),
            createdTick = player.tickCount,
        )
        cachedCombatPath = cachedPath
        unreachableCombatRoute = null
        searchLimitedCombatRoute = null
        parkourPenaltyNoRouteUntilTick = 0
        val climbableNodes = cachedPath.nodes.filter(::isClimbablePathNode)
        val stepUpNodes = cachedPath.steps.filter { it.kind == BlockPathNodeKind.STEP_UP }
        val parkourNodes = cachedPath.steps.filter { it.kind == BlockPathNodeKind.PARKOUR_JUMP }
        activeDiagnostics?.apply {
            selectedMode = if (route.path == null) "directAttack" else "pathAttack"
            selectedGoalBlock = route.candidate.blockPos
            selectedCost = route.cost
            selectedPathNodes = route.path?.nodes?.size ?: 0
            selectedClimbablePathNodes = climbableNodes.size
            selectedStepUpPathNodes = stepUpNodes.size
            selectedParkourPathNodes = parkourNodes.size
            parkourValidated = parkourNodes.isNotEmpty()
            climbablePathNodeBlocks.clear()
            climbablePathNodeBlocks += climbableNodes.take(MAX_LOGGED_CANDIDATES)
            stepUpPathNodeBlocks.clear()
            stepUpPathNodeBlocks += stepUpNodes.map { it.position }.take(MAX_LOGGED_CANDIDATES)
            parkourPathNodeBlocks.clear()
            parkourPathNodeBlocks += parkourNodes.map { it.position }.take(MAX_LOGGED_CANDIDATES)
            pathStepKinds.clear()
            pathStepKinds += cachedPath.steps.map { it.kind }.take(MAX_LOGGED_CANDIDATES)
        }

        return followCachedCombatPath(cachedPath, context.playerPosition) ?: route.candidate.position
    }

    private fun followCachedCombatPath(cachedPath: CachedCombatPath, playerPosition: Vec3): Vec3? {
        advanceReachedWaypoints(cachedPath, playerPosition)
        skipReachableWaypoints(cachedPath, playerPosition)

        if (cachedPath.waypointIndex >= cachedPath.nodes.size) {
            setActiveWaypoint(cachedPath.destination)
            return cachedPath.destination
        }

        val waypointStep = cachedPath.steps[cachedPath.waypointIndex]
        val waypointNode = waypointStep.position
        val parkourLaunchBlock = cachedPath.previousNode(cachedPath.waypointIndex)
            ?.takeIf { waypointStep.kind == BlockPathNodeKind.PARKOUR_JUMP }
        val parkourLaunchPoint = parkourLaunchBlock
            ?.let { fightBotParkourLaunchPoint(it, waypointNode) }
        val waypoint = when {
            waypointStep.kind == BlockPathNodeKind.PARKOUR_JUMP &&
                !shouldSteerToParkourLanding(waypointStep.kind) &&
                parkourLaunchPoint != null -> parkourLaunchPoint
            else -> waypointNode.bottomCenter
        }
        if (isStuckOnWaypoint(cachedPath, playerPosition, waypoint, waypointStep.kind)) {
            if (waypointStep.kind == BlockPathNodeKind.PARKOUR_JUMP && parkourLaunchBlock != null) {
                penalizeParkourEdge(parkourLaunchBlock, waypointNode)
            } else {
                penalizeGoal(cachedPath.goalBlock)
            }
            clearCombatPath()
            activeDiagnostics?.stuck = true
            return null
        }

        setActiveWaypoint(
            waypoint = waypoint,
            waypointNode = waypointNode,
            waypointKind = waypointStep.kind,
            parkourLaunchBlock = parkourLaunchBlock,
            parkourLaunchPoint = parkourLaunchPoint,
        )
        return waypoint
    }

    private fun setActiveWaypoint(
        waypoint: Vec3,
        waypointNode: Vec3i? = null,
        waypointKind: BlockPathNodeKind = BlockPathNodeKind.WALK,
        parkourLaunchBlock: Vec3i? = null,
        parkourLaunchPoint: Vec3? = null,
    ) {
        if (waypointKind == BlockPathNodeKind.PARKOUR_JUMP) {
            if (activeParkourLaunchBlock != parkourLaunchBlock || activeParkourLandingBlock != waypointNode) {
                activeParkourAirTicks = 0
                activeParkourJumpTicks = 0
                activeParkourMiss = false
                activeParkourPhase = FightBotParkourPhase.APPROACH
            }
            activeParkourLaunchBlock = parkourLaunchBlock
            activeParkourLandingBlock = waypointNode
            activeParkourLaunchPoint = parkourLaunchPoint
            if (activeParkourPhase == FightBotParkourPhase.NONE) {
                activeParkourPhase = FightBotParkourPhase.APPROACH
            }
        } else {
            activeParkourLaunchBlock = null
            activeParkourLandingBlock = null
            activeParkourLaunchPoint = null
            activeParkourAirTicks = 0
            activeParkourJumpTicks = 0
            activeParkourMiss = false
            activeParkourPhase = FightBotParkourPhase.NONE
        }

        activeWaypoint = waypoint
        activeWaypointBlock = waypointNode
        activeWaypointKind = waypointKind
        activeWaypointClimbable = waypointKind == BlockPathNodeKind.CLIMB ||
            (waypointNode?.let(::isClimbablePathNode) ?: false)
        activeDiagnostics?.apply {
            activeWaypointClimbable = this@KillAuraFightBot.activeWaypointClimbable
            activeWaypointKind = waypointKind
            stepUpJumpTicks = activeStepUpJumpTicks
            this.parkourLaunchBlock = this@KillAuraFightBot.activeParkourLaunchBlock
            this.parkourLandingBlock = this@KillAuraFightBot.activeParkourLandingBlock
            this.parkourLaunchPoint = this@KillAuraFightBot.activeParkourLaunchPoint
            this.parkourAirTicks = activeParkourAirTicks
            this.parkourPhase = activeParkourPhase
            this.parkourJumpReady = isActiveParkourJumpReady()
            this.parkourMiss = activeParkourMiss
            this.parkourPenaltyActive = isAnyParkourPenaltyActive()
        }
    }

    private fun advanceReachedWaypoints(cachedPath: CachedCombatPath, playerPosition: Vec3) {
        var nextIndex = cachedPath.waypointIndex
        val playerBlock = player.blockPosition()
        val onGround = player.onGround()
        while (nextIndex < cachedPath.nodes.size) {
            val currentStep = cachedPath.steps[nextIndex]
            val currentNode = currentStep.position
            val landedStepUp = hasLandedFightBotStepUpWaypoint(
                landingBlock = currentNode,
                playerBlock = playerBlock,
                playerY = playerPosition.y,
                onGround = onGround,
                waypointKind = currentStep.kind
            )
            val landedParkour = hasLandedFightBotParkourWaypoint(
                landingBlock = currentNode,
                playerBlock = playerBlock,
                onGround = onGround,
                waypointKind = currentStep.kind
            )
            val reached = when (currentStep.kind) {
                BlockPathNodeKind.STEP_UP -> landedStepUp
                else -> playerPosition.distanceToSqr(currentNode.bottomCenter) <= WAYPOINT_REACHED_DISTANCE_SQ ||
                    landedParkour
            }
            if (!reached) {
                break
            }

            if (!landedParkour) {
                break
            }

            if (currentStep.kind == BlockPathNodeKind.CLIMB || isClimbablePathNode(currentNode)) {
                val nextNodeIndex = nextIndex + 1
                if (nextNodeIndex < cachedPath.nodes.size) {
                    val nextStep = cachedPath.steps[nextNodeIndex]
                    val nextNode = nextStep.position
                    val isNextClimbableInColumn = nextStep.kind == BlockPathNodeKind.CLIMB &&
                        nextNode.x == currentNode.x && nextNode.z == currentNode.z

                    if (!isNextClimbableInColumn && playerPosition.y < currentNode.y + CLIMB_CLEAR_EPSILON) {
                        break
                    }
                }
            }
            nextIndex++
        }

        updateWaypointIndex(cachedPath, nextIndex)
    }

    private fun skipReachableWaypoints(cachedPath: CachedCombatPath, playerPosition: Vec3) {
        for (index in cachedPath.nodes.lastIndex downTo cachedPath.waypointIndex + 1) {
            if (containsRequiredFightBotWaypoint(cachedPath.steps, cachedPath.waypointIndex, index)) {
                continue
            }

            val waypoint = cachedPath.nodes[index].bottomCenter
            if (playerPosition.distanceTo(waypoint) <= directRange && hasStraightPath(playerPosition, waypoint)) {
                updateWaypointIndex(cachedPath, index)
                return
            }
        }
    }

    private fun CachedCombatPath.previousNode(index: Int): Vec3i? {
        return when {
            index < 0 -> null
            index == 0 -> startBlock
            index - 1 in steps.indices -> steps[index - 1].position
            else -> null
        }
    }

    private fun updateWaypointIndex(cachedPath: CachedCombatPath, waypointIndex: Int) {
        if (cachedPath.waypointIndex == waypointIndex) {
            return
        }

        cachedPath.waypointIndex = waypointIndex
        cachedPath.bestWaypointDistanceSq = Double.POSITIVE_INFINITY
        cachedPath.bestWaypointY = Double.NEGATIVE_INFINITY
        cachedPath.stagnantTicks = 0
    }

    private fun isStuckOnWaypoint(
        cachedPath: CachedCombatPath,
        playerPosition: Vec3,
        waypoint: Vec3,
        waypointKind: BlockPathNodeKind
    ): Boolean {
        val distanceSq = playerPosition.distanceToSqr(waypoint)
        if (shouldSuppressFightBotParkourStuck(waypointKind, player.onGround(), activeParkourAirTicks)) {
            cachedPath.bestWaypointDistanceSq = min(cachedPath.bestWaypointDistanceSq, distanceSq)
            cachedPath.bestWaypointY = max(cachedPath.bestWaypointY, playerPosition.y)
            cachedPath.stagnantTicks = 0
            return false
        }

        if (waypointKind == BlockPathNodeKind.PARKOUR_JUMP &&
            (activeParkourMiss || activeParkourAirTicks > PARKOUR_MAX_AIR_TICKS)
        ) {
            activeParkourMiss = true
            activeDiagnostics?.parkourMiss = true
            return true
        }

        val distanceProgress = distanceSq < cachedPath.bestWaypointDistanceSq - STUCK_PROGRESS_EPSILON_SQ
        val stepUpProgress = waypointKind == BlockPathNodeKind.STEP_UP &&
            hasFightBotStepUpVerticalProgress(playerPosition.y, cachedPath.bestWaypointY)

        if (distanceProgress || stepUpProgress) {
            cachedPath.bestWaypointDistanceSq = distanceSq
            cachedPath.bestWaypointY = playerPosition.y
            cachedPath.stagnantTicks = 0
            return false
        }

        cachedPath.stagnantTicks++
        val stuck = cachedPath.stagnantTicks >= STUCK_TICKS && distanceSq > WAYPOINT_REACHED_DISTANCE_SQ
        if (stuck && waypointKind == BlockPathNodeKind.STEP_UP) {
            activeDiagnostics?.stepUpStuck = true
        }
        if (stuck && waypointKind == BlockPathNodeKind.PARKOUR_JUMP) {
            activeParkourMiss = true
            activeDiagnostics?.parkourMiss = true
        }

        return stuck
    }

    private fun CachedCombatPath.isReusableFor(combatTarget: CombatTarget): Boolean {
        return targetId == combatTarget.entity.id &&
            targetBlock == combatTarget.entity.blockPosition() &&
            player.tickCount - createdTick <= PATH_CACHE_TICKS
    }

    private fun calculateRoutedDestination(context: CombatContext, destination: Vec3): Vec3 {
        val distance = context.playerPosition.distanceTo(destination)
        if (distance <= directRange && hasStraightPath(context.playerPosition, destination)) {
            setActiveWaypoint(destination)
            activeDiagnostics?.apply {
                selectedMode = "$selectedMode:direct"
                selectedGoalBlock = blockPosOf(destination)
            }
            return destination
        }

        if (distance <= pathfindingRange) {
            val destinationBlock = blockPosOf(destination)
            val path = findMeasuredPath(player.blockPosition(), destinationBlock)
            val nextStep = path?.steps?.firstOrNull()
            if (nextStep != null) {
                val nextNode = nextStep.position
                val parkourLaunchBlock = player.blockPosition().takeIf {
                    nextStep.kind == BlockPathNodeKind.PARKOUR_JUMP
                }
                val parkourLaunchPoint = parkourLaunchBlock?.let { fightBotParkourLaunchPoint(it, nextNode) }
                val waypoint = parkourLaunchPoint ?: nextNode.bottomCenter
                setActiveWaypoint(
                    waypoint = waypoint,
                    waypointNode = nextNode,
                    waypointKind = nextStep.kind,
                    parkourLaunchBlock = parkourLaunchBlock,
                    parkourLaunchPoint = parkourLaunchPoint,
                )
                activeDiagnostics?.apply {
                    selectedMode = "$selectedMode:path"
                    selectedGoalBlock = destinationBlock
                    selectedCost = path.totalCost
                    selectedPathNodes = path.nodes.size
                    selectedClimbablePathNodes = path.nodes.count(::isClimbablePathNode)
                    selectedStepUpPathNodes = path.steps.count { it.kind == BlockPathNodeKind.STEP_UP }
                    selectedParkourPathNodes = path.steps.count { it.kind == BlockPathNodeKind.PARKOUR_JUMP }
                    stepUpPathNodeBlocks.clear()
                    stepUpPathNodeBlocks += path.steps
                        .filter { it.kind == BlockPathNodeKind.STEP_UP }
                        .map { it.position }
                        .take(MAX_LOGGED_CANDIDATES)
                    parkourPathNodeBlocks.clear()
                    parkourPathNodeBlocks += path.steps
                        .filter { it.kind == BlockPathNodeKind.PARKOUR_JUMP }
                        .map { it.position }
                        .take(MAX_LOGGED_CANDIDATES)
                    pathStepKinds.clear()
                    pathStepKinds += path.steps.map { it.kind }.take(MAX_LOGGED_CANDIDATES)
                }
                return waypoint
            }
        }

        setActiveWaypoint(destination)
        activeDiagnostics?.apply {
            selectedMode = "$selectedMode:raw"
            selectedGoalBlock = blockPosOf(destination)
        }
        return destination
    }

    private fun findMeasuredPath(start: Vec3i, end: Vec3i): DetailedBlockPath? {
        val diagnosticsStart = System.nanoTime()
        val bounds = createFightBotPathSearchBounds(start, listOf(end))
        activeDiagnostics?.apply {
            pathRequests++
            pathGoalCount++
            pathMaxCost = combatPathMaxCost
            pathSearchMode = "singleGoal"
            pathBounds = bounds
        }

        val searchResult = findPathToAnyDetailedSearchResult(start, listOf(end), combatPathMaxCost, bounds)
        val path = searchResult.path?.let {
            DetailedBlockPath(it.nodes, it.steps, it.totalCost)
        }
        activeDiagnostics?.apply {
            pathfindingNs += System.nanoTime() - diagnosticsStart
            applyPathSearchStats(searchResult.stats)
            if (path == null || path.nodes.isEmpty()) {
                pathFailures++
            } else {
                pathSuccesses++
            }
        }

        return path
    }

    private fun findMeasuredPathToAny(start: Vec3i, goals: List<Vec3i>): DetailedBlockPathResult? {
        val diagnosticsStart = System.nanoTime()
        val bounds = createFightBotPathSearchBounds(start, goals)
        activeDiagnostics?.apply {
            pathRequests++
            pathGoalCount += goals.size
            pathMaxCost = combatPathMaxCost
            pathSearchMode = "multiGoal"
            pathBounds = bounds
        }

        val searchResult = findPathToAnyDetailedSearchResult(start, goals, combatPathMaxCost, bounds)
        val path = searchResult.path
        activeDiagnostics?.apply {
            pathfindingNs += System.nanoTime() - diagnosticsStart
            applyPathSearchStats(searchResult.stats)
            if (path == null || path.nodes.isEmpty()) {
                pathFailures++
            } else {
                pathSuccesses++
            }
        }

        return path
    }

    private fun createFightBotPathSearchBounds(start: Vec3i, goals: Collection<Vec3i>): BlockPathSearchBounds? {
        return fightBotPathSearchBounds(
            start = start,
            goals = goals,
            horizontalPadding = PATH_BOUNDS_HORIZONTAL_PADDING,
            maxStepUp = maxStepUp,
            maxDropDown = maxDropDown,
        )
    }

    private fun FightBotDiagnosticsTrace.applyPathSearchStats(stats: PathSearchStats) {
        pathExitReason = stats.exitReason.name
        pathIterations = stats.iterations
        pathVisitedNodes = stats.visitedNodes
        pathQueuePeak = stats.queuePeak
    }

    private fun calculateTargetLookPosition(combatTarget: CombatTarget): Vec3 {
        return combatTarget.entity.position().fma(
            getAttackSlotRadius(combatTarget),
            combatTarget.targetRotation.directionVector
        )
    }

    private fun getAttackSlotRadius(combatTarget: CombatTarget): Double {
        val configuredRange = min(ModuleKillAura.range.interactionRange, opponentRange).toDouble()
        return min(configuredRange, max(combatTarget.distance, MIN_ATTACK_SLOT_RADIUS))
    }

    private fun canAttackFrom(position: Vec3, target: Entity): Boolean {
        val start = position.add(0.0, player.eyeHeight.toDouble(), 0.0)
        val end = target.eyePosition

        val diagnosticsStart = System.nanoTime()
        val result = DDARaycast.hasLineOfSight(
            level = world,
            startX = start.x, startY = start.y, startZ = start.z,
            endX = end.x, endY = end.y, endZ = end.z,
            allowStartInside = true,
            isSolid = ::isBlockSolidOrHazardous
        )
        recordRaycastTime(diagnosticsStart)

        return result
    }

    private fun hasStraightPath(start: Vec3, end: Vec3): Boolean {
        val diagnosticsStart = System.nanoTime()
        val result = DDARaycast.hasLineOfSight(
            level = world,
            startX = start.x, startY = start.y + 0.5, startZ = start.z,
            endX = end.x, endY = end.y + 0.5, endZ = end.z,
            allowStartInside = true,
            isSolid = ::isBlockSolidOrHazardous
        )
        recordRaycastTime(diagnosticsStart)

        return result
    }

    private fun recordRaycastTime(startedNs: Long) {
        activeDiagnostics?.apply {
            raycasts++
            raycastNs += System.nanoTime() - startedNs
        }
    }

    private fun blockPosOf(position: Vec3): BlockPos {
        return BlockPos(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())
    }

    private fun publishDiagnostics(
        context: CombatContext,
        trace: FightBotDiagnosticsTrace,
        result: Vec3?
    ) {
        val totalMs = nsToMs(trace.totalNs)

        ModuleDebug.debugParameter(this, "FightBot Time", "%.3fms".format(totalMs))
        ModuleDebug.debugParameter(this, "FightBot Mode", trace.selectedMode)
        ModuleDebug.debugParameter(this, "FightBot Cache", trace.cacheState)
        ModuleDebug.debugParameter(this, "FightBot Candidates", trace.acceptedCandidates)
        ModuleDebug.debugParameter(this, "FightBot Paths", "${trace.pathSuccesses}/${trace.pathRequests}")
        ModuleDebug.debugParameter(this, "FightBot Path Time", "%.3fms".format(nsToMs(trace.pathfindingNs)))
        ModuleDebug.debugParameter(this, "FightBot Ray Time", "%.3fms".format(nsToMs(trace.raycastNs)))

        if (!shouldRecordDiagnostics(trace, totalMs)) {
            return
        }

        Diagnostics.lastRecordTick = player.tickCount
        val payload = createDiagnosticsPayload(context, trace, result)
        GenericDebugRecorder.recordDebugInfo(ModuleKillAura, "fightbotDiagnostics", payload)

        if (Diagnostics.clientLog) {
            logger.info("[FightBotDiagnostics] $payload")
        }
    }

    private fun shouldRecordDiagnostics(trace: FightBotDiagnosticsTrace, totalMs: Double): Boolean {
        val intervalElapsed = player.tickCount - Diagnostics.lastRecordTick >= Diagnostics.intervalTicks
        val slow = totalMs >= Diagnostics.slowThresholdMs

        return intervalElapsed || slow || trace.stuck
    }

    private fun createDiagnosticsPayload(
        context: CombatContext,
        trace: FightBotDiagnosticsTrace,
        result: Vec3?
    ): JsonObject {
        val target = context.combatTarget?.entity

        return JsonObject().apply {
            addProperty("tick", player.tickCount)
            addProperty("mode", trace.selectedMode)
            addProperty("cache", trace.cacheState)
            addProperty("stuck", trace.stuck)
            addProperty("pathSearchMode", trace.pathSearchMode)
            addProperty("pathExitReason", trace.pathExitReason)
            addProperty("parkourEnabled", trace.parkourEnabled)
            addProperty("parkourSprintAllowed", trace.parkourSprintAllowed)
            addProperty("pathMaxCost", trace.pathMaxCost)
            add("pathBounds", trace.pathBounds?.toJsonObject())
            add("timings", trace.toTimingJson())
            add("counts", trace.toCountJson())
            add("player", createEntityPositionJson(player))
            if (target != null) {
                add("target", createEntityPositionJson(target))
            }
            add("goal", result?.toJsonArray())
            add("waypoint", trace.activeWaypoint?.toJsonArray())
            addProperty("waypointClimbable", trace.activeWaypointClimbable)
            addProperty("waypointKind", trace.activeWaypointKind.name)
            add("waypointBlock", activeWaypointBlock?.toJsonObject())
            add("selectedGoalBlock", trace.selectedGoalBlock?.toJsonObject())
            add("acceptedCandidateBlocks", trace.acceptedCandidateBlocks.toBlockJsonArray())
            add("climbablePathNodes", trace.climbablePathNodeBlocks.toBlockJsonArray())
            add("stepUpPathNodes", trace.stepUpPathNodeBlocks.toBlockJsonArray())
            add("parkourPathNodes", trace.parkourPathNodeBlocks.toBlockJsonArray())
            add("selectedParkourPathNodes", trace.parkourPathNodeBlocks.toBlockJsonArray())
            add("pathStepKinds", trace.pathStepKinds.toJsonArray())
            addProperty("stepUpJumpTicks", trace.stepUpJumpTicks)
            addProperty("stepUpStuck", trace.stepUpStuck)
            add("parkourLaunchBlock", trace.parkourLaunchBlock?.toJsonObject())
            add("parkourLandingBlock", trace.parkourLandingBlock?.toJsonObject())
            add("parkourLaunchPoint", trace.parkourLaunchPoint?.toJsonArray())
            addProperty("parkourPhase", trace.parkourPhase.name)
            addProperty("parkourJumpReady", trace.parkourJumpReady)
            addProperty("parkourAirTicks", trace.parkourAirTicks)
            addProperty("parkourValidated", trace.parkourValidated)
            addProperty("parkourMiss", trace.parkourMiss)
            addProperty("parkourPenaltyActive", trace.parkourPenaltyActive)
            add("cachedPath", cachedCombatPath?.toJsonObject())
            if (Diagnostics.scanBlocks) {
                add("nearbyBlocks", scanNearbyDebugBlocks(target))
            }
        }
    }

    private fun FightBotDiagnosticsTrace.toTimingJson(): JsonObject {
        return JsonObject().apply {
            addProperty("totalMs", nsToMs(totalNs))
            addProperty("candidateScanMs", nsToMs(candidateScanNs))
            addProperty("routeSelectionMs", nsToMs(routeSelectionNs))
            addProperty("pathfindingMs", nsToMs(pathfindingNs))
            addProperty("raycastMs", nsToMs(raycastNs))
        }
    }

    private fun FightBotDiagnosticsTrace.toCountJson(): JsonObject {
        return JsonObject().apply {
            addProperty("acceptedCandidates", acceptedCandidates)
            addProperty("duplicateCandidates", candidateDuplicates)
            addProperty("collisionRejectedCandidates", candidateCollisions)
            addProperty("attackLosRejectedCandidates", candidateAttackLosFailures)
            addProperty("routeCandidates", routeCandidates)
            addProperty("directRoutes", directRoutes)
            addProperty("pathRequests", pathRequests)
            addProperty("pathSuccesses", pathSuccesses)
            addProperty("pathFailures", pathFailures)
            addProperty("raycasts", raycasts)
            addProperty("selectedCost", selectedCost)
            addProperty("selectedPathNodes", selectedPathNodes)
            addProperty("selectedClimbablePathNodes", selectedClimbablePathNodes)
            addProperty("selectedStepUpPathNodes", selectedStepUpPathNodes)
            addProperty("selectedParkourPathNodes", selectedParkourPathNodes)
            addProperty("noRouteCacheTicks", noRouteCacheTicks)
            addProperty("unreachableCooldownTicks", unreachableCooldownTicks)
            addProperty("unreachableFailures", unreachableFailures)
            addProperty("pathGoalCount", pathGoalCount)
            addProperty("pathMaxCost", pathMaxCost)
            addProperty("pathIterations", pathIterations)
            addProperty("pathVisitedNodes", pathVisitedNodes)
            addProperty("pathQueuePeak", pathQueuePeak)
            addProperty("parkourEdgeCandidates", parkourEdgeCandidates)
            addProperty("parkourEdgeAccepted", parkourEdgeAccepted)
            addProperty("parkourTransitRejected", parkourTransitRejected)
            addProperty("parkourLandingRejected", parkourLandingRejected)
            addProperty("parkourWalkableMidpointRejected", parkourWalkableMidpointRejected)
            addProperty("parkourSimulationRejected", parkourSimulationRejected)
            addProperty("stepUpJumpTicks", stepUpJumpTicks)
            addProperty("parkourAirTicks", parkourAirTicks)
            addProperty("parkourJumpReady", parkourJumpReady)
            addProperty("parkourPenaltyActive", parkourPenaltyActive)
        }
    }

    private fun CachedCombatPath.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("targetId", targetId)
            add("targetBlock", targetBlock.toJsonObject())
            add("startBlock", startBlock.toJsonObject())
            add("goalBlock", goalBlock.toJsonObject())
            add("destination", destination.toJsonArray())
            addProperty("createdTick", createdTick)
            addProperty("waypointIndex", waypointIndex)
            add("nodes", nodes.take(MAX_LOGGED_CANDIDATES).toBlockJsonArray())
            add("stepKinds", steps.map { it.kind }.take(MAX_LOGGED_CANDIDATES).toJsonArray())
            add(
                "climbableNodes",
                nodes.filter(::isClimbablePathNode).take(MAX_LOGGED_CANDIDATES).toBlockJsonArray()
            )
            add(
                "stepUpNodes",
                steps
                    .filter { it.kind == BlockPathNodeKind.STEP_UP }
                    .map { it.position }
                    .take(MAX_LOGGED_CANDIDATES)
                    .toBlockJsonArray()
            )
            add(
                "parkourNodes",
                steps
                    .filter { it.kind == BlockPathNodeKind.PARKOUR_JUMP }
                    .map { it.position }
                    .take(MAX_LOGGED_CANDIDATES)
                    .toBlockJsonArray()
            )
        }
    }

    private fun createEntityPositionJson(entity: Entity): JsonObject {
        return JsonObject().apply {
            addProperty("id", entity.id)
            addProperty("name", entity.name.string)
            addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
            add("pos", entity.position().toJsonArray())
            add("block", entity.blockPosition().toJsonObject())
            add("velocity", entity.deltaMovement.toJsonArray())
        }
    }

    private fun scanNearbyDebugBlocks(target: Entity?): JsonArray {
        data class DebugBlock(
            val pos: BlockPos,
            val blockId: String,
            val climbable: Boolean,
        )

        val playerBlock = player.blockPosition()
        val targetBlock = target?.blockPosition() ?: playerBlock
        val range = Diagnostics.blockScanRange
        val minX = min(playerBlock.x, targetBlock.x) - range
        val maxX = max(playerBlock.x, targetBlock.x) + range
        val minY = min(playerBlock.y, targetBlock.y)
        val maxY = max(playerBlock.y, targetBlock.y) + 3
        val minZ = min(playerBlock.z, targetBlock.z) - range
        val maxZ = max(playerBlock.z, targetBlock.z) + range
        val blocks = mutableListOf<DebugBlock>()
        val mutablePos = BlockPos.MutableBlockPos()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                for (z in minZ..maxZ) {
                    mutablePos.set(x, y, z)
                    val state = world.getBlockState(mutablePos)
                    val climbable = isClimbableBlock(mutablePos)
                    if (state.isAir || (state.getCollisionShape(world, mutablePos).isEmpty && !climbable)) {
                        continue
                    }

                    blocks += DebugBlock(
                        pos = mutablePos.immutable(),
                        blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
                        climbable = climbable,
                    )
                }
            }
        }

        val maxBlocks = Diagnostics.maxBlocks
        val playerSlice = blocks
            .sortedBy { it.pos.blockDistanceSq(playerBlock) }
            .take(maxBlocks / 2)
        val targetSlice = blocks
            .sortedBy { it.pos.blockDistanceSq(targetBlock) }
            .take(maxBlocks - playerSlice.size)
        val selected = (playerSlice + targetSlice)
            .distinctBy { it.pos }
            .let { selected ->
                if (selected.size >= maxBlocks) {
                    selected.take(maxBlocks)
                } else {
                    selected + blocks
                        .sortedBy { min(it.pos.blockDistanceSq(playerBlock), it.pos.blockDistanceSq(targetBlock)) }
                        .filterNot { block -> selected.any { it.pos == block.pos } }
                        .take(maxBlocks - selected.size)
                }
            }

        return JsonArray().also { result ->
            selected.forEach { block ->
                result.add(JsonObject().apply {
                    add("pos", block.pos.toJsonObject())
                    addProperty("block", block.blockId)
                    addProperty("climbable", block.climbable)
                })
            }
        }
    }

    private fun Iterable<Vec3i>.toBlockJsonArray(): JsonArray {
        return JsonArray().also { array ->
            forEach { array.add(it.toJsonObject()) }
        }
    }

    private fun Iterable<BlockPathNodeKind>.toJsonArray(): JsonArray {
        return JsonArray().also { array ->
            forEach { array.add(it.name) }
        }
    }

    private fun Vec3i.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("z", z)
        }
    }

    private fun BlockPathSearchBounds.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("minX", minX)
            addProperty("maxX", maxX)
            addProperty("minY", minY)
            addProperty("maxY", maxY)
            addProperty("minZ", minZ)
            addProperty("maxZ", maxZ)
        }
    }

    private fun nsToMs(ns: Long): Double {
        return ns / 1_000_000.0
    }

    private fun isGoalPenalized(blockPos: BlockPos): Boolean {
        val penalized = penalizedGoal
        if (penalized == null || player.tickCount >= penalizedGoalUntilTick) {
            penalizedGoal = null
            return false
        }

        return penalized == blockPos
    }

    private fun penalizeGoal(blockPos: BlockPos) {
        penalizedGoal = blockPos
        penalizedGoalUntilTick = player.tickCount + BAD_GOAL_TICKS
    }

    private fun shouldSteerToParkourLanding(
        waypointKind: BlockPathNodeKind = activeWaypointKind
    ): Boolean {
        return waypointKind == BlockPathNodeKind.PARKOUR_JUMP &&
            (activeParkourAirTicks > 0 ||
                activeParkourJumpTicks > 0 ||
                activeParkourPhase == FightBotParkourPhase.JUMP ||
                activeParkourPhase == FightBotParkourPhase.AIRBORNE ||
                isActiveParkourJumpReady(waypointKind))
    }

    private fun isActiveParkourJumpReady(
        waypointKind: BlockPathNodeKind = activeWaypointKind
    ): Boolean {
        return shouldJumpForFightBotParkourWaypoint(
            launchBlock = activeParkourLaunchBlock,
            landingBlock = activeParkourLandingBlock,
            launchPoint = activeParkourLaunchPoint,
            playerBlock = player.blockPosition(),
            playerPosition = player.position(),
            onGround = player.onGround(),
            isParkourWaypoint = waypointKind == BlockPathNodeKind.PARKOUR_JUMP,
            airTicks = activeParkourAirTicks,
            jumpTicks = activeParkourJumpTicks,
        )
    }

    private fun isParkourEdgePenalized(start: Vec3i, landing: Vec3i): Boolean {
        val penalty = activeParkourPenalty() ?: return false

        return penalty.edge.launchBlock == start.toImmutableBlockPos() &&
            penalty.edge.landingBlock == landing.toImmutableBlockPos()
    }

    private fun isAnyParkourPenaltyActive(): Boolean {
        return activeParkourPenalty() != null
    }

    private fun activeParkourPenalty(): FightBotPenalizedParkourEdge? {
        val penalty = penalizedParkourEdge ?: return null
        if (player.tickCount >= penalty.untilTick) {
            penalizedParkourEdge = null
            parkourPenaltyNoRouteUntilTick = 0
            return null
        }

        return penalty
    }

    private fun penalizeParkourEdge(launchBlock: Vec3i, landingBlock: Vec3i) {
        penalizedParkourEdge = FightBotPenalizedParkourEdge(
            edge = FightBotParkourEdge(
                launchBlock = launchBlock.toImmutableBlockPos(),
                landingBlock = landingBlock.toImmutableBlockPos(),
            ),
            untilTick = player.tickCount + BAD_PARKOUR_EDGE_TICKS,
        )
        parkourPenaltyNoRouteUntilTick = 0
        activeDiagnostics?.parkourPenaltyActive = true
    }

    private fun Vec3i.toImmutableBlockPos(): BlockPos {
        return BlockPos(x, y, z)
    }

    private fun clearCombatPath() {
        cachedCombatPath = null
        activeStepUpJumpBlock = null
        activeStepUpJumpTicks = 0
        activeParkourLaunchBlock = null
        activeParkourLandingBlock = null
        activeParkourLaunchPoint = null
        activeParkourAirTicks = 0
        activeParkourJumpTicks = 0
        activeParkourMiss = false
        activeParkourPhase = FightBotParkourPhase.NONE
    }

}
