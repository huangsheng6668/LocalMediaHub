package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.res.painterResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.SortOrder

@Composable
internal fun BrowseSortMenu(
    folderSort: SortOrder,
    fileSort: SortOrder,
    onFolderSortChange: (SortOrder) -> Unit,
    onFileSortChange: (SortOrder) -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showSortMenu = true }) {
        Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.sort))
    }
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false },
    ) {
        Text(
            stringResource(R.string.browse_sort_folder),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        listOf(
            SortOrder.NAME_ASC,
            SortOrder.NAME_DESC,
            SortOrder.NUMERIC_ASC,
            SortOrder.NUMERIC_DESC,
            SortOrder.TIME_ASC,
            SortOrder.TIME_DESC,
        ).forEach { order ->
            DropdownMenuItem(
                text = { Text(order.label) },
                trailingIcon = {
                    if (order == folderSort) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { onFolderSortChange(order) },
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.browse_sort_file),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        SortOrder.entries.forEach { order ->
            DropdownMenuItem(
                text = { Text(order.label) },
                trailingIcon = {
                    if (order == fileSort) {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { onFileSortChange(order) },
            )
        }
    }
}
