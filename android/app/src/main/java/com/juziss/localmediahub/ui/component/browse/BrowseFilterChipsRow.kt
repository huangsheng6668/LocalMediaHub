package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.ReadingStatus

@Composable
internal fun BrowseFilterChipsRow(
    statusFilter: ReadingStatus?,
    onSelect: (ReadingStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val options = listOf(
            null to stringResource(R.string.browse_status_all),
            ReadingStatus.UNREAD to stringResource(R.string.browse_status_unread),
            ReadingStatus.READING to stringResource(R.string.browse_status_reading),
            ReadingStatus.FINISHED to stringResource(R.string.browse_status_finished),
        )
        options.forEach { (value, label) ->
            FilterChip(
                selected = statusFilter == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
