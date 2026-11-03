# Reempacotamento para captura passiva (Sprint 2)

Autorizado pelo proprietário em 2026-11-03, como último recurso: o HCI snoop do Samsung não fica acessível sem root (o btsnoop não vem no `bugreport`, `/data/misc/bluetooth` dá permissão negada e não há socket) e o logcat do app não tem os bytes BLE. Só observa: **nenhum comando novo** é enviado ao patinete; o app continua sendo o original, com 3 chamadas de log a mais.

## O que muda no app
A classe nova `ix7tap/src/ix7/Tap.java` (código nosso) vai como `classes6.dex`. Ela escreve no logcat (tag `IX7TAP`) e **mascara na origem**:
- LOCK 0x32: dígitos do PIN e CRC (o CRC permitiria descobrir o PIN por força bruta);
- 0x67 (senha), 0x61 (SN) e 0x3A (gravar SN): dados e CRC inteiros;
- fragmento que não é um frame `1A A1 … 1F F1` completo: só o tamanho.

As 3 linhas inseridas no smali da FastBle, logo após `.locals` (em `research/captures/repack/base/`, ignorado):

| Arquivo | Método | Linha inserida |
|---|---|---|
| `smali_classes2/p0/a.smali` | `r([BLq0/k;Ljava/lang/String;)V` (escrita) | `invoke-static {p1}, Lix7/Tap;->tx([B)V` |
| `smali_classes2/com/clj/fastble/bluetooth/BleBluetooth$a.smali` | `onCharacteristicChanged(gatt, char)` | `invoke-static {p2}, Lix7/Tap;->rx(Landroid/bluetooth/BluetoothGattCharacteristic;)V` |
| idem | `onCharacteristicWrite(gatt, char, status)` | `invoke-static {p2, p3}, Lix7/Tap;->wr(Landroid/bluetooth/BluetoothGattCharacteristic;I)V` |

## Build (não instala nada)
```
java -jar tools/apktool.jar d -r -f -o research/captures/repack/base research/captures/device-apk/base.apk
# aplicar as 3 linhas acima
scripts/repack/build.sh          # gera research/captures/repack/out/{base,split_config.*}.apk
```
Testes: `scripts/repack/ix7tap/test/ix7/TapTest.java` (máscara, no host) e `tests/analysis/test_ix7tap_parse.py`.

## Instalação (só com "pode instalar" do proprietário)
A assinatura muda, então é preciso desinstalar o app original. **Isso apaga o login e o PIN em cache**: o proprietário precisa saber o PIN e fazer login de novo.
```
adb uninstall com.iscooter.car
adb install-multiple research/captures/repack/out/base.apk research/captures/repack/out/split_config.arm64_v8a.apk research/captures/repack/out/split_config.xxhdpi.apk
adb logcat -c                    # antes do roteiro
# proprietário: login, roteiro docs/research/captura-roteiro.md
adb logcat -d -v threadtime -s IX7TAP > research/captures/s2d/ix7tap.txt
scripts/analysis/ix7tap_parse.py research/captures/s2d/ix7tap.txt > research/captures/s2d/report.json
```
## Restaurar o original
```
adb uninstall com.iscooter.car
adb install-multiple research/captures/device-apk/base.apk research/captures/device-apk/split_config.arm64_v8a.apk research/captures/device-apk/split_config.xxhdpi.apk
```
