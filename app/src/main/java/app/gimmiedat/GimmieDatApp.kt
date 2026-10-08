package app.gimmiedat

import android.app.Application
import app.gimmiedat.data.History
import app.gimmiedat.data.Prefs
import app.gimmiedat.engine.Engine

class GimmieDatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        History.init(this)
        Grabber.init(this)
        DownloadService.ensureChannels(this)
        // unpack python/ffmpeg/yt-dlp in the background right away, so the first
        // grab doesn't wait on it
        Engine.start(this)
    }
}
