package com.alexdremov.notate.ui.home.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun DeleteConfirmationDialog(
    itemName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.border(2.dp, Color.Black, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(text = "Delete \"$itemName\"?") },
        text = { Text("This action cannot be undone.") },
        confirmButton = {
            OutlinedButton(
                onClick = onConfirm,
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
            ) {
                Text("Cancel")
            }
        },
    )
}
