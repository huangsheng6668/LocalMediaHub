package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

@Composable
internal fun DeleteConfirmDialog(
    item: Any,
    deleteRecursive: Boolean,
    onRecursiveChange: (Boolean) -> Unit,
    onConfirm: (path: String, recursive: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val name = when (item) {
        is MediaFile -> item.name
        is Folder -> item.name
        else -> ""
    }
    val isFolder = item is Folder
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = if (isFolder) stringResource(R.string.browse_delete_folder) else stringResource(R.string.browse_delete_file),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "您确定要从服务端永久删除 \"$name\" 吗？此操作不可撤销，文件将彻底消失。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isFolder) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRecursiveChange(!deleteRecursive) }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(
                            checked = deleteRecursive,
                            onCheckedChange = { onRecursiveChange(it) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.browse_delete_recursive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = when (item) {
                        is MediaFile -> item.path
                        is Folder -> item.path
                        else -> ""
                    }
                    if (path.isNotEmpty()) {
                        onConfirm(path, if (isFolder) deleteRecursive else false)
                    }
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
