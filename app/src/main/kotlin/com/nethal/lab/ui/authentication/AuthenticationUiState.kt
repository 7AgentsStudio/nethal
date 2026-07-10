package com.nethal.lab.ui.authentication

/**
 * Estado da Tela 5 — Autenticação (spec §11). A ordem de navegação implementada neste app
 * (Tela 3 → Tela 5 → Tela 4 → Tela 6, ver `NetHalNavHost`) diverge da numeração da spec §11
 * (Tela 3 → Tela 4 → Tela 5) — decisão explícita confirmada por Rafael ao encomendar a Tela 4/6
 * (autenticar antes de listar capabilities, já que a leitura de capabilities exige sessão
 * autenticada nesta implementação), não mais uma ressalva pendente.
 */
sealed interface AuthenticationUiState {

    /** Resolvendo o `CompatibilityProfile`/Driver Family a partir do `matchedProfileId` da Tela 3. */
    data object ResolvingDriver : AuthenticationUiState

    /**
     * Não é possível autenticar contra este equipamento nesta versão do app — nunca uma exceção
     * não tratada. Cobre: nenhuma identificação da Tela 3 (`matchedProfileId == null`), profile
     * não encontrado no catálogo local (drift), nenhuma Driver Family registrada para
     * `driverFamilyId` do profile (`UnknownDriverFamilyException`) e host recusado pela guarda de
     * IP privado (RFC 1918) de toda `DriverFamily`.
     */
    data class DriverUnavailable(val reason: String) : AuthenticationUiState

    data class Ready(
        val vendor: String,
        val model: String,
        /**
         * `true` só para o profile `tplink_archer_c6_stok_v1` (`driverFamilyId` =
         * `"tplink-stok-luci-driver"`) — pendência de gate registrada em
         * `docs/drivers/compatibility-catalog.md`, seção "Limitação conhecida — TOFU no handshake
         * stok/luci do TP-Link Archer C6": o handshake RSA busca as chaves do próprio host, sem
         * certificado nem pinagem (trust-on-first-use). O mesmo aviso está pendente para o driver
         * Nokia (mesma seção do documento), mas `nokia-ont-gpon-driver` ainda não tem nenhuma
         * `DriverFamilyFactory` registrada em `defaultDriverFamilyRegistry()` — na prática, esta
         * tela nunca chega ao estado `Ready` para um profile Nokia hoje (cai em
         * [DriverUnavailable] antes). Revisitar esta flag quando o driver Nokia for migrado.
         */
        val showTofuWarning: Boolean,
        val credentialTestState: CredentialTestState,
    ) : AuthenticationUiState
}

/**
 * Resultado do botão "Testar" (`CapabilityEngine.testCredentials()`). [Failure] cobre também o
 * caso honesto de Driver Family sem sessão real implementada — o `reason` devolvido pelo
 * `DriverFamily.authenticate()` default já é uma mensagem clara para o usuário nesse caso
 * ("Esta Driver Family ainda não implementa gerenciamento de sessão real"), então não existe um
 * estado `Unsupported` à parte: ver KDoc de `AuthenticationViewModel.testCredentials`.
 */
sealed interface CredentialTestState {
    data object Idle : CredentialTestState
    data object Testing : CredentialTestState
    data object Success : CredentialTestState
    data class InvalidCredentials(val reason: String) : CredentialTestState
    data class Failure(val reason: String) : CredentialTestState
}
