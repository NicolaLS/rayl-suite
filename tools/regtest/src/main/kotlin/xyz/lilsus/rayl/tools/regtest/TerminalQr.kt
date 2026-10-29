package xyz.lilsus.rayl.tools.regtest

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [value] as a QR code with two modules per character cell. Colors are explicit, so it
 * scans on light and dark terminal themes alike.
 */
internal fun terminalQr(value: String): String {
    val matrix = QRCodeWriter().encode(
        value,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2)
    )
    return buildString {
        for (y in 0 until matrix.height step 2) {
            for (x in 0 until matrix.width) {
                val top = matrix[x, y]
                val bottom = y + 1 < matrix.height && matrix[x, y + 1]
                // Upper half block: foreground paints the top module, background the bottom one.
                append("\u001b[${if (top) 30 else 97};${if (bottom) 40 else 107}m▀")
            }
            append("\u001b[0m\n")
        }
    }
}
