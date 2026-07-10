package com.nethal.lab.ui.capabilities

import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.CredentialConvention
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.ManagementDefaults
import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.WanStatus
import com.nethal.core.model.WifiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeProfile(): CompatibilityProfile = CompatibilityProfile(
        profileId = "fixture_v1",
        vendor = "TP-Link",
        model = "Archer C6",
        deviceType = CatalogDeviceType.ROUTER,
        productLine = "Archer",
        platformId = "fixture-driver",
        driverFamilyId = "fixture-driver",
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
        confidenceScoreOverall = 1.0,
        confidenceScoreOverallNote = "fixture",
    )

    private class FakeDriverRegistry(private val profile: CompatibilityProfile) : DriverRegistry {
        override fun manifestVersion(): String = "fixture"
        override fun generatedAt(): String = "fixture"
        override fun profiles(): List<CompatibilityProfile> = listOf(profile)
        override fun findProfiles(vendor: String, model: String): List<CompatibilityProfile> =
            profiles().filter { it.vendor == vendor && it.model == model }
        override fun findProfile(vendor: String, model: String): CompatibilityProfile? = findProfiles(vendor, model).firstOrNull()
        override fun profilesForVendor(vendor: String): List<CompatibilityProfile> = profiles().filter { it.vendor == vendor }
    }

    /** Devolve resultados fixos por [CapabilityId] — o que não estiver no mapa cai no `Unavailable` honesto de uma Driver Family real. */
    private class FakeDriverFamily(private val readResults: Map<CapabilityId, CapabilityReadResult>) : DriverFamily {
        override suspend fun readCapability(id: CapabilityId): CapabilityReadResult =
            readResults[id] ?: CapabilityReadResult.Unavailable(reason = "fixture não cobre $id")

        override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = DriverFamilyAuthResult.Success
    }

    private suspend fun activeEngine(readResults: Map<CapabilityId, CapabilityReadResult>): CapabilityEngine {
        val engine = CapabilityEngine(FakeDriverFamily(readResults), "admin", "secret")
        engine.testCredentials()
        return engine
    }

    @Test
    fun `no capability engine surfaces SessionUnavailable instead of faking data`() = runTest {
        val viewModel = CapabilitiesViewModel(
            capabilityEngine = null,
            matchedProfileId = "fixture_v1",
            driverRegistry = FakeDriverRegistry(fakeProfile()),
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is CapabilitiesUiState.SessionUnavailable)
    }

    @Test
    fun `full success reads every capability and surfaces vendor and model`() = runTest {
        val successResult = CapabilityReadResult.Success(
            capability = Capability(id = CapabilityId.READ_WIFI_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.Wifi(WifiStatus(radios = emptyList())),
        )
        val engine = activeEngine(mapOf(CapabilityId.READ_WIFI_STATUS to successResult))
        dispatcher.scheduler.advanceUntilIdle()

        val viewModel = CapabilitiesViewModel(
            capabilityEngine = engine,
            matchedProfileId = "fixture_v1",
            driverRegistry = FakeDriverRegistry(fakeProfile()),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is CapabilitiesUiState.Loaded)
        state as CapabilitiesUiState.Loaded
        assertEquals("TP-Link", state.vendor)
        assertEquals("Archer C6", state.model)
        // Vocabulário completo lido, não só um subconjunto escolhido a dedo.
        assertEquals(CapabilityId.entries.size, state.items.size)
        val wifiItem = state.items.first { it.id == CapabilityId.READ_WIFI_STATUS }
        assertTrue(wifiItem.result is CapabilityReadResult.Success)
        // Capability sem resultado no fixture cai honestamente em Unavailable, nunca inventada como disponível.
        val lanItem = state.items.first { it.id == CapabilityId.READ_LAN_STATUS }
        assertTrue(lanItem.result is CapabilityReadResult.Unavailable)
    }

    @Test
    fun `partial failure keeps successful items and surfaces the honest failure reason for the rest`() = runTest {
        val successResult = CapabilityReadResult.Success(
            capability = Capability(id = CapabilityId.READ_WAN_STATUS, state = CapabilityState.AVAILABLE, confidence = 1.0),
            payload = CapabilityPayload.Wan(WanStatus(ipv4Address = "203.0.113.5")),
        )
        val failureResult = CapabilityReadResult.Failure(reason = "timeout ao ler LAN")
        val engine = activeEngine(
            mapOf(
                CapabilityId.READ_WAN_STATUS to successResult,
                CapabilityId.READ_LAN_STATUS to failureResult,
            ),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val viewModel = CapabilitiesViewModel(
            capabilityEngine = engine,
            matchedProfileId = "fixture_v1",
            driverRegistry = FakeDriverRegistry(fakeProfile()),
        )
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value as CapabilitiesUiState.Loaded
        assertTrue(state.items.first { it.id == CapabilityId.READ_WAN_STATUS }.result is CapabilityReadResult.Success)
        val lanResult = state.items.first { it.id == CapabilityId.READ_LAN_STATUS }.result
        assertTrue(lanResult is CapabilityReadResult.Failure)
        assertEquals("timeout ao ler LAN", (lanResult as CapabilityReadResult.Failure).reason)
    }

    @Test
    fun `closeSession delegates to the underlying engine`() = runTest {
        val engine = activeEngine(emptyMap())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(engine.isSessionActive)

        val viewModel = CapabilitiesViewModel(
            capabilityEngine = engine,
            matchedProfileId = "fixture_v1",
            driverRegistry = FakeDriverRegistry(fakeProfile()),
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.closeSession()
        assertEquals(false, engine.isSessionActive)
    }
}
