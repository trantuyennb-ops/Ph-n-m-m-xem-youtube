package com.tuyen.tubeviet

import android.app.Application
import com.tuyen.tubeviet.newpipe.DownloaderImpl
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        NewPipe.init(
            DownloaderImpl.getInstance(),
            Localization("vi", "VN"),
            ContentCountry("VN")
        )
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
