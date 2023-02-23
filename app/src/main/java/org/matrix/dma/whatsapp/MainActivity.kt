package org.matrix.dma.whatsapp

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.matrix.dma.whatsapp.lib.ChatID
import org.matrix.dma.whatsapp.lib.Matrix
import org.matrix.dma.whatsapp.lib.MatrixCrypto
import whatsmeow.Client
import whatsmeow.Whatsmeow
import kotlin.concurrent.thread

// Buckets
const val PREF_HOMESERVER = "homeserver"

// Values
const val PREF_ACCESS_TOKEN = "access_token"
const val PREF_APPSERVICE_TOKEN = "as_token"
const val PREF_HOMESERVER_URL = "homeserver_url"

class MainActivity : AppCompatActivity() {
    private var client: Client? = null

    internal var matrix: Matrix? = null
    internal var mxCrypto: MatrixCrypto? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        this.client = Whatsmeow.newClient(applicationInfo.dataDir + "/whatsmeow.db")
        if (!this.client!!.hasSession()) {
            drawQrCode()
        } else {
            Log.d("DMA", "Already logged in")
            this.client!!.connect()
            moveToHomeserverSetup(this)
            return
        }

        val btnTestCode = findViewById<Button>(R.id.btnTestQrCode);
        btnTestCode.setOnClickListener {
            if (!this.client!!.hasSession()) {
                Toast.makeText(this, R.string.toast_whatsapp_code_not_scanned, Toast.LENGTH_SHORT).show()
            } else {
                Log.d("DMA", "Woo! Logged in!")
                moveToHomeserverSetup(this)
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

    internal fun createRemoteClient() {
        // no-op: already done
    }

    internal fun bridgeRemoteToMatrix() {
        val txtStatus = this.findViewById<TextView>(R.id.txtStatus)
        val toBridge = JSONArray(this.client!!.encodedJoinedGroups)
        txtStatus.text = resources.getString(R.string.bridging_x_chats, toBridge.length())
        for (i in 0 until toBridge.length()) {
            val group = toBridge.getJSONObject(i)
            val groupId = group.getString("jid")
            val roomId = getOrMakeRoom(this, groupId, group.optString("name", groupId))

            val members = group.getJSONArray("participants")
            for (j in 0 until members.length()) {
                val member = members.getJSONObject(j)
                val memberId = member.getString("jid")
                if (this.client!!.isSelf(memberId)) {
                    continue
                }

                val displayName = this.client!!.getUserDisplayName(memberId)
                bridgeUserTo(this, memberId, roomId, displayName)
            }
        }
    }

    internal fun setUpRemoteSync() {
        thread (start = true) {
            this.client!!.setupTimeline()
            while (this.client!!.isConnected && this.client!!.isLoggedIn) {
                val toSend = JSONArray(this.client!!.pollTimeline())
                for (i in 0 until toSend.length()) {
                    val msg = toSend.getJSONObject(i)

                    val chatId = msg.getString("chat_id")
                    val senderId = msg.getString("sender_id")
                    val text = msg.getString("message")

                    sendMatrixMessage(this, "x", chatId, senderId, this.client!!.isSelf(senderId), text)
                }

                Thread.sleep(10L) // just enough to avoid eating the CPU
            }
        }
    }

    internal fun sendToRemote(chatId: ChatID, text: String, mxEventId: String) {
        this.client!!.sendTextMessage(chatId, text)
    }

    internal fun createRoomOnRemote(name: String): ChatID {
        return this.client!!.createGroupEncoded(name)
    }
}

data class TempUser(val client: Matrix, val crypto: MatrixCrypto)
