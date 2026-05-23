package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MacSystemGreen
import com.example.ui.theme.MacSystemRed

@Composable
fun MacStatusDot(
  isActive: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 8.dp,
) {
  Box(
    modifier =
      modifier
        .size(size)
        .clip(CircleShape)
        .background(if (isActive) MacSystemGreen else MacSystemRed),
  )
}
