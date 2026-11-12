package com.music.bitchord.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.music.bitchord.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shows the line being sung in Xiaomi's Super Island ("HyperIsland").
 *
 * The island has no media-lyrics API of its own. What it does have is a Focus
 * notification: an ordinary notification carrying a JSON string under
 * `miui.focus.param`, which SystemUI draws as an island when the device and
 * the app's Focus permission allow it. This class posts exactly one such
 * notification, rewrites it as the lyric line changes, and cancels it when
 * there is nothing to show — playback paused, lyrics off, no lyrics found.
 *
 * It is a second notification beside the media one, not a change to it, so
 * Media3's own notification handling is never touched.
 *
 * Everything here is a no-op off HyperOS. See [deviceSupportsIsland].
 *
 * Reference: Xiaomi HyperOS developer docs, "小米超级岛 / 开发指南".
 */
internal class HyperIslandLyrics(
    private val context: Context,
    private val contentIntent: () -> PendingIntent,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val supported = deviceSupportsIsland(context)

    /** What is on the island now, so an unchanged line is not re-posted. */
    private var shownKey: String? = null

    /**
     * Whether the user has left Focus notifications on for BitChord. Asking is
     * a blocking provider call, so it is answered off the main thread and
     * remembered; until the first answer arrives it reads as "no".
     */
    @Volatile private var focusAllowed = false
    @Volatile private var focusCheckedAt = 0L

    /**
     * Puts [line] on the island, under the song it belongs to. Cheap to call
     * on every lyric tick: it returns at once if nothing would change.
     */
    fun show(title: String, artist: String, line: String) {
        if (!supported) return
        refreshFocusPermission()
        if (!focusAllowed) return

        val text = tidy(line)
        val subtitle = if (artist.isBlank()) title else "$title · $artist"
        val key = "$text\u0000$subtitle"
        if (key == shownKey) return
        shownKey = key

        runCatching {
            ensureChannel()
            manager.notify(NOTIFICATION_ID, build(text, subtitle))
        }.onFailure { shownKey = null }
    }

    /** Takes the lyric off the island. Safe to call when nothing is showing. */
    fun clear() {
        if (!supported || shownKey == null) return
        shownKey = null
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun build(line: String, subtitle: String): Notification {
        val pics = Bundle().apply {
            putParcelable(PIC_KEY, Icon.createWithResource(context, R.mipmap.ic_launcher))
        }
        val extras = Bundle().apply { putBundle("miui.focus.pics", pics) }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(line)
            .setContentText(subtitle)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addExtras(extras)
            .build()

        notification.extras.putString("miui.focus.param", islandJson(line, subtitle))
        return notification
    }

    /**
     * Built with [JSONObject] rather than string templates: a lyric can hold
     * quotes, backslashes and line breaks, and any of them would break a
     * hand-assembled JSON string.
     */
    private fun islandJson(line: String, subtitle: String): String {
        val pic = JSONObject().put("type", 1).put("pic", PIC_KEY)

        val textInfo = JSONObject()
            .put("title", line)
            .put("content", subtitle)
            .put("useHighLight", false)

        val bigIsland = JSONObject().put(
            "imageTextInfoLeft",
            JSONObject().put("type", 1).put("picInfo", pic).put("textInfo", textInfo),
        )
        val smallIsland = JSONObject().put("picInfo", pic)

        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", bigIsland)
            .put("smallIslandArea", smallIsland)

        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "bitchord_lyrics")
            // A new line should change the island quietly, not pop it open.
            .put("islandFirstFloat", false)
            .put("enableFloat", false)
            // Rewritten in place under the same id as the lines go by.
            .put("updatable", true)
            .put("ticker", line)
            .put("tickerPic", PIC_KEY)
            .put("aodTitle", line)
            .put("param_island", island)
            .put(
                "baseInfo",
                JSONObject().put("title", line).put("content", subtitle).put("type", 2),
            )

        return JSONObject().put("param_v2", param).toString()
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.hyper_island_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                // Lines change every few seconds; none of them may make a sound.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    private fun refreshFocusPermission() {
        val now = System.currentTimeMillis()
        if (now - focusCheckedAt < FOCUS_RECHECK_MS) return
        focusCheckedAt = now
        ioScope.launch { focusAllowed = hasFocusPermission(context) }
    }

    /** One line, trimmed to what an island can sensibly hold. */
    private fun tidy(line: String): String {
        val flat = line.replace(Regex("\\s+"), " ").trim().ifEmpty { "♪" }
        return if (flat.length <= MAX_LINE_CHARS) flat else flat.take(MAX_LINE_CHARS - 1).trimEnd() + "…"
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4C59 // "LY"
        private const val CHANNEL_ID = "bitchord_hyper_island_lyrics"
        private const val PIC_KEY = "miui.focus.pic_lyric"
        private const val MAX_LINE_CHARS = 80
        private const val FOCUS_RECHECK_MS = 30_000L

        /**
         * Whether this device draws Focus notifications as a Super Island.
         *
         * Xiaomi's documented probe: `notification_focus_protocol` is 1 on
         * HyperOS 1, 2 on HyperOS 2 and 3 on HyperOS 3, where the island
         * arrived. `>= 3` rather than `== 3` so a later release, HyperOS 4
         * included, keeps working if it reports a higher number.
         * Verify the value on a real HyperOS 4 device.
         */
        fun deviceSupportsIsland(context: Context): Boolean = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) >= 3
        }.getOrDefault(false)

        /**
         * Whether the user has Focus notifications allowed for this app, asked
         * of SystemUI the way Xiaomi documents it. Blocking: call off the main
         * thread.
         */
        private fun hasFocusPermission(context: Context): Boolean = runCatching {
            val result = context.contentResolver.call(
                Uri.parse("content://miui.statusbar.notification.public"),
                "canShowFocus",
                null,
                Bundle().apply { putString("package", context.packageName) },
            )
            result?.getBoolean("canShowFocus", false) == true
        }.getOrDefault(false)
    }
}
