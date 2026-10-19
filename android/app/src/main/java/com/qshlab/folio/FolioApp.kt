package com.qshlab.folio

import android.app.Application
import com.qshlab.folio.data.Store
import com.qshlab.folio.engine.Engine
import com.qshlab.folio.engine.FolioAppRef
import com.qshlab.folio.engine.Recorder
import com.qshlab.folio.speech.ModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class FolioApp : Application() {
    lateinit var store: Store
    lateinit var models: ModelStore
    lateinit var engine: Engine
    lateinit var recorder: Recorder
    /** Errors from background starts (e.g. recording), shown by the UI. */
    val lastError = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        instance = this
        FolioAppRef.ctx = this
        store = Store(File(filesDir, "Folio"))
        store.ensureUnsorted()
        models = ModelStore(File(filesDir, "models"))
        engine = Engine(this, store, models)
        recorder = Recorder(engine)
        engine.recording = { recorder.active }
        engine.resume()
        com.qshlab.folio.sync.Sync.start(this)
    }

    companion object {
        lateinit var instance: FolioApp
    }
}
