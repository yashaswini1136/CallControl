package com.callcontrol.app.state

/**
 * Centralized state manager for CallControl's session state machine and app-access policies.
 *
 * Ensures that:
 * 1. Session transitions (INACTIVE <-> ACTIVE) are validated and duplicate transitions are rejected.
 * 2. App-access UI labels, button text, and colors derive from a single source of truth.
 * 3. User-configured preferences are persisted across restarts when the session is inactive.
 * 4. Starting a session applies the session policy (keeping WhatsApp allowed and locking configured apps),
 *    while mid-session toggles act as temporary session overrides.
 * 5. Ending a session restores the user's saved preferences without overwriting them.
 */
class CallControlStateManager(
    private val storage: AppAccessStorage,
    private val enforcementStatusProvider: () -> EnforcementStatus = {
        EnforcementStatus.SIMULATED_POLICY_ONLY
    }
) {
    private val listeners = mutableSetOf<(DashboardState) -> Unit>()

    var currentState: DashboardState = buildState(feedbackMessage = null)
        private set

    fun addListener(listener: (DashboardState) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeListener(listener: (DashboardState) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Starts a call session if currently inactive.
     * Prevents duplicate session starts.
     */
    fun startSession(): SessionTransitionResult {
        if (currentState.sessionStatus == CallSessionStatus.ACTIVE) {
            val reason = "Session is already active."
            updateState(feedbackMessage = reason)
            return SessionTransitionResult.InvalidTransition(
                currentStatus = CallSessionStatus.ACTIVE,
                reason = reason
            )
        }

        storage.setSessionActive(true)
        for (app in ManagedApp.entries) {
            val savedLocked = storage.getSavedLocked(app)
            val sessionLocked = if (app.allowDuringActiveSession) {
                false
            } else {
                savedLocked
            }
            storage.setSessionOverrideLocked(app, sessionLocked)
        }

        val enforcement = enforcementStatusProvider()
        val message = if (enforcement == EnforcementStatus.OS_ENFORCED) {
            "Call session started. Accessibility restriction policy active."
        } else {
            "Call session started. Policy applied (enable Accessibility to enforce)."
        }
        updateState(feedbackMessage = message)
        return SessionTransitionResult.Success(
            newStatus = CallSessionStatus.ACTIVE,
            message = message
        )
    }

    /**
     * Ends the active call session and restores saved user preferences.
     * Prevents ending a session that is already inactive.
     */
    fun endSession(): SessionTransitionResult {
        if (currentState.sessionStatus == CallSessionStatus.INACTIVE) {
            val reason = "No active session to end."
            updateState(feedbackMessage = reason)
            return SessionTransitionResult.InvalidTransition(
                currentStatus = CallSessionStatus.INACTIVE,
                reason = reason
            )
        }

        storage.setSessionActive(false)
        storage.clearSessionOverrides()

        val message = "Call session ended. Saved app access preferences restored."
        updateState(feedbackMessage = message)
        return SessionTransitionResult.Success(
            newStatus = CallSessionStatus.INACTIVE,
            message = message
        )
    }

    /**
     * Toggles the LOCK/ALLOW state of the specified [app].
     * - When INACTIVE: updates and persists the user's saved baseline preference.
     * - When ACTIVE: updates the active session override without overwriting saved preferences.
     */
    fun toggleAppAccess(app: ManagedApp): AppAccessItemState {
        val currentItem = currentState.stateFor(app)
        val newLocked = !currentItem.effectiveLocked
        val newStateLabel = if (newLocked) "LOCKED" else "ALLOWED"
        val enforcement = enforcementStatusProvider()
        val modeNote = if (enforcement == EnforcementStatus.OS_ENFORCED) {
            "enforced"
        } else {
            "policy only"
        }

        val message = if (currentState.sessionStatus == CallSessionStatus.ACTIVE) {
            storage.setSessionOverrideLocked(app, newLocked)
            "${app.displayName} set to $newStateLabel for active session ($modeNote)."
        } else {
            storage.setSavedLocked(app, newLocked)
            "${app.displayName} preference saved as $newStateLabel ($modeNote)."
        }

        updateState(feedbackMessage = message)
        return currentState.stateFor(app)
    }

    /**
     * Sets an explicit session override policy for [app] while a call session is ACTIVE.
     * Does NOT overwrite saved baseline preferences.
     * Returns true if applied, or false if rejected because the session is inactive.
     */
    fun setSessionAppOverride(app: ManagedApp, locked: Boolean): Boolean {
        if (currentState.sessionStatus != CallSessionStatus.ACTIVE) {
            return false
        }
        storage.setSessionOverrideLocked(app, locked)
        val newStateLabel = if (locked) "LOCKED" else "ALLOWED"
        val enforcement = enforcementStatusProvider()
        val modeNote = if (enforcement == EnforcementStatus.OS_ENFORCED) {
            "enforced"
        } else {
            "policy only"
        }
        val message = "${app.displayName} set to $newStateLabel for active session ($modeNote)."
        updateState(feedbackMessage = message)
        return true
    }

    /**
     * Refreshes state from persistent storage and current enforcement status (e.g., on Activity resume).
     */
    fun refreshFromStorage() {
        updateState(feedbackMessage = currentState.feedbackMessage)
    }

    private fun updateState(feedbackMessage: String?) {
        currentState = buildState(feedbackMessage = feedbackMessage)
        for (listener in listeners.toList()) {
            listener(currentState)
        }
    }

    private fun buildState(feedbackMessage: String?): DashboardState {
        val sessionActive = storage.isSessionActive()
        val sessionStatus = if (sessionActive) {
            CallSessionStatus.ACTIVE
        } else {
            CallSessionStatus.INACTIVE
        }
        val enforcement = enforcementStatusProvider()

        val appStates = LinkedHashMap<ManagedApp, AppAccessItemState>()
        for (app in ManagedApp.entries) {
            val savedLocked = storage.getSavedLocked(app)
            val effectiveLocked = storage.getEffectiveLocked(app)

            appStates[app] = AppAccessItemState(
                app = app,
                savedLocked = savedLocked,
                effectiveLocked = effectiveLocked,
                hasSessionOverride = sessionActive && (effectiveLocked != savedLocked),
                enforcementStatus = enforcement
            )
        }

        return DashboardState(
            sessionStatus = sessionStatus,
            appStates = appStates,
            feedbackMessage = feedbackMessage,
            enforcementStatus = enforcement
        )
    }
}
