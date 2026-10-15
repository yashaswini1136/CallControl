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
import com.callcontrol.app.accessibility.AccessibilityServiceStatusChecker

@SuppressLint("SetTextI18n")
class AccessibilityOnboardingActivity : ComponentActivity() {

    private val backgroundColor = Color.rgb(11, 15, 20)
    private val cardColor = Color.rgb(21, 27, 35)
    private val secondaryColor = Color.rgb(32, 41, 56)

    private val white = Color.WHITE
    private val bodyTextColor = Color.rgb(214, 222, 232)
    private val muted = Color.rgb(168, 179, 194)
    private val green = Color.rgb(74, 222, 128)
    private val yellow = Color.rgb(245, 158, 11)

    private val primaryButtonBg = Color.rgb(38, 56, 45)
    private val secondaryButtonBg = Color.rgb(38, 50, 56)
    private val enabledBadgeBg = Color.rgb(22, 50, 36)

    private lateinit var serviceStatusTitleView: TextView
    private lateinit var serviceStatusDetailView: TextView
    private lateinit var serviceStatusBadgeView: TextView
    private lateinit var openSettingsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBarColors()
        createOnboardingUI()
        refreshServiceStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
    }

    private fun createOnboardingUI() {
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
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        scrollView.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Header (24sp screen title, 14sp subtitle)
        val title = TextView(this).apply {
            text = "Accessibility Disclosure"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
        val subtitle = TextView(this).apply {
            text = "User-Consented App Restriction Setup"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.2f)
        }
        content.addView(title)
        content.addView(subtitle)

        addSpace(content, 20)

        // Live Service Status Section
        addSectionLabel(content, "CURRENT SERVICE STATUS")
        addSpace(content, 8)

        val statusCard = createCard()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val statusInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = dp(12)
            }
        }

        serviceStatusTitleView = TextView(this).apply {
            text = "Accessibility Service"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }

        serviceStatusDetailView = TextView(this).apply {
            text = "Checking Android Settings..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }

        statusInfo.addView(serviceStatusTitleView)
        statusInfo.addView(serviceStatusDetailView)

        serviceStatusBadgeView = TextView(this).apply {
            text = "NOT ENABLED"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(yellow)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(secondaryColor, 100)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }

        statusRow.addView(statusInfo)
        statusRow.addView(
            serviceStatusBadgeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        statusCard.addView(statusRow)
        content.addView(statusCard)

        // Disclosure Sections
        addSpace(content, 20)
        addSectionLabel(content, "HOW CALLCONTROL USES ACCESSIBILITY")
        addSpace(content, 8)

        addDisclosureCard(
            parent = content,
            heading = "1. What the Accessibility Service Does",
            body = "When a CallControl session is ACTIVE, the service detects when a locked target app (Instagram, YouTube, WhatsApp, or Documents) enters the foreground and displays the CallControl restriction screen."
        )

        addDisclosureCard(
            parent = content,
            heading = "2. What Information It Observes",
            body = "It observes only foreground window state changes (TYPE_WINDOW_STATE_CHANGED package names). Window content retrieval is disabled (canRetrieveWindowContent=false). It never reads screen text, messages, passwords, or keystrokes."
        )

        addDisclosureCard(
            parent = content,
            heading = "3. How Selected Apps Are Detected",
            body = "It checks whether the foreground package matches your configured app list while a session is ACTIVE. Apps set to ALLOWED (such as WhatsApp during a call session) remain completely usable. When the session is IDLE, no apps are restricted."
        )

        addDisclosureCard(
            parent = content,
            heading = "4. Limitations & How to Disable",
            body = "Standard Android apps cannot block other apps without your explicit consent. Enforcement only operates while this service is enabled in Android Settings. You can disable or revoke the service at any time in Android Settings -> Accessibility -> CallControl, or tap END SESSION on the dashboard."
        )

        addSpace(content, 12)

        openSettingsButton = Button(this).apply {
            text = "OPEN ANDROID ACCESSIBILITY SETTINGS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(primaryButtonBg, 14)
            setOnClickListener {
                openAndroidAccessibilitySettings()
            }
        }
        content.addView(
            openSettingsButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )

        val backButton = Button(this).apply {
            text = "BACK TO DASHBOARD"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minHeight = dp(52)
            minimumHeight = dp(52)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBackground(secondaryButtonBg, 14)
            setOnClickListener {
                finish()
            }
        }
        content.addView(
            backButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

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

    private fun refreshServiceStatus() {
        if (!::serviceStatusBadgeView.isInitialized) return
        val enabled = AccessibilityServiceStatusChecker.isServiceEnabled(this)
        if (enabled) {
            serviceStatusTitleView.text = "CallControl Service Enabled"
            serviceStatusDetailView.text =
                "Verified in Android Settings • Active session restrictions are enforced"
            serviceStatusDetailView.setTextColor(green)
            serviceStatusBadgeView.text = "ENABLED"
            serviceStatusBadgeView.setTextColor(green)
            serviceStatusBadgeView.background = roundedBackground(enabledBadgeBg, 100)
            openSettingsButton.text = "OPEN ACCESSIBILITY SETTINGS (MANAGE / DISABLE)"
        } else {
            serviceStatusTitleView.text = "CallControl Service Not Enabled"
            serviceStatusDetailView.text =
                "Disabled in Android Settings • Dashboard changes update policy state only"
            serviceStatusDetailView.setTextColor(yellow)
            serviceStatusBadgeView.text = "NOT ENABLED"
            serviceStatusBadgeView.setTextColor(yellow)
            serviceStatusBadgeView.background = roundedBackground(secondaryColor, 100)
            openSettingsButton.text = "OPEN ANDROID ACCESSIBILITY SETTINGS"
        }
    }

    private fun openAndroidAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Unable to open Android Accessibility Settings on this device.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun addDisclosureCard(
        parent: LinearLayout,
        heading: String,
        body: String
    ) {
        val card = createCard()
        val headingView = TextView(this).apply {
            text = heading
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.2f)
        }
        val bodyView = TextView(this).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(bodyTextColor)
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(0f, 1.35f)
        }
        card.addView(headingView)
        card.addView(bodyView)
        parent.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(10)
            }
        )
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

    private fun addSectionLabel(parent: LinearLayout, text: String) {
        val label = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.15f)
        }
        parent.addView(label)
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
        fun createIntent(context: Context): Intent {
            return Intent(context, AccessibilityOnboardingActivity::class.java)
        }
    }
}
