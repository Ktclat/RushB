package dev.matchscope.app

import android.app.Application
import dev.matchscope.app.data.MediaLoader

class RushBApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaLoader.install()
    }
}
