package com.nethal.lab.ui.authentication

import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.CredentialConvention
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyAuthResult
import com.nethal.core.catalog.DriverFamilyFactory
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.ManagementDefaults
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import com.nethal.core.protocol.http.HttpTransport
import com.nethal.core.protocol.http.HttpTransportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val target = NetworkTarget(ip = "192.168.1.1", role = TargetRole.PRIMARY_GATEWAY, source = TargetSource.GATEWAY)

    private val fakeTransport = object : HttpTransport {
        override fun get(url: String, extraHeaders: Map<String, String>) = HttpTransportResponse(404, "", emptyMap(), emptyMap())
        override fun post(url: String, body: String, cookies: Map<String, String>, extraHeaders: Map<String, String>) =
            HttpTransportResponse(404, "", emptyMap(), emptyMap())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeProfile(profileId: String, driverFamilyId: String): CompatibilityProfile = CompatibilityProfile(
        profileId = profileId,
        vendor = "TP-Link",
        model = "Archer C6",
        deviceType = CatalogDeviceType.ROUTER,
        productLine = "Archer",
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

    /** Sempre devolve [result] imediatamente — cobre os casos de sucesso e credencial inválida. */
    private class FakeDriverFamilyWithRealAuth(private val result: DriverFamilyAuthResult) : DriverFamily {
        override suspend fun readCapability(id: CapabilityId): CapabilityReadResult =
            CapabilityReadResult.Unavailable(reason = "não usado neste teste")

        override suspend fun authenticate(username: String, password: String): DriverFamilyAuthResult = result
    }

    /** Não sobrescreve `authenticate()` — exercita o default honesto de `DriverFamily` (driver sem sessão real). */
    private class FakeDriverFamilyWithoutRealAuth : DriverFamily {
        override suspend fun readCapability(id: CapabilityId): CapabilityReadResult =
            CapabilityReadResult.Unavailable(reason = "não usado neste teste")
    }

    private class FakeDriverFamilyFactory(
        override val familyId: String,
        private val driverFamily: DriverFamily,
    ) : DriverFamilyFactory {
        override fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily = driverFamily
    }

    private fun viewModelFor(profile: CompatibilityProfile, driverFamily: DriverFamily): AuthenticationViewModel {
        val driverRegistry = FakeDriverRegistry(profile)
        val driverFamilyRegistry = DriverFamilyRegistry(listOf(FakeDriverFamilyFactory(profile.driverFamilyId, driverFamily)))
        return AuthenticationViewModel(
            target = target,
            matchedProfileId = profile.profileId,
            driverRegistry = driverRegistry,
            driverFamilyRegistry = driverFamilyRegistry,
            httpTransport = fakeTransport,
        )
    }

    @Test
    fun `testCredentials with valid credentials moves to Success and activates the session`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.Success))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthenticationUiState.Ready)
        assertEquals(CredentialTestState.Success, (state as AuthenticationUiState.Ready).credentialTestState)
        assertTrue(viewModel.isSessionActive)
    }

    @Test
    fun `testCredentials with invalid credentials surfaces the reason and never activates a session`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(
            profile,
            FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.InvalidCredentials("senha incorreta")),
        )
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "senha-errada")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthenticationUiState.Ready)
        val testState = (state as AuthenticationUiState.Ready).credentialTestState
        assertTrue(testState is CredentialTestState.InvalidCredentials)
        assertEquals("senha incorreta", (testState as CredentialTestState.InvalidCredentials).reason)
        assertFalse(viewModel.isSessionActive)
    }

    @Test
    fun `testCredentials against a driver without real session support shows the honest unsupported message`() = runTest {
        val profile = fakeProfile("fixture_unsupported_v1", "fixture-unsupported-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithoutRealAuth())
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is AuthenticationUiState.Ready)
        val testState = (state as AuthenticationUiState.Ready).credentialTestState
        assertTrue(testState is CredentialTestState.Failure)
        assertTrue(
            "mensagem deveria explicar que o driver não implementa sessão real, foi: ${(testState as CredentialTestState.Failure).reason}",
            testState.reason.contains("não implementa gerenciamento de sessão real"),
        )
        assertFalse(viewModel.isSessionActive)
    }

    @Test
    fun `closeSession discards the active session`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.Success))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.isSessionActive)

        viewModel.closeSession()

        assertFalse(viewModel.isSessionActive)
    }

    @Test
    fun `captureAuthenticatedSession hands off the active engine and closeSession becomes a no-op afterwards`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.Success))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.isSessionActive)

        val handedOff = viewModel.captureAuthenticatedSession()
        assertTrue(handedOff != null && handedOff.isSessionActive)

        // closeSession() não deve derrubar a sessão que acabou de ser entregue à Tela 4 — bug real
        // encontrado ao ligar a Tela 4: o DisposableEffect da Tela 5 dispara onDispose ao navegar
        // para frente, não só ao voltar.
        viewModel.closeSession()
        assertTrue(handedOff!!.isSessionActive)
        assertTrue(viewModel.isSessionActive)
    }

    @Test
    fun `retesting credentials after a previous handoff still closes the new session normally`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.Success))
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        val firstHandoff = viewModel.captureAuthenticatedSession()
        assertTrue(firstHandoff != null)

        // Simula "Continuar → voltar → Testar de novo": uma segunda sessão nasce na mesma
        // instância do ViewModel depois de uma entrega anterior já ter acontecido.
        viewModel.testCredentials("admin", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.isSessionActive)

        // Sem o reset de sessionHandedOff, esta chamada seria um no-op e a segunda sessão vazaria.
        viewModel.closeSession()
        assertFalse(viewModel.isSessionActive)
    }

    @Test
    fun `captureAuthenticatedSession returns null when no session was ever activated`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = viewModelFor(profile, FakeDriverFamilyWithRealAuth(DriverFamilyAuthResult.Failure("indisponível")))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.captureAuthenticatedSession())
    }

    @Test
    fun `missing matchedProfileId from Tela 3 never crashes and surfaces DriverUnavailable`() = runTest {
        val profile = fakeProfile("fixture_working_v1", "fixture-working-driver")
        val viewModel = AuthenticationViewModel(
            target = target,
            matchedProfileId = null,
            driverRegistry = FakeDriverRegistry(profile),
            driverFamilyRegistry = DriverFamilyRegistry(emptyList()),
            httpTransport = fakeTransport,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is AuthenticationUiState.DriverUnavailable)
    }
}
