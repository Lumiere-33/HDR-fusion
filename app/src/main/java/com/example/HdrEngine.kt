package com.example

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class BlendStyle {
    BALANCED_HDR,
    VIBRANT,
    NIGHT_SIGHT
}

enum class AlignmentMode {
    AUTO,
    MANUAL,
    NONE
}

data class AlignmentResult(
    val dx: Int,
    val dy: Int
)

data class LoadedImage(
    val id: String,
    val uri: Uri,
    val bitmap: Bitmap?, // Downscaled version (e.g. max 1024px) for fast preview editing
    val width: Int,
    val height: Int,
    val averageLuminance: Float,
    val exposureLabel: String, // "Under-exposed", "Balanced", "Over-exposed"
    val manualDx: Int = 0,
    val manualDy: Int = 0,
    val autoDx: Int = 0,
    val autoDy: Int = 0
)

object HdrEngine {

    // Safely reads orientation for rotation correction
    fun getOrientation(context: Context, uri: Uri): Int {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }
        return 0
    }

    // Loads a loaded bitmap up to a max dimension, automatically orienting it
    suspend fun loadAndPrepareBitmap(context: Context, uri: Uri, maxDimension: Int): LoadedImage? = withContext(Dispatchers.IO) {
        try {
            // Get original size
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return@withContext null

            // Compute sample size
            var sampleSize = 1
            while ((origWidth / sampleSize) > maxDimension || (origHeight / sampleSize) > maxDimension) {
                sampleSize *= 2
            }

            // Decode scaled bitmap
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap: Bitmap? = null
            context.contentResolver.openInputStream(uri).use { stream ->
                bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
            }

            if (bitmap == null) return@withContext null

            // Rotate if necessary
            val rotation = getOrientation(context, uri)
            var finalBitmap = bitmap!!
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap!!.recycle()
                }
                finalBitmap = rotated
            }

            // Calculate luminance to identify Exposure Bracket
            val lum = calculateAverageLuminance(finalBitmap)
            val label = when {
                lum < 85f -> "Highlight-Preserved (Under)"
                lum > 170f -> "Shadow-Preserved (Over)"
                else -> "Standard (Midtone)"
            }

            return@withContext LoadedImage(
                id = uri.toString(),
                uri = uri,
                bitmap = finalBitmap,
                width = origWidth,
                height = origHeight,
                averageLuminance = lum,
                exposureLabel = label
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    private fun calculateAverageLuminance(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val subsample = 16
        var totalLuminance = 0f
        var count = 0

        // Subsample the image for extremely quick average luminance estimation
        for (y in 0 until h step subsample) {
            for (x in 0 until w step subsample) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                totalLuminance += yVal
                count++
            }
        }
        return if (count > 0) totalLuminance / count else 127f
    }

    // Auto Align: pure Kotlin downscaled Census-Transform Translation estimation (Exposure Invariant)
    // ref: reference image (usually image index 0)
    // target: target image to shift
    suspend fun findAlignmentOffset(ref: Bitmap, target: Bitmap): AlignmentResult = withContext(Dispatchers.Default) {
        val w = 128
        val h = 128
        val scaledRef = Bitmap.createScaledBitmap(ref, w, h, true)
        val scaledTarget = Bitmap.createScaledBitmap(target, w, h, true)

        val grayRef = IntArray(w * h)
        val grayTarget = IntArray(w * h)

        val pixelsRef = IntArray(w * h)
        val pixelsTarget = IntArray(w * h)

        scaledRef.getPixels(pixelsRef, 0, w, 0, 0, w, h)
        scaledTarget.getPixels(pixelsTarget, 0, w, 0, 0, w, h)

        for (i in 0 until (w * h)) {
            val pRef = pixelsRef[i]
            val rRef = (pRef shr 16) and 0xFF
            val gRef = (pRef shr 8) and 0xFF
            val bRef = pRef and 0xFF
            grayRef[i] = (0.299f * rRef + 0.587f * gRef + 0.114f * bRef).toInt()

            val pTarget = pixelsTarget[i]
            val rTarget = (pTarget shr 16) and 0xFF
            val gTarget = (pTarget shr 8) and 0xFF
            val bTarget = pTarget and 0xFF
            grayTarget[i] = (0.299f * rTarget + 0.587f * gTarget + 0.114f * bTarget).toInt()
        }

        scaledRef.recycle()
        scaledTarget.recycle()

        // Compute 8-bit local Census Transform for structural matching across bracketed exposures
        val censusRef = ByteArray(w * h)
        val censusTarget = ByteArray(w * h)
        val validRef = BooleanArray(w * h)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x

                // Census Transform for Reference Image
                val centerR = grayRef[idx]
                var codeR = 0
                if (grayRef[idx - w - 1] > centerR) codeR = codeR or 1
                if (grayRef[idx - w] > centerR)     codeR = codeR or 2
                if (grayRef[idx - w + 1] > centerR) codeR = codeR or 4
                if (grayRef[idx - 1] > centerR)     codeR = codeR or 8
                if (grayRef[idx + 1] > centerR)     codeR = codeR or 16
                if (grayRef[idx + w - 1] > centerR) codeR = codeR or 32
                if (grayRef[idx + w] > centerR)     codeR = codeR or 64
                if (grayRef[idx + w + 1] > centerR) codeR = codeR or 128
                censusRef[idx] = codeR.toByte()

                // Calculate contrast filtering mask for reference pixels to ignore low-contrast noisy regions (like sky)
                var minValR = centerR
                var maxValR = centerR
                val neighbors = intArrayOf(
                    grayRef[idx - w - 1], grayRef[idx - w], grayRef[idx - w + 1],
                    grayRef[idx - 1],                       grayRef[idx + 1],
                    grayRef[idx + w - 1], grayRef[idx + w], grayRef[idx + w + 1]
                )
                for (v in neighbors) {
                    if (v < minValR) minValR = v
                    if (v > maxValR) maxValR = v
                }
                validRef[idx] = (maxValR - minValR) >= 12

                // Census Transform for Target Image
                val centerT = grayTarget[idx]
                var codeT = 0
                if (grayTarget[idx - w - 1] > centerT) codeT = codeT or 1
                if (grayTarget[idx - w] > centerT)     codeT = codeT or 2
                if (grayTarget[idx - w + 1] > centerT) codeT = codeT or 4
                if (grayTarget[idx - 1] > centerT)     codeT = codeT or 8
                if (grayTarget[idx + 1] > centerT)     codeT = codeT or 16
                if (grayTarget[idx + w - 1] > centerT) codeT = codeT or 32
                if (grayTarget[idx + w] > centerT)     codeT = codeT or 64
                if (grayTarget[idx + w + 1] > centerT) codeT = codeT or 128
                censusTarget[idx] = codeT.toByte()
            }
        }

        var bestDx = 0
        var bestDy = 0
        var minSad = Long.MAX_VALUE

        // Search window of [-12, +12] offsets
        val searchRange = 12
        val border = 16

        // Determine if we should filter by contrast (ensure we have enough textured reference pixels)
        var texturedPixelCount = 0
        for (y in border until (h - border)) {
            val row = y * w
            for (x in border until (w - border)) {
                if (validRef[row + x]) {
                    texturedPixelCount++
                }
            }
        }
        val totalSearchPixels = (h - 2 * border) * (w - 2 * border)
        // If we have at least 5% textured pixels, use contrast filtering. Otherwise use all pixels.
        val useContrastFiltering = texturedPixelCount > (totalSearchPixels * 0.05)

        for (dy in -searchRange..searchRange) {
            ensureActive()
            for (dx in -searchRange..searchRange) {
                var hammingDist = 0L

                for (y in border until (h - border)) {
                    val rowRefStart = y * w
                    val rowTargetStart = (y + dy) * w + dx
                    for (x in border until (w - border)) {
                        val refIdx = rowRefStart + x
                        if (useContrastFiltering && !validRef[refIdx]) {
                            continue
                        }
                        val codeRef = censusRef[refIdx].toInt() and 0xFF
                        val codeTarget = censusTarget[rowTargetStart + x].toInt() and 0xFF
                        val diff = codeRef xor codeTarget
                        hammingDist += java.lang.Integer.bitCount(diff)
                    }
                }

                if (hammingDist < minSad) {
                    minSad = hammingDist
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Return negative displacement since we need to apply the INVERSE translation to align the image
        return@withContext AlignmentResult(-bestDx, -bestDy)
    }

    // High quality translation offset shifting utilizing Hardware-Accelerated Canvas
    fun refineAndShiftBitmap(refWidth: Int, refHeight: Int, target: Bitmap, offsetDx: Int, offsetDy: Int, scaleFactor: Float): Bitmap {
        val result = Bitmap.createBitmap(refWidth, refHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        
        // Compute scaled translate offset values using specific scale for x and y
        val tx = offsetDx * (refWidth.toFloat() / 128f)
        val ty = offsetDy * (refHeight.toFloat() / 128f)
        
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(target, tx, ty, paint)
        return result
    }

    // High-performance Kotlin pixel blend shader optimized for mobile CPUs
    suspend fun blendExposures(
        alignedBitmaps: List<Bitmap>,
        intensity: Float, // 0.0f..1.0f
        style: BlendStyle,
        shadows: Float = 0.0f,    // -1.0f to 1.0f
        highlights: Float = 0.0f   // -1.0f to 1.0f
    ): Bitmap = withContext(Dispatchers.Default) {
        val numImages = alignedBitmaps.size
        if (numImages == 0) return@withContext Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        if (numImages == 1) return@withContext alignedBitmaps[0].copy(Bitmap.Config.ARGB_8888, true)

        val width = alignedBitmaps[0].width
        val height = alignedBitmaps[0].height

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // Bulk load pixel colors into 1D IntArrays
        val pixelsList = List(numImages) { i ->
            val p = IntArray(width * height)
            alignedBitmaps[i].getPixels(p, 0, width, 0, 0, width, height)
            p
        }

        val outPixels = IntArray(width * height)
        val basePixels = pixelsList[0] // Reference image fallback

        // Math definitions for weight calculation
        val sigmaSq2 = 2f * 64f * 64f // standard-deviation-based well-exposedness exponent

        for (i in 0 until (width * height)) {
            if (i % 20000 == 0) {
                ensureActive()
            }
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            var sumW = 0f

            for (j in 0 until numImages) {
                val p = pixelsList[j][i]
                val alpha = (p shr 24) and 0xFF
                if (alpha == 0) continue // Skip transparent/unmapped bounding pixels

                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF

                // Fast estimation of well-exposedness using luminance
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                val center = when (style) {
                    BlendStyle.NIGHT_SIGHT -> 155f // Prioritize brighter midtones
                    else -> 127.5f
                }
                val dist = y - center
                // Weight is high at center, drops at extreme dark/saturated exposures
                val w = kotlin.math.exp(-(dist * dist) / sigmaSq2) + 0.01f

                sumR += r * w
                sumG += g * w
                sumB += b * w
                sumW += w
            }

            var rOut: Float
            var gOut: Float
            var bOut: Float

            if (sumW > 0f) {
                rOut = sumR / sumW
                gOut = sumG / sumW
                bOut = sumB / sumW
            } else {
                val bp = basePixels[i]
                rOut = ((bp shr 16) and 0xFF).toFloat()
                gOut = ((bp shr 8) and 0xFF).toFloat()
                bOut = (bp and 0xFF).toFloat()
            }

            // Reference baseline blending
            val refP = basePixels[i]
            val refR = ((refP shr 16) and 0xFF).toFloat()
            val refG = ((refP shr 8) and 0xFF).toFloat()
            val refB = (refP and 0xFF).toFloat()

            // Morph between original base image (intensity = 0.0) and HDR Blend (intensity = 1.0)
            rOut = rOut * intensity + refR * (1f - intensity)
            gOut = gOut * intensity + refG * (1f - intensity)
            bOut = bOut * intensity + refB * (1f - intensity)

            // Dynamic Styling Adjustments
            if (style == BlendStyle.NIGHT_SIGHT) {
                // Raise deep shadow layers
                val shadowFactor = 0.4f * intensity
                if (rOut < 115f) rOut += (115f - rOut) * shadowFactor
                if (gOut < 115f) gOut += (115f - gOut) * shadowFactor
                if (bOut < 115f) bOut += (115f - bOut) * shadowFactor
            } else if (style == BlendStyle.VIBRANT) {
                // S-curve contrast details
                val contrastFactor = 0.3f * intensity
                rOut = applyContrastCurve(rOut, contrastFactor)
                gOut = applyContrastCurve(gOut, contrastFactor)
                bOut = applyContrastCurve(bOut, contrastFactor)
            } else {
                // BALANCED_HDR
                // Soft highlights recovery and shadows compression
                val highlightComp = 0.2f * intensity
                if (rOut > 175f) rOut -= (rOut - 175f) * highlightComp
                if (gOut > 175f) gOut -= (gOut - 175f) * highlightComp
                if (bOut > 175f) bOut -= (bOut - 175f) * highlightComp

                val shadowLift = 0.18f * intensity
                if (rOut < 75f) rOut += (75f - rOut) * shadowLift
                if (gOut < 75f) gOut += (75f - gOut) * shadowLift
                if (bOut < 75f) bOut += (75f - bOut) * shadowLift
            }

            // High Precision Shadows & Highlights Slider Mapping
            val lumY = (0.299f * rOut + 0.587f * gOut + 0.114f * bOut).coerceIn(0f, 255f)
            
            // Shadows adjustment: targeted boost or pull-down below midtones
            if (shadows != 0f) {
                // Decay weight linearly from y = 0 up to y = 140
                val shadowWeight = (1f - (lumY / 140f)).coerceIn(0f, 1f)
                val shadowShift = shadows * 50f * shadowWeight
                rOut += shadowShift
                gOut += shadowShift
                bOut += shadowShift
            }

            // Highlights adjustment: targeted recover or burn-in above midtones
            if (highlights != 0f) {
                // Rise weight linearly from y = 110 up to y = 255
                val highlightWeight = ((lumY - 110f) / 145f).coerceIn(0f, 1f)
                val highlightShift = highlights * 50f * highlightWeight
                rOut += highlightShift
                gOut += highlightShift
                bOut += highlightShift
            }

            // Vibrant saturation boosting
            if (intensity > 0.02f) {
                val sFactor = when (style) {
                    BlendStyle.VIBRANT -> 0.45f * intensity
                    BlendStyle.NIGHT_SIGHT -> 0.2f * intensity
                    else -> 0.25f * intensity
                }
                val avg = (rOut + gOut + bOut) / 3f
                rOut = avg + (rOut - avg) * (1f + sFactor)
                gOut = avg + (gOut - avg) * (1f + sFactor)
                bOut = avg + (bOut - avg) * (1f + sFactor)
            }

            val cr = rOut.coerceIn(0f, 255f).toInt()
            val cg = gOut.coerceIn(0f, 255f).toInt()
            val cb = bOut.coerceIn(0f, 255f).toInt()

            outPixels[i] = (0xFF shl 24) or (cr shl 16) or (cg shl 8) or cb
        }

        result.setPixels(outPixels, 0, width, 0, 0, width, height)
        return@withContext result
    }

    private fun applyContrastCurve(v: Float, factor: Float): Float {
        val x = v / 255f
        val sCurve = x * x * (3f - 2f * x) // classical smooth S-curve
        val blended = x * (1f - factor) + sCurve * factor
        return blended * 255f
    }

    // Saves the composite output to device DCIM/Pictures seamlessly without runtime prompt struggles
    suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/HDRFusion")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream != null) {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 97, outputStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)
                }
                return@withContext uri
            } catch (e: Exception) {
                e.printStackTrace()
                contentResolver.delete(uri, null, null)
            }
        }
        return@withContext null
    }
}
