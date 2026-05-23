package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MacSecondaryBg
import com.example.ui.theme.MacShapeSmall
import com.example.ui.theme.MacSpace1
import com.example.ui.theme.MacSystemBlue
import com.example.ui.theme.MacTertiaryBg

data class MacNavItem(
  val route: String,
  val label: String,
  val icon: ImageVector,
  val showBadge: Boolean = false,
  val badgeText: String? = null,
)

@Composable
fun MacSegmentedNavBar(
  items: List<MacNavItem>,
  selectedRoute: String,
  onItemSelected: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(horizontal = MacSpace1, vertical = MacSpace1)
        .clip(MacShapeSmall)
        .background(MacSecondaryBg)
        .padding(4.dp),
    horizontalArrangement = Arrangement.SpaceEvenly,
  ) {
    items.forEach { item ->
      val selected = item.route == selectedRoute
      Box(
        modifier =
          Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MacTertiaryBg else MacSecondaryBg)
            .clickable { onItemSelected(item.route) }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
      ) {
        val iconContent = @Composable {
          Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            modifier = Modifier.size(22.dp),
            tint = if (selected) MacSystemBlue else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (item.showBadge && item.badgeText != null) {
          BadgedBox(badge = { Badge { Text(item.badgeText) } }) { iconContent() }
        } else if (item.showBadge) {
          BadgedBox(badge = { Badge { Text("") } }) { iconContent() }
        } else {
          iconContent()
        }
      }
    }
  }
}
