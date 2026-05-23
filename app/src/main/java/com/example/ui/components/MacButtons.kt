package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MacSeparator
import com.example.ui.theme.MacShapeButton
import com.example.ui.theme.MacSystemBlue
import com.example.ui.theme.MacSystemRed

@Composable
fun MacPrimaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  destructive: Boolean = false,
  content: @Composable RowScope.() -> Unit,
) {
  Button(
    onClick = onClick,
    modifier = modifier.height(40.dp),
    enabled = enabled,
    shape = MacShapeButton,
    colors =
      ButtonDefaults.buttonColors(
        containerColor = if (destructive) MacSystemRed else MacSystemBlue,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MacSeparator.copy(alpha = 0.3f),
      ),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content = content,
  )
}

@Composable
fun MacSecondaryButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable RowScope.() -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    modifier = modifier.height(40.dp),
    enabled = enabled,
    shape = MacShapeButton,
    border = BorderStroke(1.dp, MacSeparator),
    colors =
      ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.onSurface,
      ),
    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    content = content,
  )
}

@Composable
fun MacPrimaryButtonText(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge)
}
