package com.whispereverywhere.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import com.whispereverywhere.text.InjectionAnchor
import com.whispereverywhere.text.TextJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility Service for detecting focused text fields and injecting transcribed text.
 */
class WhisperAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFocusedEditText: AccessibilityNodeInfo? = null
    private var lastFieldRect: Rect? = null
    private var lastFocusTime: Long = 0L
    private var currentPackage: String? = null

    // Injection session: the field the user STARTED dictating into, captured at record start.
    // Delivery happens seconds later (per committed segment) — without this, a mid-transcription
    // app/field switch reroutes the text to whatever happens to be focused at delivery time.
    private var sessionTargetEditText: AccessibilityNodeInfo? = null
    private var sessionTargetPackage: String? = null
    // The cursor state machine for the active dictation session (null outside a session). Captured
    // at record start from the live field; every segment places against it, ignoring the live caret.
    private var sessionAnchor: InjectionAnchor? = null

    // Apps that use custom editors where we should be more persistent
    private val documentApps = setOf(
        "com.samsung.android.app.notes",      // Samsung Notes
        "com.samsung.android.quickedit",      // Samsung Quick Edit
        "com.microsoft.office.word",          // MS Word
        "com.microsoft.office.onenote",       // OneNote
        "com.google.android.apps.docs.editors.docs", // Google Docs
        "com.google.android.apps.docs.editors.sheets", // Google Sheets
        "com.google.android.keep",            // Google Keep
        "com.evernote",                       // Evernote
        "notion.id",                          // Notion
        "md.obsidian",                        // Obsidian
        "com.automattic.simplenote",          // Simplenote
        "com.colornote.notepad",              // ColorNote
        "ru.alexandermalikov.quickedit",      // QuickEdit
        "com.rhmsoft.edit",                   // QuickEdit Pro
        "com.aor.droidedit",                  // DroidEdit
        "com.alorma.github.editor"            // Various code editors
    )

    // Social media apps that use @mentions - must use paste to preserve mention formatting
    private val socialMediaApps = setOf(
        "com.facebook.katana",                // Facebook
        "com.facebook.lite",                  // Facebook Lite
        "com.facebook.orca",                  // Messenger
        "com.facebook.mlite",                 // Messenger Lite
        "com.instagram.android",              // Instagram
        "com.twitter.android",                // Twitter/X
        "com.twitter.android.lite",           // Twitter Lite
        "com.zhiliaoapp.musically",           // TikTok
        "com.ss.android.ugc.trill",           // TikTok (alternate)
        "com.linkedin.android",               // LinkedIn
        "com.snapchat.android",               // Snapchat
        "com.pinterest",                      // Pinterest
        "com.reddit.frontpage",               // Reddit
        "com.tumblr",                         // Tumblr
        "com.discord",                        // Discord
        "org.telegram.messenger",             // Telegram
        "com.whatsapp",                       // WhatsApp
        "com.viber.voip",                     // Viber
        "jp.naver.line.android",              // LINE
        "com.google.android.youtube",         // YouTube
        "com.vkontakte.android",              // VK
        "com.Slack",                          // Slack
        "com.microsoft.teams"                 // Microsoft Teams
    )

    interface OnTextFieldFocusListener {
        fun onTextFieldFocused(rect: Rect)
        fun onTextFieldUnfocused()
    }

    /** Fires when ANY app puts something on the clipboard (best-effort; OEM-dependent). */
    interface OnClipboardChangedListener {
        fun onClipboardChanged()
    }

    // LAYER 1 of copy detection, and the weakest: since API 29 the platform suppresses
    // OnPrimaryClipChangedListener for BACKGROUND apps — accessibility context included, as the
    // owner's Samsung proved on-device 2026-08-01 (the old comment here claimed otherwise; it was
    // wrong). This listener now only fires when Whisper Everywhere itself is foreground (e.g. the
    // in-app transcripts screen). Copies made in OTHER apps are caught by the accessibility-signal
    // layers in fireCopyDetected's callers: the system clipboard overlay window (Android 13+ shows
    // one after every copy), OEM "Copied" toasts, and the selection toolbar's Copy button.
    private val clipChangedListener =
        android.content.ClipboardManager.OnPrimaryClipChangedListener {
            fireCopyDetected("primary-clip listener (foreground)")
        }

    /** Debounce across the detection layers — one copy often trips two or three of them. */
    @Volatile private var lastCopySignalMs = 0L

    private fun fireCopyDetected(signal: String) {
        val now = System.currentTimeMillis()
        if (now - lastCopySignalMs < 1_500) return
        lastCopySignalMs = now
        android.util.Log.i("WE-DIAG", "copy detected via: $signal")
        clipboardListener?.onClipboardChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .addPrimaryClipChangedListener(clipChangedListener)
        }

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    // Copy detection layer: OEM "Copied to clipboard" toasts surface here.
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                    // The pop-up CATCH-ALL (owner 2026-08-01: "everywhere a keyboard will pop
                    // up"): the per-view events above are heuristic and some fields never emit
                    // them (WebViews, Compose editors, custom canvases). The IME window itself is
                    // ground truth — when it appears, SOMETHING accepts text, and the bubble
                    // should be there. See handleWindowsChanged.
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT

            notificationTimeout = 30 // Faster response
        }
        serviceInfo = info
        applyKeyboardShowMode()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Track current package
        val eventPackage = event.packageName?.toString()
        if (eventPackage != null && eventPackage != "com.whispereverywhere") {
            currentPackage = eventPackage
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleFocusOrClick(event.source)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // User clicked - check both the source and search for editable field
                val source = event.source
                if (source != null) {
                    // Copy detection layer: the selection toolbar's Copy button is an ordinary
                    // clickable node. English-labelled UIs only — the overlay/toast layers carry
                    // other locales.
                    val clickLabel = source.text?.toString()
                        ?: source.contentDescription?.toString() ?: ""
                    if (clickLabel.equals("Copy", ignoreCase = true) ||
                        clickLabel.equals("Copy to clipboard", ignoreCase = true)
                    ) {
                        fireCopyDetected("copy button click")
                    }
                    // First check if clicked view is editable
                    if (isEditableTextField(source)) {
                        lastFocusedEditText = source
                        lastFocusTime = System.currentTimeMillis()
                        notifyTextFieldFocused(source)
                    } else {
                        // Maybe clicked on a container - search children for editable field
                        val editableChild = findEditableChild(source)
                        if (editableChild != null) {
                            lastFocusedEditText = editableChild
                            lastFocusTime = System.currentTimeMillis()
                            notifyTextFieldFocused(editableChild)
                        } else if (isDocumentApp()) {
                            // For document apps, clicking anywhere in the editor area should show bubble
                            handleDocumentAppClick(source)
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Text activity - this confirms we have an active input
                val source = event.source
                if (source != null) {
                    if (isEditableTextField(source)) {
                        if (lastFocusedEditText == null || !isSameNode(lastFocusedEditText, source)) {
                            lastFocusedEditText = source
                            lastFocusTime = System.currentTimeMillis()
                            notifyTextFieldFocused(source)
                        } else {
                            // Same field but refresh the time to keep bubble alive
                            lastFocusTime = System.currentTimeMillis()
                        }
                    } else if (isDocumentApp()) {
                        // Document app text changed - treat source as editable
                        lastFocusedEditText = source
                        lastFocusTime = System.currentTimeMillis()
                        notifyTextFieldFocused(source)
                    } else if (
                        // CARET tracking, the third catch-all layer (owner 2026-08-01: "track
                        // every cursor that's on in the app — if we can follow that, that's how
                        // we know when our bubble needs to pop up"). A selection-changed event
                        // whose range is EMPTY (from == to, both valid) on a focused node is a
                        // blinking cursor by definition — whatever the node's class claims. The
                        // empty-range requirement is what keeps read-only text selection (long-
                        // press select/copy, which produces RANGES) from false-firing the bubble.
                        event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
                        event.fromIndex >= 0 && event.fromIndex == event.toIndex &&
                        source.isFocused &&
                        event.packageName?.toString() != "com.whispereverywhere"
                    ) {
                        lastFocusedEditText = source
                        lastFocusTime = System.currentTimeMillis()
                        notifyTextFieldFocused(source)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                handleWindowsChanged()
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // Copy detection layer: OEM copy confirmations are toasts ("Copied to clipboard",
                // "Copied"). Only toast-shaped events are considered — real notifications carry
                // arbitrary text and must never trip this.
                val cls = event.className?.toString() ?: ""
                if (cls.contains("Toast", ignoreCase = true)) {
                    val text = event.text.joinToString(" ")
                    if (text.contains("copied", ignoreCase = true)) {
                        fireCopyDetected("copy toast")
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window changed - be more careful about unfocusing
                val pkg = event.packageName?.toString()

                // Copy detection layer, the strongest on 13+: EVERY copy makes SystemUI show its
                // clipboard preview overlay; that window's appearance is a copy, unambiguously.
                if (pkg == "com.android.systemui" &&
                    (event.className?.toString() ?: "").contains("clipboard", ignoreCase = true)
                ) {
                    fireCopyDetected("system clipboard overlay")
                    return
                }

                // If switched to a different app entirely, unfocus
                if (pkg != null && pkg != currentPackage && pkg != "com.whispereverywhere") {
                    notifyTextFieldUnfocused()
                    return
                }

                // For document apps, be much more lenient - don't unfocus on internal window changes
                if (isDocumentApp()) {
                    // Only unfocus if significant time has passed with no activity
                    serviceScope.launch {
                        delay(2000) // 2 second grace period for document apps
                        val timeSinceFocus = System.currentTimeMillis() - lastFocusTime
                        if (timeSinceFocus > 2000 && lastFocusedEditText != null) {
                            try {
                                val current = lastFocusedEditText
                                if (current == null || !current.refresh()) {
                                    // Double-check we're not still in a document app actively
                                    if (!isDocumentApp()) {
                                        notifyTextFieldUnfocused()
                                    }
                                }
                            } catch (e: Exception) {
                                // Keep bubble for document apps even on error
                            }
                        }
                    }
                } else {
                    // Standard apps - check if we lost focus after short delay
                    serviceScope.launch {
                        delay(300)
                        val current = lastFocusedEditText
                        if (current != null) {
                            try {
                                if (!current.refresh() || !current.isFocused) {
                                    notifyTextFieldUnfocused()
                                }
                            } catch (e: Exception) {
                                notifyTextFieldUnfocused()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isDocumentPackage(pkg: String?): Boolean {
        pkg ?: return false
        return pkg in documentApps ||
               pkg.contains("note", ignoreCase = true) ||
               pkg.contains("edit", ignoreCase = true) ||
               pkg.contains("doc", ignoreCase = true) ||
               pkg.contains("office", ignoreCase = true)
    }

    private fun isDocumentApp(): Boolean = isDocumentPackage(currentPackage)

    private fun isSocialMediaPackage(pkg: String?): Boolean {
        pkg ?: return false
        return pkg in socialMediaApps ||
               pkg.contains("facebook", ignoreCase = true) ||
               pkg.contains("instagram", ignoreCase = true) ||
               pkg.contains("twitter", ignoreCase = true) ||
               pkg.contains("tiktok", ignoreCase = true) ||
               pkg.contains("snapchat", ignoreCase = true) ||
               pkg.contains("messenger", ignoreCase = true) ||
               pkg.contains("whatsapp", ignoreCase = true) ||
               pkg.contains("telegram", ignoreCase = true) ||
               pkg.contains("discord", ignoreCase = true) ||
               pkg.contains("slack", ignoreCase = true) ||
               pkg.contains("reddit", ignoreCase = true) ||
               pkg.contains("linkedin", ignoreCase = true)
    }

    /**
     * Check if current app is a social media app that uses @mentions.
     * These apps need special handling to preserve mention formatting.
     */
    private fun isSocialMediaApp(): Boolean {
        // Get package from active window for most accurate detection
        return isSocialMediaPackage(rootInActiveWindow?.packageName?.toString() ?: currentPackage)
    }

    /**
     * Capture the field the user is dictating into. Called when a recording session starts;
     * every segment of that session then targets THIS field, not whatever is focused when the
     * segment's transcription completes.
     */
    private fun beginInjectionSessionInternal() {
        // Main-thread only (all begin/resolve/end callers run on the service main thread).
        val target = findFocusedEditText() ?: lastFocusedEditText
        sessionTargetEditText = target
        // Package pinned only WITH a node: no field at record start means no session — delivery
        // classifies by the foreground app, exactly as before this feature existed.
        sessionTargetPackage = if (target != null) {
            target.packageName?.toString() ?: currentPackage
        } else null
        // Pin the cursor anchor from the field as it is RIGHT NOW. A selection range here is the
        // one legal replace (user selected text then started dictating). No field => no anchor.
        sessionAnchor = if (target != null) {
            val raw = target.text?.toString() ?: ""
            val hint = target.hintText?.toString() ?: ""
            val isHint = raw.isNotEmpty() && (raw == hint || raw.equals(hint, ignoreCase = true))
            val current = if (isHint) "" else raw
            InjectionAnchor().apply { start(current, target.textSelectionStart, target.textSelectionEnd) }
        } else null
    }

    private fun endInjectionSessionInternal() {
        sessionTargetEditText = null
        sessionTargetPackage = null
        sessionAnchor = null
    }

    /**
     * The node to inject into: the record-start capture while it's still alive, else the field
     * focused right now (pre-session behavior). A dead session node stops being preferred so
     * later segments of the same session don't retry a stale target.
     */
    private fun resolveInjectionTarget(): AccessibilityNodeInfo? {
        val session = sessionTargetEditText
        if (session != null) {
            val alive = try { session.refresh() } catch (e: Exception) { false }
            if (alive) return session
            // Node died: drop the WHOLE session (node AND package AND anchor) so strategy
            // classification follows the fallback target's app, not the dead session's — and, crucially,
            // so the stale anchor (expectedText/anchor from the DEAD field) can never be applied against
            // an unrelated fallback field, planting the dictation mid-text into a field the user never
            // targeted. With the anchor cleared in lockstep, setTextViaAnchor builds a fresh transient
            // anchor from the fallback field's own live caret.
            sessionTargetEditText = null
            sessionTargetPackage = null
            sessionAnchor = null
        }
        return findFocusedEditText() ?: lastFocusedEditText
    }

    /**
     * App classification for choosing the injection strategy. While a session target is held,
     * classify by ITS app — the strategy must match where the text lands, not the app that
     * happens to be foreground at delivery.
     */
    private fun injectionTargetIsDocumentApp(): Boolean =
        if (sessionTargetPackage != null) isDocumentPackage(sessionTargetPackage) else isDocumentApp()

    private fun injectionTargetIsSocialMediaApp(): Boolean =
        if (sessionTargetPackage != null) isSocialMediaPackage(sessionTargetPackage) else isSocialMediaApp()

    /**
     * Check if the text field currently has a mention (starts with @)
     */
    private fun fieldHasMention(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString() ?: return false
        // Check if text starts with @ or contains @ followed by text (mention pattern)
        return text.trimStart().startsWith("@")
    }

    private fun handleDocumentAppClick(source: AccessibilityNodeInfo) {
        // For document apps, we treat large scrollable/clickable areas as potential text areas
        val rect = Rect()
        source.getBoundsInScreen(rect)

        // If it's a reasonable sized area that's scrollable or takes up significant screen space
        if (rect.width() > 200 && rect.height() > 100) {
            val className = source.className?.toString()?.lowercase() ?: ""

            // Look for editor-like views
            if (className.contains("view") ||
                className.contains("editor") ||
                className.contains("canvas") ||
                className.contains("scroll") ||
                className.contains("frame") ||
                source.isScrollable ||
                source.isClickable) {

                lastFocusedEditText = source
                lastFocusTime = System.currentTimeMillis()
                lastFieldRect = rect
                focusListener?.onTextFieldFocused(rect)
            }
        }
    }

    private fun handleFocusOrClick(source: AccessibilityNodeInfo?) {
        source ?: return

        if (isEditableTextField(source)) {
            lastFocusedEditText = source
            lastFocusTime = System.currentTimeMillis()
            notifyTextFieldFocused(source)
        } else if (isDocumentApp()) {
            // For document apps, be more aggressive about detecting editable areas
            handleDocumentAppClick(source)
        } else {
            // Check if focus moved away from our tracked field
            serviceScope.launch {
                delay(150)
                val current = lastFocusedEditText
                if (current != null) {
                    try {
                        if (!current.refresh() || !current.isFocused) {
                            notifyTextFieldUnfocused()
                        }
                    } catch (e: Exception) {
                        notifyTextFieldUnfocused()
                    }
                }
            }
        }
    }

    /**
     * Search for an editable text field among children
     * Useful when user clicks on a container/wrapper
     */
    private fun findEditableChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isEditableTextField(child)) {
                return child
            }
            // Check one more level deep
            val grandchild = findEditableChild(child)
            if (grandchild != null) {
                return grandchild
            }
        }
        return null
    }

    private fun isSameNode(a: AccessibilityNodeInfo?, b: AccessibilityNodeInfo?): Boolean {
        if (a == null || b == null) return false
        return try {
            a.hashCode() == b.hashCode() ||
                (a.className == b.className && a.viewIdResourceName == b.viewIdResourceName)
        } catch (e: Exception) {
            false
        }
    }

    private fun notifyTextFieldFocused(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Basic validation - field should have reasonable size
        if (rect.width() > 30 && rect.height() > 15) {
            lastFieldRect = rect
            focusListener?.onTextFieldFocused(rect)
        }
    }

    private fun notifyTextFieldUnfocused() {
        lastFocusedEditText = null
        lastFieldRect = null
        // A summoned keyboard is per-field: leaving the field re-arms dictation-first suppression.
        // Same 1 s toggle-echo guard as the falling edge (owner repro 2026-08-01, "three presses
        // before it starts toggling"): tapping the LOBE itself churns window state, the 300 ms
        // focus re-check briefly fails while the IME animates, and this reset was silently undoing
        // the summon the user had JUST made — until the churn died down a few taps later.
        if (keyboardSummoned) {
            if (System.currentTimeMillis() - lastSummonToggleMs > 1_000) {
                android.util.Log.i("WE-DIAG", "kbd re-arm: field unfocused")
                keyboardSummoned = false
                applyKeyboardShowMode()
            } else {
                android.util.Log.i("WE-DIAG", "kbd re-arm SKIPPED: within toggle echo window")
            }
        }
        focusListener?.onTextFieldUnfocused()
    }

    // ------------------------------------------------------------------ IME-window catch-all

    /** Whether an input-method window was present at the last TYPE_WINDOWS_CHANGED. */
    private var imeWasVisible = false

    /**
     * The catch-all: the keyboard window appearing IS a text field focusing, whatever view
     * machinery the app uses. On the rising edge, if the per-view heuristics have not already
     * fired (fresh lastFocusTime), find the input-focused node and surface the bubble for it; if
     * no node is findable at all, anchor the bubble just above the keyboard itself — the session
     * re-resolves the real target at record time (startInjectionSession's findFocusedEditText),
     * so a nodeless anchor only positions UI, it never mis-injects.
     */
    private fun handleWindowsChanged() {
        val imeVisible = runCatching {
            windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }.getOrDefault(false)
        val rising = imeVisible && !imeWasVisible
        val falling = !imeVisible && imeWasVisible
        imeWasVisible = imeVisible
        if (falling) {
            // The user dismissed the keyboard they summoned: re-arm dictation-first suppression.
            // NOT within a beat of a lobe toggle, though — the IME hides ASYNCHRONOUSLY, so a
            // hide->show double-tap lands the FIRST hide's falling edge after the second toggle
            // already set summoned=true, and honoring that stale echo silently re-hid a keyboard
            // the user just asked for. That desync compounded into the owner-reported "after a
            // few toggles it doesn't come back at all" (2026-08-01).
            if (keyboardSummoned && System.currentTimeMillis() - lastSummonToggleMs > 1_000) {
                android.util.Log.i("WE-DIAG", "kbd re-arm: IME dismissed")
                keyboardSummoned = false
                applyKeyboardShowMode()
            }
            return
        }
        if (!rising) return
        // The ordinary detection already handled this focus moments ago — don't double-fire.
        if (System.currentTimeMillis() - lastFocusTime < 800) return
        val node = findFocusedEditText()
        if (node != null) {
            lastFocusedEditText = node
            lastFocusTime = System.currentTimeMillis()
            notifyTextFieldFocused(node)
        } else {
            runCatching {
                val ime = windows.firstOrNull {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
                } ?: return
                val r = Rect()
                ime.getBoundsInScreen(r)
                // A thin strip along the keyboard's top edge: the bubble docks right above it.
                lastFocusTime = System.currentTimeMillis()
                focusListener?.onTextFieldFocused(Rect(r.left, r.top - 1, r.right, r.top))
            }
        }
    }

    // ------------------------------------------------------------------ dictation-first keyboard

    /** True while the user has explicitly summoned the system keyboard for the current field. */
    @Volatile private var keyboardSummoned = false

    /** When the lobe last toggled — the falling-edge re-arm must ignore ITS OWN hide's echo. */
    @Volatile private var lastSummonToggleMs = 0L

    /**
     * Applies the dictation-first preference to the soft-keyboard controller: suppressed while
     * the preference is on and the keyboard has not been summoned; AUTO otherwise. Never throws —
     * an OEM that ignores show modes simply keeps its keyboard, and the feature degrades to the
     * lobe doing nothing (the preference copy promises no more than this).
     */
    fun applyKeyboardShowMode() {
        runCatching {
            val prefOn = com.whispereverywhere.WhisperEverywhereApp.getInstance()
                .preferencesManager.isDictationFirstKeyboard()
            softKeyboardController.showMode =
                if (prefOn && !keyboardSummoned) android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN
                else android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        runCatching {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .removePrimaryClipChangedListener(clipChangedListener)
        }
        serviceScope.cancel()
        endInjectionSessionInternal()
        instance = null
    }

    /**
     * Check if a node is an editable text field.
     * More permissive than before - we trust the system's signals.
     */
    private fun isEditableTextField(node: AccessibilityNodeInfo): Boolean {
        // #1: isEditable is the gold standard - trust it completely
        if (node.isEditable) {
            return true
        }

        // #2: Check class name for known editable types
        val className = node.className?.toString()?.lowercase() ?: ""

        // Definitive input classes
        if (className.contains("edittext") ||
            className.contains("autocompletetextview") ||
            className.contains("textinputedittext") ||
            className.contains("extractedittext")) {
            return true
        }

        // #3: Check if it supports SET_TEXT action - key indicator
        val supportsSetText = node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }

        // If supports SET_TEXT, it's very likely an input field
        if (supportsSetText) {
            return true
        }

        // #4: Check input type - if set, it's definitely an input
        if (node.inputType != 0) {
            val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
            if (inputClass == InputType.TYPE_CLASS_TEXT ||
                inputClass == InputType.TYPE_CLASS_NUMBER ||
                inputClass == InputType.TYPE_CLASS_PHONE ||
                inputClass == InputType.TYPE_CLASS_DATETIME) {
                return true
            }
        }

        // #5: Cross-platform frameworks (React Native, Flutter)
        if (className.contains("rcttextinput") ||
            className.contains("textinputclient") ||
            className.contains("fluttertextinput")) {
            return true
        }

        // #6: Check for PASTE support with hint text (common pattern)
        val supportsPaste = node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE
        }
        if (supportsPaste && !node.hintText.isNullOrEmpty()) {
            return true
        }

        return false
    }

    /**
     * True while a soft keyboard window is on screen — the most reliable cross-app signal
     * that SOMETHING has input focus (the user's criterion: "if there's no keyboard or cursor
     * for you to type with, use our own window").
     */
    fun isImeVisible(): Boolean = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /**
     * Find focused editable field (for text injection). Searches ALL windows, not just the
     * active one — on foldables/multi-window the focused field often lives in a window that
     * rootInActiveWindow does not return (user-reported injection failures 2026-07-18).
     */
    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        // Method 1: input focus across every interactive window.
        runCatching {
            for (w in windows) {
                val focused = w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
                if (isEditableTextField(focused)) {
                    lastFocusedEditText = focused
                    return focused
                }
            }
        }

        val rootNode = rootInActiveWindow ?: return null

        // Method 2: input focus on the active window (kept as belt-and-braces).
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && isEditableTextField(focusedNode)) {
            lastFocusedEditText = focusedNode
            return focusedNode
        }

        // Method 3: Search for any focused editable field
        return findFocusedEditableRecursive(rootNode)
    }

    private fun findFocusedEditableRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditableTextField(node)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableRecursive(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * Pins the selection (cursor) to the absolute END OF TEXT via ACTION_SET_SELECTION — the
     * semantic action, deliberately NOT a screen tap. The old gesture heuristic tapped the field's
     * right-center edge, which in a MULTI-LINE field lands the cursor at the end of whatever LINE
     * sits mid-field, not the end of the text — the exact "cursor moves around while dictating
     * into Facebook/email" corruption the owner reported (2026-07-31). Paste inserts at the
     * cursor, so on the clipboard path this pin is the whole ballgame; it also makes
     * clipboardPayloadFor's end-append padding assumption actually true.
     *
     * Hint-aware: a field showing its hint reports the hint AS text; pinning to hint-length would
     * be wrong, so the effective length is 0 there. Returns false when the node is dead or the
     * editor does not support SET_SELECTION (Docs-style canvases) — callers treat that as
     * "position unknown, paste anyway", which is exactly the pre-fix behavior, not a regression.
     */
    private fun pinSelectionToEnd(node: AccessibilityNodeInfo): Boolean {
        if (!node.refresh()) return false
        val raw = node.text?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        val len = if (raw.isNotEmpty() && raw.equals(hint, ignoreCase = true)) 0 else raw.length
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The clipboard-path paste discipline, per the owner's 2026-07-31 directive: on paste-driven
     * editors (Facebook, most email apps) EVERY segment appends at the very end — pin before the
     * paste so it lands at the end of text, and re-pin after so the next segment (and the user's
     * eye) starts from the end regardless of what the app's TextWatchers did in between. The
     * post-paste pin is best-effort on a short delay (paste application is async in some editors).
     */
    private fun pasteAtEnd(node: AccessibilityNodeInfo): Boolean {
        pinSelectionToEnd(node) // best-effort; false = editor won't say where its cursor is
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) {
            serviceScope.launch {
                delay(120)
                try { pinSelectionToEnd(node) } catch (_: Exception) { /* node may be gone */ }
            }
        }
        return pasted
    }

    /**
     * Check if current app is specifically Facebook (not just any social media)
     */
    private fun isFacebookApp(): Boolean {
        // Session package first: the gesture-tap path must key off the app being injected INTO.
        val pkg = sessionTargetPackage
            ?: rootInActiveWindow?.packageName?.toString() ?: currentPackage ?: return false
        return pkg.contains("facebook", ignoreCase = true) ||
               pkg == "com.facebook.katana" ||
               pkg == "com.facebook.lite" ||
               pkg == "com.facebook.orca" ||
               pkg == "com.facebook.mlite"
    }

    /**
     * Inject text for social media apps with detailed result.
     * For Facebook with mentions, taps at end of field before pasting.
     */
    private fun injectForSocialMediaWithResult(text: String): InjectionResult {
        val targetNode = resolveInjectionTarget()

        if (targetNode?.refresh() != true) {
            // No live field: keep the raw text on the clipboard so the user can paste it manually.
            return try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                InjectionResult.CLIPBOARD_ONLY
            } catch (e: Exception) {
                InjectionResult.FAILED
            }
        }

        // Live field: pad the segment against its existing text so a pasted segment never melts
        // against the previous one, THEN place it on the clipboard for the paste actions below.
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", clipboardPayloadFor(text, targetNode)))
        } catch (e: Exception) {
            return InjectionResult.FAILED
        }

        // Check if field has existing content (might be a mention)
        val existingText = targetNode.text?.toString() ?: ""

        if (existingText.isNotEmpty() && isFacebookApp() && !pinSelectionToEnd(targetNode)) {
            // Editor refused SET_SELECTION — legacy tap fallback (end-of-LINE imperfection known;
            // the semantic pin above is always preferred and was already attempted).
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            val tapX = rect.right - 20f
            val tapY = rect.centerY().toFloat()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val path = Path()
                path.moveTo(tapX, tapY)

                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()

                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        // After tap completes, try the semantic pin once more, then paste.
                        serviceScope.launch {
                            delay(100) // Small delay to let cursor position update
                            targetNode.refresh()
                            pinSelectionToEnd(targetNode)
                            targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }
                    }
                }, null)

                // Return SUCCESS since we initiated the gesture+paste sequence
                return InjectionResult.SUCCESS
            }

            // Fallback for older Android - just try paste
            return if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                InjectionResult.SUCCESS
            } else {
                InjectionResult.CLIPBOARD_ONLY
            }
        }

        // Pinned (or pin unsupported): paste at the end, re-pin after.
        if (pasteAtEnd(targetNode)) {
            return InjectionResult.SUCCESS
        }

        // Paste failed but text is in clipboard
        return InjectionResult.CLIPBOARD_ONLY
    }

    /**
     * Recursively search for a node that supports paste and try to paste
     */
    private fun tryPasteOnAnyNode(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false

        // Check if this node supports paste
        val supportsPaste = node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_PASTE
        }

        if (supportsPaste) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                return true
            }
        }

        // Try children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tryPasteOnAnyNode(child)) {
                return true
            }
        }

        return false
    }

    private fun injectViaClipboard(text: String): Boolean {
        return try {
            // Pad against the target field so a pasted segment never melts against existing content.
            val targetNode = resolveInjectionTarget()
            val refreshed = targetNode?.refresh() == true
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Whisper", if (refreshed) clipboardPayloadFor(text, targetNode) else text),
            )

            var success = false

            // Try paste on target node
            if (refreshed) {
                success = targetNode!!.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            // Try paste on focused input
            if (!success) {
                val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }

            // For standard apps, return actual success status
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Pin the caret to [pos] after a successful SET_TEXT: resets the app's cursor games and makes
     * the next segment's anchor visibly correct. Non-fatal — a rejecting field just keeps its caret.
     */
    private fun pinCursor(node: AccessibilityNodeInfo, pos: Int) {
        try {
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, pos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, pos)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
            if (!ok) android.util.Log.d("WE-TTS", "SET_SELECTION rejected (non-fatal)")
        } catch (e: Exception) {
            android.util.Log.d("WE-TTS", "SET_SELECTION threw (non-fatal)")
        }
    }

    /**
     * The clipboard/paste delivery paths (social-media @mention fields, document apps) insert the RAW
     * segment at the caret and share none of the anchor's spacing, so multi-segment dictation melts
     * ('Hello therehow'). Pad the segment against the field's existing text under the shared [TextJoin]
     * policy BEFORE it goes on the clipboard, so a pasted segment never touches the previous one. The
     * pad is computed against the field END (append) because these exact fields report an unreliable
     * caret — the append boundary is the one that actually melts on repeated dictation. Hint text is
     * stripped so a placeholder never counts as existing content.
     */
    private fun clipboardPayloadFor(text: String, node: AccessibilityNodeInfo?): String {
        val raw = node?.text?.toString() ?: return text
        val hint = node.hintText?.toString() ?: ""
        val existing = if (raw.isNotEmpty() && raw.equals(hint, ignoreCase = true)) "" else raw
        // Append semantics: pad against the field END (right side empty). These fields report an
        // unreliable caret, so the end boundary is the one that reliably melts on repeated dictation.
        return TextJoin.pad(existing, text, "")
    }

    /**
     * The unified SET_TEXT injection: read the field, plan the write through the session anchor
     * (or a transient anchor started from the live caret when there is no session), SET_TEXT the
     * full string, then pin the caret. The anchor is committed ONLY on a successful write, so a
     * failed SET_TEXT leaves the machine untouched and the clipboard fallback runs clean.
     *
     * The old live-selection read and the delete branch are GONE: the only deletion possible is the
     * anchor's one-shot start-selection replace.
     */
    private fun setTextViaAnchor(targetNode: AccessibilityNodeInfo, text: String): InjectionResult {
        val rawText = targetNode.text?.toString() ?: ""
        val hintText = targetNode.hintText?.toString() ?: ""
        val isHintText = rawText.isNotEmpty() && (rawText == hintText || rawText.equals(hintText, ignoreCase = true))
        val currentText = if (isHintText) "" else rawText

        val anchor = sessionAnchor ?: InjectionAnchor().apply {
            start(currentText, targetNode.textSelectionStart, targetNode.textSelectionEnd)
        }
        val placement = anchor.plan(currentText, text)

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, placement.newFieldText)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        return if (success) {
            anchor.commit(placement)           // adopt only after the write lands
            pinCursor(targetNode, placement.newSelection)
            InjectionResult.SUCCESS
        } else {
            if (injectViaClipboard(text)) InjectionResult.SUCCESS else InjectionResult.CLIPBOARD_ONLY
        }
    }

    /**
     * Inject text with detailed result for better user feedback
     */
    fun injectTextWithResultInternal(text: String): InjectionResult {
        // Resolve FIRST: a dead session node clears the session (node AND package) so the
        // strategy checks below classify by the live target, not the dead session's app.
        val resolvedTarget = resolveInjectionTarget()

        // For document apps, use clipboard approach
        if (injectionTargetIsDocumentApp()) {
            return try {
                // Pad against the target field so a pasted segment never melts against existing content.
                val targetNode = resolveInjectionTarget()
                val refreshed = targetNode?.refresh() == true
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Whisper", if (refreshed) clipboardPayloadFor(text, targetNode) else text),
                )

                // Try paste methods
                var pasteWorked = false

                if (refreshed) {
                    pasteWorked = targetNode!!.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }

                if (!pasteWorked) {
                    val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focusedNode != null) {
                        pasteWorked = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                }

                if (!pasteWorked) {
                    pasteWorked = tryPasteOnAnyNode(rootInActiveWindow)
                }

                // For document apps, paste rarely works - return CLIPBOARD_ONLY
                if (pasteWorked) InjectionResult.SUCCESS else InjectionResult.CLIPBOARD_ONLY
            } catch (e: Exception) {
                e.printStackTrace()
                // At minimum, try to get it to clipboard
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                    InjectionResult.CLIPBOARD_ONLY
                } catch (e2: Exception) {
                    InjectionResult.FAILED
                }
            }
        }

        // For social media apps, use paste to preserve @mentions
        if (injectionTargetIsSocialMediaApp()) {
            return injectForSocialMediaWithResult(text)
        }

        // Standard apps - try direct injection
        val targetNode = resolvedTarget

        if (targetNode == null || !targetNode.refresh()) {
            // No target - try clipboard
            return if (injectViaClipboard(text)) InjectionResult.SUCCESS else {
                // Clipboard fallback
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                    InjectionResult.CLIPBOARD_ONLY
                } catch (e: Exception) {
                    InjectionResult.FAILED
                }
            }
        }

        // Check if node supports SET_TEXT
        val supportsSetText = targetNode.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }

        if (!supportsSetText) {
            return if (injectViaClipboard(text)) InjectionResult.SUCCESS else {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                    InjectionResult.CLIPBOARD_ONLY
                } catch (e: Exception) {
                    InjectionResult.FAILED
                }
            }
        }

        return try {
            setTextViaAnchor(targetNode, text)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                InjectionResult.CLIPBOARD_ONLY
            } catch (e2: Exception) {
                InjectionResult.FAILED
            }
        }
    }

    /**
     * Result of text injection attempt
     */
    enum class InjectionResult {
        SUCCESS,           // Text was directly injected into field
        CLIPBOARD_ONLY,    // Text is in clipboard, needs manual paste
        FAILED             // Complete failure
    }

    companion object {
        @Volatile private var instance: WhisperAccessibilityService? = null
        @Volatile private var focusListener: OnTextFieldFocusListener? = null
        @Volatile private var clipboardListener: OnClipboardChangedListener? = null

        fun setClipboardListener(listener: OnClipboardChangedListener?) {
            clipboardListener = listener
        }

        fun isEnabled(): Boolean = instance != null

        /** Re-read the dictation-first preference (Settings toggle just changed it). */
        fun applyKeyboardPreference() {
            instance?.applyKeyboardShowMode()
        }

        /**
         * The bubble's keyboard lobe: toggle the summoned system keyboard for the current field.
         * Returns the NEW summoned state (true = keyboard now allowed to show).
         */
        fun toggleSummonedKeyboard(): Boolean {
            val svc = instance ?: return false
            svc.keyboardSummoned = !svc.keyboardSummoned
            svc.lastSummonToggleMs = System.currentTimeMillis()
            svc.applyKeyboardShowMode()
            if (svc.keyboardSummoned) {
                // Lifting suppression (SHOW_MODE_AUTO) does not SHOW an IME that has settled into
                // hidden — the field must re-request it. A click on the focused field is the one
                // cross-app way an accessibility service has to ask; without it only the first
                // toggle ever worked (the initial mode flip triggers a re-evaluation, later ones
                // do not — owner repro 2026-08-01). The node is refresh()ed first (a stale node
                // no-ops the click), the anatomy is logged, and if the IME has not risen shortly
                // the request is retried once — the lobe tap's own window churn can swallow the
                // first click.
                val focused = svc.findFocusedEditText()
                val field = focused ?: svc.lastFocusedEditText?.takeIf { runCatching { it.refresh() }.getOrDefault(false) }
                val clicked = field?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                android.util.Log.i(
                    "WE-DIAG",
                    "kbd summon: field=" + (if (focused != null) "focused" else if (field != null) "last" else "none") +
                        " clickOk=" + clicked,
                )
                svc.serviceScope.launch {
                    delay(600)
                    if (svc.keyboardSummoned && !svc.imeWasVisible) {
                        val retry = (svc.findFocusedEditText() ?: svc.lastFocusedEditText)
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                        android.util.Log.i("WE-DIAG", "kbd summon retry: clickOk=" + retry)
                    }
                }
            } else {
                android.util.Log.i("WE-DIAG", "kbd toggle -> hidden")
            }
            return svc.keyboardSummoned
        }
        fun getInstance(): WhisperAccessibilityService? = instance

        fun setFocusListener(listener: OnTextFieldFocusListener?) {
            focusListener = listener
        }

        fun getLastFieldRect(): Rect? = instance?.lastFieldRect

        /** Package owning the field that most recently gained focus (null if none). */
        fun lastFocusedFieldPackage(): String? =
            instance?.lastFocusedEditText?.packageName?.toString()

        fun hasActiveFocusedField(): Boolean {
            val node = instance?.lastFocusedEditText ?: return false
            return try {
                node.refresh() && node.isFocused
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Is there a REAL input target right now: the keyboard is up, or a focused editable
         * exists in ANY window. The session-routing gate (typing sessions vs preview window) —
         * deliberately more generous than the stale-cached-node check.
         */
        fun hasLiveInputTarget(): Boolean {
            val svc = instance ?: return false
            if (svc.isImeVisible()) return true
            if (hasActiveFocusedField()) return true
            return runCatching { svc.findFocusedEditText() != null }.getOrDefault(false)
        }

        /**
         * Bind this dictation session to the field focused RIGHT NOW. Call at record start;
         * all segments delivered until [endInjectionSession] target that field even if focus
         * moves (app switch, field switch) before transcription completes.
         */
        fun beginInjectionSession() {
            instance?.beginInjectionSessionInternal()
        }

        /** Release the record-start binding; delivery reverts to the currently focused field. */
        fun endInjectionSession() {
            instance?.endInjectionSessionInternal()
        }

        /**
         * Inject text with detailed result
         */
        fun injectTextWithResult(text: String): InjectionResult {
            return instance?.injectTextWithResultInternal(text) ?: InjectionResult.FAILED
        }

        /**
         * Check if current app is a document app that may need manual paste
         */
        fun isCurrentAppDocumentApp(): Boolean {
            return instance?.isDocumentApp() ?: false
        }
    }
}
