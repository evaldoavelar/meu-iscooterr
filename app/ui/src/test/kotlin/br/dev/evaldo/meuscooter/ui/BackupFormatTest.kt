package br.dev.evaldo.meuscooter.ui

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupFormatTest {
    @Test fun `senha - curta antes de nao confere, e ok so com 8 ou mais iguais`() {
        assertEquals(PasswordCheck.TooShort, BackupFormat.check("", ""))
        assertEquals(PasswordCheck.TooShort, BackupFormat.check("1234567", "1234567"))
        assertEquals(PasswordCheck.Mismatch, BackupFormat.check("12345678", "1234567"))
        assertEquals(PasswordCheck.Mismatch, BackupFormat.check("12345678", "12345679"))
        assertEquals(PasswordCheck.Ok, BackupFormat.check("12345678", "12345678"))
    }

    @Test fun `dica de senha por situacao`() {
        assertEquals(null, BackupFormat.hint("", ""))
        assertEquals(PasswordHint("Faltam 7 caracteres para o mínimo de 8", HintTone.Attention), BackupFormat.hint("x", ""))
        assertEquals(PasswordHint("Faltam 2 caracteres para o mínimo de 8", HintTone.Attention), BackupFormat.hint("abcdef", "abcdef"))
        assertEquals(PasswordHint("Falta 1 caractere para o mínimo de 8", HintTone.Attention), BackupFormat.hint("abcdefg", "zzz"))
        assertEquals(PasswordHint("Repita a senha", HintTone.Attention), BackupFormat.hint("abcdefgh", ""))
        assertEquals(PasswordHint("As senhas não conferem", HintTone.Error), BackupFormat.hint("abcdefgh", "abcdefg"))
        assertEquals(PasswordHint("Senha ok", HintTone.Success), BackupFormat.hint("abcdefgh", "abcdefgh"))
    }

    @Test fun `nome do arquivo usa a data local`() {
        val sp = ZoneId.of("America/Sao_Paulo")
        assertEquals("meu-iscooter-2026-11-17.ixbak", BackupFormat.fileName(1794915600000L, sp))
        // 2026-11-18 01:30 UTC ainda é dia 17 em São Paulo e já é dia 18 em UTC
        assertEquals("meu-iscooter-2026-11-17.ixbak", BackupFormat.fileName(1794965400000L, sp))
        assertEquals("meu-iscooter-2026-11-18.ixbak", BackupFormat.fileName(1794965400000L, ZoneId.of("UTC")))
    }

    @Test fun `tamanho`() {
        assertEquals("0 B", BackupFormat.size(0)); assertEquals("1023 B", BackupFormat.size(1023))
        assertEquals("1 KB", BackupFormat.size(1024)); assertEquals("38 KB", BackupFormat.size(38 * 1024 + 100)); assertEquals("39 KB", BackupFormat.size(38 * 1024 + 600))
        assertEquals("1,5 MB", BackupFormat.size((1.5 * 1024 * 1024).toLong()))
    }

    @Test fun `data de criacao`() {
        assertEquals("17/11/2026, 08:40", BackupFormat.created(1794915600000L, ZoneId.of("America/Sao_Paulo")))
    }

    @Test fun `texto de restaurado`() {
        assertEquals("6 viagens, o iX7 Pro e o PIN foram recuperados. Pode conectar ao patinete.", BackupFormat.restoredText(RestoreDoneUi(6, true, PinOutcome.Restored)))
        assertEquals("1 viagem e o iX7 Pro foram recuperados. Pode conectar ao patinete.", BackupFormat.restoredText(RestoreDoneUi(1, true, PinOutcome.NoBiometrics)))
        assertEquals("3 viagens foram recuperadas.", BackupFormat.restoredText(RestoreDoneUi(3, false, PinOutcome.NotInBackup)))
        assertEquals("1 viagem foi recuperada.", BackupFormat.restoredText(RestoreDoneUi(1, false, PinOutcome.NotInBackup)))
        assertEquals("Nenhuma viagem e o PIN foram recuperados.", BackupFormat.restoredText(RestoreDoneUi(0, false, PinOutcome.Restored)))
        assertEquals("1 viagem", BackupFormat.trips(1)); assertEquals("2 viagens", BackupFormat.trips(2))
    }
}
