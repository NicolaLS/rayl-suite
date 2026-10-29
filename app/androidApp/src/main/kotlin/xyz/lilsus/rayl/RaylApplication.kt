package xyz.lilsus.rayl

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import xyz.lilsus.rayl.providers.rayl.enableRaylTimings
import xyz.lilsus.rayl.providers.spark.SparkEnvironment
import xyz.lilsus.raylsuite.core.network.initializeNetworkConnectivity

class RaylApplication :
    Application(),
    CameraXConfig.Provider {

    override fun getCameraXConfig(): CameraXConfig = CameraXConfig.Builder
        .fromConfig(Camera2Config.defaultConfig())
        .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        .build()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableRaylTimings()
        initializeNetworkConnectivity(this)
        val configuration = androidRaylConfiguration()
        startConfiguredWalletEarly(this, configuration.environment, configuration.breezApiKey)
    }
}

internal data class AndroidRaylConfiguration(
    val environment: SparkEnvironment,
    val breezApiKey: String? = null
)
