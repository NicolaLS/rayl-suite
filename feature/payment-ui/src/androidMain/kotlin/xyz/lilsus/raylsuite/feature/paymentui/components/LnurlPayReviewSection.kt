package xyz.lilsus.raylsuite.feature.paymentui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTestTags
import xyz.lilsus.raylsuite.feature.paymentui.R

/**
 * Recipient identity for a payment about to be made. The sheet lays it out as a leading row; a
 * full-screen review centers it above the amount.
 */
@Composable
internal fun LnurlPayReviewSection(
    display: LnurlPayDisplay,
    modifier: Modifier = Modifier,
    centered: Boolean = false
) {
    val sectionModifier = modifier.fillMaxWidth().testTag(PaymentTestTags.LNURL_PAY_DETAILS)
    val bitmap = remember(display.image) {
        display.image?.let {
            runCatching { it.copyEncodedBytes().decodeToImageBitmap() }.getOrNull()
        }
    }
    if (centered) {
        Column(
            modifier = sectionModifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            bitmap?.let { RecipientImage(it, size = 64.dp) }
            RecipientText(display, textAlign = TextAlign.Center)
        }
    } else {
        Row(
            modifier = sectionModifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bitmap?.let { RecipientImage(it, size = 56.dp) }
            RecipientText(display, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecipientImage(bitmap: ImageBitmap, size: Dp) {
    Image(
        bitmap = bitmap,
        contentDescription = stringResource(R.string.lnurl_payment_image_description),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
        alignment = Alignment.Center
    )
}

@Composable
private fun RecipientText(
    display: LnurlPayDisplay,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null
) {
    Text(
        text = stringResource(R.string.lnurl_payment_recipient, display.recipient),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
        modifier = modifier
    )
}
