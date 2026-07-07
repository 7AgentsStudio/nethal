package com.nethal.core.driver.family.tplink.legacycgi

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Movido de `driver/tplink/TplinkC20OntDriverTest.kt` no passo 4 do plano de refatoração HAL
 * (`docs/architecture/hal-layering-model.md` §10) — mesma cobertura de comportamento (retry,
 * classificação de falha, parsing do snapshot completo), adaptada para receber
 * [TpLinkLegacyCgiDriverConfig] explicitamente (antes as seções/campos eram hardcoded na classe).
 */
class TpLinkLegacyCgiDriverFamilyTest {

    /** Mesmo `driverConfig` do profile real `tplink_archer_c20_v1` no catálogo (ver `catalog-2026.07.13.json`). */
    private fun realProfileConfig(): TpLinkLegacyCgiDriverConfig = TpLinkLegacyCgiDriverConfig(
        loginValidationBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(
                TpLinkLegacyCgiSectionConfig("IGD_DEV_INFO", listOf("modelName", "description", "X_TP_isFD")),
                TpLinkLegacyCgiSectionConfig("ETH_SWITCH", listOf("numberOfVirtualPorts")),
                TpLinkLegacyCgiSectionConfig("SYS_MODE", listOf("mode")),
                TpLinkLegacyCgiSectionConfig("/cgi/info", emptyList()),
            ),
        ),
        deviceInfoIndex = 0,
        ethSwitchIndex = 1,
        sysModeIndex = 2,
        wifiStatusBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(TpLinkLegacyCgiSectionConfig("LAN_WLAN", listOf("name", "SSID"))),
        ),
        wifiStatusIndex = 0,
        connectedClientsBundle = TpLinkLegacyCgiBundleConfig(
            sections = listOf(
                TpLinkLegacyCgiSectionConfig(
                    "LAN_HOST_ENTRY",
                    listOf("leaseTimeRemaining", "MACAddress", "hostName", "IPAddress"),
                ),
            ),
        ),
        connectedClientsIndex = 0,
    )

    private fun basicCookie(user: String, pass: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.UTF_8))

    @Test
    fun `rejects public host at construction - never sends credentials outside the LAN`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            TpLinkLegacyCgiDriverFamily("8.8.8.8", realProfileConfig(), transport)
        }
        assertTrue(exception.message!!.contains("8.8.8.8"))
    }

    @Test
    fun `rejects other well-known public hosts too - not a single hardcoded exception`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        listOf("1.1.1.1", "203.0.113.10", "142.250.0.1").forEach { publicHost ->
            assertThrows(IllegalArgumentException::class.java) {
                TpLinkLegacyCgiDriverFamily(publicHost, realProfileConfig(), transport)
            }
        }
    }

    @Test
    fun `accepts RFC1918 private host at construction`() {
        val transport = FakeTpLinkLegacyCgiHttpTransport()

        listOf("192.168.0.1", "10.0.0.1", "172.16.5.5").forEach { privateHost ->
            TpLinkLegacyCgiDriverFamily(privateHost, realProfileConfig(), transport) // não deve lançar
        }
    }

    private fun responsesForSuccessfulSnapshot(config: TpLinkLegacyCgiDriverConfig): Map<String, com.nethal.core.driver.tplink.TplinkHttpResponse> {
        // login() e a leitura de device info usam exatamente o mesmo bundle de blocos
        // (config.loginValidationSections()) — é o único bundle com prova real de sucesso, por
        // isso as duas chamadas produzem o mesmo request body.
        val deviceInfoRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.loginValidationSections())
        val wifiRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.wifiStatusSections())
        val clientsRequestBody = TpLinkLegacyCgiResponseParser.buildRequestBody(config.connectedClientsSections())

        return mapOf(
            deviceInfoRequestBody to deviceInfoBundleResponse(),
            wifiRequestBody to lanWlanResponse(),
            clientsRequestBody to lanHostEntryResponse(),
        )
    }

    @Test
    fun `readSnapshot succeeds on first attempt and parses all confirmed sections`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "secret"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Success)
        val snapshot = (result as TpLinkLegacyCgiReadOutcome.Success).snapshot
        assertEquals("Archer C20", snapshot.deviceInfo?.modelName)
        assertEquals(4, snapshot.deviceInfo?.numberOfVirtualPorts)
        assertEquals("ETH", snapshot.deviceInfo?.mode)
        assertEquals(2, snapshot.wifi.size)
        assertEquals("Casa-2.4G", snapshot.wifi[0].ssid)
        assertEquals(1, snapshot.connectedClients.size)
        assertEquals("AA:BB:CC:**:**:**", snapshot.connectedClients.first().macAddressMasked)
    }

    @Test
    fun `readSnapshot fails fast on invalid credentials without exhausting retries`() = runTest {
        val config = realProfileConfig()
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            expectedAuthorizationCookie = basicCookie("admin", "correct-password"),
            responsesByRequestBody = responsesForSuccessfulSnapshot(config),
        )
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", config, transport, maxAttempts = 2, backoffMillis = { 0L })

        val result = driver.readSnapshot("admin", "wrong")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Failure)
        assertEquals(TpLinkLegacyCgiFailureReason.INVALID_CREDENTIALS, (result as TpLinkLegacyCgiReadOutcome.Failure).reason)
        assertEquals(1, transport.postCallCount) // sem retry para credencial invalida, so 1 chamada
    }

    @Test
    fun `readSnapshot respects conservative max attempts default of two`() = runTest {
        val transport = FakeTpLinkLegacyCgiHttpTransport(
            defaultResponse = com.nethal.core.driver.tplink.TplinkHttpResponse(500, "", emptyMap(), emptyMap()),
        )
        var backoffCalls = 0
        val driver = TpLinkLegacyCgiDriverFamily(
            "192.168.0.1",
            realProfileConfig(),
            transport,
            backoffMillis = { backoffCalls++; 0L },
        )

        val result = driver.readSnapshot("admin", "secret")

        assertTrue(result is TpLinkLegacyCgiReadOutcome.Failure)
        assertEquals(1, backoffCalls) // só 1 backoff entre as 2 tentativas (default maxAttempts=2)
    }

    @Test
    fun `readCapability on an unsupported capability id returns Unavailable, never throws`() = runTest {
        val driver = TpLinkLegacyCgiDriverFamily("192.168.0.1", realProfileConfig(), FakeTpLinkLegacyCgiHttpTransport())

        val result = driver.readCapability(com.nethal.core.model.CapabilityId.READ_WAN_STATUS)

        assertTrue(result is com.nethal.core.catalog.CapabilityReadResult.Unavailable)
    }
}
