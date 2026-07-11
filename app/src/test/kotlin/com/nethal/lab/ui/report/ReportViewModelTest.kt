package com.nethal.lab.ui.report

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.CredentialConvention
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.ManagementDefaults
import com.nethal.core.catalog.OperatorProvisioningRisk
import com.nethal.core.catalog.ProvisioningRiskLevel
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.WanStatus
import com.nethal.lab.ui.capabilities.CapabilityItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportViewModelTest {

    private fun fakeProfile(
        profileId: String = "fixture_v1",
        vendor: String = "TP-Link",
        model: String = "Archer C6",
        driverFamilyId: String = "fixture-driver",
        operatorProvisioningRisk: OperatorProvisioningRisk? = null,
    ): CompatibilityProfile = CompatibilityProfile(
        profileId = profileId,
        vendor = vendor,
        model = model,
        deviceType = CatalogDeviceType.ROUTER,
        productLine = "fixture",
        platformId = driverFamilyId,
        driverFamilyId = driverFamilyId,
        stage = DriverStage.DISCOVERY_ONLY,
        stageReason = "fixture de teste",
        physicalTestAccess = false,
        managementDefaults = ManagementDefaults(
            candidateIps = listOf("192.168.1.1"),
            ipConfidence = 1.0,
            ipConfidenceNote = "fixture",
            managementPort = 80,
            managementPortNote = "fixture",
        ),
        credentialConvention = CredentialConvention(
            confidence = 1.0,
            confidenceNote = "fixture",
            policyNote = "fixture",
        ),
        operatorProvisioningRisk = operatorProvisioningRisk,
        confidenceScoreOverall = 1.0,
        confidenceScoreOverallNote = "fixture",
    )

    private class FakeDriverRegistry(private val profile: CompatibilityProfile?) : DriverRegistry {
        override fun manifestVersion(): String = "fixture"
        override fun generatedAt(): String = "fixture"
        override fun profiles(): List<CompatibilityProfile> = listOfNotNull(profile)
        override fun findProfiles(vendor: String, model: String): List<CompatibilityProfile> =
            profiles().filter { it.vendor == vendor && it.model == model }
        override fun findProfile(vendor: String, model: String): CompatibilityProfile? = findProfiles(vendor, model).firstOrNull()
        override fun profilesForVendor(vendor: String): List<CompatibilityProfile> = profiles().filter { it.vendor == vendor }
    }

    private fun successItem(id: CapabilityId): CapabilityItem = CapabilityItem(
        id = id,
        result = CapabilityReadResult.Success(
            capability = Capability(id = id, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.Wan(WanStatus(ipv4Address = "203.0.113.5")),
        ),
    )

    private fun failureItem(id: CapabilityId, reason: String): CapabilityItem =
        CapabilityItem(id = id, result = CapabilityReadResult.Failure(reason = reason))

    private fun unavailableItem(id: CapabilityId, reason: String): CapabilityItem =
        CapabilityItem(id = id, result = CapabilityReadResult.Unavailable(reason = reason))

    @Test
    fun `missing profile surfaces Unavailable instead of a fake report`() {
        val viewModel = ReportViewModel(
            matchedProfileId = null,
            driverRegistry = FakeDriverRegistry(null),
            items = emptyList(),
        )

        assertTrue(viewModel.uiState.value is ReportUiState.Unavailable)
    }

    @Test
    fun `full success outcome when every read succeeded and no read errors happened`() {
        val profile = fakeProfile(operatorProvisioningRisk = OperatorProvisioningRisk(ProvisioningRiskLevel.LOW, "roteador de varejo"))
        val items = listOf(
            successItem(CapabilityId.READ_WAN_STATUS),
            successItem(CapabilityId.READ_LAN_STATUS),
            unavailableItem(CapabilityId.SET_WIFI_PASSWORD, "driver não suporta escrita ainda"),
        )
        val viewModel = ReportViewModel(matchedProfileId = profile.profileId, driverRegistry = FakeDriverRegistry(profile), items = items)

        val state = viewModel.uiState.value
        assertTrue(state is ReportUiState.Ready)
        state as ReportUiState.Ready
        assertEquals(ReportOutcome.FULL_SUCCESS, state.outcome)
        assertEquals("TP-Link", state.vendor)
        assertEquals("Archer C6", state.model)
        assertNull(state.provisioningWarning)
    }

    @Test
    fun `partial success outcome and CPE-ISP provisioning warning when risk is not LOW`() {
        val profile = fakeProfile(
            vendor = "Nokia",
            model = "G-1425G-A",
            operatorProvisioningRisk = OperatorProvisioningRisk(ProvisioningRiskLevel.HIGH, "ONT gerenciada por ACS da operadora"),
        )
        val items = listOf(
            successItem(CapabilityId.READ_WAN_STATUS),
            failureItem(CapabilityId.READ_LAN_STATUS, "timeout ao ler LAN"),
        )
        val viewModel = ReportViewModel(matchedProfileId = profile.profileId, driverRegistry = FakeDriverRegistry(profile), items = items)

        val state = viewModel.uiState.value as ReportUiState.Ready
        assertEquals(ReportOutcome.PARTIAL_SUCCESS, state.outcome)
        assertTrue(state.provisioningWarning != null)
        assertTrue(state.provisioningWarning!!.contains("ONT gerenciada por ACS da operadora"))
    }

    @Test
    fun `no data outcome when nothing was read successfully`() {
        val profile = fakeProfile()
        val items = listOf(failureItem(CapabilityId.READ_WAN_STATUS, "equipamento inalcançável"))
        val viewModel = ReportViewModel(matchedProfileId = profile.profileId, driverRegistry = FakeDriverRegistry(profile), items = items)

        val state = viewModel.uiState.value as ReportUiState.Ready
        assertEquals(ReportOutcome.NO_DATA, state.outcome)
    }

    @Test
    fun `sendAnonymousReport never fakes a real upload`() {
        val profile = fakeProfile()
        val viewModel = ReportViewModel(matchedProfileId = profile.profileId, driverRegistry = FakeDriverRegistry(profile), items = emptyList())

        viewModel.sendAnonymousReport()

        val state = viewModel.uiState.value as ReportUiState.Ready
        val sendState = state.sendReportState
        assertTrue(sendState is SendReportState.Unavailable)
        assertTrue((sendState as SendReportState.Unavailable).message.contains("ainda não está disponível"))
    }
}
