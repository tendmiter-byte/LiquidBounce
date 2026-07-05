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
import net.ccbluex.liquidbounce.utils.block.AStarPathBuilder
import net.ccbluex.liquidbounce.utils.block.BlockPath
import net.ccbluex.liquidbounce.utils.block.BlockPathResult
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
import net.ccbluex.liquidbounce.utils.raytracing.PathfinderRaycast
import net.ccbluex.liquidbounce.utils.raytracing.threadLocalPos
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
    val path: BlockPath?,
    val cost: Double,
    val penalized: Boolean,
)

private data class CachedCombatPath(
    val targetId: Int,
    val targetBlock: BlockPos,
    val goalBlock: BlockPos,
    val destination: Vec3,
    val nodes: List<Vec3i>,
    val createdTick: Int,
    var waypointIndex: Int = 0,
    var bestWaypointDistanceSq: Double = Double.POSITIVE_INFINITY,
    var stagnantTicks: Int = 0,
)

internal data class FightBotUnreachableRoute(
    val targetId: Int,
    val targetBlock: BlockPos,
    val playerYBand: Int,
    val originBlock: BlockPos,
    val failures: Int,
    val retryTick: Int,
)

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
    var pathSearchMode = "none"
    var stuck = false
    var activeWaypointClimbable = false
    var selectedGoalBlock: BlockPos? = null
    var activeWaypoint: Vec3? = null
    val acceptedCandidateBlocks = mutableListOf<BlockPos>()
    val climbablePathNodeBlocks = mutableListOf<Vec3i>()
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
): Boolean {
    return route.targetId != targetId ||
        route.targetBlock != targetBlock ||
        route.playerYBand != fightBotUnreachableYBand(playerBlock) ||
        route.originBlock.horizontalBlockDistanceSq(playerBlock) > UNREACHABLE_ORIGIN_RETRY_DISTANCE_SQ
}

internal fun isFightBotUnreachableRouteCoolingDown(
    route: FightBotUnreachableRoute,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    currentTick: Int,
): Boolean {
    if (shouldInvalidateFightBotUnreachableRoute(route, targetId, targetBlock, playerBlock)) {
        return false
    }

    return currentTick < route.retryTick
}

internal fun nextFightBotUnreachableRoute(
    previous: FightBotUnreachableRoute?,
    targetId: Int,
    targetBlock: BlockPos,
    playerBlock: BlockPos,
    currentTick: Int,
): FightBotUnreachableRoute {
    val canReusePrevious = previous != null &&
        !shouldInvalidateFightBotUnreachableRoute(previous, targetId, targetBlock, playerBlock)
    val failures = if (canReusePrevious) previous.failures + 1 else 1
    val originBlock = if (canReusePrevious) previous.originBlock else playerBlock
    val cooldownTicks = fightBotUnreachableCooldownTicks(failures)

    return FightBotUnreachableRoute(
        targetId = targetId,
        targetBlock = targetBlock,
        playerYBand = fightBotUnreachableYBand(playerBlock),
        originBlock = originBlock,
        failures = failures,
        retryTick = currentTick + cooldownTicks,
    )
}

private fun Vec3i.horizontalBlockDistanceSq(other: Vec3i): Int {
    val x = this.x - other.x
    val z = this.z - other.z
    return x * x + z * z
}

private const val PATH_CACHE_TICKS = 40
private const val PATH_MAX_COST = 40
private const val MAX_PATHED_ATTACK_CANDIDATES = 8
private const val WAYPOINT_REACHED_DISTANCE_SQ = 0.55 * 0.55
private const val CLIMB_ASCEND_EPSILON = 0.1
private const val CLIMB_CLEAR_EPSILON = 0.1
private const val STUCK_TICKS = 8
private const val STUCK_PROGRESS_EPSILON_SQ = 0.04
private const val BAD_GOAL_TICKS = 25
private const val BAD_GOAL_COST = 20.0
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
object KillAuraFightBot : NavigationBaseValueGroup<CombatContext>(ModuleKillAura, "FightBot", false), AStarPathBuilder {

    override val allowDiagonal: Boolean get() = true
    override val allowClimbableNavigation: Boolean get() = true
    override val maxIterations: Int get() = 500
    override val stopRange: Double get() = 0.75

    private val opponentRange by float("OpponentRange", 3f, 0.1f..10f)
    private val dangerousYawDiff by float("DangerousYaw", 55f, 0f..90f, suffix = "°")
    private val runawayOnCooldown by boolean("RunawayOnCooldown", true)
    private val pathfindingRange by float("PathfindingRange", 15f, 2f..50f)
    private val directRange by float("DirectRange", 4f, 1f..10f)

    private var cachedCombatPath: CachedCombatPath? = null
    private var unreachableCombatRoute: FightBotUnreachableRoute? = null
    private var activeWaypoint: Vec3? = null
    private var activeWaypointBlock: Vec3i? = null
    private var activeWaypointClimbable = false
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
                diagnostics.activeWaypoint = activeWaypoint
                publishDiagnostics(context, diagnostics, result)
            }
            activeDiagnostics = null
        }
    }

    private fun calculateGoalPositionInternal(context: CombatContext): Vec3? {
        activeWaypoint = null
        activeWaypointBlock = null
        activeWaypointClimbable = false

        if (LeaderFollower.running && LeaderFollower.username.isNotEmpty()) {
            clearCombatPath()
            unreachableCombatRoute = null
            val leader = world.players().find { it.gameProfile.name == LeaderFollower.username }
            activeDiagnostics?.selectedMode = "leader"
            return leader?.let { calculateLeaderGoalPosition(it.position(), context.playerPosition) }
        }

        val combatTarget = context.combatTarget ?: run {
            clearCombatPath()
            unreachableCombatRoute = null
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

        val degrees = getDegreesRelativeToView(goal.subtract(player.position()), player.yRot)
        return getDirectionalInputForDegrees(DirectionalInput.NONE, degrees, deadAngle = 20.0F)
    }

    override fun handleMovementAssist(event: MovementInputEvent, context: CombatContext, goal: Vec3) {
        super.handleMovementAssist(event, context, goal)

        val waypoint = activeWaypoint ?: goal
        val needsStepUp = !activeWaypointClimbable && waypoint.y > player.y + 0.45 && player.onGround()
        val needsClimb = shouldJumpForFightBotWaypoint(
            waypointY = waypoint.y,
            playerY = player.y,
            onClimbable = player.onClimbable(),
            isClimbableWaypoint = activeWaypointClimbable,
        )
        val blockedWhileMoving = !activeWaypointClimbable &&
            player.horizontalCollision &&
            player.onGround() &&
            player.position().distanceToSqr(waypoint) > WAYPOINT_REACHED_DISTANCE_SQ

        if (needsStepUp || needsClimb || blockedWhileMoving) {
            event.jump = true
        }
    }

    /**
     * Gets rotation based on movement and target
     *
     * @return Movement rotation or null if no target
     */
    override fun getMovementRotation(): Rotation {
        val movementRotation = super.getMovementRotation()
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

                val pathClear = PathfinderRaycast.hasLineOfSight(
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

        findBestAttackRoute(context, combatTarget)?.let { route ->
            return activateAttackRoute(context, combatTarget, route)
        }

        clearCombatPath()
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
            selectedClimbablePathNodes = climbableNodes.size
            climbablePathNodeBlocks.clear()
            climbablePathNodeBlocks += climbableNodes.take(MAX_LOGGED_CANDIDATES)
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
                playerBlock = playerBlock
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

    private fun cacheUnreachableRoute(combatTarget: CombatTarget) {
        val route = nextFightBotUnreachableRoute(
            previous = unreachableCombatRoute,
            targetId = combatTarget.entity.id,
            targetBlock = combatTarget.entity.blockPosition(),
            playerBlock = player.blockPosition(),
            currentTick = player.tickCount,
        )
        unreachableCombatRoute = route

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
            val path = BlockPath(pathResult.nodes, pathResult.totalCost)

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
            goalBlock = route.candidate.blockPos,
            destination = route.candidate.position,
            nodes = route.path?.nodes.orEmpty(),
            createdTick = player.tickCount,
        )
        cachedCombatPath = cachedPath
        unreachableCombatRoute = null
        val climbableNodes = cachedPath.nodes.filter(::isClimbablePathNode)
        activeDiagnostics?.apply {
            selectedMode = if (route.path == null) "directAttack" else "pathAttack"
            selectedGoalBlock = route.candidate.blockPos
            selectedCost = route.cost
            selectedPathNodes = route.path?.nodes?.size ?: 0
            selectedClimbablePathNodes = climbableNodes.size
            climbablePathNodeBlocks.clear()
            climbablePathNodeBlocks += climbableNodes.take(MAX_LOGGED_CANDIDATES)
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

        val waypointNode = cachedPath.nodes[cachedPath.waypointIndex]
        val waypoint = waypointNode.bottomCenter
        if (isStuckOnWaypoint(cachedPath, playerPosition, waypoint)) {
            penalizeGoal(cachedPath.goalBlock)
            clearCombatPath()
            activeDiagnostics?.stuck = true
            return null
        }

        setActiveWaypoint(waypoint, waypointNode)
        return waypoint
    }

    private fun setActiveWaypoint(waypoint: Vec3, waypointNode: Vec3i? = null) {
        activeWaypoint = waypoint
        activeWaypointBlock = waypointNode
        activeWaypointClimbable = waypointNode?.let(::isClimbablePathNode) ?: false
        activeDiagnostics?.activeWaypointClimbable = activeWaypointClimbable
    }

    private fun advanceReachedWaypoints(cachedPath: CachedCombatPath, playerPosition: Vec3) {
        var nextIndex = cachedPath.waypointIndex
        while (nextIndex < cachedPath.nodes.size &&
            playerPosition.distanceToSqr(cachedPath.nodes[nextIndex].bottomCenter) <= WAYPOINT_REACHED_DISTANCE_SQ
        ) {
            // Don't advance past a climbable node to a non-climbable node
            // unless the player has actually climbed above it
            val currentNode = cachedPath.nodes[nextIndex]
            if (isClimbablePathNode(currentNode)) {
                val nextNodeIndex = nextIndex + 1
                if (nextNodeIndex < cachedPath.nodes.size) {
                    val nextNode = cachedPath.nodes[nextNodeIndex]
                    val isNextClimbableInColumn = isClimbablePathNode(nextNode) &&
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
            if (containsClimbableWaypoint(cachedPath.nodes, cachedPath.waypointIndex, index, ::isClimbablePathNode)) {
                continue
            }

            val waypoint = cachedPath.nodes[index].bottomCenter
            if (playerPosition.distanceTo(waypoint) <= directRange && hasStraightPath(playerPosition, waypoint)) {
                updateWaypointIndex(cachedPath, index)
                return
            }
        }
    }

    private fun updateWaypointIndex(cachedPath: CachedCombatPath, waypointIndex: Int) {
        if (cachedPath.waypointIndex == waypointIndex) {
            return
        }

        cachedPath.waypointIndex = waypointIndex
        cachedPath.bestWaypointDistanceSq = Double.POSITIVE_INFINITY
        cachedPath.stagnantTicks = 0
    }

    private fun isStuckOnWaypoint(cachedPath: CachedCombatPath, playerPosition: Vec3, waypoint: Vec3): Boolean {
        val distanceSq = playerPosition.distanceToSqr(waypoint)
        if (distanceSq < cachedPath.bestWaypointDistanceSq - STUCK_PROGRESS_EPSILON_SQ) {
            cachedPath.bestWaypointDistanceSq = distanceSq
            cachedPath.stagnantTicks = 0
            return false
        }

        cachedPath.stagnantTicks++
        return cachedPath.stagnantTicks >= STUCK_TICKS && distanceSq > WAYPOINT_REACHED_DISTANCE_SQ
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
            val nextNode = path?.nodes?.firstOrNull()
            if (nextNode != null) {
                val waypoint = nextNode.bottomCenter
                setActiveWaypoint(waypoint, nextNode)
                activeDiagnostics?.apply {
                    selectedMode = "$selectedMode:path"
                    selectedGoalBlock = destinationBlock
                    selectedCost = path.totalCost
                    selectedPathNodes = path.nodes.size
                    selectedClimbablePathNodes = path.nodes.count(::isClimbablePathNode)
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

    private fun findMeasuredPath(start: Vec3i, end: Vec3i): BlockPath? {
        val diagnosticsStart = System.nanoTime()
        activeDiagnostics?.apply {
            pathRequests++
            pathGoalCount++
            pathSearchMode = "singleGoal"
        }

        val path = findPathResult(start, end, PATH_MAX_COST)
        activeDiagnostics?.apply {
            pathfindingNs += System.nanoTime() - diagnosticsStart
            if (path == null || path.nodes.isEmpty()) {
                pathFailures++
            } else {
                pathSuccesses++
            }
        }

        return path
    }

    private fun findMeasuredPathToAny(start: Vec3i, goals: List<Vec3i>): BlockPathResult? {
        val diagnosticsStart = System.nanoTime()
        activeDiagnostics?.apply {
            pathRequests++
            pathGoalCount += goals.size
            pathSearchMode = "multiGoal"
        }

        val path = findPathToAnyResult(start, goals, PATH_MAX_COST)
        activeDiagnostics?.apply {
            pathfindingNs += System.nanoTime() - diagnosticsStart
            if (path == null || path.nodes.isEmpty()) {
                pathFailures++
            } else {
                pathSuccesses++
            }
        }

        return path
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
        val result = PathfinderRaycast.hasLineOfSight(
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
        val result = PathfinderRaycast.hasLineOfSight(
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
            add("timings", trace.toTimingJson())
            add("counts", trace.toCountJson())
            add("player", createEntityPositionJson(player))
            if (target != null) {
                add("target", createEntityPositionJson(target))
            }
            add("goal", result?.toJsonArray())
            add("waypoint", trace.activeWaypoint?.toJsonArray())
            addProperty("waypointClimbable", trace.activeWaypointClimbable)
            add("waypointBlock", activeWaypointBlock?.toJsonObject())
            add("selectedGoalBlock", trace.selectedGoalBlock?.toJsonObject())
            add("acceptedCandidateBlocks", trace.acceptedCandidateBlocks.toBlockJsonArray())
            add("climbablePathNodes", trace.climbablePathNodeBlocks.toBlockJsonArray())
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
            addProperty("noRouteCacheTicks", noRouteCacheTicks)
            addProperty("unreachableCooldownTicks", unreachableCooldownTicks)
            addProperty("unreachableFailures", unreachableFailures)
            addProperty("pathGoalCount", pathGoalCount)
        }
    }

    private fun CachedCombatPath.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("targetId", targetId)
            add("targetBlock", targetBlock.toJsonObject())
            add("goalBlock", goalBlock.toJsonObject())
            add("destination", destination.toJsonArray())
            addProperty("createdTick", createdTick)
            addProperty("waypointIndex", waypointIndex)
            add("nodes", nodes.take(MAX_LOGGED_CANDIDATES).toBlockJsonArray())
            add(
                "climbableNodes",
                nodes.filter(::isClimbablePathNode).take(MAX_LOGGED_CANDIDATES).toBlockJsonArray()
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
        val playerBlock = player.blockPosition()
        val targetBlock = target?.blockPosition() ?: playerBlock
        val range = Diagnostics.blockScanRange
        val minX = min(playerBlock.x, targetBlock.x) - range
        val maxX = max(playerBlock.x, targetBlock.x) + range
        val minY = min(playerBlock.y, targetBlock.y)
        val maxY = max(playerBlock.y, targetBlock.y) + 3
        val minZ = min(playerBlock.z, targetBlock.z) - range
        val maxZ = max(playerBlock.z, targetBlock.z) + range
        val result = JsonArray()
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

                    result.add(JsonObject().apply {
                        add("pos", mutablePos.immutable().toJsonObject())
                        addProperty("block", BuiltInRegistries.BLOCK.getKey(state.block).toString())
                        addProperty("climbable", climbable)
                    })

                    if (result.size() >= Diagnostics.maxBlocks) {
                        return result
                    }
                }
            }
        }

        return result
    }

    private fun Iterable<Vec3i>.toBlockJsonArray(): JsonArray {
        return JsonArray().also { array ->
            forEach { array.add(it.toJsonObject()) }
        }
    }

    private fun Vec3i.toJsonObject(): JsonObject {
        return JsonObject().apply {
            addProperty("x", x)
            addProperty("y", y)
            addProperty("z", z)
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

    private fun clearCombatPath() {
        cachedCombatPath = null
    }

}
