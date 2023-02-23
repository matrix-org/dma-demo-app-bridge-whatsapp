package org.matrix.dma.whatsapp

import android.content.ContentValues
import android.content.Intent
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.dma.whatsapp.lib.*
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.io.path.Path
import kotlin.io.path.exists

fun moveToHomeserverSetup(app: MainActivity) {
    app.createRemoteClient()

    // Quickly make sure we're not having to skip a step
    val hsPrefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE);
    if (hsPrefs.getString(PREF_ACCESS_TOKEN, null) !== null) {
        moveToAppserviceTest(app)
        return
    }

    app.setContentView(R.layout.homeserver_setup_layout)

    val downloadRegistrationButton = app.findViewById<Button>(R.id.btnDownloadRegistration)
    downloadRegistrationButton.setOnClickListener {
        val attributes = ContentValues()
        attributes.put(MediaStore.MediaColumns.DISPLAY_NAME, "googlechat.yaml")
        attributes.put(MediaStore.MediaColumns.MIME_TYPE, "text/yaml")

        val asToken = randomString(32)
        val hsToken = randomString(32) // unused in the app

        val prefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_ACCESS_TOKEN, asToken)
            .putString(PREF_APPSERVICE_TOKEN, asToken)
            .commit()

        val uri = app.contentResolver.insert(MediaStore.Files.getContentUri("external"), attributes)!!
        val stream = app.contentResolver.openOutputStream(uri)!!
        stream.write("id: ${HARDCODED_APPSERVICE_ID}\nas_token: '$asToken'\nhs_token: '$hsToken'\nurl: null\nrate_limited: false\nsender_localpart: ${HARDCODED_NAMESPACE_PREFIX}bot\nnamespaces:\n  users: [{exclusive: true, regex: '@${HARDCODED_NAMESPACE_PREFIX}.+'}, {exclusive: false, regex: '@$HARDCODED_LOCALPART:.+'}]\n  aliases: [{exclusive: true, regex: '#${HARDCODED_NAMESPACE_PREFIX}.+'}]\n  rooms: []\n".toByteArray())
        stream.close()

        val shareIntent = Intent.createChooser(Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "googlechat.yaml")
            type = "text/plain"
        }, null)
        app.startActivity(shareIntent)

        moveToAppserviceTest(app)
    }
}

fun moveToAppserviceTest(app: MainActivity) {
    val hsPrefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE)
    val hsStoredUrl = hsPrefs.getString(PREF_HOMESERVER_URL, null)
    if (hsStoredUrl != null) {
        moveToBridgeSync(app)
        return
    }

    app.setContentView(R.layout.appservice_test_layout)

    val hsUrlBox = app.findViewById<TextView>(R.id.txtHsUrl)
    val testButton = app.findViewById<Button>(R.id.btnTestConnection)
    testButton.setOnClickListener {
        val hsUrl = hsUrlBox.text.toString()
        if (hsUrl.trim().isEmpty()) {
            Toast.makeText(app, R.string.toast_missing_homeserver_url, Toast.LENGTH_SHORT)
                .show()
            return@setOnClickListener
        }

        Thread {
            val prefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE)
            val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)!!
            val asToken = prefs.getString(PREF_APPSERVICE_TOKEN, accessToken)!!

            val client = Matrix(accessToken, hsUrl, asToken)
            try {
                val realAccessToken = client.ensureRegistered()
                client.accessToken = realAccessToken
                val whoami = client.whoAmI()
                if (whoami != null) {
                    prefs.edit()
                        .putString(PREF_HOMESERVER_URL, hsUrl)
                        .putString(PREF_ACCESS_TOKEN, realAccessToken).commit()
                } else {
                    showInvalidHomeserverUrlToast(app)
                    return@Thread
                }
            } catch (exception: java.lang.Exception) {
                showInvalidHomeserverUrlToast(app)
                return@Thread
            }

            Log.d("DMA", "Bridging as ${client.whoAmI()} (${client.whichDeviceAmI()}) / ${client.accessToken}")
            Handler(app.mainLooper).post {
                moveToBridgeSync(app)
            }
        }.start()
    }
}

fun moveToBridgeSync(app: MainActivity) {
    app.setContentView(R.layout.initial_sync_layout)

    val prefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE)
    val homeserverUrl = prefs.getString(PREF_HOMESERVER_URL, null)!!
    val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)!!
    val asToken = prefs.getString(PREF_APPSERVICE_TOKEN, accessToken)!!
    app.matrix = Matrix(
        accessToken,
        homeserverUrl,
        asToken,
    )

    val txtStatus = app.findViewById<TextView>(R.id.txtStatus)
    txtStatus.text = app.resources.getText(R.string.sync_from_remote)
    thread(start = true) {
        app.mxCrypto = MatrixCrypto(app.matrix!!, app.applicationInfo.dataDir + "/crypto")
        app.mxCrypto!!.runOnce()

        val oldActingId = app.matrix!!.actingUserId
        val oldAccessToken = app.matrix!!.accessToken
        app.matrix!!.actingUserId = app.matrix!!.whoAmI()
        app.matrix!!.accessToken = asToken

        app.bridgeRemoteToMatrix()

        app.matrix!!.actingUserId = oldActingId
        app.matrix!!.accessToken = oldAccessToken

        txtStatus.text = app.resources.getString(R.string.syncing)
        app.setUpRemoteSync()
        setUpMatrixSync(app)
    }
}

fun setUpMatrixSync(app: MainActivity) {
    val messageFn = {ev: JSONObject, id: JSONObject ->
        Log.d("DMA", "Got message: $ev\n\n$id")
        val chatId = stateIdToChatId(id)
        val senderInfo = ev.getJSONObject("X-sender")
        var text = ev.getJSONObject("content").getString("body")
        if (senderInfo.getString("X-myUserId") != ev.getString("sender")) {
            val displayName = senderInfo.optString("displayname")
            text = (if (displayName.isNotEmpty()) "<$displayName>: " else  "<${ev.getString("sender")}>: ") + text
        }
        app.sendToRemote(chatId, text, ev.getString("event_id"))
    }
    val roomFn = {roomId: String, state: JSONArray ->
        var mxName = "NoNameRoom"
        for (i in 0 until state.length()) {
            val event = state.getJSONObject(i)
            if (event.getString("type") == "m.room.name") {
                mxName = event.getJSONObject("content").optString("name", "NoNameRoom")
                break
            }
        }

        if (mxName.isEmpty()) {
            mxName = roomId
        }

        Log.d("DMA", "Attempting to assign a new chat ID to $roomId ($mxName)")
        val chatId = app.createRoomOnRemote(mxName)
        val content = getIdEventContent(chatId)
        app.matrix!!.assignChatIdToRoom(chatId, roomId)
        app.matrix!!.sendStateEvent(roomId, MATRIX_NAMESPACE, "", content)
        Log.d("DMA", "Assigned $chatId to $roomId")
        content // return
    }
    app.matrix!!.startSyncLoop(app.mxCrypto!!, messageFn, roomFn)
}

fun sendMatrixMessage(app: MainActivity, remoteId: String, chatId: ChatID, userId: UserID, isSelf: Boolean, text: String) {
    val roomId = app.matrix!!.findRoomByChatId(chatId)
    if (roomId == null) {
        Log.w("DMA", "Got message $remoteId from $userId but no bridged room - ignoring")
    } else {
        if (isSelf) {
            app.matrix!!.sendEvent(app.mxCrypto!!.encryptEvent(app.matrix!!.makeTextEvent(text), roomId), roomId)
        } else {
            val mxid = app.matrix!!.userIdForRemoteId(userId)
            val tempUser = makeTempUser(app, mxid, roomId)
            tempUser.client.sendEvent(tempUser.crypto.encryptEvent(tempUser.client.makeTextEvent(text), roomId), roomId)
            tempUser.crypto.cleanup()
        }
    }
}

fun getOrMakeRoom(app: MainActivity, id: ChatID, name: String): String {
    val existingRoomId = app.matrix!!.findRoomByChatId(id)
    if (existingRoomId != null && existingRoomId.isNotEmpty()) {
        Log.d("DMA", "$id already has room: $existingRoomId")
        return existingRoomId
    }

    return app.matrix!!.createRoom(name, id)!!
}

fun bridgeUserTo(app: MainActivity, userId: UserID, roomId: String, displayName: String? = null) {
    val mxid = app.matrix!!.createUser(userId, displayName ?: "[No Name Set]")
    app.matrix!!.appserviceJoin(mxid, roomId)

    val tempUser = makeTempUser(app, mxid) // sets up crypto
    tempUser.crypto.cleanup()
}

fun makeTempUser(app: MainActivity, mxid: String, roomId: String? = null): TempUser {
    val prefs = app.getSharedPreferences(PREF_HOMESERVER, AppCompatActivity.MODE_PRIVATE)
    val accessToken = prefs.getString(mxid, null)
    val localpart = Matrix.extractLocalpart(mxid)
    val dataPath = app.applicationInfo.dataDir + "/appservice_users/" + localpart

    val client: Matrix
    if (accessToken == null || !Path(dataPath).exists()) {
        client = Matrix(app.matrix!!.asToken, app.matrix!!.homeserverUrl, app.matrix!!.asToken)
        client.actingUserId = mxid
        client.accessToken = client.ensureRegistered()
        client.actingUserId = null
//            client = this.matrix!!.appserviceLogin(mxid)
        prefs.edit().putString(mxid, client.accessToken!!).commit()
    } else {
        client = Matrix(accessToken, app.matrix!!.homeserverUrl, app.matrix!!.asToken)
    }
    if (roomId != null) {
        app.matrix!!.appserviceJoin(mxid, roomId)
    }
    val crypto = MatrixCrypto(client, dataPath)
    crypto.runOnce()
    return TempUser(client, crypto)
}

fun showInvalidHomeserverUrlToast(app: MainActivity) {
    Handler(app.mainLooper).post {
        Toast.makeText(app, R.string.toast_invalid_homeserver_url, Toast.LENGTH_SHORT).show()
    }
}

fun randomString(length: Int) : String {
    val random = SecureRandom()
    val characters = "abcdefghijklmnopqrstuvwxyz0123456789"
    val builder = StringBuilder()
    for (i in 0 until length) {
        builder.append(characters[random.nextInt(characters.length)])
    }
    return builder.toString()
}