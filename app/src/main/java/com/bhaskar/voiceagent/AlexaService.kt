package com.bhaskar.voiceagent

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlexaService : AccessibilityService() {

    companion object {
        var instance: AlexaService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun lockScreen() {
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun clickFirstVideoResult(delayMs: Long = 4500) {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val target = findVideoNode(root)
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, delayMs)
    }

    private fun findVideoNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val keys = listOf("play video", "वीडियो चलाएं", "minutes", "मिनट", "views", "व्यू")
        if (node.isClickable && desc.isNotEmpty() && keys.any { desc.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findVideoNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun toggleSwitchWanted(delayMs: Long = 1500) {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val sw = findSwitch(root)
            sw?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, delayMs)
    }

    private fun findSwitch(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isClickable &&
            (cls.contains("Switch") || cls.contains("Toggle") || cls.contains("CheckBox"))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findSwitch(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
