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
import org.json.JSONObject
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
//            .putString(PREF_HOMESERVER_URL, "http://172.16.0.111:8338")
//            .putString(PREF_ACCESS_TOKEN, "syt_ZXhhbXBsZV91c2VyXzE2NzYwNjYxOTAxOTI_goFfnMzdAKgBjRZvrNeu_2iAnPc")
//            .putString(PREF_APPSERVICE_TOKEN, "1a12lbw3ffx4gqle2vtq4utk0vjug5t3")
//            .commit()
        val homeserverUrl = prefs.getString(PREF_HOMESERVER_URL, null)!!
        val accessToken = prefs.getString(PREF_ACCESS_TOKEN, null)!!
        val asToken = prefs.getString(PREF_APPSERVICE_TOKEN, accessToken)!!
        this.matrix = Matrix(
            "syt_ZXhhbXBsZV91c2VyXzE2NzYwNjYxOTAxOTI_goFfnMzdAKgBjRZvrNeu_2iAnPc",
            homeserverUrl,
            asToken
        )

        val txtStatus = findViewById<TextView>(R.id.txtStatus)
        txtStatus.text = resources.getText(R.string.sync_from_whatsapp)
        Thread {
            this.mxCrypto = MatrixCrypto(this.matrix!!, applicationInfo.dataDir + "/crypto")
            this.mxCrypto!!.runOnce()

//            val toBridge = this.gchat!!.listDmsAndSpaces()
//            txtStatus.text = resources.getString(R.string.bridging_x_chats, toBridge.size)
//            val myId = this.gchat!!.getSelfUserId()
//            for (gspace in toBridge) {
//                if (!gspace.hasGroupId()) continue
//                val existingRoomId = this.matrix!!.findRoomByChatId(gspace.groupId)
//                if (existingRoomId != null && existingRoomId.isNotEmpty()) {
//                    Log.d("DMA", "${gspace.groupId} already has room: $existingRoomId")
//                    continue
//                }
//                val roomId = this.matrix!!.createRoom(gspace.roomName, gspace.groupId)!!
//                val memberships = this.gchat!!.getChatMembers(gspace.groupId)
//                val joinedNotUs = memberships.toList().filter { m -> m.membershipState == MembershipState.MEMBER_JOINED && !m.id.memberId.userId.equals(myId) }
//                for (membership in joinedNotUs) {
//                    val member = this.gchat!!.getMember(membership.id.memberId)
//                    val mxid = this.matrix!!.createUser(member.user.userId.id, member.user.name)
//                    this.matrix!!.appserviceJoin(mxid, roomId)
//
//                    // Create the crypto stuff for that user too
//                    val tempAccessToken = prefs.getString(mxid, null)
//                    var tempClient: Matrix?
//                    if (tempAccessToken == null) {
//                        tempClient = this.matrix!!.appserviceLogin(mxid)
//                        prefs.edit().putString(mxid, tempClient.accessToken!!).commit()
//                    } else {
//                        tempClient = Matrix(tempAccessToken, this.matrix!!.homeserverUrl, this.matrix!!.asToken)
//                    }
//                    val tempCrypto = MatrixCrypto(tempClient, applicationInfo.dataDir + "/appservice_users/" + tempClient.getLocalpart())
//                    tempCrypto.runOnce()
//                    tempCrypto.cleanup()
//                }
//            }

            txtStatus.text = resources.getString(R.string.syncing_whatsapp)
//            this.gchat!!.startLoop()

            txtStatus.text = resources.getString(R.string.syncing_matrix)
//            this.matrix!!.startSyncLoop(this.mxCrypto!!, { ev, id ->
//                Log.d("DMA", "Got message: $ev\n\n$id")
//                val chatId = this.stateIdToChatId(id)
//                val senderInfo = ev.getJSONObject("X-sender")
//                var text = ev.getJSONObject("content").getString("body")
//                if (senderInfo.getString("X-myUserId") != ev.getString("sender")) {
//                    val displayName = senderInfo.optString("displayname")
//                    text = (if (displayName.isNotEmpty()) "<$displayName>: " else  "<${ev.getString("sender")}>: ") + text
//                }
//                this.gchat?.sendMessage(chatId, text)
//            }, { roomId, state ->
//                Log.d("DMA", "Got room: $roomId\n\n$state")
//                return@startSyncLoop JSONObject()
//            })
        }.start()
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