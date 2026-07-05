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
package net.ccbluex.liquidbounce.features.module.modules.misc

import it.unimi.dsi.fastutil.ints.Int2LongLinkedOpenHashMap
import net.ccbluex.liquidbounce.config.ConfigSystem
import net.ccbluex.liquidbounce.config.types.group.Mode
import net.ccbluex.liquidbounce.config.types.group.ModeValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.AttackEntityEvent
import net.ccbluex.liquidbounce.event.events.DeathEvent
import net.ccbluex.liquidbounce.event.events.HealthUpdateEvent
import net.ccbluex.liquidbounce.event.events.NotificationEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TagEntityEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.tickHandler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager.modulesConfig
import net.ccbluex.liquidbounce.utils.client.notification
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import java.util.ArrayDeque

/**
 * TargetLock module
 *
 * Locks on to a target and prevents targeting other entities,
 * either [Temporary]ly on attack, by [Filter]ing by username, or [Adaptive]ly by damage taken.
 */
object ModuleTargetLock : ClientModule("TargetLock", ModuleCategories.MISC) {

    private const val MILLIS_PER_SECOND = 1000L
    private const val DAMAGE_SOURCE_CORRELATION_TIMEOUT = 1000L
    private const val STATIC_TARGET_AVAILABILITY_TIMEOUT = 500L

    private fun usernameKey(name: String) = name.lowercase()

    init {
        doNotIncludeAlways()
    }

    private val mode = choices("Mode", Temporary, arrayOf(Temporary, Filter, Adaptive))

    /**
     * This option will only lock the enemy on combat modules
     */
    private val combatOnly by boolean("CombatOnly", false, aliases = listOf("Combat"))

    private sealed class LockMode(name: String) : Mode(name) {
        override val parent: ModeValueGroup<*>
            get() = mode
        abstract fun isLockedOn(playerEntity: AbstractClientPlayer): Boolean
    }

    private object Filter : LockMode("Filter") {

        val usernamesValue = textList("Usernames", mutableListOf("Notch"))
        private val usernames by usernamesValue
        private val filterType by enumChoice("FilterType", FilterType.WHITELIST)

        init {
            usernamesValue.onChanged { usernames ->
                Adaptive.removeStaticTargets(usernames)
            }
        }

        enum class FilterType(override val tag: String) : Tagged {
            WHITELIST("Whitelist"),
            BLACKLIST("Blacklist")
        }

        override fun isLockedOn(playerEntity: AbstractClientPlayer): Boolean {
            val name = playerEntity.gameProfile.name

            return when (filterType) {
                FilterType.WHITELIST -> isListedUsername(name)
                FilterType.BLACKLIST -> !isListedUsername(name)
            }

        }

        fun isListedUsername(name: String): Boolean =
            usernames.any { it.equals(name, ignoreCase = true) }

        fun isWhitelist() = filterType == FilterType.WHITELIST

        fun addListedUsername(name: String): Boolean {
            if (isListedUsername(name)) {
                return false
            }

            updateUsernames { add(0, name) }
            return true
        }

        fun removeListedUsername(name: String): Boolean {
            val index = usernames.indexOfFirst { it.equals(name, ignoreCase = true) }
            if (index == -1) {
                return false
            }

            updateUsernames { removeAt(index) }
            return true
        }

        private fun updateUsernames(mutation: MutableList<String>.() -> Unit) {
            val updated = ArrayList(usernames)
            updated.mutation()
            usernamesValue.set(updated)
            persistSettings()
        }
    }

    val listedUsernames: List<String>
        get() = Filter.usernamesValue.get().toList()

    data class TemporaryTarget(val username: String, val remainingSeconds: Long)

    val temporaryTargets: List<TemporaryTarget>
        get() = Adaptive.getTemporaryTargets()

    @JvmStatic
    fun isListedUsername(name: String): Boolean = Filter.isListedUsername(name)

    fun addListedUsername(name: String): Boolean {
        ensureStaticListModeActive()
        val added = Filter.addListedUsername(name)
        if (added) {
            Adaptive.removeTemporaryTarget(name)
        }
        return added
    }

    fun removeListedUsername(name: String): Boolean {
        ensureStaticListModeActive()
        return Filter.removeListedUsername(name)
    }

    fun removeTemporaryTarget(name: String): Boolean = Adaptive.removeTemporaryTarget(name)

    private fun ensureStaticListModeActive() {
        if (mode.activeMode !== Filter && mode.activeMode !== Adaptive) {
            mode.setByString(Filter.tag)
        }
    }

    private fun persistSettings() {
        ConfigSystem.store(modulesConfig)
    }

    private object Temporary : LockMode("Temporary") {

        private val timeUntilReset by int("MaximumTime", 30, 0..120, "s")
        private val outOfRange by float("MaximumRange", 20f, 8f..40f)

        private val whenNoLock by enumChoice("WhenNoLock", NoLockMode.ALLOW_ALL)

        // Combination of [entityId] and [time]
        private val lockList = Int2LongLinkedOpenHashMap()

        enum class NoLockMode(override val tag: String) : Tagged {
            ALLOW_ALL("AllowAll"),
            ALLOW_NONE("AllowNone")
        }

        @Suppress("unused")
        private val attackHandler = handler<AttackEntityEvent> { event ->
            val target = event.entity as? AbstractClientPlayer ?: return@handler

            if (!lockList.containsKey(target.id)) {
                notification(
                    "TargetLock",
                    message("lockedOn", target.gameProfile.name, timeUntilReset),
                    NotificationEvent.Severity.INFO
                )
            }
            lockList.put(target.id, System.currentTimeMillis() + timeUntilReset * 1000L)
        }

        @Suppress("unused")
        private val cleanUpTask = tickHandler {
            if (player.isDeadOrDying) {
                lockList.clear()
                return@tickHandler
            }

            val currentTime = System.currentTimeMillis()
            lockList.int2LongEntrySet().removeIf {
                val entityId = it.intKey
                val time = it.longValue
                // Remove if entity is out of range
                val entity = world.getEntity(entityId) as? AbstractClientPlayer ?: return@removeIf true

                if (entity.isRemoved || entity.distanceToSqr(player) > outOfRange.sq()) {
                    notification(
                        "TargetLock",
                        message("outOfRange", entity.gameProfile.name),
                        NotificationEvent.Severity.INFO
                    )
                    return@removeIf true
                }

                // Remove if time is up
                if (time < currentTime) {
                    notification(
                        "TargetLock",
                        message("timeUp", entity.gameProfile.name),
                        NotificationEvent.Severity.INFO
                    )
                    return@removeIf true
                }

                false
            }
        }

        override fun isLockedOn(playerEntity: AbstractClientPlayer): Boolean {
            val entityId = playerEntity.id

            if (lockList.isEmpty()) {
                return when (whenNoLock) {
                    NoLockMode.ALLOW_ALL -> true
                    NoLockMode.ALLOW_NONE -> false
                }
            }

            return lockList.containsKey(entityId)
        }

    }

    private object Adaptive : LockMode("Adaptive") {

        private val damageThreshold by int("DamageThreshold", 10, 1..20, "hp")
        private val damageTimespan by int("DamageTimespan", 30, 1..500, "s")
        private val expiryTime by int("ExpiryTime", 300, 1..500, "s")
        private val whenNoTarget by enumChoice("WhenNoTarget", NoTargetMode.ALLOW_NONE)

        enum class NoTargetMode(override val tag: String) : Tagged {
            ALLOW_ALL("AllowAll"),
            ALLOW_NONE("AllowNone")
        }

        private data class PendingAttacker(val username: String, val time: Long)
        private data class DamageSample(val time: Long, val damage: Float)
        private data class AdaptiveTarget(val username: String, val expiresAt: Long)

        private var pendingAttacker: PendingAttacker? = null
        private var lastStaticWhitelistTargetSeen = 0L
        private val damageSamples = mutableMapOf<String, ArrayDeque<DamageSample>>()
        private val temporaryTargets = mutableMapOf<String, AdaptiveTarget>()

        override fun disable() {
            clearTransientState()
        }

        @Suppress("unused")
        private val packetHandler = handler<PacketEvent> { event ->
            if (event.origin != TransferOrigin.INCOMING) {
                return@handler
            }

            val packet = event.packet as? ClientboundDamageEventPacket ?: return@handler
            if (packet.entityId != player.id) {
                return@handler
            }

            val attacker = runCatching { packet.getSource(world).entity }.getOrNull() as? AbstractClientPlayer
                ?: return@handler
            if (attacker.id == player.id) {
                return@handler
            }

            synchronized(this) {
                pendingAttacker = PendingAttacker(
                    username = attacker.gameProfile.name,
                    time = System.currentTimeMillis()
                )
            }
        }

        @Suppress("unused")
        private val healthUpdateHandler = handler<HealthUpdateEvent> { event ->
            val damage = event.previousHealth - event.health
            if (damage <= 0f) {
                return@handler
            }

            val currentTime = System.currentTimeMillis()
            val attacker = synchronized(this) {
                val pending = pendingAttacker
                if (pending == null || currentTime - pending.time > DAMAGE_SOURCE_CORRELATION_TIMEOUT) {
                    pendingAttacker = null
                    null
                } else {
                    pendingAttacker = null
                    pending
                }
            } ?: return@handler

            recordDamage(attacker, damage, currentTime)
        }

        @Suppress("unused")
        private val cleanUpTask = tickHandler {
            val currentTime = System.currentTimeMillis()
            if (player.isDeadOrDying) {
                clearTransientState()
            }

            cleanup(currentTime, notifyExpired = true)
        }

        @Suppress("unused")
        private val deathHandler = handler<DeathEvent> {
            clearTransientState()
        }

        @Suppress("unused")
        private val worldChangeHandler = handler<WorldChangeEvent> {
            clearTransientState()
        }

        private fun recordDamage(attacker: PendingAttacker, damage: Float, currentTime: Long) {
            val targetKey = usernameKey(attacker.username)
            val shouldNotify = synchronized(this) {
                cleanup(currentTime, notifyExpired = false)

                if (Filter.isListedUsername(attacker.username)) {
                    damageSamples.remove(targetKey)
                    temporaryTargets.remove(targetKey)
                    false
                } else {
                    val samples = damageSamples.getOrPut(targetKey, ::ArrayDeque)
                    samples.addLast(DamageSample(currentTime, damage))
                    pruneDamageSamples(samples, currentTime)
                    val totalDamage = samples.sumOf { it.damage.toDouble() }
                    if (totalDamage < damageThreshold) {
                        false
                    } else {
                        val wasAlreadyTargeted = temporaryTargets.containsKey(targetKey)
                        temporaryTargets[targetKey] = AdaptiveTarget(
                            username = attacker.username,
                            expiresAt = currentTime + expiryTime * MILLIS_PER_SECOND
                        )
                        !wasAlreadyTargeted
                    }
                }
            }

            if (shouldNotify) {
                notification(
                    "TargetLock",
                    message("lockedOn", attacker.username, expiryTime),
                    NotificationEvent.Severity.INFO
                )
            }
        }

        private fun pruneDamageSamples(samples: ArrayDeque<DamageSample>, currentTime: Long) {
            val cutoffTime = currentTime - damageTimespan * MILLIS_PER_SECOND
            while (samples.peekFirst()?.time?.let { it < cutoffTime } == true) {
                samples.removeFirst()
            }
        }

        private fun cleanup(currentTime: Long, notifyExpired: Boolean) {
            synchronized(this) {
                if (pendingAttacker?.let { currentTime - it.time > DAMAGE_SOURCE_CORRELATION_TIMEOUT } == true) {
                    pendingAttacker = null
                }

                val damageIterator = damageSamples.iterator()
                while (damageIterator.hasNext()) {
                    val samples = damageIterator.next().value
                    pruneDamageSamples(samples, currentTime)
                    if (samples.isEmpty()) {
                        damageIterator.remove()
                    }
                }

                val targetIterator = temporaryTargets.iterator()
                while (targetIterator.hasNext()) {
                    val (targetKey, target) = targetIterator.next()
                    val expired = target.expiresAt <= currentTime
                    val staticTarget = Filter.isListedUsername(target.username)

                    if (expired || staticTarget) {
                        targetIterator.remove()
                        damageSamples.remove(targetKey)

                        if (expired && notifyExpired) {
                            notification(
                                "TargetLock",
                                message("timeUp", target.username),
                                NotificationEvent.Severity.INFO
                            )
                        }
                    }
                }
            }
        }

        private fun clearTransientState() {
            synchronized(this) {
                pendingAttacker = null
                lastStaticWhitelistTargetSeen = 0L
                damageSamples.clear()
            }
        }

        fun getTemporaryTargets(): List<TemporaryTarget> {
            val currentTime = System.currentTimeMillis()
            cleanup(currentTime, notifyExpired = false)

            return synchronized(this) {
                temporaryTargets.values
                    .sortedBy { it.username.lowercase() }
                    .map {
                        TemporaryTarget(
                            username = it.username,
                            remainingSeconds = ((it.expiresAt - currentTime).coerceAtLeast(0L) + MILLIS_PER_SECOND - 1L) /
                                MILLIS_PER_SECOND
                        )
                    }
            }
        }

        fun removeTemporaryTarget(name: String): Boolean {
            val targetKey = usernameKey(name)
            return synchronized(this) {
                damageSamples.remove(targetKey)
                temporaryTargets.remove(targetKey) != null
            }
        }

        fun removeStaticTargets(usernames: Collection<String>) {
            val staticTargets = usernames.mapTo(HashSet(), ::usernameKey)
            synchronized(this) {
                temporaryTargets.keys.removeIf { targetKey ->
                    if (targetKey in staticTargets) {
                        damageSamples.remove(targetKey)
                        true
                    } else {
                        false
                    }
                }
                damageSamples.keys.removeIf { it in staticTargets }
            }
        }

        override fun isLockedOn(playerEntity: AbstractClientPlayer): Boolean {
            val name = playerEntity.gameProfile.name
            val isStaticTarget = Filter.isListedUsername(name)
            val currentTime = System.currentTimeMillis()

            if (isStaticTarget) {
                val isWhitelist = Filter.isWhitelist()
                if (isWhitelist) {
                    synchronized(this) {
                        lastStaticWhitelistTargetSeen = currentTime
                    }
                }
                return isWhitelist
            }

            val targetKey = usernameKey(name)
            return synchronized(this) {
                cleanup(currentTime, notifyExpired = false)

                if (temporaryTargets[targetKey]?.expiresAt?.let { it > currentTime } == true) {
                    return@synchronized true
                }

                val hasAvailableStaticTarget = Filter.isWhitelist() &&
                    currentTime - lastStaticWhitelistTargetSeen <= STATIC_TARGET_AVAILABILITY_TIMEOUT
                val hasEligibleTarget = temporaryTargets.isNotEmpty() || hasAvailableStaticTarget
                if (hasEligibleTarget) {
                    false
                } else {
                    when (whenNoTarget) {
                        NoTargetMode.ALLOW_ALL -> true
                        NoTargetMode.ALLOW_NONE -> false
                    }
                }
            }
        }

    }

    @Suppress("unused")
    private val tagEntityEvent = handler<TagEntityEvent> { event ->
        if (event.entity !is AbstractClientPlayer || this@ModuleTargetLock.isLockedOn(event.entity)) {
            return@handler
        }

        if (combatOnly) {
            event.dontTarget()
        } else {
            event.ignore()
        }
    }

    /**
     * Check if [entity] is in your focus
     */
    private fun isLockedOn(entity: AbstractClientPlayer): Boolean {
        if (!running) {
            return false
        }

        return mode.activeMode.isLockedOn(entity)
    }

}
