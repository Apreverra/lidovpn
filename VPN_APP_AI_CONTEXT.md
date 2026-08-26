# VPN Application Technical Audit & Context

Этот документ содержит полный технический аудит VPN-приложения «Lido VPN», включая архитектуру, механизмы работы, проблемы безопасности и рекомендации по улучшению.

---

# 1. Общая информация о приложении

*   **Назначение:** VPN-клиент с поддержкой современных протоколов (VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC) и обхода блокировок через ByeDPI. Ориентирован на обход цензуры и доступ к заблокированным ресурсам (в частности, в РФ).
*   **Платформа:** Android (minSdk 24, targetSdk 36).
*   **Язык:** Kotlin.
*   **Используемые Android API:** `VpnService`, `NotificationManager`, `WorkManager`, `SharedPreferences`, `ProcessBuilder`, `BroadcastReceiver`, `Quick Settings Tile`.
*   **Используемый VPN API:** Android `VpnService` API в связке с нативным ядром Xray (`libv2ray`) и ByeDPI (`libbyebyedpi.so`).
*   **Архитектурный подход:** MVVM с использованием Jetpack Compose. Вся логика сконцентрирована в массивном `AppViewModel` (3000+ строк).
*   **Основные библиотеки:**
    *   UI: Jetpack Compose (Material 3).
    *   Сеть: OkHttp, Gson.
    *   Фоновые задачи: WorkManager.
    *   VPN Core: `libv2ray` (Xray), `libbyebyedpi.so` (ByeDPI).
*   **Минимальная версия SDK:** 24 (Android 7.0).
*   **Целевая версия SDK:** 36 (Android 16).
*   **Структура модулей:** Одномодульное приложение (`:app`).
*   **Основные компоненты:**
    *   `MainActivity`: Точка входа, управление Compose-экранами.
    *   `AppViewModel`: Центральный менеджер состояния, бизнес-логики и управления VPN.
    *   `LidoVpnService`: Фоновый сервис (`VpnService`), управляющий TUN-интерфейсом и ядрами.
    *   `ByeDPIController`: Управление процессом ByeDPI.
    *   `XrayConfigGenerator`: Динамическая генерация JSON-конфигураций для Xray.

---

# 2. Архитектура

### Карта компонентов
*   **UI (Compose)**: `HomeScreen`, `ServersScreen`, `SettingsScreen`, `LogsScreen`.
*   **ViewModel**: `AppViewModel` — связующее звено. Хранит списки серверов, настройки, логи и управляет жизненным циклом подключения.
*   **VPN Service**: `LidoVpnService`. Взаимодействует с системой через `VpnService.Builder`.
*   **Native Cores**:
    *   `libv2ray.aar`: Позволяет запускать Xray прямо в процессе сервиса, передавая ему дескриптор TUN (FD).
    *   `libbyebyedpi.so`: Отдельный бинарник, запускаемый через `ProcessBuilder`.
*   **Data/Storage**:
    *   `SharedPreferences`: Хранение настроек и кеша серверов.
    *   `ConfigData`: Статические списки источников конфигураций.
    *   `GeoDataManager`: Загрузка и проверка `geoip.dat` / `geosite.dat`.

### Путь подключения (Connect Flow)
1.  **User Action**: Нажатие кнопки "Connect" в `HomeScreen`.
2.  **ViewModel**: Вызывается `toggleVpn()`. Проверяется наличие гео-баз (если нужно), выбирается сервер (если не выбран).
3.  **Permission Check**: Проверяется `VpnService.prepare()`. Если нужно, запрашивается разрешение через `MainActivity`.
4.  **Service Start**: Вызывается `startVpn(server)`, который отправляет `Intent` с `ACTION_START` и всеми параметрами (Host, Port, UUID, Type, ByeDPI args и т.д.) в `LidoVpnService`.
5.  **Service Setup**:
    *   `LidoVpnService` создает уведомление (Foreground Service).
    *   Создает `VpnService.Builder`, настраивает адреса (10.0.0.2), маршруты (0.0.0.0/0), DNS и MTU.
    *   Устанавливает `vpnInterface = builder.establish()`.
6.  **Core Execution**:
    *   **Xray Mode**: Генерируется JSON-конфиг. Вызывается `coreController.startLoop(config, fd)`. Xray начинает маршрутизировать трафик из TUN в туннель.
    *   **ByeDPI Mode**: Запускается бинарник ByeDPI. Xray настраивается как мост (TUN -> SOCKS -> ByeDPI).
7.  **State Notification**: Сервис отправляет `sendStateBroadcast(true)`, который ловится в `AppViewModel` (или через `LogManager`) для обновления UI.

---

# 3. VPN-механизм

*   **Протоколы:** VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC. Также поддерживается SOCKS5 прокси.
*   **Использование TUN:** Да, через `VpnService.Builder`.
*   **DNS:** Устанавливается через `builder.addDnsServer(dns)`. По умолчанию 1.1.1.1. В режиме ByeDPI используется отдельный DNS для прокси.
*   **IPv4/IPv6:** IPv4 всегда (`10.0.0.2`). IPv6 опционален (через настройки `isIpv6Enabled`). Если выключен, IPv6-маршруты не добавляются (риск утечки, если система не блокирует его принудительно).
*   **ByeDPI Engine:**
    *   Бинарник: `libbyebyedpi.so` (находится в `nativeLibraryDir`).
    *   Запуск: `ProcessBuilder` с аргументами вроде `-i 127.0.0.1 -p 10808`.
    *   Связка: Xray выступает в роли TUN2SOCKS адаптера, переправляя весь трафик на SOCKS-порт ByeDPI.
*   **Остановка:** Вызов `coreController.stopLoop()` и закрытие `vpnInterface.close()`. ByeDPI процесс убивается через `process.destroy()`.

---

# 4. Получение VPN-серверов

*   **Источники:** GitHub репозитории `whoahaow/rjsxrd` и `AvenCores/goida-vpn-configs`.
*   **Формат:** Raw текстовые файлы (вероятно, списки ссылок `vless://`, `vmess://` и т.д.).
*   **Загрузка:** Метод `fetchProviderUpdate` в `AppViewModel`. Использует OkHttp.
*   **Проверка:** Есть механизм проверки статуса репозитория через GitHub API (`/commits`).
*   **Retry/Timeout:** Тайм-ауты в `NetworkClient` (10с).
*   **Кеширование:** Список серверов сохраняется в `SharedPreferences` под ключом `saved_servers`.
*   **Валидация:** При парсинге проверяются типы протоколов. Если конфиг поврежден, он может быть пропущен или вызвать ошибку при инициализации Xray.
*   **Работоспособность:** Есть мощный механизм проверки серверов (`runAccurateHttpCheck`), который запускает временный экземпляр Xray на случайном порту и делает запрос через него.

---

# 5. Состояния VPN

*   **Disconnected**: `isConnected = false`, сервис не запущен.
*   **Connecting**: Состояние в `AppViewModel` при выполнении `connect()`, пока не придет подтверждение от сервиса.
*   **Connected**: Сервис запущен, `vpnInterface` активен.
*   **Disconnecting**: Кратковременное состояние при остановке.
*   **Единый источник истины:** Формально это `isConnected` в `AppViewModel`, но фактическое состояние в `LidoVpnService`. Связь через Broadcasts.
*   **Потенциальные рассинхроны:**
    *   Если сервис упадет или будет убит системой, `AppViewModel` может продолжать считать, что VPN подключен, пока не получит уведомление (которого может не быть при краше).
    *   Повторное нажатие "Connect" защищено проверкой `if (isConnected)`, но при `isAutoSettingUp` возможны наложения.

---

# 6. Стабильность и reconnect

*   **Смена сети:** Используется `ACCESS_NETWORK_STATE`. При смене сети (Wi-Fi <-> Mobile) `VpnService` обычно сохраняет TUN, но TCP-соединения ядра Xray могут разорваться. В коде не обнаружен явный автоматический перезапуск ядра при смене IP.
*   **Background Restrictions:** Используется `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`. Это должно защищать от убийства системы на Android 14+.
*   **Смерть сервиса:** Если `LidoVpnService` убит, TUN закрывается. Приложение не всегда мгновенно об этом узнает.
*   **Retry:** В режиме `SIMPLE` есть `pickNextBestServer()`, который автоматически ищет рабочий сервер при неудаче.

---

# 7. Производительность

*   **CPU**: Основная нагрузка идет от ядра Xray/ByeDPI.
*   **RAM**: Xray может потреблять значительный объем памяти при большом количестве соединений или тяжелых гео-базах.
*   **God Object**: `AppViewModel` (3077 строк) перегружен логикой. Это замедляет инициализацию и может приводить к лагам UI из-за большого количества состояний (`SnapshotStateList`, `SnapshotStateMap`).
*   **Блокирующие операции:** В `AppViewModel` замечены вызовы `future.get()` в `resolveHostWithTimeout`, которые хоть и в `Dispatchers.IO`, но используют фиксированный пул потоков (`blockingExecutor`).

---

# 8. Безопасность

*   **Критические уязвимости:**
    *   `usesCleartextTraffic="true"` в Manifest — позволяет приложению делать HTTP-запросы. Это огромный риск при работе с прокси/VPN конфигурациями.
    *   **Hardcoded URLs**: Прямые ссылки на GitHub репозитории в `ConfigData.kt`.
    *   **Unsafe SSL**: В методе `runAccurateHttpCheck` (AppViewModel.kt) используются `createUnsafeSslSocketFactory()` и `hostnameVerifier { _, _ -> true }`. Это отключает проверку сертификатов, делая тесты уязвимыми к MITM.
*   **Leaks:**
    *   **DNS Leak**: Если системный DNS игнорирует настройки TUN (редко на новых Android, но бывает).
    *   **IPv6 Leak**: Если IPv6 включен на устройстве, но выключен в приложении, трафик может идти мимо VPN.
    *   **Traffic Leak**: При краше ядра Xray вызывается `stopVpn()`, который закрывает TUN. Если у пользователя нет системного Kill Switch (Always-on VPN), трафик пойдет напрямую.
*   **Логи**: Логи ядра Xray транслируются через Broadcast с экшном `VPN_LOG`. Несмотря на `setPackage(packageName)`, это менее безопасно, чем внутренние callback-и.

---

# 9. Android lifecycle

*   **Foreground Service**: Настроен корректно для Android 14 (`specialUse`).
*   **Permissions**: Корректно запрашиваются разрешения на уведомления и VPN.
*   **Process Death**: Состояние `isConnected` хранится в памяти ViewModel. Если система убьет процесс и восстановит его, UI может показать "Disconnected", хотя сервис все еще работает в фоне.
*   **Quick Settings**: `VpnTileService` позволяет быстро управлять VPN, но его логика должна быть синхронизирована с `AppViewModel`.

---

# 10. Обработка ошибок

*   **Xray Errors**: Ошибки ядра пишутся в логи, но не всегда приводят к понятному алерту для пользователя.
*   **ByeDPI Errors**: Проверка "живучести" процесса через `Thread.sleep(3000)` очень ненадежна.
*   **Swallowed Exceptions**: Много блоков `try-catch` с пустыми `catch` или просто логированием, что затрудняет отладку сложных сценариев.
*   **Null Checks**: Использование `!!` (force unwrap) в `LidoVpnService.kt` (`vpnInterface!!.fd`) может привести к NPE, если интерфейс успел закрыться.

---

# 11. Код и архитектурные проблемы

*   **God Object**: `AppViewModel` требует декомпозиции на `ServerRepository`, `VpnController`, `SettingsManager` и т.д.
*   **Strong Coupling**: Сервис сильно зависит от `XrayConfigGenerator`.
*   **Magic Numbers**: Порты (10808), MTU (1500) и тайм-ауты разбросаны по коду.
*   **Threading**: Смешивание `viewModelScope.launch`, `Handler.post` и `ProcessBuilder`.

---

# 12. Тестируемость

*   **Сложность**: Тестировать `AppViewModel` практически невозможно из-за его размера и зависимостей от `Application` и `SharedPreferences`.
*   **VPN Lifecycle**: Тестируется только вручную.
*   **Инструментальные тесты**: В проекте есть заготовки, но реальных тестов логики VPN нет.

---

# 13. Критические проблемы

### 🔴 CRITICAL
*   **ID:** VPN-001
*   **Severity:** CRITICAL
*   **Файл:** `app/src/main/AndroidManifest.xml`
*   **Проблема:** `android:usesCleartextTraffic="true"`
*   **Последствия:** Возможность перехвата данных конфигураций и метаданных при обновлении списков серверов через незащищенные каналы.
*   **Рекомендация:** Установить в `false` и использовать только HTTPS.

*   **ID:** VPN-002
*   **Severity:** CRITICAL
*   **Файл:** `app/src/main/java/com/lido/vpn/AppViewModel.kt`
*   **Класс/метод:** `AppViewModel.runAccurateHttpCheck`
*   **Проблема:** Использование `createUnsafeSslSocketFactory`
*   **Последствия:** Полное отключение проверки SSL-сертификатов при тестировании серверов. Уязвимость к MITM.
*   **Рекомендация:** Использовать стандартный SSL-стек с системными сертификатами.

### 🟠 HIGH
*   **ID:** VPN-003
*   **Severity:** HIGH
*   **Файл:** `app/src/main/java/com/lido/vpn/AppViewModel.kt`
*   **Проблема:** Giant ViewModel (3000+ строк)
*   **Последствия:** Крайне низкая поддерживаемость, высокий риск внесения багов при любых изменениях, утечки памяти.
*   **Рекомендация:** Разбить на мелкие компоненты (Use Cases, Repositories).

---

# 14. Что работает нормально

*   **Поддержка ByeDPI**: Реализация через Xray-bridge — это изящное решение для объединения TUN и ByeDPI.
*   **Система проверок (Accurate Check)**: Полноценная проверка через запуск ядра дает самые точные результаты доступности серверов.
*   **Android 14 Compatibility**: Поддержка `specialUse` и типов FGS сделана вовремя.
*   **UI**: Современный интерфейс на Material 3 с поддержкой адаптивных цветов.

---

# 15. Карта проекта

**VPN Application**
*   **UI**: `ui/screens/`, `ui/components/`, `MainActivity.kt`
*   **VPN Service**: `LidoVpnService.kt`, `VpnTileService.kt`
*   **Network**: `NetworkClient.kt`, `ConfigData.kt`
*   **Server Management**: `AppViewModel.kt` (частично), `Models.kt`
*   **Configuration**: `XrayConfigGenerator.kt`
*   **Storage**: `SharedPreferences` (в `AppViewModel`)
*   **Core/Engine**: `libv2ray.aar`, `libbyebyedpi.so`, `ByeDPIController.kt`
*   **Utilities**: `Utils.kt`, `LogManager.kt`, `GeoDataManager.kt`, `NotificationHelper.kt`

---

## PRIORITY FIX ORDER

1.  **Безопасность**: Отключить `usesCleartextTraffic` и убрать `UnsafeSsl` из тестов.
2.  **Утечки**: Добавить полноценную поддержку IPv6 блокировки/маршрутизации.
3.  **Архитектура**: Начать рефакторинг `AppViewModel`, выделив логику работы с серверами в отдельный репозиторий.
4.  **Стабильность**: Улучшить мониторинг процесса ByeDPI (вместо `Thread.sleep`).
5.  **Lifecycle**: Синхронизировать состояние VPN в UI с реальным состоянием сервиса через более надежные механизмы (например, `StateFlow` в `Singleton` или `ServiceConnection`).
