package xyz.lilsus.rayl.providers.nwc.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeNwcOnboardingPage(val title: String, val subtitle: String, val body: String)

data class NativeNwcOnboardingText(
    val featurePages: List<NativeNwcOnboardingPage>,
    val autoPayBody: String,
    val agreementBody: String,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionSteps: List<String>
)

suspend fun nativeNwcOnboardingText(appName: String): NativeNwcOnboardingText =
    NativeNwcOnboardingText(
        featurePages =
            listOf(
                NativeNwcOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page1_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page1_subtitle"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page1_body"
                        ),
                        appName
                    )
                ),
                NativeNwcOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page2_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page2_subtitle"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page2_body"
                        ),
                        appName
                    )
                ),
                NativeNwcOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page3_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page3_subtitle"
                        ),
                        appName
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "NwcOnboarding",
                            key = "onboarding_features_page3_body"
                        ),
                        appName
                    )
                )
            ),
        autoPayBody = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_autopay_body"),
            appName
        ),
        agreementBody = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_agreement_body"),
            appName
        ),
        instructionsTitle = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_add_wallet_title")
        ),
        instructionsIntro = nativeString(
            NativeStringResource(table = "NwcOnboarding", key = "onboarding_add_wallet_intro"),
            appName
        ),
        instructionSteps =
            listOf(
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step1"
                    ),
                    appName
                ),
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step2"
                    )
                ),
                nativeString(
                    NativeStringResource(
                        table = "NwcOnboarding",
                        key = "onboarding_add_wallet_step3"
                    )
                )
            )
    )
