package com.nethal.lab.ui.report

import androidx.lifecycle.ViewModel
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.OperatorProvisioningRisk
import com.nethal.core.catalog.ProvisioningRiskLevel
import com.nethal.lab.ui.capabilities.CapabilityItem
import com.nethal.lab.ui.capabilities.isReadError
import com.nethal.lab.ui.capabilities.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val SEND_REPORT_UNAVAILABLE_MESSAGE = "Envio de relatório anônimo ainda não está disponível " +
    "nesta versão do NetHAL Lab — a camada que sanitiza e envia telemetria (Telemetry Collector) " +
    "ainda não foi implementada. Nenhum dado saiu deste aparelho."

/**
 * Orquestra a Tela 6 — Relatório (spec §11). Não lê nada do equipamento: os `items` já foram lidos
 * pela Tela 4 (Capabilities) e chegam prontos por construtor — mesmo padrão de estado
 * compartilhado que `NetHalNavHost` já usa para `NetworkTarget`/`matchedProfileId` entre as telas
 * anteriores. Não recebe nem depende de nenhum `CapabilityEngine`: a sessão já foi encerrada pela
 * Tela 4 (`CapabilitiesViewModel.closeSession`) antes de chegar aqui.
 */
class ReportViewModel(
    matchedProfileId: String?,
    driverRegistry: DriverRegistry,
    private val items: List<CapabilityItem>,
) : ViewModel() {

    private val _uiState = MutableStateFlow(buildInitialState(matchedProfileId, driverRegistry, items))
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    /**
     * Botão "Enviar relatório anônimo" da Tela 6. Nunca envia nada de verdade — não existe
     * Telemetry Collector implementado nesta versão
     * (`docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`), então fingir um envio
     * bem-sucedido seria desonesto com o usuário. Mostra a mensagem real do estado do produto.
     */
    fun sendAnonymousReport() {
        val current = _uiState.value as? ReportUiState.Ready ?: return
        _uiState.value = current.copy(sendReportState = SendReportState.Unavailable(SEND_REPORT_UNAVAILABLE_MESSAGE))
    }
}

private fun buildInitialState(
    matchedProfileId: String?,
    driverRegistry: DriverRegistry,
    items: List<CapabilityItem>,
): ReportUiState {
    val profile = matchedProfileId?.let { id -> driverRegistry.profiles().firstOrNull { it.profileId == id } }
        ?: return ReportUiState.Unavailable(
            reason = "Nenhum equipamento identificado chegou até o relatório. Volte ao início do diagnóstico.",
        )

    return ReportUiState.Ready(
        vendor = profile.vendor,
        model = profile.model,
        driverFamilyId = profile.driverFamilyId,
        outcome = deriveOutcome(items),
        items = items,
        provisioningWarning = provisioningWarningFor(profile.operatorProvisioningRisk),
        sendReportState = SendReportState.Idle,
    )
}

/** Ver KDoc de [ReportOutcome] para o critério de cada caso. */
private fun deriveOutcome(items: List<CapabilityItem>): ReportOutcome {
    val successCount = items.count { it.isSuccess() }
    val errorCount = items.count { it.isReadError() }
    return when {
        successCount > 0 && errorCount == 0 -> ReportOutcome.FULL_SUCCESS
        successCount > 0 && errorCount > 0 -> ReportOutcome.PARTIAL_SUCCESS
        else -> ReportOutcome.NO_DATA
    }
}

/**
 * Deriva o aviso de reprovisionamento por operadora de `operatorProvisioningRisk`, campo já
 * existente no catálogo (`docs/drivers/compatibility-catalog.md`) — não inventa detecção nova por
 * fabricante. `LOW` (perfil de varejo, ex.: TP-Link Archer) nunca mostra aviso; `MEDIUM`/`HIGH`
 * (perfil CPE-ISP, ex.: Nokia GPON gerenciado por ACS) mostra, citando o motivo já documentado no
 * catálogo (`risk.note`).
 */
private fun provisioningWarningFor(risk: OperatorProvisioningRisk?): String? {
    if (risk == null || risk.risk == ProvisioningRiskLevel.LOW) return null
    return "Este equipamento pode ser gerenciado remotamente pela operadora (ACS) — alterações locais " +
        "podem ser revertidas automaticamente. ${risk.note}"
}
