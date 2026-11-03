# T9 (parcial) — Nuvem, login e sessão — base.apk 1.0.20
Autor: coordenador. Sem revisão cruzada. Caminhos em `research/decompiled/jadx/sources/com/iscooter/car/`.

## Servidor [ESTÁTICO]
- Base de produção `https://app.iscooterglobal.com/` (arquivos `https://file.iscooterglobal.com`), `netw/NetConfig.java:42-45`. Cliente Retrofit/OkHttp em `netw/HttpsRequests.java`, config em `netw/NetWorkConfig.java`.
- Interceptors: `CommonInterceptor`, `EncodeInterceptor`, `JsonInterceptor`; o `CommonInterceptor` adiciona cabeçalho `Authorization` (`NetWorkConfig.java:274,300`) e há uso de hash (declara `NoSuchAlgorithmException`) → `[HIPÓTESE]` requisições assinadas. Detalhe da assinatura **não analisado** e desnecessário se o app novo não usar a nuvem.
- Login: `app-api/login/email`, `login/google`, `login/mobile`; sessão: token em SharedPreferences (`PreferenceSpUtil.TOKEN_TOKEN="token_token"`, `UID_UID`, `LOGINEDS`). Política de expiração do token: `[DESCONHECIDO]` (explica os “logins que expiram”, não confirmado).
- Dispositivo: `devices/add|bind|unbind|info|query|queryFunc|set|setPassword|userDevices` (campos `sn`, `bluetoothAddress`, `bluetoothName`). `devices/query` devolve `isBind` e `userInfo.uid` usados para decidir posse (`DeviceCarControlFrag.java:~1644-1715`).
- **O PIN de bloqueio é sincronizado com o servidor**: `upLockPassword` → `devices/setPassword(sn, bluetoothAddress, password)` (`HttpsRequests.java:318-321`), e o app guarda o PIN em `LOCK_PWD_LPW`. `[HIPÓTESE]` o app original pode recuperar o PIN pelo servidor ou pelo patinete (cmd 0x67).
- Outros usos de nuvem (fora de escopo): ride/ranking, loja, FAQ, firmware (OTA), mensagens, FCM.

## Conclusões
1. `[ESTÁTICO]` O envio de LOCK (0x32) é puramente BLE+PIN; nenhum token/assinatura do servidor é incluído no quadro (`q3/a.java:136-147`).
2. `[ESTÁTICO]` A UI do app original só habilita o fluxo para usuário logado e dono segundo o servidor. Isso é política do **cliente**; se o firmware também a aplica é `[DESCONHECIDO]` até a captura.
3. Para o app novo independente: cadastrar o patinete **uma vez** guardando MAC/ID estável + PIN (dado pelo proprietário) localmente (Keystore). A conta em nuvem só seria necessária se o firmware exigir 0x41=1 ou outra prova de posse.

## Pendências
Confirmar em captura HCI (Sprint 2); decidir ADR 0002; mapear como o app obtém o PIN no primeiro uso (usuário digita? servidor? patinete 0x67?).
