package com.callcontrol.app.state

/**
 * Represents the current state of the CallControl session.
 * Phase 1-3 uses a user-controlled session state machine.
 */
enum class CallSessionStatus(
    val titleText: String,
    val subtitleText: String,
    val badgeText: String,
    val actionButtonText: String
) {
    INACTIVE(
        titleText = "No active call",
        subtitleText = "CallControl session inactive",
        badgeText = "IDLE",
        actionButtonText = "START SESSION"
    ),
    ACTIVE(
        titleText = "Session active",
        subtitleText = "Session active • App access policy applied",
        badgeText = "ACTIVE",
        actionButtonText = "END SESSION"
    )
}

/**
 * Explicitly separates UI/policy state from actual Android OS restriction enforcement.
 */
enum class EnforcementStatus(
    val badgeText: String,
    val description: String
) {
    SIMULATED_POLICY_ONLY(
        badgeText = "NOT ENABLED",
        description = "Policy state only • Accessibility restriction service is not enabled"
    ),
    OS_ENFORCED(
        badgeText = "ENABLED",
        description = "Accessibility service enabled • Enforces LOCKED apps during active session"
    )
}

/**
 * Identifier, metadata, and target Android package names for each managed application.
 */
enum class ManagedApp(
    val storageKey: String,
    val displayName: String,
    val shortName: String,
    val categoryDescription: String,
    val defaultLocked: Boolean,
    val targetPackages: Set<String>,
    val allowDuringActiveSession: Boolean = false
) {
    INSTAGRAM(
        storageKey = "instagram",
        displayName = "Instagram",
        shortName = "IG",
        categoryDescription = "Social media",
        defaultLocked = true,
        targetPackages = setOf(
            "com.instagram.android",
            "com.instagram.lite"
        )
    ),
    YOUTUBE(
        storageKey = "youtube",
        displayName = "YouTube",
        shortName = "YT",
        categoryDescription = "Video platform",
        defaultLocked = true,
        targetPackages = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.mango"
        )
    ),
    WHATSAPP(
        storageKey = "whatsapp",
        displayName = "WhatsApp",
        shortName = "WA",
        categoryDescription = "Messaging",
        defaultLocked = false,
        targetPackages = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        ),
        allowDuringActiveSession = true
    ),
    DOCUMENTS(
        storageKey = "documents",
        displayName = "Documents",
        shortName = "DOC",
        categoryDescription = "Files & documents",
        defaultLocked = false,
        targetPackages = setOf(
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.google.android.apps.nbu.files"
        )
    );

    companion object {
        fun fromKey(key: String): ManagedApp? = entries.find { it.storageKey == key }

        fun fromPackageName(packageName: String?): ManagedApp? {
            if (packageName.isNullOrBlank()) return null
            val normalized = packageName.trim()
            return entries.find { normalized in it.targetPackages }
        }
    }
}

/**
 * Immutable snapshot of a single managed app's access state.
 * Centralizes status label and action button text so UI components cannot become inconsistent.
 */
data class AppAccessItemState(
    val app: ManagedApp,
    val savedLocked: Boolean,
    val effectiveLocked: Boolean,
    val hasSessionOverride: Boolean,
    val enforcementStatus: EnforcementStatus = EnforcementStatus.SIMULATED_POLICY_ONLY
) {
    val statusLabel: String
        get() = if (effectiveLocked) "LOCKED" else "ALLOWED"

    val actionButtonLabel: String
        get() = if (effectiveLocked) "ALLOW" else "LOCK"
}

/**
 * Complete dashboard state snapshot consumed by the UI.
 */
data class DashboardState(
    val sessionStatus: CallSessionStatus,
    val appStates: Map<ManagedApp, AppAccessItemState>,
    val feedbackMessage: String?,
    val enforcementStatus: EnforcementStatus = EnforcementStatus.SIMULATED_POLICY_ONLY
) {
    fun stateFor(app: ManagedApp): AppAccessItemState =
        appStates.getValue(app)
}

/**
 * Result of attempting a session state transition (START or END).
 */
sealed class SessionTransitionResult {
    data class Success(
        val newStatus: CallSessionStatus,
        val message: String
    ) : SessionTransitionResult()

    data class InvalidTransition(
        val currentStatus: CallSessionStatus,
        val reason: String
    ) : SessionTransitionResult()
}
