package com.bhaskar.voiceagent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AlexaService : AccessibilityService() {

    companion object {
        var instance: AlexaService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun lockScreen(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    fun goHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun clickFirstVideoResult(): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = findFirstClickableWithDescription(root)
        if (found != null) {
            return found.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        return false
    }

    private fun findFirstClickableWithDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && desc.length > 15 &&
            (desc.contains("Play video", true) || desc.contains("वीडियो चलाएं") ||
                desc.contains("minutes", true) || desc.contains("मिनट") ||
                desc.contains("views", true) || desc.contains("व्यू"))) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstClickableWithDescription(child)
            if (result != null) return result
        }
        return null
    }

    fun toggleSwitchWanted(wantOn: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val switches = ArrayList<AccessibilityNodeInfo>()
        collectSwitches(root, switches)
        for (sw in switches) {
            if (sw.isChecked != wantOn) {
                var target: AccessibilityNodeInfo? = sw
                while (target != null && !target.isClickable) {
                    target = target.parent
                }
                if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            } else {
                return true
            }
        }
        return false
    }

    private fun collectSwitches(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        val cls = node.className?.toString() ?: ""
        if (node.isCheckable && (cls.contains("Switch") || cls.contains("Toggle") || cls.contains("CheckBox"))) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSwitches(child, out)
        }
    }
}
