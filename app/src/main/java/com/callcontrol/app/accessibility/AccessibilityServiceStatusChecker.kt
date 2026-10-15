package com.callcontrol.app.accessibility

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.callcontrol.app.state.EnforcementStatus

/**
 * Checks whether [CallControlAccessibilityService] is actually enabled by the user in
 * Android Accessibility Settings.
 *
 * Never silently enables the service or fabricates an enabled status.
 */
object AccessibilityServiceStatusChecker {

    fun isServiceEnabled(context: Context): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            val componentName = ComponentName(
                context.packageName,
                CallControlAccessibilityService::class.java.name
            )
            isComponentEnabledInSetting(
                packageName = componentName.packageName,
                serviceClassName = componentName.className,
                accessibilityEnabled = accessibilityEnabled == 1,
                enabledServicesSetting = enabledServicesSetting
            ) || CallControlAccessibilityService.isServiceConnected
        } catch (_: Exception) {
            CallControlAccessibilityService.isServiceConnected
        }
    }

    fun currentEnforcementStatus(context: Context): EnforcementStatus {
        return if (isServiceEnabled(context)) {
            EnforcementStatus.OS_ENFORCED
        } else {
            EnforcementStatus.SIMULATED_POLICY_ONLY
        }
    }

    /**
     * Pure helper for parsing `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` colon-delimited entries.
     * Supports both full class names (`com.callcontrol.app/com.callcontrol.app.accessibility.CallControlAccessibilityService`)
     * and shorthand class names (`com.callcontrol.app/.accessibility.CallControlAccessibilityService`).
     */
    fun isComponentEnabledInSetting(
        packageName: String,
        serviceClassName: String,
        accessibilityEnabled: Boolean,
        enabledServicesSetting: String?
    ): Boolean {
        if (!accessibilityEnabled || enabledServicesSetting.isNullOrBlank()) {
            return false
        }

        val fullExpected = "$packageName/$serviceClassName"
        val relativeClass = if (serviceClassName.startsWith("$packageName.")) {
            serviceClassName.removePrefix(packageName)
        } else {
            serviceClassName
        }
        val shortExpected = "$packageName/$relativeClass"

        return enabledServicesSetting
            .split(':')
            .map { it.trim() }
            .any { entry ->
                entry.equals(fullExpected, ignoreCase = true) ||
                    entry.equals(shortExpected, ignoreCase = true)
            }
    }
}
