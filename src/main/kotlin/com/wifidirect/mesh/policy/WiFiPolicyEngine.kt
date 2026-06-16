package com.wifidirect.mesh.policy

import com.wifidirect.mesh.models.*

class WiFiCapabilityManager(
    private var wifiDirectSupported: Boolean = true,
    private var nanSupported: Boolean = false,
    private var hotspotSupported: Boolean = true,
    private var sameLanSupported: Boolean = true,
    private var maxBandwidth: Float = 150.0f
) {
    fun getCapabilities(): WiFiCapability {
        return WiFiCapability(
            wifiDirectSupported = wifiDirectSupported,
            nanSupported = nanSupported,
            hotspotSupported = hotspotSupported,
            sameLanSupported = sameLanSupported,
            maxSupportedBandwidthMbps = maxBandwidth
        )
    }

    fun setWifiDirectSupported(supported: Boolean) { wifiDirectSupported = supported }
    fun setNanSupported(supported: Boolean) { nanSupported = supported }
    fun setHotspotSupported(supported: Boolean) { hotspotSupported = supported }
    fun setSameLanSupported(supported: Boolean) { sameLanSupported = supported }
}

class WiFiPolicyEngine(private val capabilityManager: WiFiCapabilityManager) {

    fun evaluateWiFiEligibility(
        batteryLevel: Int,
        isCharging: Boolean,
        tempCelsius: Float,
        userPolicy: UserWiFiPolicy,
        hasActiveSOS: Boolean,
        isBackground: Boolean
    ): WiFiPolicyState {
        val thermalState = when {
            tempCelsius > 50.0f -> ThermalState.CRITICAL
            tempCelsius > 42.0f -> ThermalState.HIGH
            else -> ThermalState.NORMAL
        }
        val isLowPower = batteryLevel < 25 && !isCharging

        val isEligible = when {
            thermalState == ThermalState.CRITICAL && !hasActiveSOS -> false
            batteryLevel < 5 && !hasActiveSOS -> false
            userPolicy == UserWiFiPolicy.SOS_ONLY && !hasActiveSOS -> false
            isLowPower && !hasActiveSOS -> false
            else -> true
        }

        return WiFiPolicyState(
            isEligible = isEligible,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            thermalState = thermalState,
            isBackground = isBackground,
            isLowPowerMode = isLowPower,
            userWiFiPolicy = userPolicy,
            hasActiveSOS = hasActiveSOS
        )
    }

    fun calculateDiscoveryInterval(policy: WiFiPolicyState, congestionScore: Float): Long {
        if (policy.hasActiveSOS) return 5000L // 5 seconds in SOS mode (aggressive scanning)
        if (!policy.isEligible) return -1L // Disabled / Ineligible

        val baseInterval = when (policy.isLowPowerMode) {
            true -> 120000L // 2 minutes in Low Power
            false -> 15000L // 15 seconds normal
        }

        val congestionMultiplier = 1.0f + congestionScore
        val thermalMultiplier = when (policy.thermalState) {
            ThermalState.CRITICAL -> 5.0f
            ThermalState.HIGH -> 2.5f
            ThermalState.NORMAL -> 1.0f
        }

        return (baseInterval * congestionMultiplier * thermalMultiplier).toLong()
    }
}
