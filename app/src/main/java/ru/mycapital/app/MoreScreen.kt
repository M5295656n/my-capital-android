package ru.mycapital.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun MoreScreen(
    onAddAsset: () -> Unit,
    onAddPayout: () -> Unit,
    hasAssets: Boolean,
    isParsingReport: Boolean,
    isExportingData: Boolean,
    isCreatingBackup: Boolean,
    isRestoringBackup: Boolean,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onImportReport: () -> Unit,
    onExportData: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenOperations: () -> Unit,
    onOpenEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Ещё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Дополнительные инструменты и управление данными", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenAccounts)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountBalance, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Брокерские счета", fontWeight = FontWeight.SemiBold)
                    Text("Договоры и остатки по счетам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenOperations)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("История операций", fontWeight = FontWeight.SemiBold)
                    Text("Покупки, продажи, пополнения и выводы", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Card(Modifier.fillMaxWidth().clickable(onClick = onOpenEvents)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EventNote, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("События и новости", fontWeight = FontWeight.SemiBold)
                    Text("Рыночный календарь и новости компаний", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Text("Ручное добавление", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Card(Modifier.fillMaxWidth().clickable(onClick = onAddAsset)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Добавить актив", fontWeight = FontWeight.SemiBold)
                    Text("Вручную внести бумагу и её текущую позицию", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        Card(
            Modifier.fillMaxWidth().then(if (hasAssets) Modifier.clickable(onClick = onAddPayout) else Modifier),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Payments, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Добавить выплату", fontWeight = FontWeight.SemiBold)
                    Text(if (hasAssets) "Вручную внести дивиденд или купон" else "Сначала добавьте актив в портфель", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = if (hasAssets) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Файлы и резервные копии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onImportReport, enabled = !isParsingReport, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isParsingReport) "Читаю отчёт…" else "Импорт отчёта Сбербанка (HTML)")
        }
        OutlinedButton(onClick = onCreateBackup, enabled = !isCreatingBackup && !isRestoringBackup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Backup, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isCreatingBackup) "Создаю полную копию…" else "Создать резервную копию базы")
        }
        OutlinedButton(onClick = onRestoreBackup, enabled = !isCreatingBackup && !isRestoringBackup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isRestoringBackup) "Восстанавливаю…" else "Восстановить из резервной копии")
        }
        Text("Резервная копия содержит финансовые данные и историю портфеля и шифруется AES-256. Пароль не хранится в приложении. Без него восстановление невозможно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onExportData, enabled = !isExportingData, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FileDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (isExportingData) "Экспортирую данные…" else "Экспортировать таблицы (CSV ZIP)")
        }
    }
}
