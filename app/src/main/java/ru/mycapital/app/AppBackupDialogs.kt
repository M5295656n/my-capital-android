package ru.mycapital.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun AppBackupDialogs(
    pendingBackupCreate: Uri?,
    pendingBackupRestore: Uri?,
    restorePassword: String,
    isCreating: Boolean,
    isRestoring: Boolean,
    onRestorePasswordChange: (String) -> Unit,
    onDismissCreate: () -> Unit,
    onConfirmCreate: () -> Unit,
    onDismissRestore: () -> Unit,
    onConfirmRestore: () -> Unit
) {
    if (pendingBackupCreate != null) {
        AlertDialog(
            onDismissRequest = onDismissCreate,
            title = { Text("Создать резервную копию?") },
            text = {
                Text("Будет создан ZIP-архив без шифрования и пароля. Любой, у кого будет файл, сможет прочитать содержащиеся в нём финансовые данные. Храните архив в безопасном месте.")
            },
            confirmButton = {
                TextButton(enabled = !isCreating, onClick = onConfirmCreate) {
                    Text(if (isCreating) "Создаю…" else "Создать архив")
                }
            },
            dismissButton = {
                TextButton(enabled = !isCreating, onClick = onDismissCreate) { Text("Отмена") }
            }
        )
    }
    if (pendingBackupRestore != null) {
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text("Восстановить резервную копию?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Новые резервные копии открываются без пароля. Если вы восстанавливаете старый архив AES-256, введите его пароль. Сначала данные будут проверены; только после успешной проверки текущая база будет заменена.")
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = onRestorePasswordChange,
                        label = { Text("Пароль старой копии (если требуется)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isRestoring
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !isRestoring, onClick = onConfirmRestore) {
                    Text(if (isRestoring) "Восстанавливаю…" else "Восстановить")
                }
            },
            dismissButton = {
                TextButton(enabled = !isRestoring, onClick = onDismissRestore) { Text("Отмена") }
            }
        )
    }
}
