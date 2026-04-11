package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.viewmodel.BrowseViewModel

@Composable
internal fun TagFilterBar(
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onTagClick: (Tag) -> Unit,
    onManageTags: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tags.forEach { tag ->
                val isActive = activeTagFilter?.id == tag.id
                FilterChip(
                    selected = isActive,
                    onClick = { onTagClick(tag) },
                    label = { Text(tag.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(1.dp)
                        ) {
                            Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                drawCircle(
                                    color = parseColorHex(tag.color),
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = parseColorHex(tag.color).copy(alpha = 0.2f),
                    ),
                )
            }
        }
    }
}

@Composable
internal fun TagMenuDialog(
    file: MediaFile,
    tags: List<Tag>,
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val fileTags = viewModel.getTagsForFile(file.relativePath)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Tags for ${file.name}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (tags.isEmpty()) {
                Text("No tags created yet. Create tags first.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        val isApplied = fileTags.any { it.id == tag.id }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isApplied) {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = isApplied,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                            ) {
                                Canvas(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    drawCircle(color = parseColorHex(tag.color))
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tag.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

internal fun parseColorHex(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> "FF$cleaned"
            8 -> cleaned
            else -> return Color.Gray
        }
        Color(argb.toLong(16))
    } catch (_: Exception) {
        Color.Gray
    }
}
