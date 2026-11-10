package com.fayor.justtalk

import android.app.Application
import com.yandex.mapkit.MapKitFactory

class JtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the key must be set once, before anything touches MapKit; the map itself starts only when the Споты tab opens
        MapKitFactory.setApiKey(BuildConfig.MAPKIT_API_KEY)
    }
}
