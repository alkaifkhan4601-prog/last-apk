package com.example.autotype

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock

/**
 * Foreground service that does the actual auto-typing, so typing keeps working
 * even when the app is in the background or the screen is off.
 */
class TypingService : Service() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_KEY_DELAY_MS = "key_delay_ms"
        const val EXTRA_REPEAT = "repeat"              // 0 = repeat until stopped
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        const val EXTRA_START_DELAY_SEC = "start_delay_sec"
        const val EXTRA_BEEP = "beep"                  // play a sound when a form is filled
        const val EXTRA_BATCH = "batch"                // fill every text in jobTexts, one after another
        const val ACTION_STOP = "com.example.autotype.STOP"

        private const val CHANNEL_ID = "fastfill"
        private const val NOTIFICATION_ID = 1

        /** In batch mode: one ready-to-type text per form (fields joined by Tab + the ending keys). */
        @Volatile
        var jobTexts: List<String> = emptyList()

        @Volatile
        var running = false
            private set

        /** SystemClock.elapsedRealtime() when the current form started typing; 0 = not typing right now. */
        @Volatile
        var roundStartedAt = 0L
            private set

        /** How long the last form took to fill, in milliseconds. */
        @Volatile
        var lastRoundMs = 0L
            private set

        var statusCallback: ((String) -> Unit)? = null

        /** Called in batch mode with (index of the form about to be filled, total forms). */
        var progressCallback: ((Int, Int) -> Unit)? = null
    }

    @Volatile
    private var cancelled = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (running) stopTyping() else stopSelf()
            return START_NOT_STICKY
        }

        // startForegroundService() requires startForeground() to be called promptly.
        startForeground(NOTIFICATION_ID, buildNotification("Typing in progress…"))
        if (intent == null || running) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }

        val batch = intent.getBooleanExtra(EXTRA_BATCH, false)
        val texts = if (batch) jobTexts else listOf(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        val keyDelay = intent.getLongExtra(EXTRA_KEY_DELAY_MS, 50L)
        val repeat = intent.getIntExtra(EXTRA_REPEAT, 1)
        val intervalSec = intent.getLongExtra(EXTRA_INTERVAL_SEC, 0L)
        val startDelaySec = intent.getLongExtra(EXTRA_START_DELAY_SEC, 0L)
        val beep = intent.getBooleanExtra(EXTRA_BEEP, true)

        running = true
        cancelled = false
        roundStartedAt = 0L
        lastRoundMs = 0L

        worker = Thread {
            try {
                if (batch) {
                    runBatch(texts, keyDelay, startDelaySec, beep)
                } else {
                    runRepeat(texts.firstOrNull().orEmpty(), keyDelay, repeat, intervalSec, startDelaySec, beep)
                }
            } finally {
                // If Stop was pressed (or the connection dropped) mid-form, freeze the timer
                // at the time reached so far.
                if (roundStartedAt != 0L) {
                    lastRoundMs = SystemClock.elapsedRealtime() - roundStartedAt
                    roundStartedAt = 0L
                }
            }
            running = false
            statusCallback?.invoke("Typing done")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.also { it.start() }

        return START_NOT_STICKY
    }

    /**
     * Batch: for every form -> wait, type it (the text already ends with the Save keys), beep.
     * The wait ("Wait before starting") is repeated before EVERY form, so the next form
     * can open and you can get ready before typing begins.
     */
    private fun runBatch(texts: List<String>, keyDelay: Long, waitSec: Long, beep: Boolean) {
        for ((i, text) in texts.withIndex()) {
            if (cancelled) return
            progressCallback?.invoke(i, texts.size)
            if (!sleepSeconds(waitSec, "Form ${i + 1} of ${texts.size} starts in")) return

            statusCallback?.invoke("Typing form ${i + 1} of ${texts.size}…")
            if (!typeOneForm(text, keyDelay)) return

            // One beep per finished form; two beeps after the very last one.
            if (beep) playBeep(double = i == texts.lastIndex)
        }
    }

    /** Single text, optionally repeated. */
    private fun runRepeat(
        text: String,
        keyDelay: Long,
        repeat: Int,
        intervalSec: Long,
        startDelaySec: Long,
        beep: Boolean
    ) {
        // Give the user time to click into the target text field on the PC/TV.
        if (!sleepSeconds(startDelaySec, "Starting in")) return

        var round = 0
        while (!cancelled && (repeat == 0 || round < repeat)) {
            round++
            statusCallback?.invoke("Typing… (round $round)")
            if (!typeOneForm(text, keyDelay)) return

            val moreToGo = repeat == 0 || round < repeat
            if (beep) playBeep(double = !moreToGo)

            if (moreToGo && !sleepSeconds(intervalSec, "Next round in")) return
        }
    }

    /** Types one form and runs the top-right timer. Returns false if stopped or disconnected. */
    private fun typeOneForm(text: String, keyDelay: Long): Boolean {
        // The timer starts here, when the first key of this form is about to be typed.
        lastRoundMs = 0L
        roundStartedAt = SystemClock.elapsedRealtime()

        for (c in text) {
            if (cancelled) return false
            if (!HidManager.typeChar(c, keyDelay)) {
                statusCallback?.invoke("Not connected to the device, typing stopped")
                return false
            }
        }

        // Form finished: stop the timer and keep its final value on screen.
        lastRoundMs = SystemClock.elapsedRealtime() - roundStartedAt
        roundStartedAt = 0L
        return true
    }

    /** Short "pip" on the alarm volume, so it is heard even if media volume is low. */
    private fun playBeep(double: Boolean) {
        var tone: ToneGenerator? = null
        try {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
            Thread.sleep(260)
            if (double) {
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                Thread.sleep(260)
            }
        } catch (e: Exception) {
            // No sound is not worth stopping the typing for.
        } finally {
            tone?.release()
        }
    }

    /** Sleeps in 1-second steps so Stop reacts quickly. Returns false if cancelled. */
    private fun sleepSeconds(seconds: Long, label: String): Boolean {
        var left = seconds
        while (left > 0) {
            if (cancelled) return false
            statusCallback?.invoke("$label $left s…")
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return false
            }
            left--
        }
        return !cancelled
    }

    private fun stopTyping() {
        // The worker checks this flag between keys and during waits (no interrupt,
        // so a key press is never cut off halfway).
        cancelled = true
    }

    override fun onDestroy() {
        cancelled = true
        running = false
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fast Fill", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, TypingService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Fast Fill")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(0, "Stop", stopPending).build()
            )
            .build()
    }
}
