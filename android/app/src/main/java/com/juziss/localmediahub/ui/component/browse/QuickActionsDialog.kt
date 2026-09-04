package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.ReadingStatus

@Composable
internal fun QuickActionsDialog(
    item: Any,
    onEditTags: (MediaFile) -> Unit,
    onDownloadFile: (MediaFile) -> Unit,
    onDeleteFile: (MediaFile) -> Unit,
    onDownloadFolder: (Folder) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onDismiss: () -> Unit,
    onMarkStatus: ((MediaFile, ReadingStatus?) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.browse_quick_actions),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (item is MediaFile) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (item.mediaType == "text" && onMarkStatus != null) {
                        TextButton(
                            onClick = { onMarkStatus(item, ReadingStatus.FINISHED); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.browse_action_mark_finished))
                            }
                        }
                        TextButton(
                            onClick = { onMarkStatus(item, ReadingStatus.READING); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.browse_action_mark_reading))
                            }
                        }
                        TextButton(
                            onClick = { onMarkStatus(item, ReadingStatus.UNREAD); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.browse_action_mark_unread))
                            }
                        }
                        TextButton(
                            onClick = { onMarkStatus(item, null); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.browse_action_clear_manual))
                            }
                        }
                    }
                    TextButton(
                        onClick = { onEditTags(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_edit_tags))
                        }
                    }
                    TextButton(
                        onClick = { onDownloadFile(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_download_file))
                        }
                    }
                    TextButton(
                        onClick = { onDeleteFile(item) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_delete_file))
                        }
                    }
                } else if (item is Folder) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = { onDownloadFolder(item) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_download_folder))
                        }
                    }
                    TextButton(
                        onClick = { onDeleteFolder(item) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Start,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.browse_action_delete_folder))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
