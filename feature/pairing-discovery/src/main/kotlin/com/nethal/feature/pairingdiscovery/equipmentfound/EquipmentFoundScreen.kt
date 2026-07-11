package com.nethal.feature.pairingdiscovery.equipmentfound

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nethal.core.model.DetectedProtocol
import com.nethal.feature.pairingdiscovery.internal.RouterGlyph
import com.nethal.feature.pairingdiscovery.internal.StatusChip
import java.util.Locale

/**
 * Tela 2b — Equipamento detectado (spec §11, protótipo `prototypes.dc.html` #2b). Mostra
 * fabricante/modelo/firmware provável (ou "não identificado"), protocolo detectado, confiança e
 * a data do manifesto do catálogo carregado. Ação de correção manual fica sempre disponível,
 * destacada quando a confiança é baixa (`LOW_CONFIDENCE_THRESHOLD`) — lógica preservada de
 * `EquipmentDetectedViewModel` (issue #75, só o visual muda).
 */
@Composable
fun EquipmentFoundScreen(viewModel: EquipmentDetectedViewModel, onContinue: (matchedProfileId: String?) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    when (val state = uiState) {
        is EquipmentDetectedUiState.Identifying -> IdentifyingContent()
        is EquipmentDetectedUiState.Identified -> IdentifiedContent(
            state = state,
            onSubmitCorrection = viewModel::submitCorrection,
            onContinue = onContinue,
        )
    }
}

@Composable
private fun IdentifyingContent() {
    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark)
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            RouterGlyph(tint = NetHalAccent, size = 28.dp)
            Text(
                text = "Identificando o equipamento...",
                color = OnBackgroundDark,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun IdentifiedContent(
    state: EquipmentDetectedUiState.Identified,
    onSubmitCorrection: (String, String, String?) -> Unit,
    onContinue: (matchedProfileId: String?) -> Unit,
) {
    var showCorrectionForm by remember { mutableStateOf(state.isLowConfidence) }

    Scaffold(containerColor = BackgroundDark) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundDark)
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "1 roteador encontrado",
                color = OnBackgroundDark,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = SurfaceDark, shape = RoundedCornerShape(26.dp))
                    .border(width = 1.dp, color = NetHalAccent, shape = RoundedCornerShape(26.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RouterGlyph(tint = NetHalAccent, size = 28.dp)
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            text = state.vendor?.let { vendor -> "$vendor ${state.model.orEmpty()}".trim() }
                                ?: "Equipamento não identificado",
                            color = OnBackgroundDark,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(text = state.targetIp, color = OnSurfaceVariantDark, fontSize = 11.sp)
                    }
                }

                Text(
                    text = "Firmware: ${state.firmware ?: "não disponível"}",
                    color = OnSurfaceVariantDark,
                    fontSize = 12.5.sp,
                )
                Text(
                    text = "Protocolo detectado: ${protocolsLabel(state.detectedProtocols)}",
                    color = OnSurfaceVariantDark,
                    fontSize = 12.5.sp,
                )

                StatusChip(
                    label = "Confiança: ${confidencePercentLabel(state.confidence)}",
                    color = if (state.isLowConfidence) WarningDark else SuccessDark,
                )

                if (state.isLowConfidence) {
                    Text(
                        text = "Confiança baixa — considere corrigir a identificação abaixo.",
                        color = WarningDark,
                        fontSize = 12.sp,
                    )
                }
            }

            Text(
                text = "Catálogo de compatibilidade: versão ${state.manifestVersion} " +
                    "(atualizado em ${state.manifestGeneratedAt}). A identificação pode estar " +
                    "desatualizada se o catálogo for antigo.",
                color = OnSurfaceTertiaryDark,
                fontSize = 11.sp,
            )

            if (!showCorrectionForm) {
                OutlinedButton(
                    onClick = { showCorrectionForm = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariantDark),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Corrigir identificação", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            } else {
                CorrectionForm(
                    correctionSubmitted = state.correctionSubmitted,
                    onSubmit = onSubmitCorrection,
                )
            }

            Button(
                onClick = { onContinue(state.matchedProfileId) },
                colors = ButtonDefaults.buttonColors(containerColor = NetHalAccent),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Conectar a este roteador", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun CorrectionForm(
    correctionSubmitted: Boolean,
    onSubmit: (String, String, String?) -> Unit,
) {
    var vendor by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var firmware by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Corrigir identificação", color = OnBackgroundDark, fontSize = 15.sp, fontWeight = FontWeight.Bold)

        Text(
            text = "Equipamentos reconhecidos pelo catálogo atual: " +
                KNOWN_PROFILE_SUGGESTIONS.joinToString(", ") { "${it.vendor} ${it.model}" },
            color = OnSurfaceTertiaryDark,
            fontSize = 11.sp,
        )

        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = OnBackgroundDark,
            unfocusedTextColor = OnBackgroundDark,
            focusedBorderColor = NetHalAccent,
            unfocusedBorderColor = BorderDark,
            focusedContainerColor = SurfaceDark,
            unfocusedContainerColor = SurfaceDark,
        )

        OutlinedTextField(
            value = vendor,
            onValueChange = { vendor = it },
            label = { Text("Fabricante") },
            colors = fieldColors,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Modelo") },
            colors = fieldColors,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = firmware,
            onValueChange = { firmware = it },
            label = { Text("Firmware (opcional)") },
            colors = fieldColors,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "Sua correção fica registrada apenas neste aparelho como candidata — " +
                "não promove automaticamente nenhum driver para uso estável.",
            color = OnSurfaceTertiaryDark,
            fontSize = 11.sp,
        )

        Button(
            onClick = { onSubmit(vendor, model, firmware.ifBlank { null }) },
            enabled = vendor.isNotBlank() && model.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NetHalAccent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Salvar correção", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }

        if (correctionSubmitted) {
            Text(
                text = "Correção salva localmente.",
                color = SuccessDark,
                fontSize = 12.sp,
            )
        }
    }
}

private fun confidencePercentLabel(confidence: Double): String =
    "${String.format(Locale.getDefault(), "%.0f", confidence * 100)}%"

private fun protocolsLabel(protocols: List<DetectedProtocol>): String {
    if (protocols.isEmpty()) return "não detectado"
    return protocols.joinToString(", ") { protocol ->
        when (protocol) {
            DetectedProtocol.HTTP_LOCAL_WEBUI -> "WebUI local (HTTP)"
            DetectedProtocol.HTTPS_LOCAL_WEBUI -> "WebUI local (HTTPS)"
            DetectedProtocol.UNKNOWN -> "desconhecido"
        }
    }
}
