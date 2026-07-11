package com.nethal.feature.pairingdiscovery

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.nethal.core.catalog.CatalogDeviceType
import com.nethal.core.model.NetworkTarget
import com.nethal.core.model.TargetRole
import com.nethal.core.model.TargetSource
import com.nethal.core.protocol.PrivateIpRanges
import com.nethal.feature.pairingdiscovery.discovery.DiscoveryScreen
import com.nethal.feature.pairingdiscovery.discovery.DiscoveryViewModel
import com.nethal.feature.pairingdiscovery.equipmentfound.EquipmentDetectedViewModel
import com.nethal.feature.pairingdiscovery.equipmentfound.EquipmentFoundScreen
import com.nethal.feature.pairingdiscovery.manualentry.ManualIpEntryScreen
import com.nethal.feature.pairingdiscovery.selection.SelectDeviceTypeScreen
import com.nethal.feature.pairingdiscovery.selection.SelectManufacturerScreen
import com.nethal.feature.pairingdiscovery.selection.SelectModelScreen
import com.nethal.feature.pairingdiscovery.selection.deviceTypeLabel
import java.net.URLDecoder
import java.net.URLEncoder

/** Rotas internas do módulo (issues #74, #75, #80, #81, #82) — nenhuma exposta fora do grafo. */
object PairingDiscoveryRoutes {
    const val GRAPH = "pairing_discovery"
    const val DISCOVERY = "pairing_discovery/discovery"
    const val EQUIPMENT_FOUND = "pairing_discovery/equipment_found"
    const val SELECT_TYPE = "pairing_discovery/select_type"
    const val SELECT_MANUFACTURER = "pairing_discovery/select_manufacturer/{type}"
    const val SELECT_MODEL = "pairing_discovery/select_model/{type}/{vendor}"
    const val MANUAL_IP_ENTRY = "pairing_discovery/manual_ip_entry?matchedProfileId={matchedProfileId}"

    fun selectManufacturer(type: CatalogDeviceType) = "pairing_discovery/select_manufacturer/${type.name}"

    fun selectModel(type: CatalogDeviceType, vendor: String) =
        "pairing_discovery/select_model/${type.name}/${URLEncoder.encode(vendor, "UTF-8")}"

    fun manualIpEntry(matchedProfileId: String? = null): String =
        if (matchedProfileId != null) {
            "pairing_discovery/manual_ip_entry?matchedProfileId=$matchedProfileId"
        } else {
            "pairing_discovery/manual_ip_entry"
        }
}

/** Guarda o `NetworkTarget` encontrado pela descoberta automática entre "discovery" e "equipment_found". */
internal class PairingDiscoverySharedState : ViewModel() {
    var pendingTarget: NetworkTarget? = null
}

/**
 * Grafo de navegação do cluster de pareamento por descoberta (issues #74, #75, #80, #81, #82).
 * Cobre: buscando (2a) → dispositivo encontrado (2b) *ou* seleção manual tipo→fabricante→modelo
 * (2g→2h→2i) → entrada manual de IP (destino de "Outro/não sei" em 2h, de "Informar IP
 * manualmente" na falha de descoberta, e ponte necessária entre 2i e o login já que a
 * confirmação de modelo em 2i não traz IP nenhum — decisão registrada no PR).
 *
 * [onEquipmentConfirmed] é o único ponto de saída do grafo: dispara tanto a partir de 2b
 * ("Continuar", fluxo automático) quanto da entrada de IP após confirmação de modelo em 2i
 * (fluxo manual, pulando 2b conforme critério de aceite da issue #82). O composition root
 * (`:app`, ou futuramente `:feature:pairing-auth`) decide o que acontece depois — este módulo
 * nunca depende de outro `:feature:*` (ADR 0002).
 */
fun NavGraphBuilder.pairingDiscoveryGraph(
    navController: NavHostController,
    dependencies: PairingDiscoveryDependencies,
    onEquipmentConfirmed: (target: NetworkTarget, matchedProfileId: String?) -> Unit,
) {
    navigation(startDestination = PairingDiscoveryRoutes.DISCOVERY, route = PairingDiscoveryRoutes.GRAPH) {

        composable(PairingDiscoveryRoutes.DISCOVERY) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(PairingDiscoveryRoutes.GRAPH) }
            val discoveryViewModel: DiscoveryViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = discoveryViewModelFactory(dependencies),
            )
            val sharedState: PairingDiscoverySharedState = viewModel(viewModelStoreOwner = parentEntry)

            DiscoveryScreen(
                viewModel = discoveryViewModel,
                onSingleCandidateReady = { target ->
                    sharedState.pendingTarget = target
                    navController.navigate(PairingDiscoveryRoutes.EQUIPMENT_FOUND)
                },
                onCandidateChosen = { target ->
                    sharedState.pendingTarget = target
                    navController.navigate(PairingDiscoveryRoutes.EQUIPMENT_FOUND)
                },
                onSelectManually = { navController.navigate(PairingDiscoveryRoutes.SELECT_TYPE) },
                onEnterIpManually = { navController.navigate(PairingDiscoveryRoutes.manualIpEntry()) },
            )
        }

        composable(PairingDiscoveryRoutes.EQUIPMENT_FOUND) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(PairingDiscoveryRoutes.GRAPH) }
            val sharedState: PairingDiscoverySharedState = viewModel(viewModelStoreOwner = parentEntry)
            val target = sharedState.pendingTarget

            if (target == null) {
                // Estado perdido (ex.: processo recriado) — volta para a descoberta em vez de
                // mostrar uma tela sem dado nenhum (mesmo raciocínio do `NetHalNavHost` original).
                LaunchedEffect(Unit) {
                    navController.navigate(PairingDiscoveryRoutes.DISCOVERY) {
                        popUpTo(PairingDiscoveryRoutes.DISCOVERY) { inclusive = true }
                    }
                }
            } else {
                val equipmentViewModel: EquipmentDetectedViewModel = viewModel(
                    factory = equipmentDetectedViewModelFactory(target, dependencies),
                )
                EquipmentFoundScreen(
                    viewModel = equipmentViewModel,
                    onContinue = { matchedProfileId -> onEquipmentConfirmed(target, matchedProfileId) },
                )
            }
        }

        composable(PairingDiscoveryRoutes.SELECT_TYPE) {
            SelectDeviceTypeScreen(
                profiles = dependencies.driverRegistry.profiles(),
                onBack = { navController.popBackStack() },
                onTypeSelected = { type -> navController.navigate(PairingDiscoveryRoutes.selectManufacturer(type)) },
            )
        }

        composable(
            route = PairingDiscoveryRoutes.SELECT_MANUFACTURER,
            arguments = listOf(navArgument("type") { type = NavType.StringType }),
        ) { entry ->
            val typeArg = entry.arguments?.getString("type")
            if (typeArg != null) {
                val deviceType = CatalogDeviceType.valueOf(typeArg)
                SelectManufacturerScreen(
                    profiles = dependencies.driverRegistry.profiles(),
                    type = deviceType,
                    typeLabel = deviceTypeLabel(deviceType),
                    onBack = { navController.popBackStack() },
                    onManufacturerSelected = { vendor ->
                        navController.navigate(PairingDiscoveryRoutes.selectModel(deviceType, vendor))
                    },
                    onOtherSelected = { navController.navigate(PairingDiscoveryRoutes.manualIpEntry()) },
                )
            }
        }

        composable(
            route = PairingDiscoveryRoutes.SELECT_MODEL,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType },
                navArgument("vendor") { type = NavType.StringType },
            ),
        ) { entry ->
            val typeArg = entry.arguments?.getString("type")
            val vendorArg = entry.arguments?.getString("vendor")
            if (typeArg != null && vendorArg != null) {
                val deviceType = CatalogDeviceType.valueOf(typeArg)
                val vendor = URLDecoder.decode(vendorArg, "UTF-8")
                SelectModelScreen(
                    profiles = dependencies.driverRegistry.profiles(),
                    type = deviceType,
                    typeLabel = deviceTypeLabel(deviceType),
                    vendor = vendor,
                    onBack = { navController.popBackStack() },
                    onModelSelected = { profile ->
                        navController.navigate(PairingDiscoveryRoutes.manualIpEntry(profile.profileId))
                    },
                )
            }
        }

        composable(
            route = PairingDiscoveryRoutes.MANUAL_IP_ENTRY,
            arguments = listOf(
                navArgument("matchedProfileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(PairingDiscoveryRoutes.GRAPH) }
            val discoveryViewModel: DiscoveryViewModel = viewModel(
                viewModelStoreOwner = parentEntry,
                factory = discoveryViewModelFactory(dependencies),
            )
            val matchedProfileId = entry.arguments?.getString("matchedProfileId")
            val matchedProfile = matchedProfileId?.let { id ->
                dependencies.driverRegistry.profiles().find { it.profileId == id }
            }
            var manualProfileError by remember { mutableStateOf<String?>(null) }
            val discoveryError by discoveryViewModel.manualTargetError.collectAsState()

            ManualIpEntryScreen(
                deviceLabel = matchedProfile?.let { "${it.vendor} ${it.model}" },
                error = if (matchedProfileId != null) manualProfileError else discoveryError,
                onBack = { navController.popBackStack() },
                onSubmit = { ip ->
                    if (matchedProfileId != null) {
                        // Fluxo manual (2i confirmado): vendor/modelo já conhecidos, só falta o
                        // IP para poder tentar o login — pula 2b/fingerprint por completo
                        // (critério de aceite da issue #82: "navega para 2c, nunca 2b").
                        val trimmed = ip.trim()
                        if (!PrivateIpRanges.isPrivate(trimmed)) {
                            manualProfileError = "Esse IP não parece ser da sua rede local. " +
                                "O NetHAL só testa equipamentos na sua LAN."
                        } else {
                            manualProfileError = null
                            val target = NetworkTarget(
                                ip = trimmed,
                                role = TargetRole.MANUAL,
                                source = TargetSource.USER_INPUT,
                            )
                            onEquipmentConfirmed(target, matchedProfileId)
                        }
                    } else {
                        // Fluxo "Outro/não sei" ou "Informar IP manualmente": vendor/modelo
                        // desconhecidos — reusa o pipeline normal (`DiscoveryViewModel`), que
                        // segue para 2b via fingerprint como qualquer outro candidato.
                        discoveryViewModel.addManualTarget(ip)
                        navController.popBackStack(route = PairingDiscoveryRoutes.DISCOVERY, inclusive = false)
                    }
                },
            )
        }
    }
}

private fun discoveryViewModelFactory(dependencies: PairingDiscoveryDependencies): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == DiscoveryViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
            return DiscoveryViewModel(dependencies.discoveryEngine, dependencies.networkEnvironmentReader) as T
        }
    }

private fun equipmentDetectedViewModelFactory(
    target: NetworkTarget,
    dependencies: PairingDiscoveryDependencies,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == EquipmentDetectedViewModel::class.java) { "Unknown ViewModel class: $modelClass" }
        return EquipmentDetectedViewModel(
            target = target,
            fingerprintEngine = dependencies.fingerprintEngine,
            manualIdentificationRepository = dependencies.manualIdentificationRepository,
        ) as T
    }
}
