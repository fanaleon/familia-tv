package com.serchu.familiatv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Valores que se fijan al compilar (ver README). */
object Cfg {
    private val base: String = BuildConfig.RELAY_URL.trim().trimEnd('/')

    val httpBase: String get() = base

    /** https://x -> wss://x/ws   |   http://x -> ws://x/ws */
    val wsUrl: String = base.replaceFirst("http", "ws") + "/ws"

    val token: String = BuildConfig.RELAY_TOKEN.trim()

    val senderName: String = BuildConfig.SENDER_NAME.trim().ifBlank { "Family" }

    val configured: Boolean =
        base.toHttpUrlOrNull() != null &&
            !base.contains("CAMBIAR") &&
            token.isNotEmpty() &&
            token != "CAMBIAR"
}
