package com.arisa.towerbot.android

import android.app.Application
import com.arisa.towerbot.core.BotStatus
import kotlinx.coroutines.flow.MutableStateFlow

class TowerBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir)
    }

    companion object {
        lateinit var store: Store
            private set

        /** Lo que está haciendo el bot ahora mismo; lo escribe el servicio y lo lee la pantalla. */
        val status = MutableStateFlow(BotStatus())
    }
}
