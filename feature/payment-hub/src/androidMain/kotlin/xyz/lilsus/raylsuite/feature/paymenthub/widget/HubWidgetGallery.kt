package xyz.lilsus.raylsuite.feature.paymenthub.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetDefinition
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetKind
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetPerson
import xyz.lilsus.raylsuite.feature.paymenthub.HubWidgetTile
import xyz.lilsus.raylsuite.feature.paymenthub.R
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubState
import xyz.lilsus.raylsuite.feature.paymenthub.WidgetHubViewModel

@Composable
internal fun HubWidgetGallery(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(145.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(20.dp),
            modifier = Modifier.widthIn(max = 700.dp).fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.hub_widget_gallery_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            items(state.gallery, key = HubWidgetDefinition::id) { definition ->
                GalleryEntry(definition, onClick = { viewModel.selectDefinition(definition.id) })
            }
        }
    }
}

@Composable
private fun GalleryEntry(definition: HubWidgetDefinition, onClick: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().height(126.dp).clearAndSetSemantics {}
        ) {
            GalleryIllustration(definition.kind)
        }
        Text(definition.label(), style = MaterialTheme.typography.titleMedium)
        val body = definition.body()
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GalleryIllustration(kind: HubWidgetKind) {
    BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        if (kind == HubWidgetKind.Contacts) {
            val scale = minOf(1f, maxWidth / 152.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((-10).dp * scale)
            ) {
                ContactAvatar(title = null, size = 54.dp * scale, seed = 0)
                ContactAvatar(title = null, size = 64.dp * scale, seed = 1)
                ContactAvatar(title = null, size = 54.dp * scale, seed = 2)
            }
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(82.dp).background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(24.dp)
                )
            ) {
                Icon(
                    imageVector = when (kind) {
                        HubWidgetKind.Shortcut -> Icons.Filled.Bolt
                        HubWidgetKind.Favorites -> Icons.Filled.Star
                        HubWidgetKind.Recents -> Icons.Filled.AccessTime
                        HubWidgetKind.Contacts -> Icons.Filled.People
                        HubWidgetKind.Provider -> Icons.Filled.Bolt
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(42.dp)
                )
            }
        }
    }
}

@Composable
internal fun HubWidgetVariants(
    state: WidgetHubState,
    viewModel: WidgetHubViewModel,
    modifier: Modifier = Modifier
) {
    val definition = state.selectedDefinition ?: return
    val variants = definition.variants
    val pager = rememberPagerState(
        initialPage = variants.indexOfFirst {
            it.id ==
                state.editor?.variantId
        }.coerceAtLeast(0)
    ) {
        variants.size
    }
    LaunchedEffect(pager, definition.id) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            variants.getOrNull(page)?.let { viewModel.selectVariant(it.id) }
        }
    }
    // Every page reserves the tallest layout, so the previews keep their relative sizes and paging
    // never resizes the screen around them.
    val previewRows = variants.maxOf { it.rows }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                ) {
                    Text(
                        definition.label(),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        definition.body(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp)
                    )
                    HorizontalPager(
                        state = pager,
                        pageSpacing = 16.dp,
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        val variant = variants[page]
                        val description = stringResource(
                            R.string.hub_widget_preview_page,
                            page + 1,
                            variants.size
                        )
                        BoxWithConstraints(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = description
                            }
                        ) {
                            val gaps = previewGap * (previewRows - 1)
                            val unit = (maxWidth.coerceAtMost(400.dp) - previewGap) / 2
                            Box(
                                Modifier.fillMaxWidth().height(unit * previewRows + gaps),
                                contentAlignment = Alignment.Center
                            ) {
                                HubWidgetFace(
                                    tile = HubWidgetTile(
                                        id = "preview:${definition.id}:${variant.id}",
                                        definitionId = definition.id,
                                        kind = definition.kind,
                                        variant = variant,
                                        people =
                                            state.contacts.take(variant.capacity).map { contact ->
                                                HubWidgetPerson(
                                                    actionId = "",
                                                    contactId = contact.id,
                                                    title = contact.title,
                                                    address = ""
                                                )
                                            }
                                    ),
                                    onPay = {},
                                    preview = true,
                                    interactive = false,
                                    modifier = Modifier
                                        .width(
                                            unit * variant.columns +
                                                previewGap * (variant.columns - 1)
                                        )
                                        .height(
                                            unit * variant.rows + previewGap * (variant.rows - 1)
                                        )
                                )
                            }
                        }
                    }
                    if (variants.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            variants.indices.forEach { page ->
                                Box(
                                    Modifier.size(7.dp).background(
                                        if (page == pager.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        CircleShape
                                    )
                                )
                            }
                        }
                    }
                    variants.getOrNull(pager.currentPage)?.let { variant ->
                        Text(
                            variant.label(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            variant.body(),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(max = 480.dp)
                                .padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
        Button(
            onClick = {
                variants.getOrNull(pager.currentPage)?.let { viewModel.selectVariant(it.id) }
                viewModel.configureSelected()
            },
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth()
                .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp).height(56.dp)
        ) {
            Text(stringResource(R.string.hub_widget_continue))
        }
    }
}

private val previewGap = 12.dp
