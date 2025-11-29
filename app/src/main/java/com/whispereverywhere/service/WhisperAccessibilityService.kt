package com.whispereverywhere.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
                        notifyTextFieldFocused(source)
                    } else {
                        // Maybe clicked on a container - search children for editable field
                        val editableChild = findEditableChild(source)
                        if (editableChild != null) {
                            lastFocusedEditText = editableChild
                            notifyTextFieldFocused(editableChild)
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Text activity - this confirms we have an active input
                val source = event.source
                if (source != null && isEditableTextField(source)) {
                    if (lastFocusedEditText == null || !isSameNode(lastFocusedEditText, source)) {
                        lastFocusedEditText = source
                        notifyTextFieldFocused(source)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Window changed - check if we lost focus
                serviceScope.launch {
                    delay(200)
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

    private fun handleFocusOrClick(source: AccessibilityNodeInfo?) {
        source ?: return

        if (isEditableTextField(source)) {
            lastFocusedEditText = source
            notifyTextFieldFocused(source)
        } else {
            // Check if focus moved away from our tracked field
            serviceScope.launch {
                delay(100)
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
        val targetNode = findFocusedEditText() ?: lastFocusedEditText

        if (targetNode == null || !targetNode.refresh()) {
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

            val newText = StringBuilder(currentText).apply {
                val selStart = targetNode.textSelectionStart
                val selEnd = targetNode.textSelectionEnd

                when {
                    !isHintText && selStart >= 0 && selEnd > selStart -> {
                        delete(selStart, selEnd)
                        insert(selStart, text)
                    }
                    cursorPosition in 0..length -> insert(cursorPosition, text)
                    else -> append(text)
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

    private fun injectViaClipboard(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Whisper", text))

            val targetNode = lastFocusedEditText
            if (targetNode?.refresh() == true) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } else {
                rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
    }
}
