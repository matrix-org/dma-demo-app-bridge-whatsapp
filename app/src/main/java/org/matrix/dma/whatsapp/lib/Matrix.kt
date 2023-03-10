package org.matrix.dma.whatsapp.lib

import android.util.Log
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class Matrix(var accessToken: String?, val homeserverUrl: String, val asToken: String) {
    public var actingUserId: String? = null

    public fun createUser(id: UserID, name: String): String {
        val localpart = getLocalpartForUserId(id)
        var req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/register${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.asToken}")
            .post(JSONObject()
                .put("type", "m.login.application_service")
                .put("username", localpart)
                .toString().toRequestBody(JSON)
            )
            .build()
        this.doRequest(req)!! // ignore response - we just want to know it happened

        val userId = "@$localpart:${this.getDomain()!!}"

        req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/profile/$userId/displayname?user_id=$userId")
            .addHeader("Authorization", "Bearer ${this.asToken}")
            .put(JSONObject()
                .put("displayname", name)
                .toString().toRequestBody(JSON)
            )
            .build()
        this.doRequest(req)!! // ignore response - not important if it fails

        return userId
    }

    public fun appserviceJoin(userId: String, roomId: String) {
        var req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/invite${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .post(JSONObject()
                .put("user_id", userId)
                .toString().toRequestBody(JSON)
            )
            .build()
        this.doRequest(req)!! // ignore response - not important if it fails (probably because they're already invited)

        req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/join?user_id=$userId")
            .addHeader("Authorization", "Bearer ${this.asToken}")
            .post(JSONObject().toString().toRequestBody(JSON))
            .build()
        this.doRequest(req)!! // ignore response - it'll probably be fine
    }

    public fun getDomain(): String? {
        val whoami = this.whoAmI() ?: return null

        val parts = whoami.split(":")
        return parts.slice(1 until parts.size).joinToString(":")
    }

    public fun getLocalpart(): String? {
        val whoami = this.whoAmI() ?: return null

        return extractLocalpart(whoami)
    }

    public fun ensureRegistered(): String? {
        if (this.accessToken !== null) {
            val origActingUser = this.actingUserId
            this.actingUserId = null
            val domain = this.getDomain()
            this.actingUserId = origActingUser

            val targetUser = this.actingUserId ?: "@$HARDCODED_LOCALPART:$domain"
            val targetLocalpart = extractLocalpart(targetUser)

            // Do an appservice registration
            var req = Request.Builder()
                .url("${this.homeserverUrl}/_matrix/client/v3/register${this.getImpersonationQuery("?")}")
                .addHeader("Authorization", "Bearer ${this.accessToken}")
                .post(JSONObject()
                    .put("type", "m.login.application_service")
                    .put("username", targetLocalpart)
                    .toString().toRequestBody(JSON)
                )
                .build()
            var res = this.doRequest(req)!!
            if (res.optString("errcode") == "M_USER_IN_USE") {
                // Okay, try to log in
                req = Request.Builder()
                    .url("${this.homeserverUrl}/_matrix/client/v3/login${this.getImpersonationQuery("?")}")
                    .addHeader("Authorization", "Bearer ${this.accessToken}")
                    .post(JSONObject()
                        .put("type", "m.login.application_service")
                        .put("identifier", JSONObject()
                            .put("type", "m.id.user")
                            .put("user", targetUser)
                        )
                        .toString().toRequestBody(JSON)
                    )
                    .build()
                res = this.doRequest(req)!!
                return res.getString("access_token")
            } else {
                return res.getString("access_token")
            }
        } else {
            throw java.lang.RuntimeException("UIA wasn't considered for the demo")
        }
    }

    public fun appserviceLogin(userId: String): Matrix {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/login${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.asToken}")
            .post(JSONObject()
                .put("type", "m.login.application_service")
                .put("identifier", JSONObject()
                    .put("type", "m.id.user")
                    .put("user", userId)
                )
                .toString().toRequestBody(JSON)
            )
            .build()
        val res = this.doRequest(req)!!
        return Matrix(res.getString("access_token"), this.homeserverUrl, this.asToken)
    }

    public fun whoAmI(): String? {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/account/whoami${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        return this.doRequest(req)?.getString("user_id")
    }

    public fun whichDeviceAmI(): String? {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/account/whoami${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        return this.doRequest(req)?.getString("device_id")
    }

    public fun createRoom(name: String, chatId: ChatID): String? {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/createRoom${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .post(JSONObject()
                .put("preset", "private_chat")
                .put("name", name)
                .put("room_alias_name", getLocalpartForChatId(chatId))
                .put("initial_state", JSONArray().put(JSONObject()
                    .put("type", "m.room.encryption")
                    .put("state_key", "")
                    .put("content", JSONObject()
                        .put("algorithm", "m.megolm.v1.aes-sha2")
                    )
                ).put(JSONObject()
                    .put("type", MATRIX_NAMESPACE)
                    .put("state_key", "")
                    .put("content", getIdEventContent(chatId))
                ).put(JSONObject()
                    .put("type", "m.room.topic")
                    .put("state_key", "")
                    .put("content", JSONObject()
                        .put("topic", "Debugging: ${chatId.toString()}")
                    )
                ))
                .toString().toRequestBody(JSON)
            ).build()
        return this.doRequest(req)?.getString("room_id")
    }

    public fun userIdForRemoteId(id: UserID): String {
        return "@${getLocalpartForUserId(id)}:${this.getDomain()!!}"
    }

    public fun findRoomByChatId(chatId: ChatID): String? {
        val alias = "%23${getLocalpartForChatId(chatId)}:${this.getDomain()}" // XXX: We should just escape properly...
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/directory/room/${alias}${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        return this.doRequest(req)?.optString("room_id")
    }

    public fun assignChatIdToRoom(chatId: ChatID, roomId: String) {
        if (this.accessToken != this.asToken || this.actingUserId != null) {
            Matrix(this.asToken, this.homeserverUrl, this.asToken).assignChatIdToRoom(chatId, roomId)
            return
        }
        val alias = "%23${getLocalpartForChatId(chatId)}:${this.getDomain()}" // XXX: We should just escape properly...
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/directory/room/${alias}${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .put(JSONObject().put("room_id", roomId).toString().toRequestBody(JSON))
            .build()
        this.doRequest(req)!!
    }

    public fun sendEvent(event: MatrixEvent, roomId: String): String? {
        // XXX: This annotation should be encrypted. We put it in after encryption for demonstration purposes only.
        event.content.put(MATRIX_NAMESPACE, true) // annotate outbound events for ease of handling later
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/send/${event.eventType}/m${Date().time}${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .put(event.content.toString().toRequestBody(JSON))
            .build()
        return this.doRequest(req)?.getString("event_id")
    }

    public fun makeTextEvent(text: String): MatrixEvent {
        return MatrixEvent(
            "m.room.message",
            JSONObject()
                .put("msgtype", "m.text")
                .put("body", text),
            null
        )
    }

    public fun getJoinedUsers(roomId: String): List<String> {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/joined_members${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        val list = ArrayList<String>()
        for (i in this.doRequest(req)?.getJSONObject("joined")?.keys()!!) {
            list.add(i)
        }
        return list
    }

    public fun getImpersonationQuery(leadChar: String): String {
        if (this.actingUserId != null) {
            return "${leadChar}user_id=${this.actingUserId}"
        }
        return leadChar
    }

    public fun doRequest(request: Request): JSONObject? {
        try {
            Log.d("Matrix-outboundRequest", request.method + " " + request.url.toString())
            val response = HTTP_CLIENT.newCall(request).execute()
            return JSONObject(response.body!!.string())
        } catch (exception: Exception) {
            exception.message?.let { Log.e("DMA", it) }
        }
        return null
    }

    public fun registerFilter(): String {
        val filter = JSONObject()
            .put("account_data", JSONObject()
                .put("limit", 0)
                .put("senders", JSONArray())
                .put("types", JSONArray())
            )
            .put("presence", JSONObject()
                .put("limit", 0)
                .put("senders", JSONArray())
                .put("types", JSONArray())
            )
            .put("room", JSONObject()
                .put("include_leave", false)
                .put("account_data", JSONObject()
                    .put("limit", 0)
                    .put("rooms", JSONArray())
                    .put("senders", JSONArray())
                )
                .put("ephemeral", JSONObject()
                    .put("limit", 0)
                    .put("rooms", JSONArray())
                    .put("senders", JSONArray())
                )
                .put("state", JSONObject()
                    .put("limit", 100)
                    .put("types", JSONArray()
                            // We don't actually use any state events, because the state list doesn't
                            // work for us. This might be a synapse issue though where full_state causes
                            // infinite sync loops or simply doesn't include the event types we specify.
//                        .put("m.room.member")
//                        .put("m.room.encryption") // even though we don't use it...
//                        .put("m.room.name") // for bridging (if full_state worked)
                        .put(MATRIX_NAMESPACE)
                    )
                )
                // Don't set `timeline` or `rooms` filters
            )
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/user/${if (this.actingUserId != null) this.actingUserId else this.whoAmI()}/filter${this.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .post(filter.toString().toRequestBody(JSON))
            .build()
        return this.doRequest(req)!!.getString("filter_id")
    }

    public fun getStateEvent(roomId: String, eventType: String, stateKey: String): JSONObject? {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/state/$eventType/$stateKey")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        val res = this.doRequest(req)!!
        if (res.optString("errcode").isNotEmpty()) {
            return null
        }
        return res
    }

    public fun getRoomState(roomId: String): JSONArray {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/state")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        return JSONArray(HTTP_CLIENT.newCall(req).execute().body!!.string())
    }

    public fun sendStateEvent(roomId: String, eventType: String, stateKey: String, content: JSONObject): String {
        val req = Request.Builder()
            .url("${this.homeserverUrl}/_matrix/client/v3/rooms/$roomId/state/$eventType/$stateKey")
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .put(content.toString().toRequestBody(JSON))
            .build()
        return this.doRequest(req)!!.getString("event_id")
    }

    public fun doSync(token: String?, filterId: String): JSONObject {
        val req = Request.Builder()
            // XXX: We set `full_state`, but this will chew through bandwidth on large accounts. We do it to avoid a hit to `GET /state/:eventType`
            .url("${this.homeserverUrl}/_matrix/client/v3/sync?filter=$filterId&timeout=10000" + (if (token != null) "&since=$token" else "") + this.getImpersonationQuery("&"))
            .addHeader("Authorization", "Bearer ${this.accessToken}")
            .get()
            .build()
        return this.doRequest(req)!!
    }

    public fun startSyncLoop(crypto: MatrixCrypto, onMessageCallback: (ev: JSONObject, idEvContent: JSONObject) -> Unit, onRoomCallback: (roomId: String, stateEvents: JSONArray) -> JSONObject) {
        Thread {
            var nextBatch: String? = null
            val filterId = this.registerFilter()
            val myUserId = this.whoAmI()

            // TODO: Add a way to stop this madness
            while (true) {
                val res = this.doSync(nextBatch, filterId)
                val isFirstSync = nextBatch == null
                nextBatch = res.getString("next_batch")

                // send the sync request through our crypto
                crypto.consumeSync(res)

                // Scan for rooms that don't have our bridge state event, and for messages to send through
                val rooms = res.optJSONObject("rooms")?.optJSONObject("join") ?: continue
                for (roomId in rooms.keys()) {
                    val room = rooms.getJSONObject(roomId)
                    var idEvent = this.getStateEvent(roomId, MATRIX_NAMESPACE, "")

                    // XXX: We should probably handle gappy timelines, but meh
                    val timeline = room.getJSONObject("timeline").getJSONArray("events")
                    for (i in 0 until timeline.length()) {
                        val roomEvent = timeline.getJSONObject(i)
                        val flagged = roomEvent.getJSONObject("content").opt(MATRIX_NAMESPACE) != null
                        if (flagged) continue // don't even bother trying to process this

                        val decrypted = if (roomEvent.getString("type") == "m.room.encrypted") crypto.decrypt(roomEvent, roomId) ?: roomEvent else roomEvent

                        // Note: We drop messages from the first sync so we don't spam the chat
                        if (!isFirstSync && idEvent != null && decrypted.getString("type") == "m.room.message") {
                            if (decrypted.getJSONObject("content").getString("msgtype") == "m.text") {
                                // It's a real message event - let's postprocess it a bit
                                val senderId = roomEvent.getString("sender") // field exists on encrypted event
                                val memberEvent: JSONObject? = this.getStateEvent(roomId, "m.room.member", senderId)
                                if (memberEvent == null) {
                                    Log.w("DMA", "Missing member event for $senderId - ignoring message")
                                    continue
                                }

                                val copiedEvent = JSONObject()
                                for (j in roomEvent.keys()) {
                                    copiedEvent.put(j, roomEvent.get(j))
                                }
                                for (j in decrypted.keys()) {
                                    copiedEvent.put(j, decrypted.get(j))
                                }
                                memberEvent.put("X-myUserId", myUserId)
                                copiedEvent.put("X-sender", memberEvent) // for bridging purposes
                                onMessageCallback(copiedEvent, idEvent)
                            }
                        } else if (decrypted.getString("type") == "m.room.name") {
                            // This is a super sketchy hack: Synapse streams new rooms to us over `/sync`, which
                            // can mean that we end up trying to bridge a room before Synapse has sent our ID state
                            // event, thus bridging it *again*. However, by the time the room name is set, we have
                            // all the information we need (including the ID event, if any) to bridge the room to the
                            // remote service.
                            if (idEvent == null) {
                                idEvent = onRoomCallback(roomId, this.getRoomState(roomId))
                                // idEvent might be useful for events not yet processed
                            }
                        }
                    }
                }
            }
        }.start()
    }

    companion object {
        public fun extractLocalpart(id: String): String {
            val parts = id.split(":")
            return parts[0].substring(1)
        }
    }
}

data class MatrixEvent(val eventType: String, val content: JSONObject, val stateKey: String?)