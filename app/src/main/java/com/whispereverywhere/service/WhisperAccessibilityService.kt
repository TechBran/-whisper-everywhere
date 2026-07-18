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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT

            notificationTimeout = 30 // Faster response
        }
        serviceInfo = info
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
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window changed - be more careful about unfocusing
                val pkg = event.packageName?.toString()

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
    }

    private fun endInjectionSessionInternal() {
        sessionTargetEditText = null
        sessionTargetPackage = null
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
            // Node died: drop the WHOLE session (node AND package) so strategy classification
            // follows the fallback target's app, not the dead session's.
            sessionTargetEditText = null
            sessionTargetPackage = null
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
        focusListener?.onTextFieldUnfocused()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
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
     * Find focused editable field (for text injection)
     */
    private fun findFocusedEditText(): AccessibilityNodeInfo? {
        val rootNode = rootInActiveWindow ?: return null

        // Method 1: Check input focus
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && isEditableTextField(focusedNode)) {
            lastFocusedEditText = focusedNode
            return focusedNode
        }

        // Method 2: Search for any focused editable field
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
     * Inject text into the currently focused text field
     */
    fun injectTextToFocusedField(text: String): Boolean {
        // Resolve FIRST: a dead session node clears the session (node AND package) so the
        // strategy checks below classify by the live target, not the dead session's app.
        val resolvedTarget = resolveInjectionTarget()

        // For document apps, always use clipboard + paste approach
        if (injectionTargetIsDocumentApp()) {
            return injectViaClipboardForDocumentApp(text)
        }

        // For social media apps, use paste to preserve @mentions and other formatted content
        if (injectionTargetIsSocialMediaApp()) {
            return injectViaClipboardPreservingContent(text)
        }

        val targetNode = resolvedTarget

        if (targetNode == null || !targetNode.refresh()) {
            return injectViaClipboard(text)
        }

        // Check if node supports SET_TEXT
        val supportsSetText = targetNode.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SET_TEXT
        }

        if (!supportsSetText) {
            return injectViaClipboard(text)
        }

        return try {
            val rawText = targetNode.text?.toString() ?: ""
            val hintText = targetNode.hintText?.toString() ?: ""

            val isHintText = rawText.isNotEmpty() && (
                rawText == hintText || rawText.equals(hintText, ignoreCase = true)
            )

            val currentText = if (isHintText) "" else rawText
            val cursorPosition = if (targetNode.textSelectionStart >= 0 && !isHintText) {
                targetNode.textSelectionStart
            } else {
                currentText.length
            }

            val textToInject = formatTextForInjection(text, currentText, cursorPosition)

            val newText = StringBuilder(currentText).apply {
                val selStart = targetNode.textSelectionStart
                val selEnd = targetNode.textSelectionEnd

                when {
                    !isHintText && selStart >= 0 && selEnd > selStart -> {
                        delete(selStart, selEnd)
                        insert(selStart, textToInject)
                    }
                    cursorPosition in 0..length -> insert(cursorPosition, textToInject)
                    else -> append(textToInject)
                }
            }.toString()

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }

            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (!success) injectViaClipboard(text) else true
        } catch (e: Exception) {
            e.printStackTrace()
            injectViaClipboard(text)
        }
    }

    /**
     * Inject text for social media apps, preserving existing @mentions.
     * For Facebook with mentions, taps at end of field before pasting.
     */
    private fun injectViaClipboardPreservingContent(text: String): Boolean {
        val targetNode = resolveInjectionTarget()

        // Always copy to clipboard first
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
        } catch (e: Exception) {
            return false
        }

        if (targetNode?.refresh() != true) {
            return true // Text in clipboard, no target to paste to
        }

        // Check if field has existing content (might be a mention)
        val existingText = targetNode.text?.toString() ?: ""

        if (existingText.isNotEmpty() && isFacebookApp()) {
            // Facebook with existing content - use gesture to tap at end of field, then paste
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)

            // Tap near the right side of the field to position cursor at end
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
                        // After tap completes, paste
                        serviceScope.launch {
                            delay(100) // Small delay to let cursor position update
                            targetNode.refresh()
                            targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }
                    }
                }, null)

                return true
            }
        }

        // No mention or not Facebook - safe to paste directly
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return true
        }

        // Paste failed but text is in clipboard
        return true
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

        // Always copy to clipboard first
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
        } catch (e: Exception) {
            return InjectionResult.FAILED
        }

        if (targetNode?.refresh() != true) {
            return InjectionResult.CLIPBOARD_ONLY
        }

        // Check if field has existing content (might be a mention)
        val existingText = targetNode.text?.toString() ?: ""

        if (existingText.isNotEmpty() && isFacebookApp()) {
            // Facebook with existing content - use gesture to tap at end of field, then paste
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)

            // Tap near the right side of the field to position cursor at end
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
                        // After tap completes, paste
                        serviceScope.launch {
                            delay(100) // Small delay to let cursor position update
                            targetNode.refresh()
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

        // No mention or not Facebook - safe to paste directly
        if (targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
            return InjectionResult.SUCCESS
        }

        // Paste failed but text is in clipboard
        return InjectionResult.CLIPBOARD_ONLY
    }

    /**
     * Special injection for document apps - uses clipboard and gesture-based paste
     */
    private fun injectViaClipboardForDocumentApp(text: String): Boolean {
        return try {
            // Step 1: Copy text to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))

            // Step 2: Try multiple paste methods
            var success = false

            // Method 1: Try ACTION_PASTE on the target node
            val targetNode = resolveInjectionTarget()
            if (targetNode?.refresh() == true) {
                success = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }

            // Method 2: Try ACTION_PASTE on focused input
            if (!success) {
                val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }

            // Method 3: Try ACTION_PASTE on accessibility focused node
            if (!success) {
                val accessibilityFocused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                if (accessibilityFocused != null) {
                    success = accessibilityFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }
            }

            // Method 4: Search for any node that supports paste and try it
            if (!success) {
                success = tryPasteOnAnyNode(rootInActiveWindow)
            }

            // If all paste attempts failed, at least the text is in clipboard
            // Return true so user knows to manually paste
            true
        } catch (e: Exception) {
            e.printStackTrace()
            // Even on error, try to at least get it to clipboard
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))
                true
            } catch (e2: Exception) {
                false
            }
        }
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
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))

            var success = false

            // Try paste on target node
            val targetNode = resolveInjectionTarget()
            if (targetNode?.refresh() == true) {
                success = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
     * Helper to prepare text for injection, adding spaces as needed
     */
    private fun formatTextForInjection(textToInject: String, existingText: String, cursorPosition: Int): String {
        if (existingText.isEmpty() || textToInject.isEmpty()) {
            return textToInject
        }
        
        // If cursor is at the end or valid position
        val actualPos = if (cursorPosition in 0..existingText.length) cursorPosition else existingText.length
        
        if (actualPos == 0) return textToInject
        
        val prevChar = existingText[actualPos - 1]
        
        // If the previous character is not whitespace and the new text doesn't start with whitespace
        if (!prevChar.isWhitespace() && !textToInject.first().isWhitespace()) {
            return " $textToInject"
        }
        
        return textToInject
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
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))

                // Try paste methods
                var pasteWorked = false

                val targetNode = resolveInjectionTarget()
                if (targetNode?.refresh() == true) {
                    pasteWorked = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
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
            val rawText = targetNode.text?.toString() ?: ""
            val hintText = targetNode.hintText?.toString() ?: ""

            val isHintText = rawText.isNotEmpty() && (
                rawText == hintText || rawText.equals(hintText, ignoreCase = true)
            )

            val currentText = if (isHintText) "" else rawText
            val cursorPosition = if (targetNode.textSelectionStart >= 0 && !isHintText) {
                targetNode.textSelectionStart
            } else {
                currentText.length
            }

            val textToInject = formatTextForInjection(text, currentText, cursorPosition)

            val newText = StringBuilder(currentText).apply {
                val selStart = targetNode.textSelectionStart
                val selEnd = targetNode.textSelectionEnd

                when {
                    !isHintText && selStart >= 0 && selEnd > selStart -> {
                        delete(selStart, selEnd)
                        insert(selStart, textToInject)
                    }
                    cursorPosition in 0..length -> insert(cursorPosition, textToInject)
                    else -> append(textToInject)
                }
            }.toString()

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }

            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                InjectionResult.SUCCESS
            } else {
                if (injectViaClipboard(text)) InjectionResult.SUCCESS else InjectionResult.CLIPBOARD_ONLY
            }
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

        fun isEnabled(): Boolean = instance != null
        fun getInstance(): WhisperAccessibilityService? = instance

        fun setFocusListener(listener: OnTextFieldFocusListener?) {
            focusListener = listener
        }

        fun getLastFieldRect(): Rect? = instance?.lastFieldRect

        fun hasActiveFocusedField(): Boolean {
            val node = instance?.lastFocusedEditText ?: return false
            return try {
                node.refresh() && node.isFocused
            } catch (e: Exception) {
                false
            }
        }

        fun injectText(text: String): Boolean {
            return instance?.injectTextToFocusedField(text) ?: false
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
