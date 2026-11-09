package io.github.leehj825.myapp.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the screen (H.264 video), the sound that apps play (app audio) and, if [withMic] is set,
 * the microphone, into an MP4 file.
 *
 * MediaRecorder cannot record app audio, so this uses the lower-level parts: a video encoder fed by the
 * virtual display, an AudioRecord for app audio (and the microphone, mixed in), an AAC encoder, and a
 * MediaMuxer that writes the file.
 *
 * Audio needs the RECORD_AUDIO permission. Without it the video is recorded with no sound.
 */
class RecordingEngine(
    private val context: Context,
    private val projection: MediaProjection,
    fd: FileDescriptor,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val withMic: Boolean,
    private val onFailure: () -> Unit,
) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val muxerLock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var videoReady = false
    private var audioReady = false
    private var audioEnabled = false
    private var muxerStarted = false
    private var wroteSamples = false
    private var videoBaseUs = -1L

    @Volatile private var running = false

    private var videoEncoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoThread: Thread? = null

    private var playbackRecord: AudioRecord? = null
    private var micRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    private var audioThread: Thread? = null

    /** Starts the encoders and the capture. Throws if the video cannot be started. */
    fun start() {
        audioEnabled = setupAudio()
        val surface = setupVideo()
        inputSurface = surface
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        running = true
        videoThread = Thread({ videoLoop() }, "video-encoder").also { it.start() }
        if (audioEnabled) audioThread = Thread({ audioLoop() }, "audio-capture").also { it.start() }
    }

    /** Stops the capture and finishes the file. Returns true if the file has video or audio in it. */
    fun stop(): Boolean {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        try {
            videoEncoder?.signalEndOfInputStream()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not end the video input", e)
        }
        videoThread?.join(JOIN_MS)
        audioThread?.join(JOIN_MS)
        val ok = synchronized(muxerLock) {
            var finished = false
            if (muxerStarted) {
                try {
                    muxer.stop()
                    finished = wroteSamples
                } catch (e: RuntimeException) {
                    Log.w(TAG, "The file could not be finished", e)
                }
                muxerStarted = false
            }
            finished
        }
        releaseAll()
        return ok
    }

    private fun setupVideo(): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        videoEncoder = codec
        return surface
    }

    private fun setupAudio(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        playbackRecord = createPlaybackRecord()
        micRecord = if (withMic) createMicRecord() else null
        if (playbackRecord == null && micRecord == null) return false

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioEncoder = codec
        return true
    }

    /** Sound that other apps play. Apps can refuse capture; those apps are silent in the file. */
    private fun createPlaybackRecord(): AudioRecord? = try {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(capture)
            .setAudioFormat(format)
            .setBufferSizeInBytes(BUFFER_BYTES)
            .build()
        if (record.state == AudioRecord.STATE_INITIALIZED) record else {
            record.release()
            null
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "App audio is not available", e)
        null
    }

    private fun createMicRecord(): AudioRecord? = try {
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, BUFFER_BYTES
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) record else {
            record.release()
            null
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "The microphone is not available", e)
        null
    }

    private fun videoLoop() {
        val codec = videoEncoder ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (!drain(codec, video = true, timeoutUs = TIMEOUT_US, info = info)) {
                // keep draining until the end of the stream
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Video encoding failed", e)
            if (running) onFailure()
        }
    }

    private fun audioLoop() {
        val codec = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        val pcm = ShortArray(CHUNK_FRAMES * CHANNELS)
        val mic = ShortArray(CHUNK_FRAMES)
        val playback = playbackRecord
        val micRec = micRecord
        var framesQueued = 0L
        try {
            playback?.startRecording()
            micRec?.startRecording()
            while (running) {
                var count = 0
                if (playback != null) {
                    // Blocks until a chunk of app audio is ready, which paces the loop in real time.
                    count = playback.read(pcm, 0, pcm.size)
                    if (count < 0) break
                    if (count == 0) continue
                    if (micRec != null) {
                        val micFrames = micRec.read(mic, 0, count / CHANNELS).coerceAtLeast(0)
                        mixMic(pcm, count, mic, micFrames)
                    }
                } else if (micRec != null) {
                    val micFrames = micRec.read(mic, 0, CHUNK_FRAMES)
                    if (micFrames < 0) break
                    if (micFrames == 0) continue
                    // Microphone only: copy the mono sound to both channels.
                    for (i in 0 until micFrames) {
                        pcm[2 * i] = mic[i]
                        pcm[2 * i + 1] = mic[i]
                    }
                    count = micFrames * CHANNELS
                } else {
                    break
                }
                queueAudio(codec, pcm, count, framesQueued * 1_000_000L / SAMPLE_RATE, endOfStream = false)
                framesQueued += count / CHANNELS
                drain(codec, video = false, timeoutUs = 0L, info = info)
            }
            queueAudio(codec, pcm, 0, framesQueued * 1_000_000L / SAMPLE_RATE, endOfStream = true)
            while (!drain(codec, video = false, timeoutUs = TIMEOUT_US, info = info)) {
                // keep draining until the end of the stream
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Audio capture failed", e)
            if (running) onFailure()
        }
    }

    /** Adds the microphone to the app audio, both stereo channels, clamped so loud parts don't wrap. */
    private fun mixMic(pcm: ShortArray, count: Int, mic: ShortArray, micFrames: Int) {
        for (i in 0 until minOf(micFrames, count / CHANNELS)) {
            val m = mic[i].toInt()
            pcm[2 * i] = (pcm[2 * i] + m).coerceIn(-32768, 32767).toShort()
            pcm[2 * i + 1] = (pcm[2 * i + 1] + m).coerceIn(-32768, 32767).toShort()
        }
    }

    private fun queueAudio(codec: MediaCodec, pcm: ShortArray, count: Int, ptsUs: Long, endOfStream: Boolean) {
        var index = codec.dequeueInputBuffer(TIMEOUT_US)
        while (index < 0) index = codec.dequeueInputBuffer(TIMEOUT_US)
        val buf = codec.getInputBuffer(index) ?: return
        buf.clear()
        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, 0, count)
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        codec.queueInputBuffer(index, 0, count * 2, ptsUs, flags)
    }

    /**
     * Writes the encoded output that is ready. Returns true once the end-of-stream flag has been written.
     * With [timeoutUs] 0 it returns at once when nothing is ready.
     */
    private fun drain(codec: MediaCodec, video: Boolean, timeoutUs: Long, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onTrackReady(video, codec.outputFormat)
                index >= 0 -> {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (video) {
                            // Video timestamps start at zero with the first frame, like the audio.
                            if (videoBaseUs < 0) videoBaseUs = info.presentationTimeUs
                            info.presentationTimeUs -= videoBaseUs
                        }
                        writeSample(if (video) videoTrack else audioTrack, buf, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
            }
        }
    }

    /** Adds a track to the file. The file starts once the video track and, if there is sound, the audio track are known. */
    private fun onTrackReady(video: Boolean, format: MediaFormat) {
        synchronized(muxerLock) {
            if (video) {
                videoTrack = muxer.addTrack(format)
                videoReady = true
            } else {
                audioTrack = muxer.addTrack(format)
                audioReady = true
            }
            if (!muxerStarted && videoReady && (!audioEnabled || audioReady)) {
                muxer.start()
                muxerStarted = true
            }
        }
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(muxerLock) {
            if (!muxerStarted) return
            muxer.writeSampleData(track, buf, info)
            wroteSamples = true
        }
    }

    private fun releaseAll() {
        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        runCatching { inputSurface?.release() }
        runCatching { audioEncoder?.stop() }
        runCatching { audioEncoder?.release() }
        runCatching { playbackRecord?.stop() }
        runCatching { playbackRecord?.release() }
        runCatching { micRecord?.stop() }
        runCatching { micRecord?.release() }
        runCatching { muxer.release() }
    }

    /** About 4 bits per pixel per frame at 30 fps, clamped to a sane range. */
    private fun bitRateFor(width: Int, height: Int): Int =
        (width.toLong() * height * 4).coerceIn(2_000_000L, 20_000_000L).toInt()

    private companion object {
        const val TAG = "RecordingEngine"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 1024
        const val BUFFER_BYTES = 65_536
        const val AUDIO_BIT_RATE = 128_000
        const val FRAME_RATE = 30
        const val TIMEOUT_US = 10_000L
        const val JOIN_MS = 3_000L
    }
}
