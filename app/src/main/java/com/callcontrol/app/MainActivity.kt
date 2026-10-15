package com.callcontrol.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.callcontrol.app.accessibility.AccessibilityServiceStatusChecker
import com.callcontrol.app.pairing.DashboardPairingManager
import com.callcontrol.app.pairing.DeviceRole
import com.callcontrol.app.pairing.PairingUiModel
import com.callcontrol.app.pairing.PairingUiStatus
import com.callcontrol.app.state.AppAccessItemState
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.CallSessionStatus
import com.callcontrol.app.state.DashboardState
import com.callcontrol.app.state.EnforcementStatus
import com.callcontrol.app.state.ManagedApp
import com.callcontrol.app.state.SessionTransitionResult
import com.callcontrol.app.state.SharedPreferencesAppAccessStorage

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {

    // =========================================================
    // COLORS
    // =========================================================

    private val backgroundColor = Color.rgb(11, 15, 20)
    private val cardColor = Color.rgb(21, 27, 35)
    private val secondaryColor = Color.rgb(32, 41, 56)

    private val white = Color.WHITE
    private val bodyTextColor = Color.rgb(214, 222, 232)
    private val muted = Color.rgb(168, 179, 194)

    private val green = Color.rgb(74, 222, 128)
    private val red = Color.rgb(248, 113, 113)
    private val yellow = Color.rgb(245, 158, 11)

    private val allowButtonBgColor = Color.rgb(38, 50, 56)
    private val lockButtonBgColor = Color.rgb(38, 56, 45)
    private val endSessionButtonBgColor = Color.rgb(68, 34, 38)
    private val activeBadgeBgColor = Color.rgb(22, 50, 36)

    // =========================================================
    // STATE MANAGEMENT
    // =========================================================

    private lateinit var stateManager: CallControlStateManager
    private lateinit var pairingManager: DashboardPairingManager

    private lateinit var callTitleView: TextView
    private lateinit var callSubtitleView: TextView
    private lateinit var callStatusBadgeView: TextView
    private lateinit var sessionActionButton: Button
    private lateinit var feedbackBannerView: TextView

    private lateinit var enforcementTitleView: TextView
    private lateinit var enforcementNoticeView: TextView
    private lateinit var enforcementBadgeView: TextView
    private lateinit var accessibilitySetupButton: Button

    private lateinit var roleFriendButton: Button
    private lateinit var roleControllerButton: Button
    private lateinit var friendIconView: TextView
    private lateinit var friendNameView: TextView
    private lateinit var friendStatusBadgeView: TextView
    private lateinit var friendStatusDetailView: TextView
    private lateinit var pairingPrimaryButton: Button
    private lateinit var pairingSecondaryButton: Button

    private val appCardHolders = LinkedHashMap<ManagedApp, AppCardViewHolder>()

    private data class AppCardViewHolder(
        val app: ManagedApp,
        val descriptionView: TextView,
        val statusView: TextView,
        val actionButton: Button
    )

    private val stateListener: (DashboardState) -> Unit = { state ->
        renderDashboardState(state)
    }

    private val pairingUiListener: (PairingUiModel) -> Unit = { model ->
        renderPairingState(model)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applySystemBarColors()

        // IMPORTANT:
        // Do NOT force software rendering.
        // The diagnostic test already proved rendering works.

        val storage = SharedPreferencesAppAccessStorage(this)
        stateManager = CallControlStateManager(
            storage = storage,
            enforcementStatusProvider = {
                AccessibilityServiceStatusChecker.currentEnforcementStatus(this)
            }
        )
        pairingManager = DashboardPairingManager(
            stateManager = stateManager
        )

        createCallControlUI()
        stateManager.addListener(stateListener)
        pairingManager.addUiListener(pairingUiListener)
    }

    override fun onResume() {
        super.onResume()
        if (::stateManager.isInitialized) {
            stateManager.refreshFromStorage()
        }
    }

    override fun onDestroy() {
        if (::stateManager.isInitialized) {
            stateManager.removeListener(stateListener)
        }
        if (::pairingManager.isInitialized) {
            pairingManager.removeUiListener(pairingUiListener)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
    }

    // =========================================================
    // MAIN UI
    // =========================================================

    private fun createCallControlUI() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        // -----------------------------------------------------
        // SCROLL VIEW
        // -----------------------------------------------------

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        scrollView.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // =====================================================
        // HEADER
        // =====================================================

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(12)
            }
        }

        val title = TextView(this).apply {
            text = "CallControl"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        val subtitle = TextView(this).apply {
            text = "Control Center"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        }

        titleContainer.addView(title)
        titleContainer.addView(subtitle)

        val statusDot = View(this).apply {
            background = roundedBackground(
                green,
                100
            )

            layoutParams = LinearLayout.LayoutParams(
                dp(12),
                dp(12)
            )
        }

        header.addView(titleContainer)
        header.addView(statusDot)

        content.addView(header)

        // =====================================================
        // DEVICE ROLE & PAIRING
        // =====================================================

        addSpace(content, 20)

        addSectionLabel(
            content,
            "DEVICE ROLE & PAIRING"
        )

        addSpace(content, 8)

        val friendCard = createCard()

        // 1. Role Selector Row
        val roleSelectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        roleFriendButton = Button(this).apply {
            text = "FRIEND DEVICE (LOCAL)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(lockButtonBgColor, 12)
            setOnClickListener {
                if (!pairingManager.setRole(DeviceRole.FRIEND_DEVICE)) {
                    Toast.makeText(this@MainActivity, "Disconnect active session before changing roles.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        roleControllerButton = Button(this).apply {
            text = "CONTROLLER (REMOTE)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(secondaryColor, 12)
            setOnClickListener {
                if (!pairingManager.setRole(DeviceRole.CONTROLLER)) {
                    Toast.makeText(this@MainActivity, "Disconnect active session before changing roles.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        roleSelectorRow.addView(
            roleFriendButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        )
        roleSelectorRow.addView(
            roleControllerButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        friendCard.addView(roleSelectorRow)

        addSpace(friendCard, 12)

        // 2. Info Row (Icon + Title/Detail + Status Badge)
        val friendRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        friendIconView = TextView(this).apply {
            text = "F"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.rgb(186, 198, 214))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(Color.rgb(32, 41, 56), 100)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }

        val friendInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(8)
            }
        }

        friendNameView = TextView(this).apply {
            text = "Friend Device (Local)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
        }

        friendStatusDetailView = TextView(this).apply {
            text = "Not connected"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(bodyTextColor)
            setPadding(0, dp(2), 0, 0)
            setLineSpacing(0f, 1.2f)
        }

        friendInfo.addView(friendNameView)
        friendInfo.addView(friendStatusDetailView)

        friendStatusBadgeView = TextView(this).apply {
            text = "NOT PAIRED"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.rgb(186, 198, 214))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(secondaryColor, 100)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }

        friendRow.addView(friendIconView)
        friendRow.addView(friendInfo)
        friendRow.addView(
            friendStatusBadgeView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        friendCard.addView(friendRow)

        // 3. Action Buttons Row
        val friendActionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        pairingPrimaryButton = Button(this).apply {
            text = "START PAIRING (LISTEN)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(lockButtonBgColor, 14)
            setOnClickListener {
                handlePairingPrimaryButtonClick()
            }
        }

        pairingSecondaryButton = Button(this).apply {
            text = "REJECT"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(endSessionButtonBgColor, 14)
            visibility = View.GONE
            setOnClickListener {
                handlePairingSecondaryButtonClick()
            }
        }

        friendActionsRow.addView(
            pairingPrimaryButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(8)
            }
        )
        friendActionsRow.addView(
            pairingSecondaryButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        friendCard.addView(friendActionsRow)

        content.addView(friendCard)

        // =====================================================
        // CALL SESSION
        // =====================================================

        addSpace(content, 20)

        addSectionLabel(
            content,
            "CALL SESSION"
        )

        addSpace(content, 8)

        val callCard = createCard()

        val callRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val callInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(12)
            }
        }

        callTitleView = TextView(this).apply {
            text = "No active call"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        callSubtitleView = TextView(this).apply {
            text = "CallControl session inactive"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(bodyTextColor)
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }

        callInfo.addView(callTitleView)
        callInfo.addView(callSubtitleView)

        callStatusBadgeView = TextView(this).apply {
            text = "IDLE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.rgb(186, 198, 214))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            background = roundedBackground(
                secondaryColor,
                100
            )

            setPadding(
                dp(12),
                dp(6),
                dp(12),
                dp(6)
            )
        }

        callRow.addView(callInfo)
        callRow.addView(
            callStatusBadgeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        sessionActionButton = Button(this).apply {
            text = "START SESSION"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(50)
            minimumHeight = dp(50)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(lockButtonBgColor, 14)

            setOnClickListener {
                handleSessionButtonClick()
            }
        }

        feedbackBannerView = TextView(this).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(bodyTextColor)
            visibility = View.GONE
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.25f)
        }

        callCard.addView(callRow)
        callCard.addView(
            sessionActionButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )
        callCard.addView(feedbackBannerView)

        content.addView(callCard)

        // =====================================================
        // RESTRICTION SERVICE (ACCESSIBILITY ONBOARDING & STATUS)
        // =====================================================

        addSpace(content, 20)

        addSectionLabel(
            content,
            "RESTRICTION ENFORCEMENT"
        )

        addSpace(content, 8)

        val enforcementCard = createCard()

        val enforcementRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val enforcementInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(12)
            }
        }

        enforcementTitleView = TextView(this).apply {
            text = "Accessibility Service"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        enforcementNoticeView = TextView(this).apply {
            text = stateManager.currentState.enforcementStatus.description
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(bodyTextColor)
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }

        enforcementInfo.addView(enforcementTitleView)
        enforcementInfo.addView(enforcementNoticeView)

        enforcementBadgeView = TextView(this).apply {
            text = stateManager.currentState.enforcementStatus.badgeText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(yellow)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(secondaryColor, 100)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        enforcementRow.addView(enforcementInfo)
        enforcementRow.addView(
            enforcementBadgeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        accessibilitySetupButton = Button(this).apply {
            text = "ACCESSIBILITY DISCLOSURE & SETUP"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(50)
            minimumHeight = dp(50)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(allowButtonBgColor, 14)
            setOnClickListener {
                startActivity(AccessibilityOnboardingActivity.createIntent(this@MainActivity))
            }
        }

        enforcementCard.addView(enforcementRow)
        enforcementCard.addView(
            accessibilitySetupButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
        )

        content.addView(enforcementCard)

        // =====================================================
        // APP ACCESS
        // =====================================================

        addSpace(content, 20)

        addSectionLabel(
            content,
            "APP ACCESS"
        )

        addSpace(content, 8)

        appCardHolders.clear()

        // Instagram
        addAppCard(
            content = content,
            app = ManagedApp.INSTAGRAM,
            iconColor = Color.rgb(225, 48, 108)
        )

        // YouTube
        addAppCard(
            content = content,
            app = ManagedApp.YOUTUBE,
            iconColor = Color.RED
        )

        // WhatsApp
        addAppCard(
            content = content,
            app = ManagedApp.WHATSAPP,
            iconColor = Color.rgb(37, 211, 102)
        )

        // Documents
        addAppCard(
            content = content,
            app = ManagedApp.DOCUMENTS,
            iconColor = Color.rgb(91, 141, 239)
        )

        // Small bottom spacing
        addSpace(content, 16)

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // =========================================================
    // SESSION & APP ACCESS ACTIONS
    // =========================================================

    private fun handleSessionButtonClick() {
        if (::pairingManager.isInitialized && pairingManager.localRole == DeviceRole.CONTROLLER) {
            if (!pairingManager.currentUiState.status.isConnected) {
                Toast.makeText(this, "Connect to Friend Device to control call session remotely.", Toast.LENGTH_SHORT).show()
                return
            }
            if (pairingManager.currentUiState.remoteSessionStatus == CallSessionStatus.ACTIVE) {
                pairingManager.controllerRequestSessionEnd()
            } else {
                pairingManager.controllerRequestSessionStart()
            }
            return
        }

        val currentStatus = stateManager.currentState.sessionStatus
        val result = when (currentStatus) {
            CallSessionStatus.INACTIVE -> stateManager.startSession()
            CallSessionStatus.ACTIVE -> stateManager.endSession()
        }

        val feedback = when (result) {
            is SessionTransitionResult.Success -> result.message
            is SessionTransitionResult.InvalidTransition -> result.reason
        }
        Toast.makeText(this, feedback, Toast.LENGTH_SHORT).show()
    }

    private fun renderDashboardState(state: DashboardState) {
        renderEnforcementCard(state.enforcementStatus)

        if (::pairingManager.isInitialized && pairingManager.localRole == DeviceRole.CONTROLLER) {
            renderControllerSessionAndApps(pairingManager.currentUiState)
            return
        }

        // 1. Update Call Session card
        val session = state.sessionStatus
        callTitleView.text = session.titleText
        callSubtitleView.text = session.subtitleText
        callStatusBadgeView.text = session.badgeText

        if (session == CallSessionStatus.ACTIVE) {
            callStatusBadgeView.setTextColor(green)
            callStatusBadgeView.background = roundedBackground(activeBadgeBgColor, 100)
            sessionActionButton.text = session.actionButtonText
            sessionActionButton.background = roundedBackground(endSessionButtonBgColor, 14)
        } else {
            callStatusBadgeView.setTextColor(Color.rgb(186, 198, 214))
            callStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            sessionActionButton.text = session.actionButtonText
            sessionActionButton.background = roundedBackground(lockButtonBgColor, 14)
        }

        // 2. Update confirmation feedback banner
        val feedback = state.feedbackMessage
        if (!feedback.isNullOrBlank()) {
            feedbackBannerView.text = feedback
            feedbackBannerView.visibility = View.VISIBLE
        } else {
            feedbackBannerView.visibility = View.GONE
        }

        // 3. Update all App Access cards from the centralized state
        for ((app, holder) in appCardHolders) {
            val itemState = state.stateFor(app)
            bindAppCardHolder(holder, itemState)
        }
    }

    private fun renderEnforcementCard(enforcement: EnforcementStatus) {
        enforcementNoticeView.text = enforcement.description
        enforcementBadgeView.text = enforcement.badgeText
        if (enforcement == EnforcementStatus.OS_ENFORCED) {
            enforcementBadgeView.setTextColor(green)
            enforcementBadgeView.background = roundedBackground(activeBadgeBgColor, 100)
            accessibilitySetupButton.text = "ACCESSIBILITY DISCLOSURE & SETTINGS"
        } else {
            enforcementBadgeView.setTextColor(yellow)
            enforcementBadgeView.background = roundedBackground(secondaryColor, 100)
            accessibilitySetupButton.text = "ACCESSIBILITY DISCLOSURE & SETUP"
        }
    }

    private fun renderControllerSessionAndApps(model: PairingUiModel) {
        val remoteActive = (model.remoteSessionStatus == CallSessionStatus.ACTIVE)
        callTitleView.text = if (remoteActive) "Call session in progress (Remote)" else "No active call (Remote)"
        callSubtitleView.text = if (remoteActive) {
            "Friend device session active • Remote enforcement enabled"
        } else {
            "Friend device session inactive"
        }
        callStatusBadgeView.text = if (remoteActive) "ACTIVE" else "IDLE"

        if (remoteActive) {
            callStatusBadgeView.setTextColor(green)
            callStatusBadgeView.background = roundedBackground(activeBadgeBgColor, 100)
            sessionActionButton.text = "END REMOTE SESSION"
            sessionActionButton.background = roundedBackground(endSessionButtonBgColor, 14)
        } else {
            callStatusBadgeView.setTextColor(Color.rgb(186, 198, 214))
            callStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            sessionActionButton.text = "START REMOTE SESSION"
            sessionActionButton.background = roundedBackground(lockButtonBgColor, 14)
        }

        val feedback = model.feedbackMessage
        if (!feedback.isNullOrBlank()) {
            feedbackBannerView.text = feedback
            feedbackBannerView.visibility = View.VISIBLE
        } else {
            feedbackBannerView.visibility = View.GONE
        }

        for ((app, holder) in appCardHolders) {
            val isLocked = model.remoteAppStates[app] ?: app.defaultLocked
            holder.descriptionView.text = "${app.categoryDescription} • Remote Policy"
            if (isLocked) {
                holder.statusView.text = "LOCKED"
                holder.statusView.setTextColor(red)
                holder.actionButton.text = "ALLOW"
                holder.actionButton.background = roundedBackground(allowButtonBgColor, 14)
            } else {
                holder.statusView.text = "ALLOWED"
                holder.statusView.setTextColor(green)
                holder.actionButton.text = "LOCK"
                holder.actionButton.background = roundedBackground(lockButtonBgColor, 14)
            }
        }
    }

    private fun renderPairingState(model: PairingUiModel) {
        // 1. Update role selector buttons
        if (model.selectedRole == DeviceRole.FRIEND_DEVICE) {
            roleFriendButton.background = roundedBackground(lockButtonBgColor, 12)
            roleFriendButton.setTextColor(white)
            roleControllerButton.background = roundedBackground(secondaryColor, 12)
            roleControllerButton.setTextColor(muted)
            friendIconView.text = "F"
            friendNameView.text = "Friend Device (Local)"
        } else {
            roleFriendButton.background = roundedBackground(secondaryColor, 12)
            roleFriendButton.setTextColor(muted)
            roleControllerButton.background = roundedBackground(lockButtonBgColor, 12)
            roleControllerButton.setTextColor(white)
            friendIconView.text = "C"
            friendNameView.text = "Controller (Remote)"
        }

        // 2. Update status badge
        friendStatusBadgeView.text = model.status.badgeText
        when (model.status) {
            PairingUiStatus.CONNECTED -> {
                friendStatusBadgeView.setTextColor(green)
                friendStatusBadgeView.background = roundedBackground(activeBadgeBgColor, 100)
            }
            PairingUiStatus.LISTENING,
            PairingUiStatus.CONNECTING,
            PairingUiStatus.HANDSHAKE_IN_PROGRESS,
            PairingUiStatus.WAITING_APPROVAL -> {
                friendStatusBadgeView.setTextColor(yellow)
                friendStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            }
            PairingUiStatus.REJECTED,
            PairingUiStatus.EXPIRED -> {
                friendStatusBadgeView.setTextColor(red)
                friendStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            }
            PairingUiStatus.NOT_PAIRED,
            PairingUiStatus.DISCONNECTED,
            PairingUiStatus.REVOKED -> {
                friendStatusBadgeView.setTextColor(Color.rgb(186, 198, 214))
                friendStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            }
        }

        // 3. Update detail text
        friendStatusDetailView.text = model.statusDetail

        // 4. Update action buttons
        when (model.status) {
            PairingUiStatus.NOT_PAIRED,
            PairingUiStatus.DISCONNECTED,
            PairingUiStatus.REVOKED -> {
                pairingPrimaryButton.text = if (model.selectedRole == DeviceRole.FRIEND_DEVICE) {
                    "START PAIRING (LISTEN)"
                } else {
                    "CONNECT TO FRIEND"
                }
                pairingPrimaryButton.background = roundedBackground(lockButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.visibility = View.GONE
            }
            PairingUiStatus.LISTENING -> {
                pairingPrimaryButton.text = "CANCEL LISTENING"
                pairingPrimaryButton.background = roundedBackground(endSessionButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.visibility = View.GONE
            }
            PairingUiStatus.CONNECTING,
            PairingUiStatus.HANDSHAKE_IN_PROGRESS -> {
                pairingPrimaryButton.text = "CANCEL"
                pairingPrimaryButton.background = roundedBackground(endSessionButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.visibility = View.GONE
            }
            PairingUiStatus.WAITING_APPROVAL -> {
                pairingPrimaryButton.text = "APPROVE"
                pairingPrimaryButton.background = roundedBackground(lockButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.text = "REJECT"
                pairingSecondaryButton.background = roundedBackground(endSessionButtonBgColor, 14)
                pairingSecondaryButton.visibility = View.VISIBLE
            }
            PairingUiStatus.CONNECTED -> {
                pairingPrimaryButton.text = "DISCONNECT"
                pairingPrimaryButton.background = roundedBackground(allowButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.text = "REVOKE"
                pairingSecondaryButton.background = roundedBackground(endSessionButtonBgColor, 14)
                pairingSecondaryButton.visibility = View.VISIBLE
            }
            PairingUiStatus.REJECTED,
            PairingUiStatus.EXPIRED -> {
                pairingPrimaryButton.text = "TRY AGAIN"
                pairingPrimaryButton.background = roundedBackground(lockButtonBgColor, 14)
                pairingPrimaryButton.isEnabled = true
                pairingSecondaryButton.visibility = View.GONE
            }
        }

        // 5. Update feedback if available
        if (!model.feedbackMessage.isNullOrBlank()) {
            feedbackBannerView.text = model.feedbackMessage
            feedbackBannerView.visibility = View.VISIBLE
        }

        // 6. Sync session and app cards based on active role
        if (model.selectedRole == DeviceRole.CONTROLLER) {
            renderControllerSessionAndApps(model)
        } else {
            renderDashboardState(stateManager.currentState)
        }
    }

    private fun handlePairingPrimaryButtonClick() {
        val model = pairingManager.currentUiState
        when (model.status) {
            PairingUiStatus.NOT_PAIRED,
            PairingUiStatus.DISCONNECTED,
            PairingUiStatus.REVOKED,
            PairingUiStatus.REJECTED,
            PairingUiStatus.EXPIRED -> {
                if (pairingManager.localRole == DeviceRole.FRIEND_DEVICE) {
                    pairingManager.startFriendListening()
                } else {
                    showControllerConnectDialog()
                }
            }
            PairingUiStatus.LISTENING -> {
                pairingManager.cancelFriendListening()
            }
            PairingUiStatus.CONNECTING,
            PairingUiStatus.HANDSHAKE_IN_PROGRESS -> {
                pairingManager.resetToUnpaired()
            }
            PairingUiStatus.WAITING_APPROVAL -> {
                pairingManager.approvePendingPairing()
            }
            PairingUiStatus.CONNECTED -> {
                pairingManager.disconnectOrRevoke(permanent = false)
            }
        }
    }

    private fun handlePairingSecondaryButtonClick() {
        val model = pairingManager.currentUiState
        when (model.status) {
            PairingUiStatus.WAITING_APPROVAL -> {
                pairingManager.rejectPendingPairing("User declined pairing request")
            }
            PairingUiStatus.CONNECTED -> {
                AlertDialog.Builder(this)
                    .setTitle("Revoke Pairing?")
                    .setMessage("This will permanently terminate the pairing association and wipe shared encryption keys.")
                    .setPositiveButton("Revoke") { _, _ ->
                        pairingManager.disconnectOrRevoke(permanent = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                pairingSecondaryButton.visibility = View.GONE
            }
        }
    }

    private fun showControllerConnectDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val ipInput = EditText(this).apply {
            hint = "Friend IP Address (e.g. 192.168.1.50)"
            setText("127.0.0.1")
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            minHeight = dp(48)
            minimumHeight = dp(48)
        }

        val portInput = EditText(this).apply {
            hint = "Port (default 8492)"
            setText("8492")
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            minHeight = dp(48)
            minimumHeight = dp(48)
        }

        val codeInput = EditText(this).apply {
            hint = "6-Digit Pairing Code"
            inputType = InputType.TYPE_CLASS_NUMBER
            maxLines = 1
            minHeight = dp(48)
            minimumHeight = dp(48)
        }

        dialogLayout.addView(ipInput)
        addSpace(dialogLayout, 8)
        dialogLayout.addView(portInput)
        addSpace(dialogLayout, 8)
        dialogLayout.addView(codeInput)

        AlertDialog.Builder(this)
            .setTitle("Connect to Friend Device")
            .setView(dialogLayout)
            .setPositiveButton("Connect") { _, _ ->
                val host = ipInput.text.toString().trim()
                val portText = portInput.text.toString().trim()
                val code = codeInput.text.toString().trim()

                if (host.isEmpty()) {
                    Toast.makeText(this, "IP address cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val port = portText.toIntOrNull() ?: 8492
                if (port !in 1..65535) {
                    Toast.makeText(this, "Invalid port number (1-65535).", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (code.length != 6 || !code.all { it.isDigit() }) {
                    Toast.makeText(this, "Pairing code must be exactly 6 digits.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                pairingManager.initiateControllerConnection(
                    host = host,
                    port = port,
                    enteredCode = code
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindAppCardHolder(
        holder: AppCardViewHolder,
        itemState: AppAccessItemState
    ) {
        holder.descriptionView.text = if (itemState.hasSessionOverride) {
            "${itemState.app.categoryDescription} • Session override"
        } else {
            itemState.app.categoryDescription
        }

        holder.statusView.text = itemState.statusLabel
        holder.actionButton.text = itemState.actionButtonLabel

        if (itemState.effectiveLocked) {
            holder.statusView.setTextColor(red)
            holder.actionButton.background = roundedBackground(allowButtonBgColor, 14)
        } else {
            holder.statusView.setTextColor(green)
            holder.actionButton.background = roundedBackground(lockButtonBgColor, 14)
        }
    }

    // =========================================================
    // APP CARD
    // =========================================================

    private fun addAppCard(
        content: LinearLayout,
        app: ManagedApp,
        iconColor: Int
    ) {
        val card = createCard()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // -----------------------------------------------------
        // APP ICON
        // -----------------------------------------------------

        val icon = TextView(this).apply {
            text = app.shortName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(iconColor)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD

            background = roundedBackground(
                darkerColor(iconColor),
                14
            )

            layoutParams = LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        }

        // -----------------------------------------------------
        // APP INFO
        // -----------------------------------------------------

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = dp(14)
                rightMargin = dp(12)
            }
        }

        val name = TextView(this).apply {
            text = app.displayName
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        val desc = TextView(this).apply {
            text = app.categoryDescription
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(2))
            setLineSpacing(0f, 1.2f)
        }

        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
        }

        info.addView(name)
        info.addView(desc)
        info.addView(status)

        // -----------------------------------------------------
        // BUTTON
        // -----------------------------------------------------

        val button = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false

            minWidth = dp(88)
            minimumWidth = dp(88)
            minHeight = dp(48)
            minimumHeight = dp(48)

            setPadding(
                dp(16),
                dp(10),
                dp(16),
                dp(10)
            )

            background = roundedBackground(
                lockButtonBgColor,
                14
            )

            setOnClickListener {
                if (::pairingManager.isInitialized && pairingManager.localRole == DeviceRole.CONTROLLER) {
                    if (!pairingManager.currentUiState.status.isConnected) {
                        Toast.makeText(this@MainActivity, "Connect to Friend Device to control apps remotely.", Toast.LENGTH_SHORT).show()
                    } else if (pairingManager.currentUiState.remoteSessionStatus != CallSessionStatus.ACTIVE) {
                        Toast.makeText(this@MainActivity, "Remote session must be ACTIVE to toggle app policies.", Toast.LENGTH_SHORT).show()
                    } else {
                        pairingManager.controllerRequestPolicyToggle(app)
                    }
                } else {
                    stateManager.toggleAppAccess(app)
                }
            }
        }

        val holder = AppCardViewHolder(
            app = app,
            descriptionView = desc,
            statusView = status,
            actionButton = button
        )
        appCardHolders[app] = holder
        bindAppCardHolder(holder, stateManager.currentState.stateFor(app))

        row.addView(icon)
        row.addView(info)
        row.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        card.addView(row)

        content.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
    }

    // =========================================================
    // CARD
    // =========================================================

    private fun createCard(): LinearLayout {

        return LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setPadding(
                dp(16),
                dp(16),
                dp(16),
                dp(16)
            )

            background = roundedBackground(
                cardColor,
                18
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    // =========================================================
    // SECTION LABEL
    // =========================================================

    private fun addSectionLabel(
        parent: LinearLayout,
        text: String
    ) {

        val label = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        parent.addView(label)
    }

    // =========================================================
    // SPACING
    // =========================================================

    private fun addSpace(
        parent: LinearLayout,
        heightDp: Int
    ) {

        val spacer = View(this)

        parent.addView(
            spacer,
            LinearLayout.LayoutParams(
                1,
                dp(heightDp)
            )
        )
    }

    // =========================================================
    // BACKGROUND
    // =========================================================

    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    // =========================================================
    // DARKER ICON COLOR
    // =========================================================

    private fun darkerColor(color: Int): Int {

        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return Color.rgb(
            (r * 0.18f).toInt(),
            (g * 0.18f).toInt(),
            (b * 0.18f).toInt()
        )
    }

    // =========================================================
    // DP
    // =========================================================

    private fun dp(value: Int): Int {

        return (value * resources.displayMetrics.density)
            .toInt()
    }
}