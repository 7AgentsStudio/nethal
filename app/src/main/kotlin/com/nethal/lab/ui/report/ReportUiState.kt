package com.nethal.lab.ui.report

import com.nethal.lab.ui.capabilities.CapabilityItem

/**
 * Estado da Tela 6 — Relatório (spec §11): resultado geral, dados lidos, capabilities, erros,
 * driver usado, aviso de reprovisionamento por operadora (quando aplicável) e botão de envio de
 * relatório anônimo — ver `ReportViewModel` para como cada campo é derivado.
 */
sealed interface ReportUiState {

    /**
     * Nenhum equipamento identificado chegou até esta tela — estado perdido (processo recriado,
     * navegação direta) ou fluxo interrompido antes da Tela 3. Nunca finge ter um relatório: manda
     * voltar ao início do diagnóstico.
     */
    data class Unavailable(val reason: String) : ReportUiState

    data class Ready(
        val vendor: String,
        val model: String,
        val driverFamilyId: String,
        val outcome: ReportOutcome,
        /** Mesma lista de itens lida na Tela 4 — "dados lidos"/"capabilities"/"erros" são recortes desta lista por tipo de resultado, não uma segunda leitura. */
        val items: List<CapabilityItem>,
        /**
         * Texto do aviso de reprovisionamento por operadora, `null` quando não se aplica.
         * Derivado de `CompatibilityProfile.operatorProvisioningRisk` (campo já existente no
         * catálogo, ver `docs/drivers/compatibility-catalog.md`) — nunca de uma heurística nova
         * por fabricante (`/seguranca-nethal`, `CLAUDE.md`: "sem condicional por fabricante").
         */
        val provisioningWarning: String?,
        val sendReportState: SendReportState,
    ) : ReportUiState
}

/** "Resultado geral" da Tela 6 — derivado de quantos itens tiveram leitura bem-sucedida vs. erro de leitura real (ver `ReportViewModel.deriveOutcome`). */
enum class ReportOutcome {
    /** Todo item com `CapabilityReadResult.Success` leu com sucesso e nenhum erro de leitura (`Failure`/`SessionExpired`) ocorreu. */
    FULL_SUCCESS,

    /** Pelo menos uma leitura teve sucesso e pelo menos uma teve erro de leitura real. */
    PARTIAL_SUCCESS,

    /** Nenhuma leitura teve sucesso — equipamento/driver não devolveu nenhum dado real nesta sessão. */
    NO_DATA,
}

/**
 * Estado do botão "Enviar relatório anônimo". Nunca existe um caso de sucesso de envio nesta
 * versão: não há Telemetry Collector implementado
 * (`docs/architecture/adr/0001-fronteira-sanitizacao-telemetria.md`), então nenhum dado sai deste
 * aparelho por este botão — [Unavailable] é o único desfecho possível ao clicar, com mensagem
 * honesta em vez de fingir um envio que não acontece.
 */
sealed interface SendReportState {
    data object Idle : SendReportState
    data class Unavailable(val message: String) : SendReportState
}
