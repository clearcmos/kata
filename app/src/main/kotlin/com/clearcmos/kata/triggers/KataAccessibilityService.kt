package com.clearcmos.kata.triggers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.clearcmos.kata.engine.Kata
import com.clearcmos.kata.engine.TriggerEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground-app detection and on-screen interaction.
 *
 * This is the widest grant kata asks for: while bound it can read everything drawn on screen.
 * It stays unbound until the user enables it in Settings, nothing registers it implicitly, and
 * /capabilities reports its state explicitly rather than leaving it to be discovered.
 *
 * The running instance is published statically so [com.clearcmos.kata.actions.ActionRunner] can
 * reach it. The system owns the lifecycle, so the reference is cleared on unbind and every
 * caller has to cope with it being null.
 */
class KataAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The platform unbinds and rebinds this service on its own schedule. Without seeding
        // here, foregroundPackage stays null until the next app switch, so the first switch
        // after a rebind has no previous value and emits no app_background at all.
        foregroundPackage = activeApplicationPackage()
        Log.i(TAG, "accessibility service connected, foreground=$foregroundPackage")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        foregroundPackage = null
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        foregroundPackage = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // event.packageName is whatever raised the event, which includes keyboards, toasts and
        // popups. Asking the window list for the active application window instead means an IME
        // appearing over an app does not read as an app switch.
        val current = activeApplicationPackage() ?: return
        val previous = foregroundPackage
        if (current == previous) return
        foregroundPackage = current

        val engine = Kata.engine(applicationContext)
        if (previous != null) {
            engine.onEvent(TriggerEvent("app_background", mapOf("package" to previous)))
        }
        engine.onEvent(TriggerEvent("app_foreground", mapOf("package" to current)))
    }

    fun activeApplicationPackage(): String? = runCatching {
        windows
            .firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?.root
            ?.packageName
            ?.toString()
    }.getOrNull()

    // -- interaction, called from the engine thread -------------------------------------

    fun runGlobalAction(action: Int): Boolean = performGlobalAction(action)

    /**
     * Captures the screen, blocking the caller until the system answers.
     *
     * takeScreenshot is asynchronous and hands back a hardware buffer, which is copied into a
     * software bitmap before returning: a hardware bitmap cannot be compressed reliably, and the
     * buffer has to be closed before this method returns or it leaks.
     */
    fun captureScreen(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? {
        val latch = CountDownLatch(1)
        val captured = AtomicReference<Bitmap?>(null)
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    screenshot.hardwareBuffer.use { buffer ->
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        captured.set(wrapped?.copy(Bitmap.Config.ARGB_8888, false))
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "screenshot refused, error $errorCode")
                    latch.countDown()
                }
            }
        )
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return captured.get()
    }

    /**
     * Finds a node matching [matcher] and clicks it, polling until [timeoutMs].
     *
     * The node that carries a label is often not the clickable one, so this walks up to the
     * nearest clickable ancestor. Returns the label it clicked, or null if nothing matched.
     */
    fun tap(matcher: NodeMatcher, timeoutMs: Int): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val root = rootInActiveWindow
            if (root != null) {
                val target = find(root, matcher)
                if (target != null) {
                    val clickable = clickableAncestor(target)
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return describe(target)
                    }
                }
            }
            Thread.sleep(POLL_MS)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    /** Labels currently on screen, so a failed tap can say what it saw instead of just failing. */
    fun visibleLabels(limit: Int = 25): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = LinkedHashSet<String>()
        walk(root) { node ->
            describe(node)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            out.size < limit
        }
        return out.toList()
    }

    private fun find(root: AccessibilityNodeInfo, matcher: NodeMatcher): AccessibilityNodeInfo? {
        matcher.viewId?.let { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return it }
        }
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (matcher.matches(node)) {
                found = node
                false
            } else {
                true
            }
        }
        return found
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    /** Depth-first walk. [visit] returns false to stop. */
    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (!visit(node)) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!walk(child, visit)) return false
        }
        return true
    }

    private fun describe(node: AccessibilityNodeInfo): String? = node.text?.toString()?.takeIf { it.isNotBlank() }
        ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

    data class NodeMatcher(
        val text: String? = null,
        val contentDescription: String? = null,
        val viewId: String? = null,
        val exact: Boolean = false
    ) {
        fun matches(node: AccessibilityNodeInfo): Boolean {
            text?.let { if (compare(node.text?.toString(), it)) return true }
            contentDescription?.let { if (compare(node.contentDescription?.toString(), it)) return true }
            return false
        }

        private fun compare(actual: String?, wanted: String): Boolean {
            if (actual == null) return false
            return if (exact) actual.equals(wanted, ignoreCase = true) else actual.contains(wanted, ignoreCase = true)
        }

        fun describe(): String = listOfNotNull(
            text?.let { "text=\"$it\"" },
            contentDescription?.let { "content_description=\"$it\"" },
            viewId?.let { "view_id=$it" }
        ).joinToString(" ")
    }

    companion object {
        private const val TAG = "KataA11y"
        private const val POLL_MS = 150L
        private const val MAX_ANCESTOR_HOPS = 12
        private const val SCREENSHOT_TIMEOUT_MS = 5000L

        @Volatile
        var instance: KataAccessibilityService? = null
            private set

        /**
         * Cached foreground package, kept only so [onAccessibilityEvent] can tell an app switch
         * from a repeat. Read [currentPackage] instead: a cached value can be stale across a
         * rebind, and there is no way to tell from the outside.
         */
        @Volatile
        private var foregroundPackage: String? = null

        /**
         * The application package in front right now, or null when the service is not running.
         * Queried live rather than read from the cache, so it is correct even immediately after
         * a rebind and before any event has arrived.
         */
        val currentPackage: String?
            get() = instance?.activeApplicationPackage()

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return flat.split(":").any { it.startsWith("${context.packageName}/") }
        }
    }
}
