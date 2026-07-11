package com.nethal.feature.pairingdiscovery.selection

import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.catalog.CompatibilityProfile
import com.nethal.core.catalog.DriverStage

/**
 * Filtro tipo → fabricante → modelo do cluster de seleção manual (2g/2h/2i, issues #80-82),
 * sempre em cima do catálogo real (`DriverRegistry.profiles()`) — nunca lista hardcoded (spec
 * `docs/design/specs/2026-07-11-onboarding-e-pareamento-manual.md` §0/Lote 2).
 */

/** Os 4 tipos exibidos em 2g, na ordem do protótipo (`Modem` do protótipo virou `Roteador`+`AP` reais). */
private val SELECTABLE_DEVICE_TYPES: List<Pair<CatalogDeviceType, String>> = listOf(
    CatalogDeviceType.ROUTER to "Roteador",
    CatalogDeviceType.ONT to "ONT",
    CatalogDeviceType.MESH to "Mesh",
    CatalogDeviceType.AP to "Ponto de acesso",
)

data class DeviceTypeOption(
    val type: CatalogDeviceType,
    val label: String,
    val available: Boolean,
)

/** Rótulo PT-BR de um tipo, para telas que só recebem `CatalogDeviceType` via nav args (2h/2i). */
fun deviceTypeLabel(type: CatalogDeviceType): String =
    SELECTABLE_DEVICE_TYPES.firstOrNull { it.first == type }?.second ?: type.name

/**
 * Disponibilidade calculada a partir do catálogo real (não hardcoded) — hoje só ROUTER/ONT têm
 * profile, mas se um driver de Mesh/AP nascer amanhã, esta tela reflete sem mudança de código.
 */
fun deviceTypeOptions(profiles: List<CompatibilityProfile>): List<DeviceTypeOption> =
    SELECTABLE_DEVICE_TYPES.map { (type, label) ->
        DeviceTypeOption(type = type, label = label, available = profiles.any { it.deviceType == type })
    }

data class ManufacturerOption(val vendor: String)

/** Fabricantes reais do tipo escolhido — "Outro / não sei" é tratado à parte pela tela (não é dado de catálogo). */
fun manufacturerOptions(profiles: List<CompatibilityProfile>, type: CatalogDeviceType): List<ManufacturerOption> =
    profiles
        .filter { it.deviceType == type }
        .map { it.vendor }
        .distinct()
        .sorted()
        .map { ManufacturerOption(vendor = it) }

enum class ModelSupportLevel { READ, READ_WRITE, IN_RESEARCH }

fun ModelSupportLevel.uiLabel(): String = when (this) {
    ModelSupportLevel.READ -> "Leitura"
    ModelSupportLevel.READ_WRITE -> "Leitura e escrita"
    ModelSupportLevel.IN_RESEARCH -> "Em pesquisa"
}

data class ModelOption(
    val profile: CompatibilityProfile,
    val supportLevel: ModelSupportLevel,
) {
    val enabled: Boolean get() = supportLevel != ModelSupportLevel.IN_RESEARCH
}

/** Maturidade relativa de estágio, só para escolher o melhor profile num grupo (vendor, model) duplicado. */
private val STAGE_MATURITY_RANK: Map<DriverStage, Int> = mapOf(
    DriverStage.BLOCKED to -2,
    DriverStage.DEPRECATED to -1,
    DriverStage.DRAFT to 0,
    DriverStage.DISCOVERY_ONLY to 1,
    DriverStage.READ_ONLY_ALPHA to 2,
    DriverStage.READ_ONLY_BETA to 2,
    DriverStage.WRITE_BETA to 3,
    DriverStage.STABLE to 3,
)

private fun supportLevelFor(stage: DriverStage): ModelSupportLevel = when (stage) {
    DriverStage.READ_ONLY_ALPHA, DriverStage.READ_ONLY_BETA -> ModelSupportLevel.READ
    DriverStage.WRITE_BETA, DriverStage.STABLE -> ModelSupportLevel.READ_WRITE
    DriverStage.DRAFT, DriverStage.DISCOVERY_ONLY, DriverStage.DEPRECATED, DriverStage.BLOCKED ->
        ModelSupportLevel.IN_RESEARCH
}

/**
 * Modelos do fabricante escolhido, deduplicados por `(vendor, model)` usando o estágio de maior
 * maturidade do grupo (decisão registrada na spec — caso do Archer C6, que tem dois profiles:
 * `stok-luci` real vs. `encrypted-web` sem unidade confirmada). O usuário nunca escolhe entre
 * `driverFamilyId`s manualmente.
 */
fun modelOptions(profiles: List<CompatibilityProfile>, type: CatalogDeviceType, vendor: String): List<ModelOption> =
    profiles
        .filter { it.deviceType == type && it.vendor.equals(vendor, ignoreCase = true) }
        .groupBy { it.model.lowercase() }
        .values
        .map { group -> group.maxBy { STAGE_MATURITY_RANK[it.stage] ?: -2 } }
        .sortedBy { it.model }
        .map { profile -> ModelOption(profile = profile, supportLevel = supportLevelFor(profile.stage)) }
