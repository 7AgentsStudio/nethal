package com.nethal.feature.pairingdiscovery.manualentry

import com.nethal.core.designsystem.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Campo de IP manual — extraído de `DiscoveryFailedScreen`/`MultipleCandidatesScreen` (decisão
 * registrada na spec de #80: "reaproveitar o campo já implementado, não duplicar código").
 * Reutilizado por [ManualIpEntryScreen] (destino de "Outro/não sei" em 2h e "Informar IP
 * manualmente" na tela 2b-falha) e, inline, por `MultipleCandidatesScreen` (2c), que não muda de
 * fluxo — só de visual.
 */
@Composable
fun ManualIpField(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submitLabel: String = "Adicionar equipamento",
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("IP do equipamento, ex.: 192.168.1.1") },
            isError = error != null,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnBackgroundDark,
                unfocusedTextColor = OnBackgroundDark,
                focusedBorderColor = NetHalAccent,
                unfocusedBorderColor = BorderDark,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Text(text = error, color = ErrorDark, fontSize = 12.sp)
        }

        Button(
            onClick = onSubmit,
            enabled = value.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = NetHalAccent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(submitLabel, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
