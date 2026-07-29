package com.paperpanorama.ocr

import android.app.Application
import com.paperpanorama.ocr.stitch.OpenCvBootstrap

class PaperPanoramaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OpenCvBootstrap.ensureInitialized()
    }
}
