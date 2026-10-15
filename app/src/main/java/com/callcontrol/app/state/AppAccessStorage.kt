package com.callcontrol.app.state

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persistence contract for user-configured app access settings and active session state.
 */
interface AppAccessStorage {
    fun getSavedLocked(app: ManagedApp): Boolean
    fun setSavedLocked(app: ManagedApp, locked: Boolean)

    fun isSessionActive(): Boolean
    fun setSessionActive(active: Boolean)

    fun getSessionOverrideLocked(app: ManagedApp): Boolean?
    fun setSessionOverrideLocked(app: ManagedApp, locked: Boolean)
    fun clearSessionOverrides()

    /**
     * Computes the effective lock policy for [app] based on whether a session is currently active,
     * any session-specific overrides, and the user's saved baseline preference.
     */
    fun getEffectiveLocked(app: ManagedApp): Boolean {
        val savedLocked = getSavedLocked(app)
        return if (isSessionActive()) {
            getSessionOverrideLocked(app) ?: if (app.allowDuringActiveSession) false else savedLocked
        } else {
            savedLocked
        }
    }
}

/**
 * SharedPreferences-backed persistence implementation so user preferences and active session
 * overrides survive activity recreation and application restarts.
 */
class SharedPreferencesAppAccessStorage(
    private val prefs: SharedPreferences
) : AppAccessStorage {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun getSavedLocked(app: ManagedApp): Boolean {
        return prefs.getBoolean(savedKey(app), app.defaultLocked)
    }

    override fun setSavedLocked(app: ManagedApp, locked: Boolean) {
        prefs.edit { putBoolean(savedKey(app), locked) }
    }

    override fun isSessionActive(): Boolean {
        return prefs.getBoolean(KEY_SESSION_ACTIVE, false)
    }

    override fun setSessionActive(active: Boolean) {
        prefs.edit { putBoolean(KEY_SESSION_ACTIVE, active) }
    }

    override fun getSessionOverrideLocked(app: ManagedApp): Boolean? {
        val key = sessionOverrideKey(app)
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, app.defaultLocked)
        } else {
            null
        }
    }

    override fun setSessionOverrideLocked(app: ManagedApp, locked: Boolean) {
        prefs.edit { putBoolean(sessionOverrideKey(app), locked) }
    }

    override fun clearSessionOverrides() {
        prefs.edit {
            for (app in ManagedApp.entries) {
                remove(sessionOverrideKey(app))
            }
        }
    }

    private fun savedKey(app: ManagedApp): String = "$KEY_PREFIX_SAVED${app.storageKey}"
    private fun sessionOverrideKey(app: ManagedApp): String = "$KEY_PREFIX_SESSION_OVERRIDE${app.storageKey}"

    companion object {
        const val PREFS_NAME = "call_control_preferences"
        private const val KEY_SESSION_ACTIVE = "session_active"
        private const val KEY_PREFIX_SAVED = "saved_locked_"
        private const val KEY_PREFIX_SESSION_OVERRIDE = "session_override_locked_"
    }
}

/**
 * In-memory implementation of [AppAccessStorage] for fast, isolated unit tests.
 */
class InMemoryAppAccessStorage : AppAccessStorage {
    private val savedStates = mutableMapOf<ManagedApp, Boolean>()
    private val sessionOverrides = mutableMapOf<ManagedApp, Boolean>()
    private var sessionActive: Boolean = false

    override fun getSavedLocked(app: ManagedApp): Boolean {
        return savedStates[app] ?: app.defaultLocked
    }

    override fun setSavedLocked(app: ManagedApp, locked: Boolean) {
        savedStates[app] = locked
    }

    override fun isSessionActive(): Boolean = sessionActive

    override fun setSessionActive(active: Boolean) {
        sessionActive = active
    }

    override fun getSessionOverrideLocked(app: ManagedApp): Boolean? {
        return sessionOverrides[app]
    }

    override fun setSessionOverrideLocked(app: ManagedApp, locked: Boolean) {
        sessionOverrides[app] = locked
    }

    override fun clearSessionOverrides() {
        sessionOverrides.clear()
    }
}
