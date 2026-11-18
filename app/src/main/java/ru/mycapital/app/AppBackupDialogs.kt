package ru.mycapital.app

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun AppBackupDialogs(
    pendingBackupCreate: Uri?,
    pendingBackupRestore: Uri?,
    createPassword: String,
    createPasswordConfirm: String,
    restorePassword: String,
    isCreating: Boolean,
    isRestoring: Boolean,
    onCreatePasswordChange: (String) -> Unit,
    onCreatePasswordConfirmChange: (String) -> Unit,
    onRestorePasswordChange: (String) -> Unit,
    onDismissCreate: () -> Unit,
    onConfirmCreate: () -> Unit,
    onDismissRestore: () -> Unit,
    onConfirmRestore: () -> Unit
) {
    if (pendingBackupCreate != null) {
        AlertDialog(
            onDismissRequest = onDismissCreate,
            title = { Text("Защитить резервную копию") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Архив будет зашифрован AES-256. Придумайте пароль длиной не менее 9 символов и сохраните его отдельно. Без пароля восстановить данные будет невозможно. Приложение пароль не сохраняет.")
                    OutlinedTextField(
                        value = createPassword,
                        onValueChange = onCreatePasswordChange,
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isCreating
                    )
                    OutlinedTextField(
                        value = createPasswordConfirm,
                        onValueChange = onCreatePasswordConfirmChange,
                        label = { Text("Повторите пароль") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isCreating
                    )
                    if (createPasswordConfirm.isNotEmpty() && createPassword != createPasswordConfirm) {
                        Text("Пароли не совпадают.", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isCreating && createPassword.length >= 9 && createPassword == createPasswordConfirm,
                    onClick = onConfirmCreate
                ) { Text(if (isCreating) "Создаю…" else "Создать архив") }
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
                    Text("Введите пароль AES-256. Сначала архив будет расшифрован и проверен; только после успешной проверки текущая база будет заменена. Перед продолжением рекомендуется сохранить свежую копию текущих данных.")
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = onRestorePasswordChange,
                        label = { Text("Пароль архива") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isRestoring
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring && restorePassword.isNotBlank(),
                    onClick = onConfirmRestore
                ) { Text(if (isRestoring) "Восстанавливаю…" else "Восстановить") }
            },
            dismissButton = {
                TextButton(enabled = !isRestoring, onClick = onDismissRestore) { Text("Отмена") }
            }
        )
    }
}
