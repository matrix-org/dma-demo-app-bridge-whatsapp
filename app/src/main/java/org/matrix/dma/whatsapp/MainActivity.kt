package org.matrix.dma.whatsapp

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.dma.whatsapp.lib.MATRIX_NAMESPACE
import org.matrix.dma.whatsapp.lib.Matrix
import org.matrix.dma.whatsapp.lib.MatrixCrypto
import whatsmeow.Client
import whatsmeow.Whatsmeow
import java.security.SecureRandom

// Buckets
const val PREF_HOMESERVER = "homeserver"

// Values
const val PREF_ACCESS_TOKEN = "access_token"
const val PREF_APPSERVICE_TOKEN = "as_token"
const val PREF_HOMESERVER_URL = "homeserver_url"

class MainActivity : AppCompatActivity() {
    private var client: Client? = null
    private var matrix: Matrix? = null
    private var mxCrypto: MatrixCrypto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.client = Whatsmeow.newClient(applicationInfo.dataDir + "/whatsmeow.db")
        if (!this.client!!.hasSession()) {
            drawQrCode()
        } else {
            Log.d("DMA", "Already logged in")
            this.client!!.connect()
            this.moveToHomeserverSetup()
            return
        }

        val btnTestCode = findViewById<Button>(R.id.btnTestQrCode);
        btnTestCode.setOnClickListener {
            if (!this.client!!.hasSession()) {
                Toast.makeText(this, R.string.toast_whatsapp_code_not_scanned, Toast.LENGTH_SHORT).show()
            } else {
                Log.d("DMA", "Woo! Logged in!")
                this.moveToHomeserverSetup()
            }
        }

        val btnRegenQrCode = findViewById<Button>(R.id.btnRefreshQr)
        btnRegenQrCode.setOnClickListener {
            drawQrCode()
        }
    }

    private fun drawQrCode() {
        // DANGER: We're not supporting the QR changing like we're supposed to!
        // In whatsmeow, this is actually a `channel` for giving a constant stream of QR codes
        // because they change every 30-ish seconds or so. What we're doing here is actually just
        // grabbing the first code we get and hoping the user follows instructions quickly.
        val qrCode = this.client!!.qrCodeImage
        val img = findViewById<ImageView>(R.id.imgQrCode)
        val bmp = BitmapFactory.decodeByteArray(qrCode, 0, qrCode.size)
        img.setImageBitmap(bmp)
    }

    private fun moveToHomeserverSetup() {
        // Quickly make sure we're not having to skip a step
        val hsPrefs = getSharedPreferences(PREF_HOMESERVER, MODE_PRIVATE);
        if (hsPrefs.getString(PREF_ACCESS_TOKEN, null) !== null) {
            moveToAppserviceTest();
            return
        }

        setContentView(R.layout.homeserver_setup_layout)

        val downloadRegistrationButton = findViewById<Button>(R.id.btnDownloadRegistration)
        downloadRegistrationButton.setOnClickListener {
            val attributes = ContentValues()
            attributes.put(MediaStore.MediaColumns.DISPLAY_NAME, "whatsapp.yaml")
            attributes.put(MediaStore.MediaColumns.MIME_TYPE, "text/yaml")

            val asToken = this.randomString(32)
            val hsToken = this.randomString(32) // unused in the app

            val prefs = getSharedPreferences(PREF_HOMESERVER, MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_ACCESS_TOKEN, asToken)
                .putString(PREF_APPSERVICE_TOKEN, asToken)
                .commit()

            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), attributes)!!
            val stream = contentResolver.openOutputStream(uri)!!
            stream.write("id: whatsapp\nas_token: '$asToken'\nhs_token: '$hsToken'\nurl: null\nrate_limited: false\nsender_localpart: wa_bot\nnamespaces:\n  users: [{exclusive: true, regex: '@wa_.+'}, {exclusive: false, regex: '@demo:.+'}]\n  aliases: [{exclusive: true, regex: '#wa_.+'}]\n  rooms: []\n".toByteArray())
            stream.close()

            val shareIntent = Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "whatsapp.yaml")
                type = "text/plain"
            }, null)
            startActivity(shareIntent)

            moveToAppserviceTest()
        }
    }

    private fun moveToAppserviceTest() {
        val hsPrefs = getSharedPreferences(PREF_HOMESERVER, MODE_PRIVATE)
        val hsStoredUrl = hsPrefs.getString(PREF_HOMESERVER_URL, null)
        if (hsStoredUrl != null) {
            moveToBridgeSync()
            return
        }

        setContentView(R.layout.appservice_test_layout)

        val hsUrlBox = findViewById<TextView>(R.id.txtHsUrl)
        val testButton = findViewById<Button>(R.id.btnTestConnection)
        testButton.setOnClickListener {
            val hsUrl = hsUrlBox.text.toString()
            if (hsUrl.trim().isEmpty()) {
                Toast.makeText(this, R.string.toast_missing_homeserver_url, Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            Thread {
                val prefs = getSharedPreferences(PREF_HOMESERVER, MODE_PRIVATE)
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
                        this.showInvalidHomeserverUrlToast()
                        return@Thread
                    }
                } catch (exception: java.lang.Exception) {
                    this.showInvalidHomeserverUrlToast()
                    return@Thread
                }

                Log.d("DMA", "Bridging as ${client.whoAmI()} (${client.whichDeviceAmI()}) / ${client.accessToken}")
                Handler(this.mainLooper).post {
                    moveToBridgeSync()
                }
            }.start()
        }
    }

    private fun moveToBridgeSync() {
        setContentView(R.layout.initial_sync_layout)

        val prefs = getSharedPreferences(PREF_HOMESERVER, MODE_PRIVATE)
//        prefs.edit()
//            .remove("@wa_f65b2070729ae95f7bc77b67b79a5acf:localhost")
//            .commit()
//        prefs.edit()
//            .putString(PREF_HOMESERVER_URL, "http://172.16.0.111:8338")
//            .putString(PREF_ACCESS_TOKEN, "syt_ZXhhbXBsZV91c2VyXzE2NzYwNjYxOTAxOTI_WacRMEsfmhakGzrsjuUW_0XxlE5")
//            .putString(PREF_APPSERVICE_TOKEN, "1a12lbw3ffx4gqle2vtq4utk0vjug5t3")
//            .commit()
        val homeserverUrl = prefs.getString(PREF_HOMESERVER_URL, null)!!
        val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)!!
        val asToken = prefs.getString(PREF_APPSERVICE_TOKEN, accessToken)!!
        this.matrix = Matrix(
            "syt_ZXhhbXBsZV91c2VyXzE2NzYwNjYxOTAxOTI_WacRMEsfmhakGzrsjuUW_0XxlE5",
            homeserverUrl,
            asToken
        )

        val txtStatus = findViewById<TextView>(R.id.txtStatus)
        txtStatus.text = resources.getText(R.string.sync_from_whatsapp)
        Thread {
            this.mxCrypto = MatrixCrypto(this.matrix!!, applicationInfo.dataDir + "/crypto")
            this.mxCrypto!!.runOnce()

            val oldActingId = this.matrix!!.actingUserId
            val oldAccessToken = this.matrix!!.accessToken
            this.matrix!!.actingUserId = this.matrix!!.whoAmI()
            this.matrix!!.accessToken = asToken

            val toBridge = JSONArray(this.client!!.encodedJoinedGroups)
            txtStatus.text = resources.getString(R.string.bridging_x_chats, toBridge.length())
            for (i in 0 until toBridge.length()) {
                val group = toBridge.getJSONObject(i)
                val groupId = group.getString("jid")
                val existingRoomId = this.matrix!!.findRoomByChatId(groupId)
                if (existingRoomId != null && existingRoomId.isNotEmpty()) {
                    Log.d("DMA", "$groupId already has room: $existingRoomId")
                    continue
                }

                val roomId = this.matrix!!.createRoom(group.optString("name", ""), groupId)!!
                val members = group.getJSONArray("participants")
                for (j in 0 until members.length()) {
                    val member = members.getJSONObject(j)
                    val memberId = member.getString("jid")
                    if (this.client!!.isSelf(memberId)) {
                        continue
                    }

                    // Checking for ourselves doesn't work, so just bridge straight through
                    val displayName = this.client!!.getUserDisplayName(memberId)
                    val mxid = this.matrix!!.createUser(memberId, displayName)
                    this.matrix!!.appserviceJoin(mxid, roomId)

                    // Create the crypto stuff for that user too
                    val tempAccessToken = prefs.getString(mxid, null)
                    var tempClient: Matrix?
                    if (tempAccessToken == null) {
                        tempClient = this.matrix!!.appserviceLogin(mxid)
                        prefs.edit().putString(mxid, tempClient.accessToken!!).commit()
                    } else {
                        tempClient = Matrix(tempAccessToken, this.matrix!!.homeserverUrl, this.matrix!!.asToken)
                    }
                    val tempCrypto = MatrixCrypto(tempClient, applicationInfo.dataDir + "/appservice_users/" + tempClient.getLocalpart())
                    tempCrypto.runOnce()
                    tempCrypto.cleanup()
                }
            }

            this.matrix!!.actingUserId = oldActingId
            this.matrix!!.accessToken = oldAccessToken

            txtStatus.text = resources.getString(R.string.syncing_whatsapp)
//            this.gchat!!.startLoop()

            txtStatus.text = resources.getString(R.string.syncing_matrix)
            this.matrix!!.startSyncLoop(this.mxCrypto!!, { ev, id ->
                Log.d("DMA", "Got message: $ev\n\n$id")
                val chatId = this.stateIdToChatId(id)
                val senderInfo = ev.getJSONObject("X-sender")
                var text = ev.getJSONObject("content").getString("body")
                if (senderInfo.getString("X-myUserId") != ev.getString("sender")) {
                    val displayName = senderInfo.optString("displayname")
                    text = (if (displayName.isNotEmpty()) "<$displayName>: " else  "<${ev.getString("sender")}>: ") + text
                }
                this.client!!.sendTextMessage(chatId, text)
            }, { roomId, state ->
                var name = "NoNameRoom"
                for (i in 0 until state.length()) {
                    val event = state.getJSONObject(i)
                    if (event.getString("type") == "m.room.name") {
                        name = event.getJSONObject("content").optString("name", "NoNameRoom")
                        break
                    }
                }

                if (name.isEmpty()) {
                    name = roomId
                }

                Log.d("DMA", "Attempting to assign a new JID to $roomId ($name)")
                val jid = this.client!!.createGroupEncoded(name)
                val content = JSONObject().put("jid", jid)
                this.matrix!!.assignChatIdToRoom(jid, roomId)
                this.matrix!!.sendStateEvent(roomId, MATRIX_NAMESPACE, "", content)
                Log.d("DMA", "Assigned $jid to $roomId")
                return@startSyncLoop content
            })
        }.start()
    }

    private fun stateIdToChatId(id: JSONObject): String {
        return id.getString("jid")
    }

    private fun showInvalidHomeserverUrlToast() {
        Handler(this.mainLooper).post {
            Toast.makeText(this, R.string.toast_invalid_homeserver_url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun randomString(length: Int) : String {
        val random = SecureRandom()
        val characters = "abcdefghijklmnopqrstuvwxyz0123456789"
        val builder = StringBuilder()
        for (i in 0 until length) {
            builder.append(characters[random.nextInt(characters.length)])
        }
        return builder.toString()
    }
}