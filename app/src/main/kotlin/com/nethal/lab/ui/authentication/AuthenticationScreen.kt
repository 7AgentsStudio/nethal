package com.nethal.lab.ui.authentication

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nethal.core.capability.CapabilityEngine

/**
 * Tela 5 — Autenticação (spec §11). Campos obrigatórios da spec: usuário, senha, botão testar,
 * aviso de que a senha não será salva, aviso de sessão única. O aviso de TOFU no handshake RSA do
 * TP-Link stok/luci (pendência de gate registrada em `docs/drivers/compatibility-catalog.md`) é
 * exibido só quando o driver resolvido é `tplink-stok-luci-driver`
 * ([AuthenticationUiState.Ready.showTofuWarning]).
 *
 * `onAuthenticated` recebe a sessão já autenticada ([CapabilityEngine], via
 * `viewModel.captureAuthenticatedSession()`) para a Tela 4 (Capabilities) reaproveitar sem logar
 * de novo — `null` só no caso defensivo de a sessão ter caído entre o "Testar" bem-sucedido e o
 * clique em "Continuar" (chamador decide como tratar, mesmo espírito de estado perdido já usado
 * por `NetHalNavHost` para `NetworkTarget`).
 *
 * `DisposableEffect` garante `viewModel.closeSession()` ao sair de composição desta tela — cobre
 * navegar para trás (a sessão é descartada de verdade) e também navegar para frente depois de
 * [captureAuthenticatedSession] já ter marcado a sessão como entregue (nesse caso o `closeSession`
 * do ViewModel vira no-op, ver seu KDoc).
 *
 * Ressalva aberta (revisão Marisa, 2026-07-08): o texto do aviso de TOFU em [ReadyContent]
 * simplifica "duas chaves RSA distintas" (redação técnica de `compatibility-catalog.md`, seção
 * "Limitação conhecida — TOFU no handshake stok/luci do TP-Link Archer C6") para "sua própria
 * chave de criptografia" (singular) — não distorce o risco para o usuário final, mas diverge do
 * detalhe técnico documentado. Cosmético, não corrigido por ser decisão de copy (simplicidade para
 * usuário leigo pode ser intencional) — fica em aberto para Rafael/Bruno decidirem se vale
 * literalidade maior.
 */
@Composable
fun AuthenticationScreen(
    viewModel: AuthenticationViewModel,
    onBack: () -> Unit,
    onAuthenticated: (CapabilityEngine?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose { viewModel.closeSession() }
    }

    when (val state = uiState) {
        is AuthenticationUiState.ResolvingDriver -> ResolvingDriverContent()
        is AuthenticationUiState.DriverUnavailable -> DriverUnavailableContent(state = state, onBack = onBack)
        is AuthenticationUiState.Ready -> ReadyContent(
            state = state,
            onTestCredentials = viewModel::testCredentials,
            onContinue = { onAuthenticated(viewModel.captureAuthenticatedSession()) },
        )
    }
}

@Composable
private fun ResolvingDriverContent() {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(text = "Preparando autenticação...", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun DriverUnavailableContent(state: AuthenticationUiState.DriverUnavailable, onBack: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Não é possível autenticar", style = MaterialTheme.typography.headlineSmall)
            Text(text = state.reason, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar")
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AuthenticationUiState.Ready,
    onTestCredentials: (username: String, password: String) -> Unit,
    onContinue: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val testState = state.credentialTestState
    val isTesting = testState is CredentialTestState.Testing
    val canSubmit = username.isNotBlank() && password.isNotBlank() && !isTesting

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Autenticação", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Entre com as credenciais de administração de ${state.vendor} ${state.model} " +
                    "para ler as informações do equipamento.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Usuário") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Senha") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Sua senha não é salva neste aparelho nem enviada a nenhum servidor — ela " +
                            "existe só durante esta sessão, na memória do app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Este e outros equipamentos da mesma família aceitam só uma sessão " +
                            "administrativa por vez. Se a WebUI do equipamento estiver aberta em um " +
                            "navegador, feche-a antes de testar — do contrário a autenticação pode falhar.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.showTofuWarning) {
                        Text(
                            text = "Este equipamento busca sua própria chave de criptografia no momento " +
                                "do login, sem certificado digital — não é possível confirmar de antemão " +
                                "que você está realmente falando com o seu roteador. Use esta autenticação " +
                                "apenas na sua própria rede local, em que você confia.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Button(
                onClick = { onTestCredentials(username, password) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isTesting) "Testando..." else "Testar")
            }

            CredentialTestFeedback(testState)

            if (testState is CredentialTestState.Success) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text("Continuar")
                }
            }
        }
    }
}

@Composable
private fun CredentialTestFeedback(testState: CredentialTestState) {
    when (testState) {
        is CredentialTestState.Idle, is CredentialTestState.Testing -> Unit
        is CredentialTestState.Success -> Text(
            text = "Autenticação bem-sucedida.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is CredentialTestState.InvalidCredentials -> Text(
            text = "Usuário ou senha incorretos: ${testState.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is CredentialTestState.Failure -> Text(
            text = "Não foi possível autenticar: ${testState.reason}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
