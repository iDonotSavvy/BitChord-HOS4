package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The do-nothing twin of the hos4 flavor's LyriconBridge, so the dev and prod
 * builds compile the same calling code without carrying Lyricon's SDK.
 */
@Suppress("UNUSED_PARAMETER")
object LyriconBridge {
    const val isAvailable: Boolean = false
    val connected: StateFlow<Boolean> = MutableStateFlow(false)

    fun attach(context: Context) {}
    fun setTrack(newSong: Song?, newLines: List<LyricLine>?, newDurationMs: Long) {}
    fun tick(isPlaying: Boolean, positionMs: Long) {}
    fun release() {}
}
