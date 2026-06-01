package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProcessState {
    IDLE,
    LOADING_IMAGES,
    ALIGNING,
    BLENDING,
    SAVING,
    SUCCESS,
    ERROR
}

data class HdrUiState(
    val selectedImages: List<LoadedImage> = emptyList(),
    val alignmentMode: AlignmentMode = AlignmentMode.AUTO,
    val blendStyle: BlendStyle = BlendStyle.BALANCED_HDR,
    val intensity: Float = 0.6f,
    val shadows: Float = 0.0f,
    val highlights: Float = 0.0f,
    val processState: ProcessState = ProcessState.IDLE,
    val statusText: String = "",
    val blendedPreviewBitmap: Bitmap? = null,
    val savedUri: Uri? = null,
    val showCompareOriginal: Boolean = false,
    val displayLog: List<String> = emptyList()
)

class HdrViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HdrUiState())
    val uiState: StateFlow<HdrUiState> = _uiState.asStateFlow()

    private var blendJob: Job? = null
    private var alignmentJob: Job? = null

    fun addLog(message: String) {
        _uiState.update { currentState ->
            currentState.copy(
                displayLog = currentState.displayLog + message,
                statusText = message
            )
        }
    }

    fun clearLog() {
        _uiState.update { it.copy(displayLog = emptyList()) }
    }

    fun loadImagesFromUris(context: Context, uris: List<Uri>) {
        if (uris.size < 2 || uris.size > 3) {
            _uiState.update { 
                it.copy(
                    processState = ProcessState.ERROR,
                    statusText = "Please select exactly 2 or 3 photos to construct an HDR exposure."
                )
            }
            return
        }

        alignmentJob?.cancel()
        blendJob?.cancel()

        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    selectedImages = emptyList(),
                    processState = ProcessState.LOADING_IMAGES,
                    statusText = "Loading photos from gallery...",
                    displayLog = emptyList(),
                    blendedPreviewBitmap = null,
                    savedUri = null
                )
            }

            addLog("Preparing image pipeline...")

            val loaded = mutableListOf<LoadedImage>()
            uris.forEachIndexed { index, uri ->
                addLog("Decoding exposure frame #${index + 1}...")
                val loadedImage = HdrEngine.loadAndPrepareBitmap(context, uri, maxDimension = 640)
                if (loadedImage != null) {
                    loaded.add(loadedImage)
                    addLog("Luminance decoded: ${loadedImage.averageLuminance.toInt()}/255 -> ${loadedImage.exposureLabel}")
                }
            }

            if (loaded.size < 2) {
                _uiState.update { 
                    it.copy(
                        processState = ProcessState.ERROR,
                        statusText = "Failed to load photos. Please try other files."
                    )
                }
                return@launch
            }

            _uiState.update { 
                it.copy(
                    selectedImages = loaded,
                    processState = ProcessState.IDLE
                )
            }

            // Always run alignment after loading images
            runAlignment()
        }
    }

    fun removeImage(id: String) {
        alignmentJob?.cancel()
        blendJob?.cancel()

        _uiState.update { currentState ->
            val updated = currentState.selectedImages.filter { it.id != id }
            currentState.copy(
                selectedImages = updated,
                blendedPreviewBitmap = null,
                savedUri = null
            )
        }
        if (_uiState.value.selectedImages.size >= 2) {
            runAlignment()
        } else {
            _uiState.update { it.copy(processState = ProcessState.IDLE, statusText = "Requires at least 2 photos") }
        }
    }

    override fun onCleared() {
        super.onCleared()
        alignmentJob?.cancel()
        blendJob?.cancel()
    }

    fun setAlignmentMode(mode: AlignmentMode) {
        _uiState.update { it.copy(alignmentMode = mode) }
        runAlignment()
    }

    fun setBlendStyle(style: BlendStyle) {
        _uiState.update { it.copy(blendStyle = style) }
        triggerQuickBlend()
    }

    fun setIntensity(intensity: Float) {
        _uiState.update { it.copy(intensity = intensity) }
        triggerQuickBlend()
    }

    fun setShadows(shadows: Float) {
        _uiState.update { it.copy(shadows = shadows) }
        triggerQuickBlend()
    }

    fun setHighlights(highlights: Float) {
        _uiState.update { it.copy(highlights = highlights) }
        triggerQuickBlend()
    }

    fun setShowCompare(compare: Boolean) {
        _uiState.update { it.copy(showCompareOriginal = compare) }
    }

    fun adjustManualOffset(imageId: String, dx: Int, dy: Int) {
        _uiState.update { currentState ->
            val updated = currentState.selectedImages.map {
                if (it.id == imageId) {
                    it.copy(
                        manualDx = (it.manualDx + dx).coerceIn(-150, 150),
                        manualDy = (it.manualDy + dy).coerceIn(-150, 150)
                    )
                } else {
                    it
                }
            }
            currentState.copy(selectedImages = updated)
        }
        triggerQuickBlend()
    }

    fun resetOffsets(imageId: String) {
        _uiState.update { currentState ->
            val updated = currentState.selectedImages.map {
                if (it.id == imageId) {
                    it.copy(manualDx = 0, manualDy = 0)
                } else {
                    it
                }
            }
            currentState.copy(selectedImages = updated)
        }
        triggerQuickBlend()
    }

    private fun runAlignment() {
        val images = _uiState.value.selectedImages
        if (images.size < 2) return

        alignmentJob?.cancel()
        alignmentJob = viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    processState = ProcessState.ALIGNING,
                    statusText = "Aligning exposure layers..."
                )
            }
            addLog("Executing image registration...")

            val refImage = images[0]
            val refBitmap = refImage.bitmap ?: return@launch

            val updatedImages = images.mapIndexed { index, loadedImg ->
                if (index == 0) {
                    loadedImg
                } else {
                    val targetBitmap = loadedImg.bitmap
                    if (targetBitmap != null) {
                        addLog("Calculating translation delta for frame #${index + 1}...")
                        val offset = HdrEngine.findAlignmentOffset(refBitmap, targetBitmap)
                        addLog("Alignment localized: Shift (dx: ${offset.dx}, dy: ${offset.dy}) relative to baseline.")
                        loadedImg.copy(autoDx = offset.dx, autoDy = offset.dy)
                    } else {
                        loadedImg
                    }
                }
            }

            _uiState.update { 
                it.copy(
                    selectedImages = updatedImages,
                    processState = ProcessState.IDLE
                )
            }

            triggerQuickBlend()
        }
    }

    // Runs the blending algorithm on the background thread with job cancellation
    fun triggerQuickBlend() {
        val state = _uiState.value
        val images = state.selectedImages
        if (images.size < 2) return

        blendJob?.cancel() // Cancel previous blend immediately if user is dragging slider

        blendJob = viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(processState = ProcessState.BLENDING, statusText = "Synthesizing dynamic range...") }
            
            val refImg = images[0]
            val refBitmap = refImg.bitmap ?: return@launch
            val refW = refBitmap.width
            val refH = refBitmap.height

            val alignedList = mutableListOf<Bitmap>()
            alignedList.add(refBitmap)

            var blended: Bitmap? = null
            var success = false

            try {
                // Shift and refine remaining bitmaps to match reference size perfectly
                for (i in 1 until images.size) {
                    ensureActive()
                    val targetImg = images[i]
                    val tBitmap = targetImg.bitmap ?: continue

                    // Calculate perfect scaled tx, ty displacement
                    val (tx, ty) = when (state.alignmentMode) {
                        AlignmentMode.AUTO -> {
                            val scaleX = refW.toFloat() / 128f
                            val scaleY = refH.toFloat() / 128f
                            // Auto alignment offset needs to be inverted (negated) to register image back
                            Pair(-targetImg.autoDx * scaleX, -targetImg.autoDy * scaleY)
                        }
                        AlignmentMode.MANUAL -> {
                            Pair(targetImg.manualDx.toFloat(), targetImg.manualDy.toFloat())
                        }
                        AlignmentMode.NONE -> {
                            Pair(0f, 0f)
                        }
                    }

                    // Create hardware shifted bitmap alignment
                    val shifted = HdrEngine.refineAndShiftBitmap(
                        refWidth = refW,
                        refHeight = refH,
                        target = tBitmap,
                        tx = tx,
                        ty = ty
                    )
                    alignedList.add(shifted)
                }

                ensureActive()

                // Execute raw blend formula
                blended = HdrEngine.blendExposures(
                    alignedList,
                    state.intensity,
                    state.blendStyle,
                    state.shadows,
                    state.highlights
                )

                ensureActive()

                _uiState.update { 
                    it.copy(
                        blendedPreviewBitmap = blended,
                        processState = ProcessState.IDLE,
                        statusText = "HDR exposure synthesized successfully."
                    )
                }
                success = true

            } catch (t: Throwable) {
                if (!success) {
                    blended?.recycle()
                }
                throw t
            } finally {
                // Clean up temporary shifted bitmaps to free memory under any success/failure/cancellation
                for (i in 1 until alignedList.size) {
                    if (alignedList[i] != images[i].bitmap) {
                        alignedList[i].recycle()
                    }
                }
            }
        }
    }

    // Processes the full-resolution photos selected and saves to the user's gallery
    fun saveHighResExposure(context: Context) {
        val state = _uiState.value
        val images = state.selectedImages
        if (images.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    processState = ProcessState.SAVING,
                    statusText = "Rendering full-resolution print..."
                )
            }
            addLog("Executing high-resolution export pipeline...")

            // 1. Load full resolution images
            val fullBitmaps = mutableListOf<Bitmap>()
            var refW = 0
            var refH = 0

            try {
                for (i in 0 until images.size) {
                    addLog("Loading frame #${i + 1} at full resolution...")
                    // Load raw full scale bitmap
                    val model = HdrEngine.loadAndPrepareBitmap(
                        context,
                        images[i].uri,
                        maxDimension = 4000 // support up to 4K resolution safely
                    )
                    val b = model?.bitmap
                    if (b != null) {
                        fullBitmaps.add(b)
                        if (i == 0) {
                            refW = b.width
                            refH = b.height
                            addLog("Base print template: ${refW}x${refH} pixels")
                        }
                    }
                }

                if (fullBitmaps.size < 2) {
                    _uiState.update { 
                        it.copy(
                            processState = ProcessState.ERROR,
                            statusText = "Not enough images to complete the export."
                        )
                    }
                    return@launch
                }

                val alignedList = mutableListOf<Bitmap>()
                alignedList.add(fullBitmaps[0])

                // 2. Refine shifts at full resolution
                for (i in 1 until fullBitmaps.size) {
                    val targetImg = images[i]
                    val tBitmap = fullBitmaps[i]

                    addLog("Applying sub-pixel translation alignment on frame #${i + 1}...")
                    
                    val (tx, ty) = when (state.alignmentMode) {
                        AlignmentMode.AUTO -> {
                            val scaleX = refW.toFloat() / 128f
                            val scaleY = refH.toFloat() / 128f
                            // AUTO registration shift is negated to align
                            Pair(-targetImg.autoDx * scaleX, -targetImg.autoDy * scaleY)
                        }
                        AlignmentMode.MANUAL -> {
                            val previewW = targetImg.bitmap?.width ?: 1
                            val previewH = targetImg.bitmap?.height ?: 1
                            val scaleX = refW.toFloat() / previewW.toFloat()
                            val scaleY = refH.toFloat() / previewH.toFloat()
                            Pair(targetImg.manualDx * scaleX, targetImg.manualDy * scaleY)
                        }
                        AlignmentMode.NONE -> {
                            Pair(0f, 0f)
                        }
                    }

                    val shifted = HdrEngine.refineAndShiftBitmap(
                        refWidth = refW,
                        refHeight = refH,
                        target = tBitmap,
                        tx = tx,
                        ty = ty
                    )
                    alignedList.add(shifted)
                }

                // 3. Blend exposures
                addLog("Fusing exposures with high precision color mapping...")
                val blended = HdrEngine.blendExposures(
                    alignedList,
                    state.intensity,
                    state.blendStyle,
                    state.shadows,
                    state.highlights
                )

                // 4. Save to gallery
                addLog("Compressing dynamic range and saving image...")
                val timestamp = System.currentTimeMillis()
                val filename = "HDR_FUSION_$timestamp.jpg"
                val uri = HdrEngine.saveBitmapToGallery(context, blended, filename)

                // 5. Clean up allocations
                blended.recycle()
                for (i in 0 until fullBitmaps.size) {
                    fullBitmaps[i].recycle()
                }
                for (i in 1 until alignedList.size) {
                    alignedList[i].recycle()
                }

                if (uri != null) {
                    addLog("Saved to gallery: Pictures/HDRFusion/$filename")
                    _uiState.update { 
                        it.copy(
                            savedUri = uri,
                            processState = ProcessState.SUCCESS,
                            statusText = "Saved successfully!"
                        )
                    }
                } else {
                    _uiState.update { 
                        it.copy(
                            processState = ProcessState.ERROR,
                            statusText = "Failed to save to gallery."
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update { 
                    it.copy(
                        processState = ProcessState.ERROR,
                        statusText = "Error during save operation: ${e.localizedMessage}"
                    )
                }
            }
        }
    }
}
