package com.nethal.feature.wifinetwork

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityId
import com.nethal.core.model.CapabilityPayload
import com.nethal.core.model.CapabilityState
import com.nethal.core.model.WifiBand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ordem fixa das 3 linhas de "Ações da rede" do protótipo `3b`/`3e` — sempre as mesmas 3 capabilities
 * de escrita nesta tela (canal, SSID, senha). Ativar rede guest e demais ações citadas na issue como
 * "etc." não têm `CapabilityId` correspondente hoje — fora de escopo (ver KDoc da issue #84,
 * "Implementação de capability nova no SDK").
 */
private val NETWORK_ACTION_CAPABILITIES = listOf(
    CapabilityId.SET_WIFI_CHANNEL,
    CapabilityId.SET_WIFI_SSID,
    CapabilityId.SET_WIFI_PASSWORD,
)

/**
 * Orquestra a tela "Wi-Fi & Rede" (issue #84): lê `READ_WIFI_STATUS` para os cards por banda e o
 * estado de cada [NETWORK_ACTION_CAPABILITIES] para a seção "Ações da rede", usando a MESMA sessão
 * (`CapabilityEngine`) que a tela recebe já resolvida — nunca abre sessão nova nem pede credencial
 * aqui (mesmo padrão de `CapabilitiesViewModel`, hoje em `:app`).
 *
 * ## Por que nenhuma ação aparece como executável nesta rodada
 *
 * `DriverFamily` (`:core:catalog`) só expõe `readCapability`/`authenticate` — não existe hoje nenhum
 * executor de escrita no Core (`SET_WIFI_CHANNEL`/`SET_WIFI_SSID`/`SET_WIFI_PASSWORD` são lidos, mas
 * não há como de fato chamar a ação, em nenhum driver, nem no TP-Link Archer C6). Fingir um botão
 * "disponível" que não faz nada ao ser confirmado seria pior do que deixar claro que a ação ainda
 * não está implementada — por isso toda linha da seção "Ações da rede" usa
 * `com.nethal.feature.wifinetwork.unavailable.UnavailableResourceState`, com o motivo real:
 * o `reason` do driver quando a capability é lida como indisponível, ou uma nota explícita de "Core
 * ainda não implementa a execução" quando o driver já declara a capability como disponível para
 * leitura. Implementar o executor de escrita de verdade é tarefa própria do Core (Bruno), fora do
 * escopo desta tela — quando existir, só [WifiNetworkActionUiModel.available] muda de `false` para
 * `true`, o resto do contrato desta tela não muda.
 */
class WifiNetworkViewModel(
    private val capabilityEngine: CapabilityEngine?,
) : ViewModel() {

    private val _uiState = MutableStateFlow<WifiNetworkUiState>(WifiNetworkUiState.Loading)
    val uiState: StateFlow<WifiNetworkUiState> = _uiState.asStateFlow()

    init {
        loadWifiNetwork()
    }

    private fun loadWifiNetwork() {
        val engine = capabilityEngine
        if (engine == null) {
            _uiState.value = WifiNetworkUiState.SessionUnavailable(
                reason = "Nenhuma sessão autenticada chegou até esta tela. Volte e conecte-se a um " +
                    "equipamento antes de ver Wi-Fi & Rede.",
            )
            return
        }

        _uiState.value = WifiNetworkUiState.Loading
        viewModelScope.launch {
            val wifiResult = engine.readCapability(CapabilityId.READ_WIFI_STATUS)
            val actionResults = NETWORK_ACTION_CAPABILITIES.map { id -> id to engine.readCapability(id) }

            _uiState.value = WifiNetworkUiState.Loaded(
                radios = wifiResult.toRadioUiModels(),
                radiosUnavailableReason = wifiResult.toUnavailableReasonOrNull(),
                actions = actionResults.map { (id, result) -> result.toActionUiModel(id) },
            )
        }
    }

    /** Encerra a sessão em uso — chamar de `DisposableEffect`/`onDispose` da tela ao sair de composição, mesmo padrão de `CapabilitiesViewModel.closeSession`. */
    fun closeSession() {
        capabilityEngine?.closeSession()
    }
}

private fun CapabilityReadResult.toRadioUiModels(): List<WifiRadioUiModel> {
    val payload = (this as? CapabilityReadResult.Success)?.payload as? CapabilityPayload.Wifi ?: return emptyList()
    return payload.status.radios.map { radio ->
        WifiRadioUiModel(
            bandLabel = when (radio.band) {
                WifiBand.GHZ_2_4 -> WifiRadioBandLabel.GHZ_2_4
                WifiBand.GHZ_5 -> WifiRadioBandLabel.GHZ_5
                WifiBand.GHZ_6 -> WifiRadioBandLabel.GHZ_6
                WifiBand.UNKNOWN -> WifiRadioBandLabel.UNKNOWN
            },
            ssid = radio.ssid ?: "SSID não lido",
            channel = radio.channel?.toString() ?: "não lido",
            bandwidth = radio.bandwidth ?: "não lida",
            security = radio.security ?: "não lida",
            clientCount = radio.clientCount?.toString() ?: "não lido",
            enabled = radio.enabled,
        )
    }
}

private fun CapabilityReadResult.toUnavailableReasonOrNull(): String? = when (this) {
    is CapabilityReadResult.Success -> null
    is CapabilityReadResult.Unavailable -> reason
    is CapabilityReadResult.Failure -> "Falha ao ler o status de Wi-Fi: $reason"
    is CapabilityReadResult.SessionExpired -> "Sessão expirou ao ler o status de Wi-Fi: $reason"
}

private fun CapabilityReadResult.toActionUiModel(id: CapabilityId): WifiNetworkActionUiModel {
    val label = when (id) {
        CapabilityId.SET_WIFI_CHANNEL -> "Alterar canal Wi-Fi"
        CapabilityId.SET_WIFI_SSID -> "Renomear rede (SSID)"
        CapabilityId.SET_WIFI_PASSWORD -> "Alterar senha"
        else -> id.name
    }
    val reason = when (this) {
        is CapabilityReadResult.Success -> when (capability.state) {
            CapabilityState.AVAILABLE -> "O equipamento conectado declara suporte a esta ação, mas o " +
                "NetHAL Core ainda não implementa a execução de escrita (Command Executor) — leitura " +
                "confirmada, ação em desenvolvimento."
            else -> capability.reason
                ?: "Capability listada como \"${capability.state.name.lowercase()}\" pelo driver conectado."
        }
        is CapabilityReadResult.Unavailable -> reason
        is CapabilityReadResult.Failure -> "Falha ao consultar esta ação no equipamento: $reason"
        is CapabilityReadResult.SessionExpired -> "Sessão expirou ao consultar esta ação: $reason"
    }
    // `available` fica sempre `false` nesta rodada — ver KDoc de WifiNetworkViewModel: não existe
    // executor de escrita no Core hoje, então nenhuma ação é de fato executável, independente do
    // que a leitura de capability diga.
    return WifiNetworkActionUiModel(id = id, label = label, available = false, reason = reason)
}

class WifiNetworkViewModelFactory(
    private val capabilityEngine: CapabilityEngine?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(WifiNetworkViewModel::class.java)) {
            "WifiNetworkViewModelFactory só constrói WifiNetworkViewModel, recebido: $modelClass"
        }
        return WifiNetworkViewModel(capabilityEngine) as T
    }
}
