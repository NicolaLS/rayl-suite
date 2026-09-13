package xyz.lilsus.raylsuite.feature.paymentui

import androidx.compose.runtime.Immutable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import xyz.lilsus.raylsuite.core.model.LightningAddress

@Immutable
data class LnurlPayDisplay(
    val domain: String,
    val description: String,
    val image: LnurlPayImage? = null,
    val recipient: String = domain
) {
    companion object {
        fun fromUntrusted(
            domain: String,
            description: String?,
            imagePngBase64: String? = null,
            imageJpegBase64: String? = null,
            lightningAddress: LightningAddress? = null
        ): LnurlPayDisplay? {
            val safeDomain = sanitizeDomain(domain) ?: return null
            val safeDescription = sanitizeDescription(description) ?: return null
            return LnurlPayDisplay(
                domain = safeDomain,
                description = safeDescription,
                image =
                    LnurlPayImage.fromBase64(LnurlPayImageFormat.Png, imagePngBase64)
                        ?: LnurlPayImage.fromBase64(
                            LnurlPayImageFormat.Jpeg,
                            imageJpegBase64
                        ),
                recipient = lightningAddress?.full ?: safeDomain
            )
        }

        private fun sanitizeDomain(value: String): String? {
            val domain = value.trim().trimEnd('.').lowercase()
            if (domain.isEmpty() || domain.length > MAX_DOMAIN_LENGTH) return null
            if (domain.contains("..")) return null
            if (domain.any { !it.isLetterOrDigit() && it !in ".-:" }) return null
            return domain
        }

        private fun sanitizeDescription(value: String?): String? {
            val source = value?.take(MAX_DESCRIPTION_INPUT_LENGTH) ?: return null
            val sanitized = buildString(source.length) {
                var previousWasSpace = false
                source.forEach { character ->
                    val replacement = when {
                        character.isUnsafeControl() -> ' '
                        character == '<' -> '‹'
                        character == '>' -> '›'
                        character.isWhitespace() -> ' '
                        else -> character
                    }
                    if (replacement == ' ') {
                        if (!previousWasSpace) append(replacement)
                        previousWasSpace = true
                    } else {
                        append(replacement)
                        previousWasSpace = false
                    }
                }
            }.trim().take(MAX_DESCRIPTION_LENGTH)
            return sanitized.ifEmpty { null }
        }

        private fun Char.isUnsafeControl(): Boolean = code in 0x00..0x1f ||
            code in 0x7f..0x9f ||
            code in 0x200b..0x200f ||
            code in 0x202a..0x202e ||
            code in 0x2060..0x2069 ||
            code == 0xfeff

        private const val MAX_DOMAIN_LENGTH = 253
        private const val MAX_DESCRIPTION_INPUT_LENGTH = 2_048
        private const val MAX_DESCRIPTION_LENGTH = 280
    }
}

@Immutable
class LnurlPayImage private constructor(
    private val encodedBytes: ByteArray,
    val format: LnurlPayImageFormat
) {
    override fun equals(other: Any?): Boolean = other is LnurlPayImage &&
        format == other.format &&
        encodedBytes.contentEquals(other.encodedBytes)

    override fun hashCode(): Int = 31 * format.hashCode() + encodedBytes.contentHashCode()

    internal fun copyEncodedBytes(): ByteArray = encodedBytes.copyOf()

    @OptIn(ExperimentalEncodingApi::class)
    internal fun encodedBase64(): String = Base64.Default.encode(encodedBytes)

    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        internal fun fromBase64(format: LnurlPayImageFormat, encoded: String?): LnurlPayImage? {
            if (encoded.isNullOrEmpty() || encoded.length > MAX_BASE64_LENGTH) return null
            val bytes = runCatching { Base64.Default.decode(encoded) }.getOrNull() ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return null
            val dimensions = when (format) {
                LnurlPayImageFormat.Png -> pngDimensions(bytes)
                LnurlPayImageFormat.Jpeg -> jpegDimensions(bytes)
            } ?: return null
            if (!dimensions.areSafe()) return null
            return LnurlPayImage(bytes.copyOf(), format)
        }

        private fun pngDimensions(bytes: ByteArray): ImageDimensions? {
            if (bytes.size < 24 || !bytes.startsWith(PNG_SIGNATURE)) return null
            if (bytes.copyOfRange(12, 16).decodeToString() != "IHDR") return null
            return ImageDimensions(
                width = bytes.readInt(16),
                height = bytes.readInt(20)
            )
        }

        private fun jpegDimensions(bytes: ByteArray): ImageDimensions? {
            if (bytes.size < 4 || bytes[0].unsigned() != 0xff || bytes[1].unsigned() != 0xd8) {
                return null
            }
            var offset = 2
            while (offset + 3 < bytes.size) {
                if (bytes[offset].unsigned() != 0xff) return null
                while (offset < bytes.size && bytes[offset].unsigned() == 0xff) offset++
                if (offset >= bytes.size) return null
                val marker = bytes[offset].unsigned()
                offset++
                if (marker == 0xd9 || marker == 0xda) return null
                if (marker == 0x01 || marker in 0xd0..0xd7) continue
                if (offset + 1 >= bytes.size) return null
                val segmentLength = bytes.readUnsignedShort(offset)
                if (segmentLength < 2 || offset + segmentLength > bytes.size) return null
                if (marker in JPEG_START_OF_FRAME_MARKERS) {
                    if (segmentLength < 7) return null
                    return ImageDimensions(
                        width = bytes.readUnsignedShort(offset + 5),
                        height = bytes.readUnsignedShort(offset + 3)
                    )
                }
                offset += segmentLength
            }
            return null
        }

        private fun ImageDimensions.areSafe(): Boolean = width in 1..MAX_IMAGE_DIMENSION &&
            height in 1..MAX_IMAGE_DIMENSION &&
            width.toLong() * height <= MAX_IMAGE_PIXELS

        private fun ByteArray.startsWith(prefix: IntArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it].unsigned() == prefix[it] }

        private fun ByteArray.readInt(offset: Int): Int = (this[offset].unsigned() shl 24) or
            (this[offset + 1].unsigned() shl 16) or
            (this[offset + 2].unsigned() shl 8) or
            this[offset + 3].unsigned()

        private fun ByteArray.readUnsignedShort(offset: Int): Int =
            (this[offset].unsigned() shl 8) or this[offset + 1].unsigned()

        private fun Byte.unsigned(): Int = toInt() and 0xff

        private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        private val JPEG_START_OF_FRAME_MARKERS =
            setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
        private const val MAX_BASE64_LENGTH = 136_536
        private const val MAX_IMAGE_BYTES = 100 * 1024
        private const val MAX_IMAGE_DIMENSION = 2_048
        private const val MAX_IMAGE_PIXELS = 4_000_000L
    }
}

enum class LnurlPayImageFormat {
    Png,
    Jpeg
}

private data class ImageDimensions(val width: Int, val height: Int)
