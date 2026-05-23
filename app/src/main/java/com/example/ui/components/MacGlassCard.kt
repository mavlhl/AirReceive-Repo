package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.MacShapeLarge
import com.example.ui.theme.MacShapeMedium
import com.example.ui.theme.MacSpace2

@Composable
fun MacGlassCard(
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(MacSpace2),
  useLargeRadius: Boolean = false,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = modifier,
    shape = if (useLargeRadius) MacShapeLarge else MacShapeMedium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(Dp.Hairline, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }
}
