package com.nethal.feature.wifinetwork

import com.nethal.core.model.CapabilityId

/**
 * Estado da tela "Wi-Fi & Rede" (issue #84, protótipos `3b`/`3e`) — leitura direta de
 * [com.nethal.core.model.CapabilityId.READ_WIFI_STATUS] pela sessão ativa, mais o estado de cada
 * ação de escrita da rede (canal, SSID, senha) conforme lido pelo driver conectado.
 *
 * Igual ao padrão já usado pela Tela 4 (`CapabilitiesUiState`, hoje em `:app`): nunca finge sessão
 * nem capability — [SessionUnavailable] cobre tanto "sem sessão nenhuma chegou até aqui" quanto
 * "engine presente mas não foi possível autenticar".
 */
sealed interface WifiNetworkUiState {

    /** Lendo `READ_WIFI_STATUS` e o estado de cada ação de escrita via `CapabilityEngine`. */
    data object Loading : WifiNetworkUiState

    /**
     * Nenhuma sessão autenticada disponível para esta tela (`CapabilityEngine` nulo, ou
     * autenticação falhou). A fiação real de "de onde vem a sessão no modo uso diário" é resolvida
     * na consolidação dos 4 módulos de feature (ver `wifiNetworkGraph`) — aqui só a UI honesta do
     * caso "sem sessão".
     */
    data class SessionUnavailable(val reason: String) : WifiNetworkUiState

    data class Loaded(
        /** Rádios lidos com sucesso (`READ_WIFI_STATUS` → `CapabilityPayload.Wifi`). Vazio se a capability não estiver disponível — ver [radiosUnavailableReason] para o motivo. */
        val radios: List<WifiRadioUiModel>,
        /** Motivo de `radios` estar vazio por falha/indisponibilidade de leitura — `null` quando a leitura teve sucesso (mesmo que sem nenhum rádio). */
        val radiosUnavailableReason: String?,
        /** Uma entrada por ação de escrita da seção "Ações da rede" do protótipo (canal, SSID, senha) — nunca decide disponibilidade sozinha, só reflete o que [WifiNetworkViewModel] leu. */
        val actions: List<WifiNetworkActionUiModel>,
    ) : WifiNetworkUiState
}

enum class WifiRadioBandLabel { GHZ_2_4, GHZ_5, GHZ_6, UNKNOWN }

data class WifiRadioUiModel(
    val bandLabel: WifiRadioBandLabel,
    val ssid: String,
    val channel: String,
    val bandwidth: String,
    val security: String,
    val clientCount: String,
    val enabled: Boolean?,
)

/**
 * Uma linha da seção "Ações da rede" (Alterar canal Wi-Fi / Renomear rede / Alterar senha).
 * [available] nunca é `true` nesta rodada — ver KDoc de [WifiNetworkViewModel] — mas o campo já
 * existe com esse nome para o dia em que o Core ganhar um executor de escrita real, sem precisar
 * redesenhar o estado desta tela: quando isso acontecer, [available] passa a refletir a leitura de
 * capability de verdade e a tela troca o item de [com.nethal.feature.wifinetwork.unavailable] por
 * uma linha tocável de verdade, sem esconder nada por trás.
 */
data class WifiNetworkActionUiModel(
    val id: CapabilityId,
    val label: String,
    val available: Boolean,
    val reason: String,
)
