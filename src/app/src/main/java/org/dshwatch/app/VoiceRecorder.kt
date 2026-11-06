package org.dshwatch.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceRecorder {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    var error: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(onLimit: () -> Unit) {
        val size = maxOf(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            throw IllegalStateException("手表麦克风暂时不可用")
        }
        recorder = input
        pcm.reset()
        error = null
        running = true
        try { input.startRecording() } catch (e: Exception) { running = false; input.release(); recorder = null; throw e }
        worker = Thread {
            val buffer = ByteArray(size)
            while (running && pcm.size() < 16000 * 2 * 30) {
                val count = input.read(buffer, 0, buffer.size)
                if (count <= 0) { error = "录音中断，请重试"; break }
                pcm.write(buffer, 0, count)
            }
            if (running) onLimit()
        }.also { it.start() }
    }

    fun stop(): ByteArray {
        running = false
        runCatching { recorder?.stop() }
        worker?.join(1500)
        recorder?.release()
        recorder = null
        worker = null
        val data = pcm.toByteArray()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(data.size)
        return header.array() + data
    }
}
