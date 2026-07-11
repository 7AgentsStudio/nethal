package com.nethal.feature.pairingdiscovery.selection

import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.CredentialConvention
import com.nethal.core.catalog.DriverStage
import com.nethal.core.catalog.ManagementDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre o filtro em cascata tipo → fabricante → modelo (issues #80-82) contra um catálogo real
 * simplificado, com o mesmo shape do manifesto de produção (`catalog-2026.07.26.json`, ver spec
 * `docs/design/specs/2026-07-11-onboarding-e-pareamento-manual.md` §0): Nokia G-1425G-B (ONT),
 * TP-Link Archer C6 (dois profiles do mesmo modelo comercial, mecanismos diferentes), TP-Link
 * Archer C20, TP-Link Archer C50 v4 (`DRAFT`).
 */
class ManualSelectionCatalogTest {

    private fun profile(
        profileId: String,
        vendor: String,
        model: String,
        deviceType: CatalogDeviceType,
        stage: DriverStage,
    ) = CompatibilityProfile(
        profileId = profileId,
        vendor = vendor,
        model = model,
        deviceType = deviceType,
        productLine = "test",
        platformId = "test-platform",
        driverFamilyId = "$profileId-driver",
        stage = stage,
        stageReason = "fixture de teste",
        physicalTestAccess = false,
        managementDefaults = ManagementDefaults(
            ipConfidence = 0.5,
            ipConfidenceNote = "fixture",
            managementPort = 80,
            managementPortNote = "fixture",
        ),
        credentialConvention = CredentialConvention(
            confidence = 0.0,
            confidenceNote = "fixture",
            policyNote = "fixture",
        ),
        confidenceScoreOverall = 0.5,
        confidenceScoreOverallNote = "fixture",
    )

    private val catalog = listOf(
        profile("nokia_g1425gb_v1", "Nokia", "G-1425G-B", CatalogDeviceType.ONT, DriverStage.READ_ONLY_ALPHA),
        // Archer C6 com dois profiles do mesmo (vendor, model) — dedupe deve escolher o de maior
        // maturidade (stok-luci real, READ_ONLY_ALPHA) e nunca listar os dois separadamente.
        profile("tplink_c6_stok_v1", "TP-Link", "Archer C6", CatalogDeviceType.ROUTER, DriverStage.READ_ONLY_ALPHA),
        profile("tplink_c6_encrypted_v1", "TP-Link", "Archer C6", CatalogDeviceType.ROUTER, DriverStage.DRAFT),
        profile("tplink_c20_v1", "TP-Link", "Archer C20", CatalogDeviceType.ROUTER, DriverStage.READ_ONLY_ALPHA),
        profile("tplink_c50v4_v1", "TP-Link", "Archer C50 v4", CatalogDeviceType.ROUTER, DriverStage.DRAFT),
    )

    @Test
    fun `deviceTypeOptions marca disponivel so tipo com profile real no catalogo`() {
        val options = deviceTypeOptions(catalog)

        val router = options.first { it.type == CatalogDeviceType.ROUTER }
        val ont = options.first { it.type == CatalogDeviceType.ONT }
        val mesh = options.first { it.type == CatalogDeviceType.MESH }
        val ap = options.first { it.type == CatalogDeviceType.AP }

        assertTrue(router.available)
        assertTrue(ont.available)
        assertFalse(mesh.available)
        assertFalse(ap.available)
    }

    @Test
    fun `manufacturerOptions filtra por tipo e nunca inclui fabricante sem profile`() {
        val routerManufacturers = manufacturerOptions(catalog, CatalogDeviceType.ROUTER).map { it.vendor }
        val ontManufacturers = manufacturerOptions(catalog, CatalogDeviceType.ONT).map { it.vendor }

        assertEquals(listOf("TP-Link"), routerManufacturers)
        assertEquals(listOf("Nokia"), ontManufacturers)
        assertTrue(manufacturerOptions(catalog, CatalogDeviceType.MESH).isEmpty())
    }

    @Test
    fun `modelOptions dedupe Archer C6 usando o estagio de maior maturidade`() {
        val models = modelOptions(catalog, CatalogDeviceType.ROUTER, "TP-Link")

        val c6 = models.first { it.profile.model == "Archer C6" }
        assertEquals("tplink_c6_stok_v1", c6.profile.profileId)
        assertEquals(ModelSupportLevel.READ, c6.supportLevel)
        assertTrue(c6.enabled)

        // Só 3 modelos distintos (C6, C20, C50 v4), não 4 — o profile DRAFT do C6 nunca aparece
        // como linha separada.
        assertEquals(3, models.size)
    }

    @Test
    fun `modelOptions marca modelo DRAFT como em pesquisa e desabilitado`() {
        val models = modelOptions(catalog, CatalogDeviceType.ROUTER, "TP-Link")

        val c50 = models.first { it.profile.model == "Archer C50 v4" }
        assertEquals(ModelSupportLevel.IN_RESEARCH, c50.supportLevel)
        assertFalse(c50.enabled)
    }

    @Test
    fun `modelOptions filtra por fabricante case-insensitive`() {
        val models = modelOptions(catalog, CatalogDeviceType.ROUTER, "tp-link")

        assertEquals(3, models.size)
    }

    @Test
    fun `deviceTypeLabel traduz enum para rotulo PT-BR`() {
        assertEquals("Roteador", deviceTypeLabel(CatalogDeviceType.ROUTER))
        assertEquals("ONT", deviceTypeLabel(CatalogDeviceType.ONT))
        assertEquals("Ponto de acesso", deviceTypeLabel(CatalogDeviceType.AP))
    }
}
