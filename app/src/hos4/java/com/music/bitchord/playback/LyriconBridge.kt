package com.music.bitchord.playback

import android.content.Context
import android.os.Build
import android.util.Log
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricAlignment
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song as LyriconSong
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Sends the song, its synced lyrics and the playback position to Lyricon, the
 * system-lyrics service behind HyperLyrics Enhanced, which draws them in the
 * Super Island with the island's own width limit lifted.
 *
 * Only the `hos4` build contains this. The other flavors carry a do-nothing
 * [LyriconBridge] with the same shape (src/stub), so the code that calls it is
 * the same in all of them and none of them pulls in the SDK.
 *
 * Needs LSPosed with HyperLyrics Enhanced enabled. Without it nothing connects,
 * [connected] stays false, and [HyperIslandLyrics] keeps doing the work.
 */
object LyriconBridge {
    private const val TAG = "BitChordLyricon"

    /** Lyricon's SDK needs Android 8.1; the hos4 flavor's minSdk is 27 to match. */
    val isAvailable: Boolean = Build.VERSION.SDK_INT >= 27

    private val _connected = MutableStateFlow(false)

    /** Whether Lyricon's central service is there and listening. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var provider: LyriconProvider? = null

    private var song: Song? = null
    private var lines: List<LyricLine>? = null
    private var durationMs = 0L

    private var lastPlaying: Boolean? = null
    private var lastSentPosition = 0L
    private var lastSentAt = 0L

    // Whether register() went through, and when; see refreshLink.
    private var registerOk = false
    private var registeredAt = 0L
    private var linked = false
    private var lastLinkCheckAt = 0L

    /**
     * Called once the service is up. Follows the settings switch from then on:
     * on connects and resends what is playing, off disconnects.
     */
    @Synchronized
    fun attach(context: Context) {
        if (!isAvailable || scope != null) return
        appContext = context.applicationContext
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            AppSettings.lyriconProvider.collect { on -> if (on) connect() else disconnect() }
        }
    }

    /** The track now playing, with its lyrics once they are known. */
    @Synchronized
    fun setTrack(newSong: Song?, newLines: List<LyricLine>?, newDurationMs: Long) {
        song = newSong
        lines = newLines
        durationMs = newDurationMs
        pushSong()
    }

    /** Called on every lyric tick, so it only speaks up when something moved. */
    @Synchronized
    fun tick(isPlaying: Boolean, positionMs: Long) {
        val p = provider ?: return
        refreshLink(p)
        if (!linked) return
        runCatching {
            if (isPlaying != lastPlaying) {
                lastPlaying = isPlaying
                p.player.setPlaybackState(isPlaying)
                sendPosition(p, positionMs)
                return
            }
            // Lyricon runs the clock between updates, so only a drift (a seek,
            // or a stall) is worth a message.
            val now = System.currentTimeMillis()
            val expected = if (isPlaying) lastSentPosition + (now - lastSentAt) else lastSentPosition
            if (abs(positionMs - expected) > DRIFT_MS) sendPosition(p, positionMs)
        }.onFailure { Log.w(TAG, "tick failed", it) }
    }

    @Synchronized
    fun release() {
        scope?.cancel()
        scope = null
        disconnect()
    }

    // --- connection -------------------------------------------------------

    @Synchronized
    private fun connect() {
        if (provider != null) return
        val context = appContext ?: return
        runCatching {
            val p = LyriconFactory.createProvider(
                context = context,
                logo = ProviderLogo.fromDrawable(context, R.drawable.ic_notification_logo),
            )
            provider = p
            // Whether register() says yes or no is read as a Boolean when it is
            // one; the SDK's documentation is not clear that it is.
            registerOk = (p.register() as Any?) as? Boolean ?: true
            registeredAt = System.currentTimeMillis()
            lastLinkCheckAt = 0L
            Log.i(TAG, "register() accepted = $registerOk")
        }.onFailure { Log.w(TAG, "could not start the Lyricon provider", it) }
    }

    @Synchronized
    private fun disconnect() {
        _connected.value = false
        linked = false
        registerOk = false
        lastPlaying = null
        provider?.let { runCatching { it.destroy() } }
        provider = null
    }

    /**
     * Works out whether Lyricon is on the other end, about once a second, and
     * sends the current song the moment it is.
     *
     * The SDK documents a connection listener, but its DSL form did not
     * compile against the version this builds with. So the state is read
     * from the SDK's own connection status by reflection instead, which cannot
     * break the build. If that is not readable, a provider that registered is
     * taken as connected after a short grace period.
     */
    private fun refreshLink(p: LyriconProvider) {
        val now = System.currentTimeMillis()
        if (now - lastLinkCheckAt < LINK_CHECK_MS) return
        lastLinkCheckAt = now

        val reported = readConnected(p)
        val nowLinked = reported ?: (registerOk && now - registeredAt > GRACE_MS)
        if (nowLinked == linked) return
        linked = nowLinked
        _connected.value = nowLinked
        Log.i(TAG, if (nowLinked) "linked to Lyricon (status read: $reported)" else "lost the link to Lyricon")
        if (nowLinked) {
            lastPlaying = null
            pushSong()
        }
    }

    /** The SDK's own connection status, or null where it cannot be read. */
    private fun readConnected(p: LyriconProvider): Boolean? = runCatching {
        val service = p.javaClass.getMethod("getService").invoke(p) ?: return null
        val status = service.javaClass.getMethod("getConnectionStatus").invoke(service) ?: return null
        status.javaClass.getMethod("isConnected").invoke(status) as? Boolean
    }.getOrNull()

    // --- what goes over --------------------------------------------------------

    private fun pushSong() {
        val p = provider ?: return
        if (!linked) return
        runCatching {
            val s = song
            if (s == null) {
                p.player.setSong(null)
                return
            }
            val rich = lines?.let { toRich(it, durationMs) }
            val out = if (rich.isNullOrEmpty()) {
                LyriconSong(id = s.videoId, name = s.title, artist = s.artist, duration = durationMs)
            } else {
                LyriconSong(
                    id = s.videoId,
                    name = s.title,
                    artist = s.artist,
                    duration = durationMs,
                    lyrics = rich,
                )
            }
            p.player.setSong(out)
            lastPlaying = null
        }.onFailure { Log.w(TAG, "setSong failed", it) }
    }

    private fun sendPosition(p: LyriconProvider, positionMs: Long) {
        p.player.setPosition(positionMs)
        lastSentPosition = positionMs
        lastSentAt = System.currentTimeMillis()
    }

    private fun toRich(source: List<LyricLine>, durationMs: Long): List<RichLyricLine> {
        val out = ArrayList<RichLyricLine>(source.size)
        source.forEachIndexed { index, line ->
            // A gap is an instrumental stretch. It is left out, and the line
            // before it ends where the gap starts, so the island clears.
            if (line.isGap || line.text.isBlank()) return@forEachIndexed
            val begin = line.timeMs
            val nextStart = source.getOrNull(index + 1)?.timeMs
            val end = when {
                line.hasKnownEnd -> line.endMs
                nextStart != null -> nextStart
                durationMs > 0 -> durationMs
                else -> begin + LAST_LINE_FALLBACK_MS
            }.coerceAtLeast(begin + 1)

            val back = line.background?.text?.takeIf { it.isNotBlank() }
            val words = wordsOf(line)
            out += when {
                words != null && back != null -> RichLyricLine(
                    begin = begin, end = end, text = line.text, words = words, secondary = back,
                    isAlignedRight = line.alignment == LyricAlignment.End,
                )
                words != null -> RichLyricLine(
                    begin = begin, end = end, text = line.text, words = words,
                    isAlignedRight = line.alignment == LyricAlignment.End,
                )
                back != null -> RichLyricLine(
                    begin = begin, end = end, text = line.text, secondary = back,
                    isAlignedRight = line.alignment == LyricAlignment.End,
                )
                else -> RichLyricLine(
                    begin = begin, end = end, text = line.text,
                    isAlignedRight = line.alignment == LyricAlignment.End,
                )
            }
        }
        return out
    }

    /**
     * BitChord keeps a word without the space before it; Lyricon joins words
     * as given, so each one is cut out of the line with its leading space.
     */
    private fun wordsOf(line: LyricLine): List<LyriconWord>? {
        if (line.words.isEmpty()) return null
        val spans = line.wordSpans
        var cursor = 0
        return line.words.mapIndexed { i, word ->
            val span = spans[i]
            val from = minOf(cursor, span.first).coerceAtLeast(0)
            val to = (span.last + 1).coerceAtMost(line.text.length)
            val text = if (to > from) line.text.substring(from, to) else word.text
            cursor = to
            LyriconWord(text = text, begin = word.startMs, end = word.endMs)
        }
    }

    private const val DRIFT_MS = 700L
    private const val LINK_CHECK_MS = 1_000L
    private const val GRACE_MS = 2_500L
    private const val LAST_LINE_FALLBACK_MS = 8_000L
}
