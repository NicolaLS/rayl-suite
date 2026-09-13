package xyz.lilsus.raylsuite.feature.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.feature.onboarding.R

data class OnboardingFeaturePage(val title: String, val subtitle: String, val body: String)

@Composable
fun FeaturesScreen(
    pages: List<OnboardingFeaturePage>,
    currentPage: Int,
    stepIndex: Int,
    totalSteps: Int,
    onPageChanged: (Int) -> Unit,
    onContinue: () -> Unit,
    onRequestCameraPermission: () -> Unit = {},
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    require(pages.isNotEmpty()) { "Onboarding requires at least one feature page" }

    val initialPage = currentPage.coerceIn(pages.indices)
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageChanged)
    }

    LaunchedEffect(currentPage, pages.size) {
        val requestedPage = currentPage.coerceIn(pages.indices)
        if (pagerState.currentPage != requestedPage) {
            pagerState.scrollToPage(requestedPage)
        }
    }

    OnboardingScaffold(
        stepIndex = stepIndex,
        totalSteps = totalSteps,
        showBackButton = onBack != null,
        onBack = { onBack?.invoke() }
    ) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(OnboardingTestTags.FEATURES_SCREEN),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HorizontalPager(
                state = pagerState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
            ) { page ->
                FeatureCard(
                    content = pages[page],
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            PageIndicator(
                pagerState = pagerState,
                pageCount = pages.size
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onRequestCameraPermission()
                    onContinue()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(OnboardingTestTags.FEATURES_CONTINUE)
            ) {
                Text(stringResource(R.string.onboarding_features_continue))
            }
        }
    }
}

@Composable
private fun FeatureCard(content: OnboardingFeaturePage, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(horizontal = 8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = content.title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = content.subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = content.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PageIndicator(pagerState: PagerState, pageCount: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val size by
                animateDpAsState(
                    targetValue = if (index == pagerState.currentPage) 10.dp else 8.dp,
                    animationSpec = tween(durationMillis = 300),
                    label = "indicatorSize"
                )
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
            )
        }
    }
}
