# Modem Doctor

**Диагностическое приложение для анализа проблем с модемом на Android**

## Описание

Modem Doctor — это инструмент для диагностики проблем с мобильной связью на Android устройствах с root-доступом. Приложение специально разработано для отладки проблем с модемом, таких как:
- Потеря сети при перемещении между базовыми станциями
- "Зависание" модема при handover
- Проблемы с регистрацией в сети
- Неожиданная потеря сигнала

## Возможности

- ✅ **Root-доступ** — полный доступ к системным логам модема
- 📊 **Сбор логов** — radio logcat, dmesg, telephony registry, RIL logs, QCRIL DB
- 👀 **Мониторинг в реальном времени** — отслеживание состояния сети и сигнала
- 📤 **Автозагрузка на GitHub Gist** — лёгкий обмен логами для анализа
- 🔔 **Фоновая служба** — мониторинг даже когда приложение закрыто
- 📱 **Автозапуск** — возможность начать мониторинг при загрузке устройства

## Требования

- Android 7.0+ (API 31+ для полной функциональности)
- Root-доступ (Magisk, KernelSU или другой root-менеджер)
- GitHub Personal Access Token (для загрузки логов)

## Сборка

### Вариант 1: Android Studio

1. Откройте Android Studio
2. File → Open → выберите папку `ModemDoctor`
3. Дождитесь синхронизации Gradle
4. Build → Build APK(s)
5. APK будет в `app/build/outputs/apk/debug/`

### Вариант 2: Командная строка

```bash
# Linux/macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

APK будет в `app/build/outputs/apk/debug/app-debug.apk`

## Установка

1. Установите APK на устройство
2. Предоставьте все запрошенные разрешения
3. Предоставьте root-доступ при запросе

## Использование

### Начало работы

1. **Проверка Root** — при запуске приложение проверит наличие root-доступа
2. **Настройка GitHub Token** (опционально):
   - Создайте токен: https://github.com/settings/tokens/new
   - Нужны права: `gist` (create gists)
   - Введите токен в настройках приложения

### Сбор логов

**Вариант 1: Разовый сбор**
- Нажмите "Collect Logs Now"
- Дождитесь завершения (прогресс отображается)
- Скопируйте URL Gist или найдите лог в памяти устройства

**Вариант 2: Фоновый мониторинг**
- Нажмите "Start Monitoring"
- Приложение будет отслеживать состояние сети
- При обнаружении проблемы нажмите "Problem: No Signal" или "Problem: Network Stuck"

### Что собирается

| Тип лога | Описание |
|----------|----------|
| radio_logcat | Логи radio buffer из logcat |
| modem_dmesg | Сообщения ядра о модеме |
| telephony_registry | Состояние telephony subsystem |
| modem_properties | System properties модема |
| ril_logs | Radio Interface Layer логи |
| qcril_db | Qualcomm RIL database |
| modem_stats | Статистика сетевых интерфейсов |
| last_kmsg | Последние сообщения ядра |

## Анализ логов

После загрузки лога на Gist, поделитесь ссылкой для анализа. 

### Что искать в логах

1. **Признаки зависания модема**:
   ```
   rild: QCRIL: ... timeout
   modem: watch dog bite
   ```

2. **Проблемы handover**:
   ```
   RILJ: HANDOVER_FAILED
   qcril: reselection failed
   ```

3. **Ошибки регистрации**:
   ```
   REG_STATE_CHANGE: REGISTERED -> NOT_REGISTERED
   MM: Location update reject
   ```

## Совместимость

### Проверено на:
- Pixel 6A (bluejay) — Android 13-16
- Pixel 7 (panther) — Android 14
- Samsung Galaxy S23 — Android 14

### Модемы:
- Samsung Exynos Modem 5300 (Pixel 6 series)
- Samsung Exynos Modem 5400 (Pixel 7 series)
- Qualcomm Snapdragon X70 (Galaxy S23)

## Устранение неполадок

### Root не обнаружен
- Убедитесь, что Magisk/KernelSU установлен
- Проверьте, что приложение имеет root-доступ в менеджере

### Логи не загружаются
- Проверьте интернет-соединение
- Убедитесь, что GitHub токен валиден

### Мониторинг не работает
- Проверьте разрешения (Location, Phone)
- Убедитесь, что служба не отключена в настройках батареи

## Разработчикам

### Структура проекта

```
app/src/main/java/com/modemdoctor/
├── core/
│   ├── RootShell.kt        # Выполнение root-команд
│   ├── LogCollector.kt     # Сбор логов
│   ├── ModemMonitorService.kt  # Фоновый мониторинг
│   └── BootReceiver.kt     # Автозапуск
├── network/
│   └── GitHubUploader.kt   # Загрузка на Gist
└── ui/
    ├── MainViewModel.kt    # MVVM ViewModel
    └── theme/              # Compose тема
```

### Расширение функционала

Для добавления новых источников логов, отредактируйте `LogCollector.kt`:

```kotlin
private fun collectCustomLog(): String {
    val (_, output) = RootShell.execute("your_command_here")
    return output
}
```

## Лицензия

MIT License - используйте свободно

## Автор

Создано для диагностики проблем с модемом Pixel 6A
