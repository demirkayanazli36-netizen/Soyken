package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SoykenGold
import com.example.ui.tv.soykenTvFocus

@Composable
fun CategoryChips(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Combine standard categories with dynamic categories
    val allOptions = listOf("Tümü", "⭐ Favoriler") + categories.filter { it != "Tümü" && it != "⭐ Favoriler" }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("category_chips_row"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
    ) {
        items(allOptions) { category ->
            val isSelected = category.equals(selectedCategory, ignoreCase = true)

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) SoykenGold else MaterialTheme.colorScheme.surfaceVariant,
                label = "chip_bg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface,
                label = "chip_text"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) SoykenGold else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .soykenTvFocus(RoundedCornerShape(20.dp), scaleOnFocus = 1.05f)
                    .clickable { onCategorySelect(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("category_chip_$category"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = textColor
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}
