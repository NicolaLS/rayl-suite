package xyz.lilsus.flint

import android.app.Application

class FlintApplication : Application()

internal data class AndroidFlintConfiguration(
    val environment: FlintEnvironment,
    val breezApiKey: String? = null
)
