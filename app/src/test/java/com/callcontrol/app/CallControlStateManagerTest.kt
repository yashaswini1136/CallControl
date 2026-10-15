package com.callcontrol.app

import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.CallSessionStatus
import com.callcontrol.app.state.EnforcementStatus
import com.callcontrol.app.state.InMemoryAppAccessStorage
import com.callcontrol.app.state.ManagedApp
import com.callcontrol.app.state.SessionTransitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControlStateManagerTest {

    @Test
    fun initialState_matchesDashboardDefaults() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val state = manager.currentState

        assertEquals(CallSessionStatus.INACTIVE, state.sessionStatus)
        assertEquals("No active call", state.sessionStatus.titleText)
        assertEquals("IDLE", state.sessionStatus.badgeText)
        assertEquals("START SESSION", state.sessionStatus.actionButtonText)
        assertEquals(EnforcementStatus.SIMULATED_POLICY_ONLY, state.enforcementStatus)

        val ig = state.stateFor(ManagedApp.INSTAGRAM)
        assertTrue(ig.effectiveLocked)
        assertEquals("LOCKED", ig.statusLabel)
        assertEquals("ALLOW", ig.actionButtonLabel)

        val yt = state.stateFor(ManagedApp.YOUTUBE)
        assertTrue(yt.effectiveLocked)
        assertEquals("LOCKED", yt.statusLabel)
        assertEquals("ALLOW", yt.actionButtonLabel)

        val wa = state.stateFor(ManagedApp.WHATSAPP)
        assertFalse(wa.effectiveLocked)
        assertEquals("ALLOWED", wa.statusLabel)
        assertEquals("LOCK", wa.actionButtonLabel)

        val doc = state.stateFor(ManagedApp.DOCUMENTS)
        assertFalse(doc.effectiveLocked)
        assertEquals("ALLOWED", doc.statusLabel)
        assertEquals("LOCK", doc.actionButtonLabel)
    }

    @Test
    fun startAndEndSession_transitionsValidly_andPreventsDuplicateTransitions() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)

        // Ending when already inactive must be rejected
        val invalidEnd = manager.endSession()
        assertTrue(invalidEnd is SessionTransitionResult.InvalidTransition)
        assertEquals(CallSessionStatus.INACTIVE, manager.currentState.sessionStatus)

        // Starting when inactive succeeds
        val startResult = manager.startSession()
        assertTrue(startResult is SessionTransitionResult.Success)
        assertEquals(CallSessionStatus.ACTIVE, manager.currentState.sessionStatus)
        assertEquals("Session active", manager.currentState.sessionStatus.titleText)
        assertEquals("ACTIVE", manager.currentState.sessionStatus.badgeText)
        assertEquals("END SESSION", manager.currentState.sessionStatus.actionButtonText)
        assertNotNull(manager.currentState.feedbackMessage)

        // Duplicate start while active must be rejected
        val duplicateStart = manager.startSession()
        assertTrue(duplicateStart is SessionTransitionResult.InvalidTransition)
        assertEquals(CallSessionStatus.ACTIVE, manager.currentState.sessionStatus)

        // Ending when active returns to inactive
        val endResult = manager.endSession()
        assertTrue(endResult is SessionTransitionResult.Success)
        assertEquals(CallSessionStatus.INACTIVE, manager.currentState.sessionStatus)
        assertEquals("No active call", manager.currentState.sessionStatus.titleText)
        assertEquals("IDLE", manager.currentState.sessionStatus.badgeText)
        assertEquals("START SESSION", manager.currentState.sessionStatus.actionButtonText)
    }

    @Test
    fun toggleAppAccess_whenInactive_persistsAcrossManagerRestart() {
        val storage = InMemoryAppAccessStorage()
        val manager1 = CallControlStateManager(storage)

        // Unlock Instagram and Lock Documents while session is inactive
        val updatedIg = manager1.toggleAppAccess(ManagedApp.INSTAGRAM)
        assertFalse(updatedIg.effectiveLocked)
        assertEquals("ALLOWED", updatedIg.statusLabel)
        assertEquals("LOCK", updatedIg.actionButtonLabel)

        val updatedDoc = manager1.toggleAppAccess(ManagedApp.DOCUMENTS)
        assertTrue(updatedDoc.effectiveLocked)
        assertEquals("LOCKED", updatedDoc.statusLabel)
        assertEquals("ALLOW", updatedDoc.actionButtonLabel)

        // Simulate app restart with a new manager instance using the same storage
        val manager2 = CallControlStateManager(storage)
        assertFalse(manager2.currentState.stateFor(ManagedApp.INSTAGRAM).effectiveLocked)
        assertTrue(manager2.currentState.stateFor(ManagedApp.YOUTUBE).effectiveLocked)
        assertFalse(manager2.currentState.stateFor(ManagedApp.WHATSAPP).effectiveLocked)
        assertTrue(manager2.currentState.stateFor(ManagedApp.DOCUMENTS).effectiveLocked)
    }

    @Test
    fun sessionStartAndEnd_restoresSavedPreferencesWithoutOverwritingThem() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)

        // Saved defaults: Instagram=LOCKED, YouTube=LOCKED, WhatsApp=ALLOWED, Documents=ALLOWED
        manager.startSession()
        assertEquals(CallSessionStatus.ACTIVE, manager.currentState.sessionStatus)

        // Temporarily allow Instagram and lock Documents during the active session
        val igDuringSession = manager.toggleAppAccess(ManagedApp.INSTAGRAM)
        assertFalse(igDuringSession.effectiveLocked)
        assertTrue(igDuringSession.savedLocked)
        assertTrue(igDuringSession.hasSessionOverride)

        val docDuringSession = manager.toggleAppAccess(ManagedApp.DOCUMENTS)
        assertTrue(docDuringSession.effectiveLocked)
        assertFalse(docDuringSession.savedLocked)
        assertTrue(docDuringSession.hasSessionOverride)

        // End session: saved preferences must be restored (Instagram=LOCKED, Documents=ALLOWED)
        manager.endSession()
        assertEquals(CallSessionStatus.INACTIVE, manager.currentState.sessionStatus)

        val igAfterSession = manager.currentState.stateFor(ManagedApp.INSTAGRAM)
        assertTrue(igAfterSession.effectiveLocked)
        assertFalse(igAfterSession.hasSessionOverride)

        val docAfterSession = manager.currentState.stateFor(ManagedApp.DOCUMENTS)
        assertFalse(docAfterSession.effectiveLocked)
        assertFalse(docAfterSession.hasSessionOverride)
    }

    @Test
    fun whatsapp_isAllowedWhenSessionStarts_andRestoresSavedPreferenceWhenSessionEnds() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)

        // User locks WhatsApp while inactive
        manager.toggleAppAccess(ManagedApp.WHATSAPP)
        assertTrue(manager.currentState.stateFor(ManagedApp.WHATSAPP).effectiveLocked)

        // Starting a call session automatically allows WhatsApp for the session
        manager.startSession()
        val waDuringSession = manager.currentState.stateFor(ManagedApp.WHATSAPP)
        assertFalse(waDuringSession.effectiveLocked)
        assertTrue(waDuringSession.savedLocked)
        assertTrue(waDuringSession.hasSessionOverride)

        // Ending the call session restores the user's saved WhatsApp preference
        manager.endSession()
        val waAfterSession = manager.currentState.stateFor(ManagedApp.WHATSAPP)
        assertTrue(waAfterSession.effectiveLocked)
    }
}
