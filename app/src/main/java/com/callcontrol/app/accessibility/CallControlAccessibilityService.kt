package com.callcontrol.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.callcontrol.app.RestrictedAppActivity
import com.callcontrol.app.state.SharedPreferencesAppAccessStorage

/**
 * User-consented [AccessibilityService] that monitors foreground window changes
 * (`TYPE_WINDOW_STATE_CHANGED`) to enforce CallControl's session app-access policy.
 *
 * Privacy & Security Guarantees:
 * - Only processes `TYPE_WINDOW_STATE_CHANGED` events (package transitions).
 * - Does NOT inspect window hierarchy content, text fields, keystrokes, or personal data.
 * - Only triggers [RestrictedAppActivity] when a CallControl session is ACTIVE and the
 *   detected foreground package belongs to a [com.callcontrol.app.state.ManagedApp] whose
 *   effective policy is LOCKED.
 */
class CallControlAccessibilityService : AccessibilityService() {

    private lateinit var policyEvaluator: RestrictionPolicyEvaluator

    override fun onCreate() {
        super.onCreate()
        val storage = SharedPreferencesAppAccessStorage(this)
        policyEvaluator = RestrictionPolicyEvaluator(
            storage = storage,
            ownPackageName = packageName
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        if (::policyEvaluator.isInitialized) {
            policyEvaluator.resetCooldown()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // Ignore non-full-screen overlays/keyboards/toasts when possible
        val detectedPackage = event.packageName?.toString() ?: return

        if (!::policyEvaluator.isInitialized) {
            policyEvaluator = RestrictionPolicyEvaluator(
                storage = SharedPreferencesAppAccessStorage(this),
                ownPackageName = packageName
            )
        }

        val decision = policyEvaluator.evaluateForegroundPackage(
            packageName = detectedPackage,
            currentTimeMs = SystemClock.elapsedRealtime()
        )

        if (decision is RestrictionDecision.Restrict) {
            launchRestrictionScreen(
                appStorageKey = decision.app.storageKey,
                restrictedPackageName = decision.packageName
            )
        }
    }

    override fun onInterrupt() {
        // Required override; no background polling or state to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        if (::policyEvaluator.isInitialized) {
            policyEvaluator.resetCooldown()
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isServiceConnected = false
        super.onDestroy()
    }

    private fun launchRestrictionScreen(
        appStorageKey: String,
        restrictedPackageName: String
    ) {
        try {
            val intent = RestrictedAppActivity.createIntent(
                context = this,
                appKey = appStorageKey,
                restrictedPackageName = restrictedPackageName
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            // Fail safely without crashing the AccessibilityService process.
        }
    }

    companion object {
        @Volatile
        var isServiceConnected: Boolean = false
            private set
    }
}
