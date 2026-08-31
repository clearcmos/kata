package com.clearcmos.kata.actions

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.net.toUri
import com.clearcmos.kata.R
import com.clearcmos.kata.engine.DeviceState
import com.clearcmos.kata.engine.Notifications
import com.clearcmos.kata.engine.Store
import com.clearcmos.kata.engine.VarStore
import com.clearcmos.kata.model.Step
import com.clearcmos.kata.triggers.KataAccessibilityService
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Raised when an action cannot run. The message is surfaced verbatim in the run log. */
class ActionError(message: String) : RuntimeException(message)

/**
 * What an action did, plus anything it publishes for later steps.
 *
 * [outputs] land in the run's variable scope, so an action can consume what an earlier one
 * produced through ${vars.name}. Without this an automation could only ever be a fixed sequence
 * of independent effects.
 */
data class ActionOutcome(val detail: String, val outputs: Map<String, String> = emptyMap())

/**
 * Executes one action. An interface so [com.clearcmos.kata.engine.Engine]'s sequencing, retry
 * and variable handling can be tested without a device attached.
 */
interface ActionExecutor {
    fun execute(step: Step): ActionOutcome
}

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
    private val persistentVars: VarStore,
    private val device: DeviceState = DeviceState(context)
) : ActionExecutor {
    private var tts: TextToSpeech? = null
    private val ttsReady = CountDownLatch(1)

    override fun execute(step: Step): ActionOutcome = when (val result = dispatch(step)) {
        is ActionOutcome -> result
        else -> ActionOutcome(result.toString())
    }

    /**
     * Returns a plain description, or an [ActionOutcome] when the action publishes variables.
     * Most actions have nothing to publish, so requiring every branch to wrap its string would
     * be noise for no gain.
     */
    private fun dispatch(step: Step): Any {
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
            "var_set" -> varSet(step)
            "text_replace" -> textReplace(step)
            "text_match" -> textMatch(step)
            "datetime_format" -> dateTimeFormat(step)
            "file_read" -> fileRead(step)
            "file_write" -> fileWrite(step, append = false)
            "file_append" -> fileWrite(step, append = true)
            "file_list" -> fileList(step)
            "file_delete" -> fileDelete(step)
            "download" -> download(step)
            "wol" -> wakeOnLan(step)
            "ping" -> ping(step)
            "screenshot" -> screenshot(step)
            "play_sound" -> playSound(args.optString("sound") ?: "notification")
            "clipboard_get" -> clipboardGet(step)
            "sms_send" -> sendSms(args.string("to"), args.string("message"))
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

    // -- variables and text ---------------------------------------------------------------

    private fun varSet(step: Step): ActionOutcome {
        val name = step.args.string("name")
        val value = step.args.string("value")
        // Persisting is handled by the engine's var scope; the outcome carries the run-scoped
        // value either way so a later action sees it without a round trip through storage.
        if (step.args.bool("persist", false)) persistentVars.set(name, value)
        return ActionOutcome("$name = $value", mapOf(name to value))
    }

    private fun textReplace(step: Step): ActionOutcome {
        val text = step.args.string("text")
        val find = step.args.string("find")
        val replace = step.args.string("replace")
        val result =
            if (step.args.bool("regex", false)) {
                runCatching { text.replace(Regex(find), replace) }
                    .getOrElse { throw ActionError("invalid regular expression '$find': ${it.message}") }
            } else {
                text.replace(find, replace)
            }
        val into = step.args.optString("into") ?: "result"
        return ActionOutcome("$into = $result", mapOf(into to result))
    }

    private fun textMatch(step: Step): ActionOutcome {
        val text = step.args.string("text")
        val pattern = step.args.string("pattern")
        val group = step.args.int("group", 1)
        val regex = runCatching { Regex(pattern) }
            .getOrElse { throw ActionError("invalid regular expression '$pattern': ${it.message}") }
        val match = regex.find(text)
            ?: throw ActionError("'$pattern' did not match: ${text.take(TEXT_PREVIEW)}")
        val value = match.groupValues.getOrNull(group)
            ?: throw ActionError("'$pattern' has no capture group $group")
        val into = step.args.optString("into") ?: "match"
        return ActionOutcome("$into = $value", mapOf(into to value))
    }

    private fun dateTimeFormat(step: Step): ActionOutcome {
        val pattern = step.args.string("format")
        val epoch = step.args.optString("epoch_ms")?.toLongOrNull() ?: System.currentTimeMillis()
        val formatted =
            runCatching { SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epoch)) }
                .getOrElse { throw ActionError("invalid date format '$pattern': ${it.message}") }
        val into = step.args.optString("into") ?: "now"
        return ActionOutcome("$into = $formatted", mapOf(into to formatted))
    }

    // -- files ------------------------------------------------------------------------------

    /**
     * Resolves a rule-supplied path inside kata's own storage.
     *
     * Paths are relative and normalised, and anything that climbs out of the base directory is
     * refused. A rule is data pushed from a repo, so it must not be able to name an arbitrary
     * filesystem location.
     */
    private fun resolveFile(path: String): File {
        val base = context.filesDir.resolve("automation").apply { mkdirs() }
        val target = File(base, path).canonicalFile
        if (!target.path.startsWith(base.canonicalFile.path)) {
            throw ActionError("path '$path' escapes kata's storage directory")
        }
        return target
    }

    private fun fileRead(step: Step): ActionOutcome {
        val file = resolveFile(step.args.string("path"))
        if (!file.isFile) throw ActionError("no file at ${step.args.string("path")}")
        val content = file.readText()
        val into = step.args.optString("into") ?: "content"
        return ActionOutcome("read ${content.length} characters", mapOf(into to content))
    }

    private fun fileWrite(step: Step, append: Boolean): String {
        val file = resolveFile(step.args.string("path"))
        file.parentFile?.mkdirs()
        val text = step.args.string("text")
        if (append) file.appendText(text + "\n") else file.writeText(text)
        return "${if (append) "appended to" else "wrote"} ${step.args.string("path")}"
    }

    private fun fileList(step: Step): ActionOutcome {
        val dir = resolveFile(step.args.optString("path") ?: ".")
        val names = (dir.listFiles() ?: emptyArray()).map { it.name }.sorted()
        val into = step.args.optString("into") ?: "files"
        return ActionOutcome("${names.size} entries", mapOf(into to names.joinToString("\n")))
    }

    private fun fileDelete(step: Step): String {
        val file = resolveFile(step.args.string("path"))
        val existed = file.delete()
        return if (existed) "deleted ${step.args.string("path")}" else "${step.args.string("path")} was not there"
    }

    private fun download(step: Step): ActionOutcome {
        val url = step.args.string("url")
        val target = resolveFile(step.args.string("path"))
        target.parentFile?.mkdirs()
        val timeout = step.args.int("timeout_ms", 30_000)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            val code = connection.responseCode
            if (code !in 200..299) throw ActionError("$url returned $code")
            val bytes = connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            return ActionOutcome(
                "downloaded $bytes bytes to ${step.args.string("path")}",
                mapOf("path" to target.absolutePath, "bytes" to bytes.toString())
            )
        } catch (e: ActionError) {
            throw e
        } catch (e: Exception) {
            throw ActionError("could not download $url: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection.disconnect()
        }
    }

    // -- network ----------------------------------------------------------------------------

    private fun wakeOnLan(step: Step): String {
        val mac = step.args.string("mac")
        val bytes = mac.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size != MAC_BYTES) throw ActionError("'$mac' is not a MAC address like a1:b2:c3:d4:e5:f6")
        // A magic packet is six 0xFF bytes then the MAC repeated sixteen times.
        val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(MAC_BYTES * 16) { bytes[it % MAC_BYTES] }
        val address = step.args.optString("broadcast") ?: "255.255.255.255"
        val port = step.args.int("port", 9)
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), port))
            }
        }.getOrElse { throw ActionError("could not send magic packet to $address:$port: ${it.message}") }
        return "sent magic packet for $mac to $address:$port"
    }

    private fun ping(step: Step): ActionOutcome {
        val host = step.args.string("host")
        val timeout = step.args.int("timeout_ms", 3000)
        val started = System.currentTimeMillis()
        val reachable =
            runCatching { InetAddress.getByName(host).isReachable(timeout) }
                .getOrElse { throw ActionError("could not resolve '$host': ${it.message}") }
        val elapsed = System.currentTimeMillis() - started
        if (!reachable) throw ActionError("$host did not answer within ${timeout}ms")
        val into = step.args.optString("into") ?: "ping_ms"
        return ActionOutcome("$host answered in ${elapsed}ms", mapOf(into to elapsed.toString()))
    }

    // -- capture and sound ------------------------------------------------------------------

    private fun screenshot(step: Step): ActionOutcome {
        val target = resolveFile(step.args.optString("path") ?: "screenshot.png")
        target.parentFile?.mkdirs()
        val bitmap = accessibility().captureScreen()
            ?: throw ActionError("the system did not return a screenshot")
        runCatching {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        }.getOrElse { throw ActionError("could not write the screenshot: ${it.message}") }
        return ActionOutcome("saved ${target.name}", mapOf("path" to target.absolutePath))
    }

    private fun playSound(kind: String): String {
        val type = when (kind.lowercase()) {
            "notification" -> RingtoneManager.TYPE_NOTIFICATION
            "alarm" -> RingtoneManager.TYPE_ALARM
            "ringtone" -> RingtoneManager.TYPE_RINGTONE
            else -> throw ActionError("unknown sound '$kind'")
        }
        val uri = RingtoneManager.getDefaultUri(type)
            ?: throw ActionError("no default $kind tone is configured")
        RingtoneManager.getRingtone(context, uri)?.play()
            ?: throw ActionError("could not play the $kind tone")
        return "played the $kind tone"
    }

    private fun clipboardGet(step: Step): ActionOutcome {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: throw ActionError("clipboard unavailable")
        // Android blocks background clipboard reads outright, so this reports empty rather than
        // failing: a rule that only wants the text when it is available should not break.
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        val into = step.args.optString("into") ?: "clipboard"
        return ActionOutcome(
            if (text.isEmpty()) {
                "clipboard empty or not readable from the background"
            } else {
                "read ${text.length} characters"
            },
            mapOf(into to text)
        )
    }

    private fun sendSms(to: String, message: String): String {
        if (!device.hasPermission(Manifest.permission.SEND_SMS)) {
            throw ActionError(
                "SEND_SMS is not granted; run: adb shell pm grant ${context.packageName} android.permission.SEND_SMS"
            )
        }
        val manager = context.getSystemService(SmsManager::class.java)
            ?: throw ActionError("no SMS service on this device")
        // Long messages have to be split; the single-part call silently truncates them.
        val parts = manager.divideMessage(message)
        runCatching { manager.sendMultipartTextMessage(to, null, parts, null, null) }
            .getOrElse { throw ActionError("could not send to $to: ${it.message}") }
        return "sent ${parts.size} part(s) to $to"
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
        const val TEXT_PREVIEW = 120
        const val MAC_BYTES = 6
        const val PNG_QUALITY = 100
    }
}
