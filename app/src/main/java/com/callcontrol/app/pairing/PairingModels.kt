package com.callcontrol.app.pairing

/**
 * Explicit roles in a CallControl device association.
 */
enum class DeviceRole(val displayName: String, val roleDescription: String) {
    /**
     * The protected target device running CallControl's accessibility restriction service.
     * Retains absolute authority over pairing approval, local app policy, and immediate revocation.
     */
    FRIEND_DEVICE(
        displayName = "Friend Device (Protected)",
        roleDescription = "Enforces app restrictions during active call sessions. Authorizes remote control."
    ),

    /**
     * The supervising device requesting session transitions and policy adjustments.
     * Has no authority unless explicitly approved by the Friend Device.
     */
    CONTROLLER(
        displayName = "Controller",
        roleDescription = "Monitors session status and requests app access policy changes."
    )
}

/**
 * Complete lifecycle states of a device-to-device pairing association.
 */
enum class PairingState(val badgeText: String, val description: String) {
    /**
     * No active pairing or pairing credentials exist.
     */
    UNPAIRED(
        badgeText = "UNPAIRED",
        description = "No remote device is paired."
    ),

    /**
     * A pairing credential has been issued and is awaiting explicit approval on the Friend Device.
     */
    PAIRING_PENDING(
        badgeText = "PENDING",
        description = "Pairing code active • Awaiting approval."
    ),

    /**
     * Both devices have verified credentials and established an authenticated, active association.
     */
    CONNECTED(
        badgeText = "CONNECTED",
        description = "Authenticated pairing active • Session synchronization enabled."
    ),

    /**
     * The Friend Device explicitly rejected the pairing request.
     */
    REJECTED(
        badgeText = "REJECTED",
        description = "Pairing request was declined by the Friend Device."
    ),

    /**
     * The pairing code or handshake timed out without completing.
     */
    EXPIRED(
        badgeText = "EXPIRED",
        description = "Pairing code expired before verification."
    ),

    /**
     * The association was temporarily lost due to transport disconnect or timeout.
     * Local policy remains safe; remote commands cannot be executed.
     */
    DISCONNECTED(
        badgeText = "DISCONNECTED",
        description = "Connection lost • Remote synchronization suspended."
    ),

    /**
     * The pairing association was permanently terminated by either party.
     * All shared secrets and session tokens have been securely destroyed.
     */
    REVOKED(
        badgeText = "REVOKED",
        description = "Pairing revoked • Re-pairing required to restore remote control."
    )
}

/**
 * Action requested for a remote call session transition.
 */
enum class SessionAction {
    START,
    END
}
