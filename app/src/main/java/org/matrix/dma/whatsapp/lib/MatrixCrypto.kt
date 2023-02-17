package org.matrix.dma.whatsapp.lib

import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.crypto.*

class MatrixCrypto {
    private val client: Matrix
    private val machine: OlmMachine

    constructor(client: Matrix, storagePath: String) {
        this.client = client
        this.machine = OlmMachine(this.client.whoAmI()!!, this.client.whichDeviceAmI()!!, storagePath, null)
    }

    public fun cleanup() {
        this.machine.destroy()
    }

    public fun consumeSync(sync: JSONObject) {
        val toDeviceEvents = sync.optJSONObject("to_device")?.getJSONArray("events")
        val deviceLists = sync.optJSONObject("device_lists")
        val changed = deviceLists?.optJSONArray("changed")
        val left = deviceLists?.optJSONArray("left")
        val otks = sync.optJSONObject("device_one_time_keys_count")
        val unusedKeys = sync.optJSONArray("device_unused_fallback_key_types")
        this.machine.receiveSyncChanges(
            (JSONObject().put("events", toDeviceEvents ?: JSONArray())).toString(),
            DeviceLists(arrayToList(changed ?: JSONArray()), arrayToList(left ?: JSONArray())),
            objectToMap(otks ?: JSONObject()),
            arrayToList(unusedKeys ?: JSONArray())
        )
    }

    public fun decrypt(event: JSONObject, roomId: String): JSONObject? {
        try {
            val decrypted = this.machine.decryptRoomEvent(event.toString(), roomId, false)
            return JSONObject(decrypted.clearEvent)
        } catch (ex: Exception) {
            Log.e("DMA", "Error decrypting event: $ex")
        }
        return null
    }

    public fun runOnce() {
        val requests = this.machine.outgoingRequests()
        for (request in requests) {
            when (request) {
                is Request.KeysUpload -> this.doKeyUpload(request)
                is Request.KeysQuery -> this.doKeyQuery(request)
                is Request.KeysClaim -> this.doKeyClaim(request)
                is Request.ToDevice -> this.doToDevice(request)
                else -> throw java.lang.RuntimeException("Unsupported request type: $request")
            }
        }
    }

    private fun doKeyUpload(req: Request.KeysUpload) {
        val response = this.client.doRequest(okhttp3.Request.Builder()
            .url("${this.client.homeserverUrl}/_matrix/client/v3/keys/upload${this.client.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.client.accessToken}")
            .post(req.body.toRequestBody(JSON))
            .build())!!
        this.machine.markRequestAsSent(req.requestId, RequestType.KEYS_UPLOAD, response.toString())
    }

    private fun doKeyQuery(req: Request.KeysQuery) {
        val userObject = JSONObject()
        for (user in req.users) {
            userObject.put(user, JSONArray())
        }
        val response = this.client.doRequest(okhttp3.Request.Builder()
            .url("${this.client.homeserverUrl}/_matrix/client/v3/keys/query${this.client.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.client.accessToken}")
            .post(JSONObject()
                .put("device_keys", userObject)
                .toString().toRequestBody(JSON))
            .build())!!
        this.machine.markRequestAsSent(req.requestId, RequestType.KEYS_QUERY, response.toString())
    }

    private fun doKeyClaim(req: Request.KeysClaim) {
        val response = this.client.doRequest(okhttp3.Request.Builder()
            .url("${this.client.homeserverUrl}/_matrix/client/v3/keys/claim${this.client.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.client.accessToken}")
            .post(JSONObject().put("one_time_keys", JSONObject(req.oneTimeKeys)).toString().toRequestBody(JSON))
            .build())!!
        this.machine.markRequestAsSent(req.requestId, RequestType.KEYS_CLAIM, response.toString())
    }

    private fun doToDevice(req: Request.ToDevice) {
        val response = this.client.doRequest(okhttp3.Request.Builder()
            .url("${this.client.homeserverUrl}/_matrix/client/v3/sendToDevice/${req.eventType}/${req.requestId}${this.client.getImpersonationQuery("?")}")
            .addHeader("Authorization", "Bearer ${this.client.accessToken}")
            .put(JSONObject().put("messages", JSONObject(req.body)).toString().toRequestBody(JSON))
            .build())!!
        this.machine.markRequestAsSent(req.requestId, RequestType.TO_DEVICE, response.toString())
    }

    public fun encryptEvent(event: MatrixEvent, roomId: String): MatrixEvent {
        this.runOnce()
        val users = this.client.getJoinedUsers(roomId)!!
        this.machine.updateTrackedUsers(users)
        val req = this.machine.getMissingSessions(users)
        if (req != null) {
            when (req) {
                is Request.KeysClaim -> this.doKeyClaim(req)
                else -> throw java.lang.RuntimeException("Invalid state: expected a KeysClaim request")
            }
        }
        val reqs = this.machine.shareRoomKey(roomId, users, EncryptionSettings(
            // XXX: We probably shouldn't assume the algorithm type or rotation settings
            EventEncryptionAlgorithm.MEGOLM_V1_AES_SHA2,
            604800000u,
            100u,
            // XXX: We probably shouldn't assume the history visibility either...
            HistoryVisibility.SHARED,
            false,
        ))
        for (tdReq in reqs) {
            when (tdReq) {
                is Request.ToDevice -> this.doToDevice(tdReq)
                else -> throw java.lang.RuntimeException("Invalid state: expected a ToDevice request")
            }
        }

        val encrypted = JSONObject(this.machine.encrypt(roomId, event.eventType, event.content.toString()))
        this.runOnce()
        return MatrixEvent("m.room.encrypted", encrypted, null)
    }
}

fun <T> arrayToList(arr: JSONArray): List<T> {
    val list = ArrayList<T>()
    for (i in 0 until arr.length()) {
        @Suppress("UNCHECKED_CAST")
        list.add(arr.get(i) as T)
    }
    return list
}

fun <K, V> objectToMap(obj: JSONObject): Map<K, V> {
    val map = HashMap<K, V>()
    for (k in obj.keys()) {
        @Suppress("UNCHECKED_CAST")
        map[k as K] = obj.get(k) as V
    }
    return map
}