package network.loki.mesenger

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.integration.android.IntentIntegrator

class QrScannerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val integrator = IntentIntegrator(this)
        integrator.setOrientationLocked(false)
        integrator.setPrompt("Scan a QR")
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents == null) {
                // Usuario canceló
                finish()
            } else {
                // Tenemos un QR
                val scannedKey = result.contents

                // Lanzar ImportKeyActivity
                val intent = Intent(this, ImportKeyActivity::class.java)
                intent.putExtra(ImportKeyActivity.EXTRA_KEY_SCANNED, scannedKey)
                startActivity(intent)
                finish()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
