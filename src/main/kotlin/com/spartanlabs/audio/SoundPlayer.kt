package com.spartanlabs.audio

import javazoom.jl.player.Player
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.util.concurrent.Executors

private val log: Logger = LoggerFactory.getLogger(SoundPlayer::class.java)

/** Classpath directory every sound name is looked up under. */
private const val SOUND_DIR = "sounds"

/**
 * Plays short MP3 sound effects loaded off the classpath. A name is a bare
 * file name resolved against `/[SOUND_DIR]` - `"beep-07a.mp3"` plays
 * `/sounds/beep-07a.mp3`, packaged from `src/main/resources/sounds/`.
 *
 * Each [play] call runs on a background daemon thread: JLayer's
 * [Player.play] decodes and streams to the system mixer synchronously, and
 * the caller (a mouse handler, the render loop) must not block on it. A
 * missing resource or a decode failure is logged, never thrown - a silent
 * click beats a crash.
 *
 * Call [close] on shutdown to stop accepting new playbacks.
 */
class SoundPlayer {

    private val playbackThreads = Executors.newCachedThreadPool { task ->
        Thread(task, "sound-playback").apply { isDaemon = true }
    }

    /** Plays `/[SOUND_DIR]/[name]` once, returning immediately. */
    fun play(name: String) {
        val submitted = runCatching {
            playbackThreads.execute { playBlocking(name) }
        }
        submitted.onFailure { cause ->
            log.warn("Could not schedule sound '{}': {}", name, cause.message)
        }
    }

    /** Stops accepting new playbacks; in-flight ones run to completion. */
    fun close() {
        playbackThreads.shutdown()
    }

    private fun playBlocking(name: String) {
        runCatching {
            val resource = SoundPlayer::class.java.getResourceAsStream("/$SOUND_DIR/$name")
                ?: throw RuntimeException("no classpath resource '/$SOUND_DIR/$name'")
            resource.use { stream ->
                val player = Player(BufferedInputStream(stream))
                try {
                    player.play()
                } finally {
                    player.close()
                }
            }
        }.onFailure { cause -> log.warn("Could not play sound '{}': {}", name, cause.message) }
    }
}
