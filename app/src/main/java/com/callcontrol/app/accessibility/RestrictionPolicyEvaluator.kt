package com.callcontrol.app.accessibility

import com.callcontrol.app.state.AppAccessStorage
import com.callcontrol.app.state.ManagedApp

/**
 * Result of evaluating whether a foreground package transition should be restricted.
 */
sealed class RestrictionDecision {
    data class Allow(val reason: String) : RestrictionDecision()

    data class CooldownSkipped(
        val app: ManagedApp,
        val packageName: String
    ) : RestrictionDecision()

    data class Restrict(
        val app: ManagedApp,
        val packageName: String
    ) : RestrictionDecision()
}

/**
 * Pure, testable policy evaluator used by [CallControlAccessibilityService] to decide whether
 * a foreground window change should trigger the restriction screen.
 *
 * Safeguards:
 * - Never restricts CallControl itself, Android Settings, Permission Controller, or System UI.
 * - Never restricts any app when the CallControl session is INACTIVE.
 * - Only restricts configured [ManagedApp] packages whose effective policy is LOCKED.
 * - Uses a cooldown window to prevent rapid duplicate activity launches or infinite loops.
 */
class RestrictionPolicyEvaluator(
    private val storage: AppAccessStorage,
    private val ownPackageName: String = DEFAULT_OWN_PACKAGE,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    private var lastRestrictedPackage: String? = null
    private var lastRestrictedTimestampMs: Long = -1L

    fun evaluateForegroundPackage(
        packageName: String?,
        currentTimeMs: Long
    ): RestrictionDecision {
        if (packageName.isNullOrBlank()) {
            return RestrictionDecision.Allow("Empty package name")
        }

        val normalizedPkg = packageName.trim()

        // 1. Always allow CallControl itself and critical system packages
        if (normalizedPkg == ownPackageName || normalizedPkg in EXEMPT_SYSTEM_PACKAGES) {
            return RestrictionDecision.Allow("Exempt package: $normalizedPkg")
        }

        // 2. Only enforce restrictions while a CallControl session is ACTIVE
        if (!storage.isSessionActive()) {
            resetCooldown()
            return RestrictionDecision.Allow("Session is inactive")
        }

        // 3. Identify whether the foreground package belongs to a managed target app
        val managedApp = ManagedApp.fromPackageName(normalizedPkg)
        if (managedApp == null) {
            // User navigated to a normal/unmanaged app (e.g., launcher); clear cooldown
            lastRestrictedPackage = null
            return RestrictionDecision.Allow("Unmanaged package: $normalizedPkg")
        }

        // 4. Check effective lock state for the current active session
        val isLocked = storage.getEffectiveLocked(managedApp)
        if (!isLocked) {
            if (lastRestrictedPackage == normalizedPkg) {
                resetCooldown()
            }
            return RestrictionDecision.Allow("${managedApp.displayName} is ALLOWED")
        }

        // 5. Cooldown check to prevent infinite activity-launch loops
        val elapsed = currentTimeMs - lastRestrictedTimestampMs
        if (
            lastRestrictedPackage == normalizedPkg &&
            lastRestrictedTimestampMs >= 0L &&
            elapsed in 0 until cooldownMs
        ) {
            return RestrictionDecision.CooldownSkipped(
                app = managedApp,
                packageName = normalizedPkg
            )
        }

        lastRestrictedPackage = normalizedPkg
        lastRestrictedTimestampMs = currentTimeMs
        return RestrictionDecision.Restrict(
            app = managedApp,
            packageName = normalizedPkg
        )
    }

    fun resetCooldown() {
        lastRestrictedPackage = null
        lastRestrictedTimestampMs = -1L
    }

    companion object {
        const val DEFAULT_OWN_PACKAGE = "com.callcontrol.app"
        const val DEFAULT_COOLDOWN_MS = 1500L

        val EXEMPT_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )
    }
}
