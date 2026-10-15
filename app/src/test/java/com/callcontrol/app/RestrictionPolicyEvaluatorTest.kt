package com.callcontrol.app

import com.callcontrol.app.accessibility.AccessibilityServiceStatusChecker
import com.callcontrol.app.accessibility.RestrictionDecision
import com.callcontrol.app.accessibility.RestrictionPolicyEvaluator
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.EnforcementStatus
import com.callcontrol.app.state.InMemoryAppAccessStorage
import com.callcontrol.app.state.ManagedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionPolicyEvaluatorTest {

    @Test
    fun whenSessionInactive_neverRestrictsLockedTargetPackages() {
        val storage = InMemoryAppAccessStorage()
        val evaluator = RestrictionPolicyEvaluator(storage)

        // Instagram is LOCKED by default, but session is INACTIVE
        val decision = evaluator.evaluateForegroundPackage(
            packageName = "com.instagram.android",
            currentTimeMs = 1_000L
        )
        assertTrue(decision is RestrictionDecision.Allow)
    }

    @Test
    fun whenSessionActive_restrictsLockedApps_andAllowsAllowedApps() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage)

        manager.startSession()

        // Instagram (LOCKED) -> Restrict
        val igDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.instagram.android",
            currentTimeMs = 1_000L
        )
        assertTrue(igDecision is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.INSTAGRAM, (igDecision as RestrictionDecision.Restrict).app)

        // YouTube (LOCKED) -> Restrict
        val ytDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.google.android.youtube",
            currentTimeMs = 3_000L
        )
        assertTrue(ytDecision is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.YOUTUBE, (ytDecision as RestrictionDecision.Restrict).app)

        // WhatsApp (ALLOWED) -> Allow
        val waDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.whatsapp",
            currentTimeMs = 4_000L
        )
        assertTrue(waDecision is RestrictionDecision.Allow)

        // Documents (ALLOWED) -> Allow
        val docDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.google.android.documentsui",
            currentTimeMs = 5_000L
        )
        assertTrue(docDecision is RestrictionDecision.Allow)
    }

    @Test
    fun cooldownPreventsInfiniteLaunchLoops_andResetsAfterLauncherOrCooldownExpiry() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage, cooldownMs = 1500L)

        manager.startSession()

        val first = evaluator.evaluateForegroundPackage("com.instagram.android", 10_000L)
        assertTrue(first is RestrictionDecision.Restrict)

        // Duplicate event 200ms later is skipped by cooldown
        val duplicate = evaluator.evaluateForegroundPackage("com.instagram.android", 10_200L)
        assertTrue(duplicate is RestrictionDecision.CooldownSkipped)

        // Event after cooldown window (1600ms later) triggers restriction again
        val afterExpiry = evaluator.evaluateForegroundPackage("com.instagram.android", 11_600L)
        assertTrue(afterExpiry is RestrictionDecision.Restrict)

        // Navigating to launcher resets cooldown immediately
        val launcher = evaluator.evaluateForegroundPackage("com.google.android.apps.nexuslauncher", 11_700L)
        assertTrue(launcher is RestrictionDecision.Allow)

        val reEntered = evaluator.evaluateForegroundPackage("com.instagram.android", 11_800L)
        assertTrue(reEntered is RestrictionDecision.Restrict)
    }

    @Test
    fun midSessionPolicyToggle_andEndSession_immediatelyUpdateRestrictionBehavior() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage)

        manager.startSession()

        // Lock Documents during active session -> should now be restricted
        manager.toggleAppAccess(ManagedApp.DOCUMENTS)
        val docLockedDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.google.android.documentsui",
            currentTimeMs = 20_000L
        )
        assertTrue(docLockedDecision is RestrictionDecision.Restrict)

        // Allow Instagram during active session -> should now be allowed
        manager.toggleAppAccess(ManagedApp.INSTAGRAM)
        val igAllowedDecision = evaluator.evaluateForegroundPackage(
            packageName = "com.instagram.android",
            currentTimeMs = 22_000L
        )
        assertTrue(igAllowedDecision is RestrictionDecision.Allow)

        // End session -> all restrictions disabled immediately
        manager.endSession()
        val ytAfterEnd = evaluator.evaluateForegroundPackage(
            packageName = "com.google.android.youtube",
            currentTimeMs = 25_000L
        )
        assertTrue(ytAfterEnd is RestrictionDecision.Allow)
    }

    @Test
    fun exemptSystemPackages_andOwnPackage_areNeverRestricted() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage)

        manager.startSession()

        assertTrue(
            evaluator.evaluateForegroundPackage("com.callcontrol.app", 1_000L)
                is RestrictionDecision.Allow
        )
        assertTrue(
            evaluator.evaluateForegroundPackage("com.android.settings", 1_000L)
                is RestrictionDecision.Allow
        )
        assertTrue(
            evaluator.evaluateForegroundPackage("com.android.systemui", 1_000L)
                is RestrictionDecision.Allow
        )
    }

    @Test
    fun accessibilityServiceStatusChecker_parsesEnabledServicesAccurately() {
        val pkg = "com.callcontrol.app"
        val cls = "com.callcontrol.app.accessibility.CallControlAccessibilityService"

        // Full component name enabled
        assertTrue(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "com.other/com.other.Svc:$pkg/$cls"
            )
        )

        // Shorthand component name enabled
        assertTrue(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "$pkg/.accessibility.CallControlAccessibilityService"
            )
        )

        // Master accessibility switch disabled -> false
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = false,
                enabledServicesSetting = "$pkg/$cls"
            )
        )

        // Unrelated service only -> false
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "com.example.other/.OtherAccessibilityService"
            )
        )
    }

    @Test
    fun stateManager_reflectsDynamicEnforcementStatusChanges() {
        val storage = InMemoryAppAccessStorage()
        var serviceEnabled = false
        val manager = CallControlStateManager(
            storage = storage,
            enforcementStatusProvider = {
                if (serviceEnabled) EnforcementStatus.OS_ENFORCED
                else EnforcementStatus.SIMULATED_POLICY_ONLY
            }
        )

        assertEquals(EnforcementStatus.SIMULATED_POLICY_ONLY, manager.currentState.enforcementStatus)

        // User enables Accessibility Service in Android Settings and returns to app
        serviceEnabled = true
        manager.refreshFromStorage()
        assertEquals(EnforcementStatus.OS_ENFORCED, manager.currentState.enforcementStatus)

        // User revokes Accessibility Service in Android Settings
        serviceEnabled = false
        manager.refreshFromStorage()
        assertEquals(EnforcementStatus.SIMULATED_POLICY_ONLY, manager.currentState.enforcementStatus)
    }

    @Test
    fun nullAndBlankPackageNames_areSafelyAllowed() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage)

        manager.startSession()

        assertTrue(evaluator.evaluateForegroundPackage(null, 1_000L) is RestrictionDecision.Allow)
        assertTrue(evaluator.evaluateForegroundPackage("", 1_000L) is RestrictionDecision.Allow)
        assertTrue(evaluator.evaluateForegroundPackage("   ", 1_000L) is RestrictionDecision.Allow)
    }

    @Test
    fun distinctLockedApps_areRestrictedWithoutBeingBlockedByCooldownOfPreviousApp() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage, cooldownMs = 1500L)

        manager.startSession()

        // Restrict Instagram at t=10_000ms
        val ig = evaluator.evaluateForegroundPackage("com.instagram.android", 10_000L)
        assertTrue(ig is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.INSTAGRAM, (ig as RestrictionDecision.Restrict).app)

        // Switching immediately to YouTube at t=10_200ms must restrict YouTube, not be cooldown-skipped
        val yt = evaluator.evaluateForegroundPackage("com.google.android.youtube", 10_200L)
        assertTrue(yt is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.YOUTUBE, (yt as RestrictionDecision.Restrict).app)

        // Immediate duplicate of YouTube at t=10_400ms is cooldown-skipped
        val ytDup = evaluator.evaluateForegroundPackage("com.google.android.youtube", 10_400L)
        assertTrue(ytDup is RestrictionDecision.CooldownSkipped)
    }

    @Test
    fun packageAliases_areCorrectlyMappedToManagedApps() {
        val storage = InMemoryAppAccessStorage()
        val manager = CallControlStateManager(storage)
        val evaluator = RestrictionPolicyEvaluator(storage)

        manager.startSession()

        // Instagram Lite
        val igLite = evaluator.evaluateForegroundPackage("com.instagram.lite", 1_000L)
        assertTrue(igLite is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.INSTAGRAM, (igLite as RestrictionDecision.Restrict).app)

        // YouTube Mango
        val ytMango = evaluator.evaluateForegroundPackage("com.google.android.apps.youtube.mango", 2_000L)
        assertTrue(ytMango is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.YOUTUBE, (ytMango as RestrictionDecision.Restrict).app)

        // WhatsApp Business (allowed by default in active session)
        val waBusiness = evaluator.evaluateForegroundPackage("com.whatsapp.w4b", 3_000L)
        assertTrue(waBusiness is RestrictionDecision.Allow)

        // Lock Documents and test alternative package names
        manager.toggleAppAccess(ManagedApp.DOCUMENTS)
        val aospDocs = evaluator.evaluateForegroundPackage("com.android.documentsui", 4_000L)
        assertTrue(aospDocs is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.DOCUMENTS, (aospDocs as RestrictionDecision.Restrict).app)

        val filesByGoogle = evaluator.evaluateForegroundPackage("com.google.android.apps.nbu.files", 6_000L)
        assertTrue(filesByGoogle is RestrictionDecision.Restrict)
        assertEquals(ManagedApp.DOCUMENTS, (filesByGoogle as RestrictionDecision.Restrict).app)
    }

    @Test
    fun accessibilityServiceStatusChecker_handlesEdgeCasesGracefully() {
        val pkg = "com.callcontrol.app"
        val cls = "com.callcontrol.app.accessibility.CallControlAccessibilityService"

        // Null, empty, or whitespace settings string
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = null
            )
        )
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = ""
            )
        )
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "   "
            )
        )

        // Empty colons or malformed entries
        assertFalse(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = ":::"
            )
        )

        // Case-insensitive matching
        assertTrue(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "${pkg.uppercase()}/${cls.uppercase()}"
            )
        )

        // Service surrounded by other valid and invalid services
        assertTrue(
            AccessibilityServiceStatusChecker.isComponentEnabledInSetting(
                packageName = pkg,
                serviceClassName = cls,
                accessibilityEnabled = true,
                enabledServicesSetting = "com.dummy/.DummyService: :$pkg/$cls:com.other/.Other"
            )
        )
    }
}
