package me.rerere.rikkahub.ui.pages.imggen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.shouldRethrowVmError
import java.io.File

@Serializable
data class GeneratedImage(
    val id: Int,
    val prompt: String,
    val filePath: String,
    val timestamp: Long,
    val model: String
)

/**
 * Map a throwable that escaped image generation/edit to the message to surface in `_error`, OR null
 * when it must be rethrown instead of reported. Cancellation (per [shouldRethrowVmError]) returns null
 * so the caller rethrows it — cancelling generation must reset state without surfacing an error; every
 * other throwable, Exception OR Error, maps to a non-null message so a non-Exception Throwable (e.g.
 * OutOfMemoryError) is reported to local UI state instead of escaping the root coroutine to the scope's
 * uncaught handler (which CrashHandler.install turns into markCrashed -> safe-mode). Pure (no Android,
 * no coroutines), so the rethrow-vs-report contract is JVM-testable, mirroring [backupListThrowableToState].
 */
fun imgGenErrorMessage(t: Throwable): String? =
    if (shouldRethrowVmError(t)) null else t.message ?: "Unknown error occurred"

/**
 * The preview-slot key for an incoming partial frame: the OUTPUT-image index, i.e. the number of
 * images finalized so far on this stream. NOT the frame's `partial_image_index`.
 *
 * `partial_image_index` is the OpenAI wire field for a single image's progressive-refinement pass
 * (0..partial_images-1) and resets to 0 for each of the n output images. Keying preview slots on it
 * would make image 1's first partial overwrite image 0's slot-0 tracking, leaking image 0's preview.
 * The endpoint streams images sequentially (image k's partials, then its `completed`, before k+1),
 * so the still-uncompleted output image is exactly [finalizedCount]. Pure so the slot-key contract
 * is JVM-testable without Android/IO.
 */
internal fun previewSlotKey(finalizedCount: Int): Int = finalizedCount

/**
 * Per-output-image tracking of outstanding streaming preview files. A single-var design loses every
 * preview but the most recently written one when numOfImages > 1 (each output image produces its own
 * preview filename), leaking the earlier slots' temp files. Keyed by output-image index (see
 * [previewSlotKey]), [drain] returns every still-open slot so the collector's terminal cleanup
 * deletes them on any exit path (cancel / failure / done).
 *
 * Pure (no Android, no IO) so the leak-no-slot invariant is JVM-testable in isolation. [File] is only
 * a value handle here; deletion happens in the caller.
 */
internal class PreviewSlots {
    private val slots = mutableMapOf<Int, File>()

    /** Remove and return the file currently tracked for [index], if any (caller deletes it). */
    fun take(index: Int): File? = slots.remove(index)

    /** Track [file] as the live preview for [index], replacing any prior handle. */
    fun put(index: Int, file: File) {
        slots[index] = file
    }

    /** Remove and return every outstanding preview file (caller deletes them). */
    fun drain(): List<File> {
        val out = slots.values.toList()
        slots.clear()
        return out
    }
}

private fun GenMediaEntity.toGeneratedImage(filesManager: FilesManager): GeneratedImage {
    val imagesDir = filesManager.getImagesDir()
    val fullPath = File(imagesDir, this.path.removePrefix("images/")).absolutePath

    return GeneratedImage(
        id = this.id,
        prompt = this.prompt,
        filePath = fullPath,
        timestamp = this.createAt,
        model = this.modelId
    )
}

class ImgGenVM(
    context: Application,
    val settingsStore: SettingsStore,
    val providerManager: ProviderManager,
    val genMediaRepository: GenMediaRepository,
    private val filesManager: FilesManager,
) : AndroidViewModel(context) {
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _numberOfImages = MutableStateFlow(1)
    val numberOfImages: StateFlow<Int> = _numberOfImages

    private val _aspectRatio = MutableStateFlow(ImageAspectRatio.SQUARE)
    val aspectRatio: StateFlow<ImageAspectRatio> = _aspectRatio

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating
    private var cancelJob: Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentGeneratedImages = MutableStateFlow<List<GeneratedImage>>(emptyList())
    val currentGeneratedImages: StateFlow<List<GeneratedImage>> = _currentGeneratedImages

    private val _referenceImages = MutableStateFlow<List<String>>(emptyList())
    val referenceImages: StateFlow<List<String>> = _referenceImages

    val pager = Pager(
        config = PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { genMediaRepository.getAllMedia() }
    )
    val generatedImages: Flow<PagingData<GeneratedImage>> = pager.flow
        .map { pagingData ->
            pagingData.map { entity -> entity.toGeneratedImage(filesManager) }
        }
        .cachedIn(viewModelScope)

    fun updatePrompt(prompt: String) {
        _prompt.value = prompt
    }

    fun updateNumberOfImages(count: Int) {
        _numberOfImages.value = count.coerceIn(1, 4)
    }

    fun updateAspectRatio(aspectRatio: ImageAspectRatio) {
        _aspectRatio.value = aspectRatio
    }

    fun addReferenceImages(paths: List<String>) {
        _referenceImages.value = (_referenceImages.value + paths).distinct().take(MAX_REFERENCE_IMAGES)
    }

    fun removeReferenceImage(path: String) {
        _referenceImages.value = _referenceImages.value.filterNot { it == path }
        deleteReferenceFiles(listOf(path))
    }

    fun clearReferenceImages() {
        deleteReferenceFiles(_referenceImages.value)
        _referenceImages.value = emptyList()
    }

    fun clearError() {
        _error.value = null
    }

    fun startNewSession() {
        cancelJob?.cancel()
        clearReferenceImages()
        _prompt.value = ""
        _currentGeneratedImages.value = emptyList()
        _error.value = null
        _isGenerating.value = false
    }

    fun generateImage() {
        if(prompt.value.isBlank()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val requestPrompt = _prompt.value
                val params = ImageGenerationParams(
                    model = model,
                    prompt = requestPrompt,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .generateImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                )
            } catch (t: Throwable) {
                val message = imgGenErrorMessage(t) ?: throw t
                Log.e(TAG, "Failed to generate image", t)
                _error.value = message
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun editImage() {
        if (prompt.value.isBlank() || referenceImages.value.isEmpty()) return
        cancelJob?.cancel()
        cancelJob = viewModelScope.launch {
            try {
                _isGenerating.value = true
                _error.value = null
                _currentGeneratedImages.value = emptyList()

                val settings = settingsStore.settingsFlow.first()
                val model = settings.findModelById(settings.imageGenerationModelId)
                    ?: throw IllegalStateException("No model selected")

                val provider = model.findProvider(settings.providers)
                    ?: throw IllegalStateException("Provider not found")

                val providerSetting = settings.providers.find { it.id == provider.id }
                    ?: throw IllegalStateException("Provider setting not found")

                val requestPrompt = _prompt.value
                val sourceImages = _referenceImages.value
                val params = ImageEditParams(
                    model = model,
                    prompt = requestPrompt,
                    images = sourceImages,
                    numOfImages = _numberOfImages.value,
                    aspectRatio = _aspectRatio.value,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies
                )

                val images = providerManager.getProviderByType(provider)
                    .editImage(providerSetting, params)

                collectImageGeneration(
                    images = images,
                    prompt = requestPrompt,
                    modelName = model.displayName,
                    type = GenMediaEntity.TYPE_IMAGE_EDIT,
                    sourcePaths = sourceImages.joinToString("\n"),
                )
            } catch (t: Throwable) {
                val message = imgGenErrorMessage(t) ?: throw t
                Log.e(TAG, "Failed to edit image", t)
                _error.value = message
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun cancelGeneration() {
        cancelJob?.cancel()
    }

    /**
     * Collect the streaming image flow: a partial item updates a per-output-image throwaway preview
     * slot (written under appTempFolder) while a final item is persisted to storage and appended to
     * the result list.
     *
     * Slots are keyed by the OUTPUT-image index (`finalIndex`), NOT the frame's `partial_image_index`.
     * Per the OpenAI image-streaming wire contract, `partial_image_index` is the 0-based PROGRESSIVE
     * REFINEMENT pass of a single image (0..partial_images-1) and resets to 0 for each of the n output
     * images — so with numOfImages > 1 distinct images would collide on slot 0 and the earlier image's
     * preview would leak. The endpoint streams images sequentially (all partials for image k, then its
     * `completed` frame, before image k+1 begins), so the still-uncompleted image is always `finalIndex`.
     *
     * The terminal `finally` deletes every outstanding preview on EVERY exit path (final frames
     * consumed, user cancel, or SSE failure), so streaming never leaks temp files even when the flow
     * ends after a partial.
     */
    private suspend fun collectImageGeneration(
        images: Flow<ImageGenerationItem>,
        prompt: String,
        modelName: String,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ) {
        val finalImages = mutableListOf<GeneratedImage>()
        val previewSlots = PreviewSlots()
        var finalIndex = 0

        try {
            images.collect { item ->
                val slot = previewSlotKey(finalIndex)
                if (item.partial) {
                    previewSlots.take(slot)?.delete()
                    val imageFile = saveImagePreview(
                        item = item,
                        modelName = modelName,
                        index = slot,
                    )
                    previewSlots.put(slot, imageFile)
                    _currentGeneratedImages.value = finalImages + GeneratedImage(
                        id = 0,
                        prompt = prompt,
                        filePath = imageFile.absolutePath,
                        timestamp = System.currentTimeMillis(),
                        model = modelName
                    )
                } else {
                    previewSlots.take(slot)?.delete()
                    val imageFile = saveImageToStorage(
                        item = item,
                        prompt = prompt,
                        modelName = modelName,
                        index = finalIndex,
                        type = type,
                        sourcePaths = sourcePaths,
                    )
                    finalImages.add(
                        GeneratedImage(
                            id = 0, // Will be updated after database insertion
                            prompt = prompt,
                            filePath = imageFile.absolutePath,
                            timestamp = System.currentTimeMillis(),
                            model = modelName
                        )
                    )
                    finalIndex++
                    _currentGeneratedImages.value = finalImages.toList()
                }
            }
        } finally {
            previewSlots.drain().forEach { it.delete() }
            // The drained previews above are now deleted files. _currentGeneratedImages may still
            // point at one of them (partial-then-cancel / partial-then-SSE-failure ends the flow
            // after a preview was published but before its final frame), and the screen renders
            // this list unconditionally. Re-pin it to the persisted finals so the UI never shows
            // an AsyncImage backed by a just-deleted temp file.
            _currentGeneratedImages.value = finalImages.toList()
        }
    }

    private fun saveImagePreview(
        item: ImageGenerationItem,
        modelName: String,
        index: Int,
    ): File {
        val timestamp = System.currentTimeMillis()
        val imageFile = File(
            getApplication<Application>().appTempFolder,
            "imggen_${timestamp}_${modelName}_$index.png"
        )
        return filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)
    }

    private suspend fun saveImageToStorage(
        item: ImageGenerationItem,
        prompt: String,
        modelName: String,
        index: Int,
        type: String = GenMediaEntity.TYPE_IMAGE_GENERATION,
        sourcePaths: String? = null,
    ): File {
        val imagesDir = filesManager.getImagesDir()

        val timestamp = System.currentTimeMillis()
        val filename = "${timestamp}_${modelName}_$index.png"
        val imageFile = File(imagesDir, filename)

        val createdFile = filesManager.createImageFileFromBase64(item.data, imageFile.absolutePath)

        // Save to database with relative path
        val relativePath = "images/${imageFile.name}"
        val entity = GenMediaEntity(
            path = relativePath,
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = type,
            sourcePaths = sourcePaths,
        )
        genMediaRepository.insertMedia(entity)

        return createdFile
    }

    fun deleteImage(image: GeneratedImage) {
        viewModelScope.launch {
            try {
                // Delete from database first
                genMediaRepository.deleteMedia(image.id)

                // Then delete the file
                val file = File(image.filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete image", e)
                _error.value = "Failed to delete image"
            }
        }
    }

    private fun deleteReferenceFiles(paths: List<String>) {
        viewModelScope.launch {
            paths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        private const val TAG = "ImgGenVM"
        private const val MAX_REFERENCE_IMAGES = 16
    }
}
