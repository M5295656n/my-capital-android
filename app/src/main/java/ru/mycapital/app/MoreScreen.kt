package ru.mycapital.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onOpenEvents: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "Ещё",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Дополнительные инструменты MyCapital",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Управление портфелем",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Счета, рыночные события и ручное управление данными в одном месте.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        MoreSectionTitle("Разделы")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoreActionCard(
                icon = Icons.Default.AccountBalance,
                title = "Брокерские счета",
                description = "Договоры, денежные средства и позиции",
                onClick = onOpenAccounts
            )
            MoreActionCard(
                icon = Icons.Default.EventNote,
                title = "События и новости",
                description = "Рыночный календарь и новости компаний",
                onClick = onOpenEvents
            )
        }

        MoreSectionTitle("Ручное добавление")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoreActionCard(
                icon = Icons.Default.AddCircleOutline,
                title = "Добавить актив",
                description = "Внести бумагу и текущую позицию вручную",
                onClick = onAddAsset
            )
            MoreActionCard(
                icon = Icons.Default.Payments,
                title = "Добавить выплату",
                description = if (hasAssets) {
                    "Внести дивиденд или купон вручную"
                } else {
                    "Сначала добавьте актив в портфель"
                },
                onClick = onAddPayout,
                enabled = hasAssets
            )
        }

        MoreSectionTitle("Файлы и резервные копии")
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MoreFileAction(
                    icon = Icons.Default.FileOpen,
                    title = if (isParsingReport) "Читаю отчёт…" else "Импорт отчёта Сбербанка",
                    description = "Загрузить брокерский отчёт в формате HTML",
                    onClick = onImportReport,
                    enabled = !isParsingReport,
                    loading = isParsingReport
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreFileAction(
                    icon = Icons.Default.Backup,
                    title = if (isCreatingBackup) "Создаю резервную копию…" else "Создать резервную копию",
                    description = "Полная зашифрованная копия портфеля",
                    onClick = onCreateBackup,
                    enabled = !isCreatingBackup && !isRestoringBackup,
                    loading = isCreatingBackup
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreFileAction(
                    icon = Icons.Default.Restore,
                    title = if (isRestoringBackup) "Восстанавливаю…" else "Восстановить из копии",
                    description = "Вернуть данные из ZIP-архива",
                    onClick = onRestoreBackup,
                    enabled = !isCreatingBackup && !isRestoringBackup,
                    loading = isRestoringBackup
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MoreFileAction(
                    icon = Icons.Default.FileDownload,
                    title = if (isExportingData) "Экспортирую данные…" else "Экспортировать таблицы",
                    description = "Сохранить данные в CSV внутри ZIP-архива",
                    onClick = onExportData,
                    enabled = !isExportingData,
                    loading = isExportingData
                )
            }
        }

        Text(
            "Резервная копия содержит финансовые данные и историю портфеля и шифруется AES-256. Пароль не хранится в приложении. Без него восстановление невозможно.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun MoreSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun MoreActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MoreFileAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean,
    loading: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled || loading) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
