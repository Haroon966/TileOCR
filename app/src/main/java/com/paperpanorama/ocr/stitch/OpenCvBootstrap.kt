package com.paperpanorama.ocr.stitch

import android.util.Log
import org.opencv.android.OpenCVLoader

object OpenCvBootstrap {
    @Volatile
    private var ready = false

    fun ensureInitialized(): Boolean {
        if (ready) return true
        synchronized(this) {
            if (ready) return true
            ready = OpenCVLoader.initLocal()
            if (!ready) {
                Log.e(TAG, "OpenCV initLocal() failed")
            }
            return ready
        }
    }

    private const val TAG = "OpenCvBootstrap"
}
