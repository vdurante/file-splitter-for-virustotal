package io.github.vdurante.filesplitter

import android.app.Application
import io.github.vdurante.filesplitter.scan.ScanEngine
import io.github.vdurante.filesplitter.scan.ScanService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FileSplitterApp : Application() {

    /** App-wide so scans outlive the activity; [ScanService] keeps the process alive while busy. */
    val engine by lazy { ScanEngine(this) }

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            engine.busy.collect { busy -> if (busy) ScanService.start(this@FileSplitterApp) }
        }
    }
}
