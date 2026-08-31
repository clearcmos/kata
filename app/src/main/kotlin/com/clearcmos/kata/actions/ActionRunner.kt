package com.clearcmos.kata.actions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import androidx.core.net.toUri
import com.clearcmos.kata.R
import com.clearcmos.kata.engine.DeviceState
import com.clearcmos.kata.engine.Notifications
import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.model.Step
import com.clearcmos.kata.triggers.KataAccessibilityService
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Raised when an action cannot run. The message is surfaced verbatim in the run log. */
class ActionError(message: String) : RuntimeException(message)

/**
 * Executes one action and returns a one-line description of what it did.
 *
 * Every method runs on the engine's background thread, never the main thread, so the blocking
 * calls here (HTTP, wait, TTS warm-up) are safe. Failures throw [ActionError] with a message
 * that says which prerequisite is missing, because "action failed" alone sends an authoring
 * agent looking in the wrong place.
 */
class ActionRunner(
    private val context: Context,
    private val store: Store,
    private val device: DeviceState = DeviceState(context)
) {
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)

    fun execute(step: Step): String {
        val args = step.args
        return when (step.type) {
            "notify" ->
                notify(
                    args.string("title"),
                    args.optString("text").orEmpty(),
                    args.int("id", DEFAULT_NOTIFICATION_ID)
                )
            "cancel_notification" -> {
                notificationManager().cancel(args.int("id"))
                "cancelled notification ${args.int("id")}"
            }
            "dnd" -> setDnd(args.string("mode"))
            "ringer_mode" -> setRinger(args.string("mode"))
            "volume" -> setVolume(args.string("stream"), args.int("level"))
            "media" -> sendMediaKey(args.string("command"))
            "vibrate" -> vibrate(args.int("ms", 300).toLong())
            "torch" -> setTorch(args.bool("on"))
            "tts" -> speak(args.string("text"))
            "http_request" -> httpRequest(step)
            "launch_app" -> launchApp(args.string("package"))
            "start_activity" -> startActivity(args.string("uri"))
            "broadcast" -> broadcast(step)
            "clipboard" -> setClipboard(args.string("text"))
            "secure_setting" -> writeSetting(SettingScope.SECURE, args.string("key"), args.string("value"))
            "global_setting" -> writeSetting(SettingScope.GLOBAL, args.string("key"), args.string("value"))
            "system_setting" -> writeSetting(SettingScope.SYSTEM, args.string("key"), args.string("value"))
            "wake_screen" -> wakeScreen(args.int("seconds", 5))
            "wait" -> {
                val ms = args.int("ms").coerceIn(0, MAX_WAIT_MS)
                Thread.sleep(ms.toLong())
                "waited ${ms}ms"
            }
            "log" -> args.string("message")
            "ssh" -> ssh(step)
            "global_action" -> globalAction(args.string("action"))
            "tap_ui" -> tapUi(step)
            "set_enabled" -> setEnabled(args.string("id"), args.bool("enabled"))
            else -> throw ActionError("unknown action type '${step.type}'")
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private fun notificationManager(): NotificationManager = context.getSystemService(NotificationManager::class.java)
        ?: throw ActionError("notification service unavailable")

    private fun notify(title: String, text: String, id: Int): String {
        if (!device.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            throw ActionError("POST_NOTIFICATIONS is not granted; open kata and allow notifications")
        }
        val notification =
            Notification
                .Builder(context, Notifications.CHANNEL_AUTOMATION)
                .setSmallIcon(R.drawable.ic_kata)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        notificationManager().notify(id, notification)
        return "posted notification $id: $title"
    }

    private fun setDnd(mode: String): String {
        val manager = notificationManager()
        if (!manager.isNotificationPolicyAccessGranted) {
            throw ActionError(
                "Do Not Disturb access is not granted; enable kata under Settings > Notifications > Do Not Disturb access"
            )
        }
        val filter =
            when (mode.lowercase()) {
                "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
                "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                else -> throw ActionError("unknown dnd mode '$mode'")
            }
        manager.setInterruptionFilter(filter)
        return "dnd set to $mode"
    }

    private fun audioManager(): AudioManager = context.getSystemService(AudioManager::class.java)
        ?: throw ActionError("audio service unavailable")

    private fun setRinger(mode: String): String {
        val value =
            when (mode.lowercase()) {
                "normal" -> AudioManager.RINGER_MODE_NORMAL
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> throw ActionError("unknown ringer mode '$mode'")
            }
        // Dropping to silent counts as changing DND policy, which the platform gates.
        if (value == AudioManager.RINGER_MODE_SILENT && !notificationManager().isNotificationPolicyAccessGranted) {
            throw ActionError(
                "silent needs Do Not Disturb access; enable kata under Settings > Notifications > Do Not Disturb access"
            )
        }
        audioManager().ringerMode = value
        return "ringer set to $mode"
    }

    private fun setVolume(stream: String, level: Int): String {
        val streamType =
            when (stream.lowercase()) {
                "music" -> AudioManager.STREAM_MUSIC
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "call" -> AudioManager.STREAM_VOICE_CALL
                "system" -> AudioManager.STREAM_SYSTEM
                else -> throw ActionError("unknown volume stream '$stream'")
            }
        val manager = audioManager()
        val max = manager.getStreamMaxVolume(streamType)
        val target = (level.coerceIn(0, 100) * max / 100.0).toInt().coerceIn(0, max)
        runCatching { manager.setStreamVolume(streamType, target, 0) }
            .onFailure { throw ActionError("could not set $stream volume: ${it.message}") }
        return "$stream volume set to $level% ($target/$max)"
    }

    private fun sendMediaKey(command: String): String {
        val code =
            when (command.lowercase()) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> throw ActionError("unknown media command '$command'")
            }
        val manager = audioManager()
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "sent media key $command"
    }

    private fun vibrate(ms: Long): String {
        val manager =
            context.getSystemService(VibratorManager::class.java)
                ?: throw ActionError("vibrator unavailable")
        manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return "vibrated ${ms}ms"
    }

    private fun setTorch(on: Boolean): String {
        val manager =
            context.getSystemService(CameraManager::class.java)
                ?: throw ActionError("camera service unavailable")
        val cameraId =
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw ActionError("no camera on this device reports a flash")
        runCatching { manager.setTorchMode(cameraId, on) }
            .onFailure { throw ActionError("torch failed: ${it.message}") }
        return "torch ${if (on) "on" else "off"}"
    }

    private fun speak(text: String): String {
        if (tts == null) {
            tts =
                TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) ttsReady.countDown()
                }
        }
        if (!ttsReady.await(TTS_INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw ActionError("text-to-speech engine did not initialise within ${TTS_INIT_TIMEOUT_SECONDS}s")
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "kata")
        return "spoke ${text.length} characters"
    }

    private fun httpRequest(step: Step): String {
        val args = step.args
        val method = args.optString("method")?.uppercase() ?: "GET"
        val url = args.string("url")
        val timeout = args.int("timeout_ms", 10_000)
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            args.stringMap("headers").forEach { (name, value) -> connection.setRequestProperty(name, value) }
            args.optString("body")?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val preview =
                stream
                    ?.use { it.readBytes().decodeToString() }
                    .orEmpty()
                    .take(RESPONSE_PREVIEW_CHARS)
                    .replace('\n', ' ')
            "$method $url -> $code${if (preview.isBlank()) "" else " $preview"}"
        } catch (e: Exception) {
            throw ActionError("$method $url failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun launchApp(packageName: String): String {
        val intent =
            context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw ActionError("$packageName is not installed, or has no launcher activity")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "launched $packageName"
    }

    private fun startActivity(uri: String): String {
        val intent =
            runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }
                .getOrElse { Intent(Intent.ACTION_VIEW, uri.toUri()) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { throw ActionError("nothing handles $uri: ${it.message}") }
        return "started $uri"
    }

    private fun broadcast(step: Step): String {
        val action = step.args.string("action")
        val intent = Intent(action)
        step.args.stringMap("extras").forEach { (key, value) -> intent.putExtra(key, value) }
        step.args.optString("package")?.let { intent.setPackage(it) }
        context.sendBroadcast(intent)
        return "broadcast $action"
    }

    private fun setClipboard(text: String): String {
        val manager =
            context.getSystemService(ClipboardManager::class.java)
                ?: throw ActionError("clipboard unavailable")
        manager.setPrimaryClip(ClipData.newPlainText("kata", text))
        return "copied ${text.length} characters"
    }

    private enum class SettingScope { SECURE, GLOBAL, SYSTEM }

    private fun writeSetting(scope: SettingScope, key: String, value: String): String {
        if (scope == SettingScope.SYSTEM && !Settings.System.canWrite(context)) {
            throw ActionError(
                "Modify system settings is not granted; enable kata under Settings > Apps > Special access"
            )
        }
        val resolver = context.contentResolver
        try {
            when (scope) {
                SettingScope.SECURE -> Settings.Secure.putString(resolver, key, value)
                SettingScope.GLOBAL -> Settings.Global.putString(resolver, key, value)
                SettingScope.SYSTEM -> Settings.System.putString(resolver, key, value)
            }
        } catch (e: SecurityException) {
            throw ActionError(
                "writing ${scope.name.lowercase()} setting '$key' was refused: ${e.message}. " +
                    "Run: adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
            )
        }
        return "${scope.name.lowercase()} setting $key = $value"
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(seconds: Int): String {
        val manager =
            context.getSystemService(PowerManager::class.java)
                ?: throw ActionError("power service unavailable")
        val lock =
            manager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kata:wake_screen"
            )
        lock.acquire(seconds.coerceIn(1, MAX_WAKE_SECONDS) * 1000L)
        return "woke screen for ${seconds}s"
    }

    private val ssh by lazy { SshClient(context) }

    private fun ssh(step: Step): String {
        val args = step.args
        val host = args.string("host")
        val user = args.string("user")
        val result = ssh.run(
            host = host,
            user = user,
            port = args.int("port", 22),
            command = args.string("command"),
            timeoutMs = args.int("timeout_ms", 8000)
        )
        val output = result.output.replace('\n', ' ').take(SSH_OUTPUT_PREVIEW)
        if (result.exitStatus != 0) {
            throw ActionError("$user@$host: exit ${result.exitStatus}${if (output.isBlank()) "" else " $output"}")
        }
        return "$user@$host: ok${if (output.isBlank()) "" else " $output"}"
    }

    private fun accessibility(): KataAccessibilityService = KataAccessibilityService.instance
        ?: throw ActionError(
            "the accessibility service is not running; enable kata under " +
                "Settings > Accessibility > Installed apps"
        )

    private fun globalAction(action: String): String {
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "dismiss_shade" -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> throw ActionError("unknown global action '$action'")
        }
        if (!accessibility().runGlobalAction(code)) throw ActionError("the system refused global action '$action'")
        return "performed $action"
    }

    private fun tapUi(step: Step): String {
        val args = step.args
        val matcher = KataAccessibilityService.NodeMatcher(
            text = args.optString("text"),
            contentDescription = args.optString("content_description"),
            viewId = args.optString("view_id"),
            exact = args.bool("exact", false)
        )
        val service = accessibility()
        val clicked = service.tap(matcher, args.int("timeout_ms", 3000))
        if (clicked != null) return "tapped \"$clicked\""
        // A tap that finds nothing is the most common failure here, and the useful thing to
        // report is what was actually on screen rather than only what was wanted.
        val visible = service.visibleLabels()
        val sawWhat = if (visible.isEmpty()) "nothing readable on screen" else "saw: " + visible.joinToString(", ")
        throw ActionError("no tappable node matched ${matcher.describe()}; $sawWhat")
    }

    private fun setEnabled(id: String, enabled: Boolean): String {
        if (!store.setEnabled(id, enabled)) throw ActionError("no automation with id '$id'")
        return "${if (enabled) "enabled" else "disabled"} $id"
    }

    private companion object {
        const val DEFAULT_NOTIFICATION_ID = 1000
        const val MAX_WAIT_MS = 30_000
        const val MAX_WAKE_SECONDS = 60
        const val RESPONSE_PREVIEW_CHARS = 200
        const val TTS_INIT_TIMEOUT_SECONDS = 5L
        const val SSH_OUTPUT_PREVIEW = 200
    }
}
