package com.serchu.familiatv

import android.app.Application

/** Antes de que arranque cualquier pantalla o servicio, se carga el idioma elegido. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
    }
}
