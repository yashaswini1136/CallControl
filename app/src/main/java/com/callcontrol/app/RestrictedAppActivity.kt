package com.callcontrol.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.callcontrol.app.accessibility.AccessibilityServiceStatusChecker
import com.callcontrol.app.state.CallControlStateManager
import com.callcontrol.app.state.ManagedApp
import com.callcontrol.app.state.SharedPreferencesAppAccessStorage

@SuppressLint("SetTextI18n")
class RestrictedAppActivity : ComponentActivity() {

    private val backgroundColor = Color.rgb(11, 15, 20)
    private val cardColor = Color.rgb(21, 27, 35)
    private val white = Color.WHITE
    private val bodyTextColor = Color.rgb(214, 222, 232)
    private val muted = Color.rgb(168, 179, 194)
    private val red = Color.rgb(248, 113, 113)

    private val primaryButtonBg = Color.rgb(38, 56, 45)
    private val secondaryButtonBg = Color.rgb(38, 50, 56)
    private val dangerButtonBg = Color.rgb(68, 34, 38)
    private val lockedBadgeBg = Color.rgb(58, 24, 30)

    private lateinit var storage: SharedPreferencesAppAccessStorage
    private var restrictedApp: ManagedApp? = null
    private var restrictedPackage: String = ""

    private lateinit var appTitleView: TextView
    private lateinit var packageSubtitleView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarColors()

        storage = SharedPreferencesAppAccessStorage(this)
        parseIntentExtras(intent)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateToHomeScreen()
                }
            }
        )

        createRestrictionUI()
        updateDisplayedAppInfo()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntentExtras(intent)
        updateDisplayedAppInfo()
    }

    override fun onResume() {
        super.onResume()
        // If the session ended or the app was unlocked while this screen was shown, dismiss automatically
        val app = restrictedApp
        if (!storage.isSessionActive() || (app != null && !storage.getEffectiveLocked(app))) {
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
    }

    private fun parseIntentExtras(sourceIntent: Intent?) {
        val appKey = sourceIntent?.getStringExtra(EXTRA_APP_KEY).orEmpty()
        restrictedPackage = sourceIntent?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        restrictedApp = ManagedApp.fromKey(appKey)
            ?: ManagedApp.fromPackageName(restrictedPackage)
    }

    private fun createRestrictionUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(backgroundColor)
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        scrollView.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Header badge (12sp status badge)
        val badge = TextView(this).apply {
            text = "CALL SESSION RESTRICTION ACTIVE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(red)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(lockedBadgeBg, 100)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        content.addView(
            badge,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        addSpace(content, 16)

        val headerTitle = TextView(this).apply {
            text = "CallControl"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        val headerSubtitle = TextView(this).apply {
            text = "User-Consented Session Enforcement"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(headerTitle)
        content.addView(headerSubtitle)

        addSpace(content, 20)

        // Restricted App Card
        val infoCard = createCard()

        appTitleView = TextView(this).apply {
            text = "Application Locked"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        packageSubtitleView = TextView(this).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(10))
            setLineSpacing(0f, 1.2f)
        }

        val explanationView = TextView(this).apply {
            text = "This application is currently set to LOCKED during your active CallControl session. " +
                "To use this app now, you can return to the CallControl dashboard to ALLOW it, or end the active session."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(bodyTextColor)
            setLineSpacing(0f, 1.35f)
        }

        infoCard.addView(appTitleView)
        infoCard.addView(packageSubtitleView)
        infoCard.addView(explanationView)
        content.addView(infoCard)

        addSpace(content, 16)

        // Safe Exit & Recovery Actions Card
        val actionsCard = createCard()

        val recoveryLabel = TextView(this).apply {
            text = "SAFE EXIT & RECOVERY OPTIONS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
            setLineSpacing(0f, 1.15f)
        }
        actionsCard.addView(recoveryLabel)

        val homeButton = createActionButton(
            label = "RETURN TO HOME SCREEN",
            bgColor = primaryButtonBg
        ) {
            navigateToHomeScreen()
        }
        actionsCard.addView(homeButton, buttonLayoutParams())

        val dashboardButton = createActionButton(
            label = "OPEN CALLCONTROL DASHBOARD",
            bgColor = secondaryButtonBg
        ) {
            openDashboard()
        }
        actionsCard.addView(dashboardButton, buttonLayoutParams())

        val endSessionButton = createActionButton(
            label = "END SESSION & ALLOW ACCESS NOW",
            bgColor = dangerButtonBg
        ) {
            endSessionAndAllowAccess()
        }
        actionsCard.addView(endSessionButton, buttonLayoutParams())

        val settingsButton = createActionButton(
            label = "ACCESSIBILITY SETTINGS (DISABLE SERVICE)",
            bgColor = secondaryButtonBg
        ) {
            openAccessibilitySettings()
        }
        actionsCard.addView(
            settingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(actionsCard)

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

    private fun updateDisplayedAppInfo() {
        if (!::appTitleView.isInitialized) return
        val app = restrictedApp
        if (app != null) {
            appTitleView.text = "${app.displayName} is LOCKED"
            packageSubtitleView.text = if (restrictedPackage.isNotBlank()) {
                "${app.categoryDescription} • Package: $restrictedPackage"
            } else {
                app.categoryDescription
            }
        } else {
            appTitleView.text = "Selected App is LOCKED"
            packageSubtitleView.text = if (restrictedPackage.isNotBlank()) {
                "Package: $restrictedPackage"
            } else {
                "Restricted during active CallControl session"
            }
        }
    }

    private fun navigateToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (_: Exception) {
            openDashboard()
            return
        }
        finish()
    }

    private fun openDashboard() {
        val dashboardIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(dashboardIntent)
        finish()
    }

    private fun endSessionAndAllowAccess() {
        val manager = CallControlStateManager(
            storage = storage,
            enforcementStatusProvider = {
                AccessibilityServiceStatusChecker.currentEnforcementStatus(this)
            }
        )
        manager.endSession()
        Toast.makeText(
            this,
            "CallControl session ended. Restrictions removed.",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Unable to open Android Settings.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        finish()
    }

    private fun createActionButton(
        label: String,
        bgColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(bgColor, 14)
            setOnClickListener { onClick() }
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(cardColor, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun addSpace(parent: LinearLayout, heightDp: Int) {
        val spacer = View(this)
        parent.addView(spacer, LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun roundedBackground(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_APP_KEY = "extra_app_key"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        fun createIntent(
            context: Context,
            appKey: String,
            restrictedPackageName: String
        ): Intent {
            return Intent(context, RestrictedAppActivity::class.java).apply {
                putExtra(EXTRA_APP_KEY, appKey)
                putExtra(EXTRA_PACKAGE_NAME, restrictedPackageName)
            }
        }
    }
}
