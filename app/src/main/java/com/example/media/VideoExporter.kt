package com.example.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Exports the dubbed video: the original picture with its audio replaced by the AI voice track,
 * and (optionally) the blur box burned in.
 */
@OptIn(UnstableApi::class)
class VideoExporter(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Writes an MP4 to [output] whose video comes from [videoFile] and whose only audio track is
     * [audioFile]. The original audio is dropped.
     *
     * Without a blur box the video stream is copied as-is (fast, no quality loss). With a blur
     * box, or if copying the stream fails, the video is re-encoded to H.264.
     *
     * Must be called from a coroutine; the Transformer runs on the main thread.
     */
    suspend fun export(
        videoFile: File,
        audioFile: File,
        blur: BlurRegion?,
        output: File,
        onProgress: (percent: Int) -> Unit
    ) {
        output.parentFile?.mkdirs()
        if (blur == null) {
            try {
                runTransformer(videoFile, audioFile, null, output, transmuxVideo = true, onProgress = onProgress)
                return
            } catch (e: ExportException) {
                // E.g. a codec the MP4 muxer can't copy: fall back to re-encoding.
                output.delete()
            }
        }
        runTransformer(videoFile, audioFile, blur, output, transmuxVideo = false, onProgress = onProgress)
    }

    private suspend fun runTransformer(
        videoFile: File,
        audioFile: File,
        blur: BlurRegion?,
        output: File,
        transmuxVideo: Boolean,
        onProgress: (Int) -> Unit
    ): ExportResult = withContext(Dispatchers.Main) {
        val videoEffects: List<Effect> = if (blur != null) RegionBlurEffect.create(blur) else emptyList()
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(videoFile)))
            .setEffects(Effects(/* audioProcessors= */ emptyList(), videoEffects))
            .build()
        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audioFile))).build()
        // Picture from the original video, sound only from the AI voice track.
        val composition = Composition.Builder(
            EditedMediaItemSequence.withVideoFrom(listOf(videoItem)),
            EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
        )
            .setTransmuxVideo(transmuxVideo)
            .build()

        suspendCancellableCoroutine<ExportResult> { continuation ->
            val handler = Handler(Looper.getMainLooper())
            val progressHolder = ProgressHolder()
            lateinit var poller: Runnable

            val builder = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poller)
                        if (continuation.isActive) continuation.resume(exportResult)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        handler.removeCallbacks(poller)
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
            if (!transmuxVideo) builder.setVideoMimeType(MimeTypes.VIDEO_H264)
            val transformer = builder.build()

            poller = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress)
                    }
                    handler.postDelayed(this, PROGRESS_POLL_MS)
                }
            }

            continuation.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poller)
                    transformer.cancel()
                    output.delete()
                }
            }

            try {
                transformer.start(composition, output.absolutePath)
                handler.post(poller)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    }

    private companion object {
        const val PROGRESS_POLL_MS = 250L
    }
}
