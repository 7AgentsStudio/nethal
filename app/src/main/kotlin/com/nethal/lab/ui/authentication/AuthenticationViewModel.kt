package com.nethal.lab.ui.authentication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nethal.core.capability.CapabilityEngine
import com.nethal.core.capability.CapabilitySessionResult
import com.nethal.core.catalog.DriverFamily
import com.nethal.core.catalog.DriverFamilyRegistry
import com.nethal.core.catalog.DriverRegistry
import com.nethal.core.catalog.UnknownDriverFamilyException
import com.nethal.core.model.NetworkTarget
import com.nethal.core.protocol.http.HttpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** `driverFamilyId` do único profile com handshake RSA TOFU documentado nesta rodada — ver KDoc de `AuthenticationUiState.Ready.showTofuWarning`. */
private const val TPLINK_STOK_LUCI_DRIVER_FAMILY_ID = "tplink-stok-luci-driver"

/**
 * Orquestra a Tela 5 — Autenticação (spec §11): resolve o `CompatibilityProfile`/`DriverFamily`
 * reais a partir do `matchedProfileId` produzido pelo Fingerprint Engine na Tela 3, e usa
 * `CapabilityEngine.testCredentials()` para o botão "Testar" — nunca inventa outro caminho de
 * autenticação (ver KDoc de `CapabilityEngine`).
 *
 * ## Credencial nunca persistida
 *
 * `username`/`password` só existem como parâmetros de [testCredentials] e dentro do
 * `CapabilityEngine` criado ali (que já garante nunca logar/persistir, ver seu KDoc) — este
 * ViewModel nunca guarda a senha em nenhum campo próprio, nunca a expõe em `uiState` e nunca a
 * passa para `SavedStateHandle`/`Bundle`. [closeSession] descarta a sessão e a credencial em
 * memória; deve ser chamado pela Tela 5 ao sair de composição (`DisposableEffect`) ou ao navegar
 * para trás — a UI nunca deixa uma credencial digitada sobreviver depois que o usuário sai desta
 * tela.
 *
 * ## Driver sem sessão real implementada (honestidade, não falha silenciosa)
 *
 * Hoje só `TpLinkStokLuciDriverFamily` (`driverFamilyId` = `"tplink-stok-luci-driver"`) implementa
 * `authenticate()`/`readCapability()` de verdade. As demais Driver Families já registradas em
 * `defaultDriverFamilyRegistry()` (`tplink-legacy-cgi-driver`, `tplink-gdpr-cgi-driver`,
 * `tplink-xdr-ds-driver`) usam o `authenticate()` default de `DriverFamily`, que devolve
 * `DriverFamilyAuthResult.Failure("Esta Driver Family ainda não implementa gerenciamento de sessão
 * real (authenticate()).")` sem tocar a rede. Esse texto já é uma mensagem honesta para o usuário
 * final — por isso [testCredentials] não precisa de um estado `Unsupported` à parte: o `reason` de
 * `CapabilitySessionResult.Failure` chega inalterado a `CredentialTestState.Failure` e a tela
 * mostra exatamente isso, em vez de travar ou fingir sucesso.
 *
 * Profiles cujo `driverFamilyId` não tem nenhuma `DriverFamilyFactory` registrada (hoje: Nokia —
 * `nokia-ont-gpon-driver` — e a plataforma TP-Link não-stok — `tplink-encrypted-web-driver`) nunca
 * chegam a [AuthenticationUiState.Ready]: `DriverFamilyRegistry.resolve` lança
 * [UnknownDriverFamilyException] durante [resolveDriver], tratada aqui como
 * [AuthenticationUiState.DriverUnavailable] com mensagem clara, nunca como crash.
 *
 * ## Ressalva aberta (revisão Diego, 2026-07-08) — [resolveDriver] não verifica `profile.stage`
 *
 * [resolveDriver] chega a [AuthenticationUiState.Ready] para qualquer profile com
 * `DriverFamilyFactory` registrada, **sem checar `CompatibilityProfile.stage`** (`DraftStage`
 * incluso). Hoje isso não produz um resultado desonesto — um profile `DRAFT` sem `authenticate()`
 * real (ex.: `tplink_archer_c6_v1`/`legacy-cgi`) ainda cai na mensagem honesta do bloco acima ao
 * clicar "Testar" — mas nada impede o usuário de chegar à Tela 5 e tentar autenticar contra um
 * driver cujo estágio de catálogo ainda não passou por nenhuma validação real. Não corrigido nesta
 * rodada: é decisão de produto (a Tela 5 deveria bloquear/avisar por `DriverStage` antes mesmo de
 * tentar `authenticate()`?), não uma falha de segurança ou bug funcional — fica em aberto para
 * Rafael decidir.
 */
class AuthenticationViewModel(
    private val target: NetworkTarget,
    private val matchedProfileId: String?,
    private val driverRegistry: DriverRegistry,
    private val driverFamilyRegistry: DriverFamilyRegistry,
    private val httpTransport: HttpTransport,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthenticationUiState>(AuthenticationUiState.ResolvingDriver)
    val uiState: StateFlow<AuthenticationUiState> = _uiState.asStateFlow()

    private var resolvedDriverFamily: DriverFamily? = null
    private var capabilityEngine: CapabilityEngine? = null

    /**
     * `true` depois de [captureAuthenticatedSession] devolver o [CapabilityEngine] ativo para a
     * Tela 4 (Capabilities) assumir — a partir daí [closeSession] vira no-op aqui: encerrar a
     * sessão passa a ser responsabilidade de quem a recebeu (ver KDoc de
     * [captureAuthenticatedSession]).
     */
    private var sessionHandedOff = false

    /** Exposto só para a Tela 5 poder refletir "sessão ativa" se precisar — nunca guarda a credencial em si. */
    val isSessionActive: Boolean get() = capabilityEngine?.isSessionActive == true

    init {
        resolveDriver()
    }

    private fun resolveDriver() {
        val profileId = matchedProfileId
        if (profileId.isNullOrBlank()) {
            _uiState.value = AuthenticationUiState.DriverUnavailable(
                reason = "Nenhum equipamento foi identificado com confiança suficiente na etapa anterior. " +
                    "Volte e corrija a identificação antes de autenticar.",
            )
            return
        }

        val profile = driverRegistry.profiles().firstOrNull { it.profileId == profileId }
        if (profile == null) {
            _uiState.value = AuthenticationUiState.DriverUnavailable(
                reason = "O driver \"$profileId\" não foi encontrado no catálogo local deste app " +
                    "(catálogo pode estar desatualizado).",
            )
            return
        }

        val driverFamily = try {
            driverFamilyRegistry.resolve(profile, target.ip, httpTransport)
        } catch (e: UnknownDriverFamilyException) {
            _uiState.value = AuthenticationUiState.DriverUnavailable(
                reason = "Ainda não existe driver implementado para ${profile.vendor} ${profile.model} " +
                    "nesta versão do app.",
            )
            return
        } catch (e: IllegalArgumentException) {
            _uiState.value = AuthenticationUiState.DriverUnavailable(
                reason = e.message ?: "Endereço do equipamento recusado por segurança.",
            )
            return
        }

        resolvedDriverFamily = driverFamily
        _uiState.value = AuthenticationUiState.Ready(
            vendor = profile.vendor,
            model = profile.model,
            showTofuWarning = profile.driverFamilyId == TPLINK_STOK_LUCI_DRIVER_FAMILY_ID,
            credentialTestState = CredentialTestState.Idle,
        )
    }

    /**
     * Botão "Testar" da Tela 5 — sempre cria um novo `CapabilityEngine` para esta credencial (nunca
     * reaproveita um engine de uma tentativa anterior, mesmo raciocínio conservador do resto do
     * NetHAL: uma nova tentativa é sempre uma nova sessão explícita, não uma renovação silenciosa).
     * O engine anterior, se existir, é descartado sem chamar `closeSession()` nele — não há sessão
     * de servidor para encerrar num engine cujo login sequer foi tentado ainda ou já falhou.
     *
     * Reseta [sessionHandedOff] para `false`: esta é sempre uma sessão nova que ainda não foi
     * entregue a ninguém, mesmo que uma sessão anterior desta mesma tela já tenha sido entregue à
     * Tela 4 (fluxo: Continuar → voltar → Testar de novo). Sem este reset, [closeSession] ficaria
     * permanentemente no-op depois da primeira entrega, vazando qualquer sessão nova criada aqui
     * depois disso.
     */
    fun testCredentials(username: String, password: String) {
        val currentState = _uiState.value as? AuthenticationUiState.Ready ?: return
        val driverFamily = resolvedDriverFamily ?: return
        if (username.isBlank() || password.isBlank()) return

        _uiState.value = currentState.copy(credentialTestState = CredentialTestState.Testing)

        viewModelScope.launch {
            val engine = CapabilityEngine(driverFamily, username, password)
            capabilityEngine = engine
            sessionHandedOff = false
            val result = engine.testCredentials()

            val newTestState = when (result) {
                is CapabilitySessionResult.Active -> CredentialTestState.Success
                is CapabilitySessionResult.InvalidCredentials -> CredentialTestState.InvalidCredentials(result.reason)
                is CapabilitySessionResult.Failure -> CredentialTestState.Failure(result.reason)
            }

            val latestState = _uiState.value as? AuthenticationUiState.Ready ?: return@launch
            _uiState.value = latestState.copy(credentialTestState = newTestState)
        }
    }

    /**
     * Entrega a sessão autenticada ativa (mesma instância de [CapabilityEngine], com sessão já
     * aberta pelo botão "Testar") para a Tela 4 (Capabilities) ler capabilities sem autenticar de
     * novo — reaproveitamento explícito de sessão entre telas, mesmo espírito de estado
     * compartilhado que `NetHalNavHost` já usa para `NetworkTarget` entre Discovery e
     * EquipmentDetected. Chamado só a partir do botão "Continuar" da Tela 5, e só quando
     * [CredentialTestState.Success] já foi alcançado — devolve `null` (nunca um engine morto) se
     * não houver sessão ativa no momento da chamada.
     *
     * Marca [sessionHandedOff] = `true`: a partir daqui [closeSession] deixa de fechar a sessão
     * aqui, porque a posse dela passou para quem a recebeu (a Tela 4 é quem decide quando encerrar
     * — ver seu próprio `closeSession`).
     *
     * ## Ressalva aberta (revisão Marisa, 2026-07-08) — voltar da Tela 4 após sessão já encerrada
     *
     * Se o usuário navegar de volta da Tela 4 (`Loaded`) para esta tela via gesto/botão físico
     * depois que `CapabilitiesViewModel.closeSession()` já encerrou a sessão entregue aqui, o
     * `credentialTestState` desta tela continua `Success` (não é resetado ao voltar) e o botão
     * "Continuar" reaparece — mas um novo clique nele chama este método de novo, que agora devolve
     * `null` (`isSessionActive` já é `false`), levando a navegação para a Tela 4 mostrando "sessão
     * indisponível". Comportamento honesto e seguro (nunca finge uma sessão viva), mas é uma
     * superfície de UX que pode intrigar o usuário — não corrigido nesta rodada por ser decisão de
     * produto (ex.: resetar `credentialTestState` para `Idle` ao detectar sessão encerrada), não
     * falha de segurança. Fica em aberto para Rafael/Bruno decidirem.
     */
    fun captureAuthenticatedSession(): CapabilityEngine? {
        val engine = capabilityEngine?.takeIf { it.isSessionActive } ?: return null
        sessionHandedOff = true
        return engine
    }

    /**
     * Encerra a sessão local (se houver) e descarta a credencial em memória do `CapabilityEngine`
     * — chamar de `DisposableEffect`/`onDispose` na Tela 5 ao sair de composição, e também ao
     * navegar para trás a partir dela. Não faz logout no servidor (mesma ressalva já registrada em
     * `CapabilityEngine.closeSession`); só garante que nada relacionado à credencial sobrevive além
     * do tempo de uso da tela neste processo.
     *
     * No-op se a sessão já foi entregue à Tela 4 via [captureAuthenticatedSession] — fechar aqui
     * derrubaria a sessão que a próxima tela acabou de assumir (bug real encontrado ao ligar a
     * Tela 4 de verdade: o `DisposableEffect` da Tela 5 dispara `onDispose` assim que o composable
     * sai da árvore, o que acontece imediatamente ao navegar para frente, não só ao voltar).
     */
    fun closeSession() {
        if (sessionHandedOff) return
        capabilityEngine?.closeSession()
    }
}
