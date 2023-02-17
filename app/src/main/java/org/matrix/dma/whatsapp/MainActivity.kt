package org.matrix.dma.whatsapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import whatsmeow.Client
import whatsmeow.Whatsmeow

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val client = Whatsmeow.newClient(applicationInfo.dataDir + "/whatsmeow.db")
        if (!client.hasSession()) {
            drawQrCode(client)
        } else {
            Log.d("DMA", "Already logged in")
            client.connect()
            this.moveToHomeserverSetup()
            return
        }

        val btnTestCode = findViewById<Button>(R.id.btnTestQrCode);
        btnTestCode.setOnClickListener {
            if (!client.hasSession()) {
                Toast.makeText(this, R.string.toast_whatsapp_code_not_scanned, Toast.LENGTH_SHORT).show()
            } else {
                Log.d("DMA", "Woo! Logged in!")
                this.moveToHomeserverSetup()
            }
        }

        val btnRegenQrCode = findViewById<Button>(R.id.btnRefreshQr)
        btnRegenQrCode.setOnClickListener {
            drawQrCode(client)
        }
    }

    private fun drawQrCode(client: Client) {
        // DANGER: We're not supporting the QR changing like we're supposed to!
        // In whatsmeow, this is actually a `channel` for giving a constant stream of QR codes
        // because they change every 30-ish seconds or so. What we're doing here is actually just
        // grabbing the first code we get and hoping the user follows instructions quickly.
        val qrCode = client.qrCodeImage
        val img = findViewById<ImageView>(R.id.imgQrCode)
        val bmp = BitmapFactory.decodeByteArray(qrCode, 0, qrCode.size)
        img.setImageBitmap(bmp)
    }

    private fun moveToHomeserverSetup() {

    }
}