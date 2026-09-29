package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.settings.SecureStringStore
import xyz.lilsus.raylsuite.core.settings.createSecureSettings

internal actual fun platformWidgetSecretStore(): SecureStringStore? =
    createSecureSettings("rayl.widget-secrets")
