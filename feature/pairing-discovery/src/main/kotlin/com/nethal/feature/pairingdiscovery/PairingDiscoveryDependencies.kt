package com.nethal.feature.pairingdiscovery

import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.ManualIdentificationRepository
import com.nethal.core.discovery.DiscoveryEngine
import com.nethal.core.discovery.NetworkEnvironmentReader
import com.nethal.core.fingerprint.FingerprintEngine

/**
 * Dependências que o composition root (`:app`) injeta no grafo (composição manual, sem
 * framework de DI — mesmo padrão de `NetHalViewModelFactory`, ADR 0002). `:core:fingerprint`
 * entra aqui porque a Tela 2b (#75) roda o Fingerprint Engine sobre o `NetworkTarget`
 * descoberto — não é capability de "discovery" propriamente, mas é exigida pelo
 * `EquipmentDetectedViewModel` migrado para este módulo.
 */
data class PairingDiscoveryDependencies(
    val discoveryEngine: DiscoveryEngine,
    val networkEnvironmentReader: NetworkEnvironmentReader,
    val fingerprintEngine: FingerprintEngine,
    val manualIdentificationRepository: ManualIdentificationRepository,
    val driverRegistry: DriverRegistry,
)
