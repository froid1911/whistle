package dev.whistle.app.ui.pair

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * On-device QR decoding (ZXing core) of camera luminance frames. Both polarities: the bridge
 * prints light modules as blocks, so on a light terminal theme the code is inverted.
 */
class QrDecoder {
    // MultiFormatReader (QR only) because it implements ALSO_INVERTED; QRCodeReader alone ignores it.
    private val reader = MultiFormatReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /** Decodes a Y plane ([rowStride] ≥ [width]); null when no QR code is found. */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val src = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(src)), hints).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
