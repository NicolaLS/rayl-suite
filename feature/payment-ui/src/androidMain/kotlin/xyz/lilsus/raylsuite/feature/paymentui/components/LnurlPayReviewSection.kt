package xyz.lilsus.raylsuite.feature.paymentui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.lilsus.raylsuite.feature.paymentui.LnurlPayDisplay
import xyz.lilsus.raylsuite.feature.paymentui.PaymentTestTags
import xyz.lilsus.raylsuite.feature.paymentui.R

@Composable
internal fun LnurlPayReviewSection(display: LnurlPayDisplay, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().testTag(PaymentTestTags.LNURL_PAY_DETAILS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        display.image?.let { image ->
            val bitmap = remember(image) {
                runCatching { image.copyEncodedBytes().decodeToImageBitmap() }.getOrNull()
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription =
                        stringResource(R.string.lnurl_payment_image_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp),
                    alignment = Alignment.Center
                )
            }
        }
        Text(
            text = stringResource(R.string.lnurl_payment_recipient, display.recipient),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
    }
}
