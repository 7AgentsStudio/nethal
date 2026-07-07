package com.nethal.core.catalog

import com.nethal.core.model.Capability
import com.nethal.core.model.CapabilityId
import com.nethal.core.protocol.http.HttpTransport

/**
 * Resultado de leitura de uma capability por uma [DriverFamily]. Espelha o vocabulário já usado em
 * `Capability`/`CapabilityState` (`core/model/Capability.kt`) em vez de inventar um novo — a leitura
 * ou confirma um [Capability] com dado real, ou devolve o mesmo motivo de indisponibilidade que o
 * Capability Engine já espera (spec §8.6/§8.7).
 *
 * Desenho deliberadamente simples nesta rodada: ainda não existe nenhuma implementação real de
 * `DriverFamily` (isso é o passo 4 do plano de refatoração, `hal-layering-model.md` §10) — só depois
 * de migrar `TplinkOntDriver`/`TplinkC20OntDriver`/`NokiaOntDriver` para implementar esta interface é
 * que o formato de retorno (payload por capability, granularidade de erro, etc.) será validado contra
 * os três casos reais. Este tipo pode mudar de forma quando isso acontecer.
 */
sealed interface CapabilityReadResult {
    data class Success(val capability: Capability) : CapabilityReadResult
    data class Unavailable(val reason: String) : CapabilityReadResult
    data class Failure(val reason: String, val cause: Throwable? = null) : CapabilityReadResult
}

/**
 * Toda a lógica de comunicação com o equipamento para uma plataforma tecnológica compartilhada
 * (ver `docs/architecture/hal-layering-model.md` §5.5) — o que hoje está mistura em
 * `TplinkOntDriver`/`TplinkC20OntDriver`/`NokiaOntDriver`, sem separação entre "protocolo/driver" e
 * "dado de modelo específico".
 *
 * Uma `DriverFamily` recebe o [CompatibilityProfile] correspondente como configuração (via
 * [DriverFamilyFactory.create]) e nunca tem endpoint, seção ou campo de modelo hardcoded no próprio
 * código — esse dado vive em `profile.driverConfig` (§5.6/§11.1 do doc de arquitetura).
 *
 * Só cobre leitura (`READ_ONLY`) nesta rodada: escrita (`SET_*`, `REBOOT_*`) entra no mesmo desenho
 * quando o produto avançar para essa fase, sempre gateada pelo Safety Guard (ver `/seguranca-nethal`)
 * — não faz parte do escopo deste passo.
 */
interface DriverFamily {
    /**
     * Lê o estado atual de uma capability específica no equipamento. Implementações decidem
     * internamente, a partir de `profile.driverConfig`, quais endpoints/seções consultar — nunca a
     * partir de `if (vendor == ...)`.
     */
    suspend fun readCapability(id: CapabilityId): CapabilityReadResult
}

/**
 * Fábrica de instâncias de [DriverFamily], uma por plataforma tecnológica (`driverFamilyId`).
 * Registrada uma única vez no [DriverFamilyRegistry], montado na inicialização do `core` — nunca via
 * reflection ou scan dinâmico (`hal-layering-model.md` §8, passo 4).
 */
interface DriverFamilyFactory {
    /** Chave estável usada para resolver esta factory a partir de `profile.driverFamilyId`. */
    val familyId: String

    /**
     * Constrói uma [DriverFamily] parametrizada por [profile] (fonte de `driverConfig` e demais
     * metadados de catálogo) e pelo [host]/[transport] já resolvidos pelo Discovery Engine.
     */
    fun create(profile: CompatibilityProfile, host: String, transport: HttpTransport): DriverFamily
}
