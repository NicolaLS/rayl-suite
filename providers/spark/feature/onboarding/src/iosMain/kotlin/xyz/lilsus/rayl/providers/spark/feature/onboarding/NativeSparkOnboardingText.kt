package xyz.lilsus.rayl.providers.spark.feature.onboarding

import xyz.lilsus.raylsuite.core.ui.resources.NativeStringResource
import xyz.lilsus.raylsuite.core.ui.resources.nativeString

data class NativeSparkOnboardingPage(val title: String, val subtitle: String, val body: String)

data class NativeSparkOnboardingText(
    val welcomeTitle: String,
    val welcomeSubtitle: String,
    val welcomeDescription: String,
    val featurePages: List<NativeSparkOnboardingPage>,
    val autoPayBody: String,
    val agreementBody: String,
    val instructionsTitle: String,
    val instructionsIntro: String,
    val instructionSteps: List<String>
)

suspend fun nativeSparkOnboardingText(appName: String): NativeSparkOnboardingText =
    NativeSparkOnboardingText(
        welcomeTitle = nativeString(
            NativeStringResource(table = "SparkOnboarding", key = "spark_onboarding_welcome_title"),
            appName
        ),
        welcomeSubtitle = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_welcome_subtitle_line1"
            )
        ),
        welcomeDescription = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_welcome_subtitle_line2"
            )
        ),
        featurePages =
            listOf(
                NativeSparkOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page1_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page1_subtitle"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page1_body"
                        ),
                        appName
                    )
                ),
                NativeSparkOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page2_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page2_subtitle"
                        )
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page2_body"
                        ),
                        appName
                    )
                ),
                NativeSparkOnboardingPage(
                    title = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page3_title"
                        )
                    ),
                    subtitle = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page3_subtitle"
                        ),
                        appName
                    ),
                    body = nativeString(
                        NativeStringResource(
                            table = "SparkOnboarding",
                            key = "spark_onboarding_features_page3_body"
                        ),
                        appName
                    )
                )
            ),
        autoPayBody = nativeString(
            NativeStringResource(table = "SparkOnboarding", key = "spark_onboarding_autopay_body"),
            appName
        ),
        agreementBody = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_agreement_body"
            ),
            appName
        ),
        instructionsTitle = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_add_wallet_title"
            )
        ),
        instructionsIntro = nativeString(
            NativeStringResource(
                table = "SparkOnboarding",
                key = "spark_onboarding_add_wallet_intro"
            ),
            appName
        ),
        instructionSteps =
            listOf(
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step1"
                    )
                ),
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step2"
                    ),
                    appName
                ),
                nativeString(
                    NativeStringResource(
                        table = "SparkOnboarding",
                        key = "spark_onboarding_add_wallet_step3"
                    ),
                    appName
                )
            )
    )
