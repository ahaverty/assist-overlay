package com.ahaverty.assist

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "AssistVoice"

/**
 * Speaks one reply: text arrives in pieces while it streams, each cleaned piece (links read as their
 * name) becomes a TTS clip from HA, and the clips play back to back. [onFinished] fires once everything
 * queued has played and [end] was called. Any failure only skips audio; the text reply is unaffected.
 */
class ReplyVoice(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ha: Ha,
    private val onStarted: () -> Unit,
    private val onFinished: () -> Unit,
) {
    private val chunker = SentenceChunker()
    private val pieces = Channel<String>(Channel.UNLIMITED)
    private var player: ExoPlayer? = null
    private var ended = false
    private var fetching = 0
    private var stopped = false
    private var finishedSent = false
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    private val worker: Job = scope.launch {
        for (piece in pieces) {
            val text = speakable(piece)
            if (text.isBlank()) continue
            fetching++
            try {
                val url = ha.ttsUrl(text)
                if (!stopped) enqueue(url)
            } catch (e: Exception) {
                Log.w(TAG, "TTS failed for a piece", e)
            } finally {
                fetching--
            }
            checkDone()
        }
    }

    fun add(delta: String) {
        if (!stopped) chunker.add(delta).forEach { pieces.trySend(it) }
    }

    /** The reply text is complete. */
    fun end() {
        if (stopped) return
        chunker.flush()?.let { pieces.trySend(it) }
        ended = true
        pieces.close()
        scope.launch {
            worker.join()
            checkDone()
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        pieces.close()
        worker.cancel()
        release()
    }

    private fun enqueue(url: String) {
        val p = player ?: ExoPlayer.Builder(context).build().also { p ->
            // ExoPlayer only manages focus itself for media/game usage, so ducking is done by hand.
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ASSISTANT).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) checkDone()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Playback failed", error)
                    // Skip the broken clip and carry on with the rest.
                    if (p.hasNextMediaItem()) {
                        p.seekToNextMediaItem()
                        p.prepare()
                        p.play()
                    } else {
                        p.clearMediaItems()
                        checkDone()
                    }
                }
            })
            audioManager.requestAudioFocus(focus)
            player = p
            onStarted()
        }
        val wasEnded = p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE
        p.addMediaItem(MediaItem.fromUri(url))
        if (wasEnded) {
            if (p.mediaItemCount > 1 && p.playbackState == Player.STATE_ENDED) p.seekTo(p.mediaItemCount - 1, 0)
            p.prepare()
        }
        p.play()
    }

    private fun checkDone() {
        if (finishedSent || stopped || !ended || fetching > 0 || !worker.isCompleted) return
        val p = player
        if (p != null && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE && p.mediaItemCount > 0) return
        finishedSent = true
        release()
        onFinished()
    }

    private fun release() {
        player?.let {
            player = null
            it.stop()
            it.release()
            audioManager.abandonAudioFocusRequest(focus)
        }
    }
}
