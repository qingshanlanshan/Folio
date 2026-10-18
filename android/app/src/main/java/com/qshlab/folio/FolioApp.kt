package com.qshlab.folio

import android.app.Application
import com.qshlab.folio.data.Store
import com.qshlab.folio.speech.ModelStore
import java.io.File

class FolioApp : Application() {
    lateinit var store: Store
    lateinit var models: ModelStore

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(File(filesDir, "Folio"))
        models = ModelStore(File(filesDir, "models"))
    }

    companion object {
        lateinit var instance: FolioApp
    }
}
