package com.qshlab.folio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.qshlab.folio.ui.FolioNav
import com.qshlab.folio.ui.FolioTheme

class MainActivity : ComponentActivity() {
    /** A file shared into Folio (Android "Share" → Folio), waiting for a project to import into. */
    val shared = mutableStateOf<Uri?>(null)
    /** A meeting to open (from a notification). */
    val openMeeting = mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            FolioTheme { Surface(Modifier.fillMaxSize()) { FolioNav(this) } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND) {
            shared.value = if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (i.getBooleanExtra("selftest", false)) Thread {
            val r = com.qshlab.folio.ai.SelfTest.run(this)
            android.util.Log.i("FolioSelfTest", r)
            FolioApp.instance.lastError.value = r
        }.start()
        val pid = i.getStringExtra("pid"); val mid = i.getStringExtra("mid")
        if (pid != null && mid != null) openMeeting.value = pid to mid
    }

    override fun onStart() { super.onStart(); FolioApp.instance.engine.foreground = true }
    override fun onStop() { super.onStop(); FolioApp.instance.engine.foreground = false }
}
