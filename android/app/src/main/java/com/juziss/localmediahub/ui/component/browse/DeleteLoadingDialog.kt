package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R

@Composable
internal fun DeleteLoadingDialog() {
    AlertDialog(
        onDismissRequest = {},
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.browse_deleting), fontWeight = FontWeight.Bold) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.browse_deleting_desc))
            }
        },
        confirmButton = {},
    )
}
