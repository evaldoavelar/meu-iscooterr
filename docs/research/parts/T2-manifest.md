# T2 — Manifest (Apktool 3.0.3, base.apk 1.0.20)
Fonte: `research/decompiled/apktool/AndroidManifest.xml`. `[ESTÁTICO]`
- `package=com.iscooter.car`, compileSdk 36 (manifest); minSdk 24 e targetSdk 36 vêm de `research/decompiled/apktool/apktool.yml:7-8`; splits por ABI e densidade.
- `allowBackup=true` (relevante: o app **novo** deve usar `false` ao guardar PIN/identidade). Não debuggable; sem `usesCleartextTraffic` explícito.
- `BleCService` (`blue/Interfa`): `exported=false`, `foregroundServiceType=connectedDevice` → modelo a seguir no app novo (FGS `connectedDevice`, opcional).
- Outros serviços: NavInfoSer, LocationGpService, NavigationService (location); RecordInfoServices (mediaProjection); PushIscooterService (FCM). Fora do escopo.
- Permissões BLE: BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE; localização FINE/COARSE; FOREGROUND_SERVICE(+CONNECTED_DEVICE, LOCATION, MEDIA_PROJECTION); POST_NOTIFICATIONS; RECEIVE_BOOT_COMPLETED; WAKE_LOCK. Sem `uses-feature` de Bluetooth declarado.
- App novo precisa apenas de: BLUETOOTH_SCAN (`neverForLocation`), BLUETOOTH_CONNECT, FOREGROUND_SERVICE(+CONNECTED_DEVICE) e POST_NOTIFICATIONS (opcionais); sem localização se usar CompanionDeviceManager/scan filtrado `[HIPÓTESE]` a validar no Android alvo.
