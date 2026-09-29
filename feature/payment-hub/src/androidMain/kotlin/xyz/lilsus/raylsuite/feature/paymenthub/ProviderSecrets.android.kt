package xyz.lilsus.raylsuite.feature.paymenthub

import xyz.lilsus.raylsuite.core.settings.SecureStringStore

// Provider widgets are iOS-first in the spike; Android keeps secrets in memory only.
internal actual fun platformWidgetSecretStore(): SecureStringStore? = null
