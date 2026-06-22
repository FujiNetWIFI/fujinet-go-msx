package online.fujinet.go.msx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import online.fujinet.go.msx.core.EmulatorNative
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * Streams openMSX's audio to an AudioTrack as a "game" audio source.
 *
 * The core pushes interleaved stereo 44100 Hz samples into a native
 * ring each frame; a high-priority feeder thread pulls *full* blocks via the
 * blocking [EmulatorNative.nativeFillAudio] and writes them with
 * `WRITE_BLOCKING`, so AudioTrack is always handed a complete, real-time-paced
 * buffer — no partial/choppy writes, and a producer hiccup degrades to a brief
 * silence pad (in the native fill) rather than a stutter. The track uses the
 * low-latency fast-mixer path and the game usage/content types.
 */
class AudioOutput {
    private companion object {
        const val SAMPLE_RATE = 44100
        const val BYTES_PER_FRAME = 4 // stereo * 16-bit
        const val TAG = "FujiMsxAudio"
    }

    @Volatile private var running = false
    private var feeder: Thread? = null
    private var track: AudioTrack? = null

    fun start() {
        if (running) return

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).let { if (it > 0) it else (SAMPLE_RATE / 20) * BYTES_PER_FRAME }

        // ~50ms track buffer: enough to ride out frame-pacing jitter without
        // adding noticeable latency.
        val trackBufferBytes = max(minBuf, (SAMPLE_RATE / 20) * BYTES_PER_FRAME)
        // Transfer one emulator frame's worth per pull (~16ms), aligned to the
        // core's per-frame audio batch and bounded by half the track buffer.
        val transferFrames = max(
            SAMPLE_RATE / 100,
            min(trackBufferBytes / BYTES_PER_FRAME / 2, SAMPLE_RATE / 60),
        )
        val transferSamples = transferFrames * 2

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(trackBufferBytes)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        track = newTrack

        Log.i(TAG, "start trackBuffer=$trackBufferBytes transferSamples=$transferSamples")
        EmulatorNative.nativeAudioSetActive(true)
        running = true
        newTrack.play()

        feeder = thread(name = "msx-audio") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(transferSamples)
            while (running) {
                try {
                    EmulatorNative.nativeFillAudio(buffer) // blocks for a full block
                    if (!running) break
                    newTrack.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                } catch (t: Throwable) {
                    Log.e(TAG, "audio feeder error", t)
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        EmulatorNative.nativeAudioSetActive(false) // unblock a waiting fill
        feeder?.join(500)
        feeder = null
        track?.run {
            runCatching { pause(); flush(); stop() }
            release()
        }
        track = null
    }
}
