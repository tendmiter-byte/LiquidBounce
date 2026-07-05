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

package net.ccbluex.liquidbounce.integration.interop.protocol.rest.v1.client

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ccbluex.liquidbounce.features.module.modules.misc.ModuleTargetLock
import net.ccbluex.liquidbounce.utils.kotlin.Minecraft
import net.ccbluex.netty.http.routing.Routing

@JvmRecord
private data class TargetRequest(val username: String)

private fun Routing.getTargets() = get {
    val array = JsonArray()
    ModuleTargetLock.listedUsernames.forEach { array.add(it) }
    call.respond(array)
}

private fun Routing.postTarget() = post {
    val body = call.receive<TargetRequest>()
    val success = withContext(Dispatchers.Minecraft) {
        ModuleTargetLock.addListedUsername(body.username)
    }
    if (success) {
        call.respondNoContent()
    } else {
        call.forbidden("Player already targeted")
    }
}

private fun Routing.deleteTarget() = delete {
    val body = call.receive<TargetRequest>()
    val success = withContext(Dispatchers.Minecraft) {
        ModuleTargetLock.removeListedUsername(body.username)
    }
    if (success) {
        call.respondNoContent()
    } else {
        call.forbidden("Player is not targeted")
    }
}

private fun Routing.getTemporaryTargets() = get {
    val targets = withContext(Dispatchers.Minecraft) {
        ModuleTargetLock.temporaryTargets
    }
    val array = JsonArray()
    targets.forEach { target ->
        array.add(JsonObject().apply {
            addProperty("username", target.username)
            addProperty("remainingSeconds", target.remainingSeconds)
        })
    }
    call.respond(array)
}

private fun Routing.deleteTemporaryTarget() = delete {
    val body = call.receive<TargetRequest>()
    val success = withContext(Dispatchers.Minecraft) {
        ModuleTargetLock.removeTemporaryTarget(body.username)
    }
    if (success) {
        call.respondNoContent()
    } else {
        call.forbidden("Player is not temporarily targeted")
    }
}

internal fun Routing.targetLockRoutes() = route("/targets") {
    getTargets()
    postTarget()
    deleteTarget()
    route("/temporary") {
        getTemporaryTargets()
        deleteTemporaryTarget()
    }
}
