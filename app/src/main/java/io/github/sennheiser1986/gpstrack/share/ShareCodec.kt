package io.github.sennheiser1986.gpstrack.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A peer decoded from a scanned QR code.
 *
 * @property id the peer's sharing id.
 * @property label the peer's display name, or a shortened id when the code carried no name.
 */
data class ScannedPeer(
    val id: String,
    val label: String,
)

/**
 * Encodes and decodes the payload carried in an instance's sharing QR code, and renders the QR
 * bitmap. The payload is a URI:
 *
 *     gpstrack://peer?id=<sharing id>&name=<display name>
 *
 * A bare sharing id is also accepted when decoding, so a code made by other means still works.
 */
object ShareCodec {

    private const val SCHEME = "gpstrack"
    private const val HOST = "peer"

    /**
     * Accepts a sharing id: the current 43-character URL-safe base64 tokens, and the random
     * UUIDs older versions minted. Broad enough to be format-agnostic, tight enough to reject
     * arbitrary scanned text.
     */
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,128}$")

    /**
     * Builds the QR payload text for this instance.
     *
     * @param instanceId this device's sharing id.
     * @param displayName the name to embed for peers to see.
     * @return the payload URI as a string.
     */
    fun encode(instanceId: String, displayName: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter("id", instanceId)
            .appendQueryParameter("name", displayName)
            .build()
            .toString()

    /**
     * Parses a scanned string into a [ScannedPeer].
     *
     * @param scanned the raw text from the scanner.
     * @return the peer, or null when the text is neither a valid payload URI nor a bare id.
     */
    fun decode(scanned: String): ScannedPeer? {
        val trimmed = scanned.trim()
        if (ID_PATTERN.matches(trimmed)) {
            return ScannedPeer(id = trimmed, label = trimmed.take(8))
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val id = uri.getQueryParameter("id")?.takeIf { ID_PATTERN.matches(it) } ?: return null
        val name = uri.getQueryParameter("name")?.takeIf { it.isNotBlank() } ?: id.take(8)
        return ScannedPeer(id = id, label = name)
    }

    private val DECODE_HINTS = mapOf(DecodeHintType.TRY_HARDER to true)

    /**
     * Reads a QR code out of a still image and returns its raw text, or null when the image
     * holds no readable QR code. The result is meant to be passed straight to [decode].
     *
     * It tries progressively harder: two binarizers ([HybridBinarizer] for uneven lighting,
     * [GlobalHistogramBinarizer] for flat images), each on the image as-is and inverted, and if
     * all of that fails, the same set again on a 2x-upscaled copy — which recovers a small or
     * soft QR lifted out of a screenshot.
     *
     * @param bitmap the image to scan.
     * @return the QR payload text, or null.
     */
    fun decodeQrPayload(bitmap: Bitmap): String? {
        luminanceSource(bitmap)?.let { attemptDecode(it) }?.let { return it }

        // A 2x pass only helps when the QR is small; skip it (and its allocation) for images
        // that are already large.
        if (maxOf(bitmap.width, bitmap.height) > MAX_UPSCALE_INPUT_EDGE) return null
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
        }.getOrNull() ?: return null
        return luminanceSource(scaled)?.let { attemptDecode(it) }
    }

    /**
     * Builds a ZXing luminance source from a bitmap's pixels.
     *
     * @param bitmap the image.
     * @return the source, or null when the bitmap has no pixels.
     */
    private fun luminanceSource(bitmap: Bitmap): RGBLuminanceSource? {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return RGBLuminanceSource(width, height, pixels)
    }

    /**
     * Tries to read one QR code from a luminance source using both binarizers and both
     * polarities.
     *
     * @param source the image luminance.
     * @return the QR text on the first success, or null.
     */
    private fun attemptDecode(source: RGBLuminanceSource): String? {
        val binaries = listOf(
            BinaryBitmap(HybridBinarizer(source)),
            BinaryBitmap(HybridBinarizer(source.invert())),
            BinaryBitmap(GlobalHistogramBinarizer(source)),
            BinaryBitmap(GlobalHistogramBinarizer(source.invert())),
        )
        for (binary in binaries) {
            val text = runCatching {
                QRCodeReader().decode(binary, DECODE_HINTS).text
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    /**
     * Loads an image the reader picked, downscaled enough to scan without exhausting memory.
     *
     * @param context any context, for its content resolver.
     * @param uri the picked image.
     * @return the decoded bitmap, or null when it could not be read.
     */
    fun readImageForScanning(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_SCAN_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_SCAN_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * Largest edge, in pixels, a picked image is decoded to before scanning. Kept high enough
     * that a typical phone screenshot (~1080x2400) is read at full resolution rather than
     * halved by the power-of-two downsample, which is what makes a QR that only fills part of a
     * screenshot decodable.
     */
    private const val MAX_SCAN_DIMENSION = 2600

    /** Above this edge length, the 2x upscale retry is skipped (it would not help and costs memory). */
    private const val MAX_UPSCALE_INPUT_EDGE = 1400

    /**
     * Renders a QR code bitmap for a payload.
     *
     * @param content the payload text, normally from [encode].
     * @param sizePixels the width and height of the square bitmap.
     * @return an ARGB_8888 bitmap with black modules on a white background.
     */
    fun qrBitmap(content: String, sizePixels: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePixels, sizePixels, hints)
        val bitmap = Bitmap.createBitmap(sizePixels, sizePixels, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePixels) {
            for (y in 0 until sizePixels) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
