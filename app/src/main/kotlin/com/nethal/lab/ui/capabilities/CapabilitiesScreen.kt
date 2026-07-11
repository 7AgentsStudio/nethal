package com.nethal.lab.ui.capabilities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nethal.core.catalog.CapabilityReadResult
import com.nethal.core.model.CapabilityState

/**
 * Tela 4 — Capabilities (spec §11): lista o vocabulário oficial de [com.nethal.core.model.CapabilityId]
 * com estado, mostrando o motivo (`reason`) de todo item que não estiver disponível — nunca só o
 * estado sozinho (mockup da spec §11: "Trocar senha: indisponível (driver não suporta esta ação
 * neste modelo)").
 *
 * `DisposableEffect` garante `viewModel.closeSession()` ao sair de composição — este é o ponto
 * final da sessão aberta na Tela 5 (ver `CapabilitiesViewModel`).
 */
@Composable
fun CapabilitiesScreen(
    viewModel: CapabilitiesViewModel,
    onBack: () -> Unit,
    onContinue: (items: List<CapabilityItem>) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    when (val state = uiState) {
        is CapabilitiesUiState.Loading -> LoadingContent()
        is CapabilitiesUiState.SessionUnavailable -> SessionUnavailableContent(state = state, onBack = onBack)
        is CapabilitiesUiState.Loaded -> LoadedContent(state = state, onContinue = onContinue)
    }
}

@Composable
private fun LoadingContent() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(text = "Lendo capabilities do equipamento...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SessionUnavailableContent(state: CapabilitiesUiState.SessionUnavailable, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Sessão indisponível", style = MaterialTheme.typography.headlineSmall)
            Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar para autenticação")
            }
        }
    }
}

@Composable
private fun LoadedContent(state: CapabilitiesUiState.Loaded, onContinue: (List<CapabilityItem>) -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Capabilities", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "O que o NetHAL Lab sabe fazer com ${state.vendor} ${state.model} agora, lido " +
                    "direto do equipamento nesta sessão.",
                style = MaterialTheme.typography.bodyMedium,
            )

            state.items.forEach { item -> CapabilityRow(item) }

            Button(onClick = { onContinue(state.items) }, modifier = Modifier.fillMaxWidth()) {
                Text("Ver relatório")
            }
        }
    }
}

@Composable
private fun CapabilityRow(item: CapabilityItem) {
    val isAvailable = (item.result as? CapabilityReadResult.Success)?.capability?.state == CapabilityState.AVAILABLE

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = capabilityStatusLine(item),
                style = MaterialTheme.typography.bodyMedium,
                color = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val successResult = item.result as? CapabilityReadResult.Success
            if (successResult != null) {
                Text(
                    text = capabilityPayloadSummary(successResult.payload),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
