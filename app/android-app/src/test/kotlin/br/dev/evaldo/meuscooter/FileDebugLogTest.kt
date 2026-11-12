package br.dev.evaldo.meuscooter

import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDebugLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun formatsLine() {
        val ms = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 12, 9, 8, 7); set(Calendar.MILLISECOND, 6) }.timeInMillis
        assertEquals("2026-11-12 09:08:07.006 IX7GPS subscribe", formatDebugLogLine(ms, "IX7GPS", "subscribe"))
    }

    @Test fun writesAndAppendsAcrossInstances() {
        val dir = tmp.newFolder("a")
        RotatingLogFiles(dir, 1000, 4).apply { write("um"); close() }
        RotatingLogFiles(dir, 1000, 4).apply { write("dois"); close() }
        assertEquals("um\ndois\n", File(dir, "ix7-0.log").readText())
    }

    @Test fun rotatesBySizeKeepingNewestAsZeroAndDropsOldest() {
        val dir = tmp.newFolder("b")
        val r = RotatingLogFiles(dir, 10, 3) // cada linha "aaaa" = 5 bytes; 2 por arquivo
        for (l in listOf("1111", "2222", "3333", "4444", "5555", "6666", "7777")) r.write(l)
        r.close()
        assertEquals("7777\n", File(dir, "ix7-0.log").readText())
        assertEquals("5555\n6666\n", File(dir, "ix7-1.log").readText())
        assertEquals("3333\n4444\n", File(dir, "ix7-2.log").readText())
        assertFalse(File(dir, "ix7-3.log").exists())
    }

    @Test fun existingBigFileRotatesOnOpen() {
        val dir = tmp.newFolder("c")
        File(dir, "ix7-0.log").writeText("x".repeat(20))
        RotatingLogFiles(dir, 10, 2).apply { write("novo"); close() }
        assertEquals("novo\n", File(dir, "ix7-0.log").readText())
        assertEquals(20, File(dir, "ix7-1.log").length())
    }

    @Test fun ioFailureDoesNotPropagate() {
        val blocker = tmp.newFile("arquivo") // "diretório" é um arquivo: mkdirs/abrir falham
        val r = RotatingLogFiles(File(blocker, "sub"), 100, 2)
        r.write("x"); r.flush(); r.close()
        val log = FileDebugLog(File(blocker, "sub2"))
        log.append("T", "x")
        log.shutdownAndFlush()
    }

    @Test fun appendAfterShutdownDoesNotThrow() {
        val log = FileDebugLog(tmp.newFolder("d"))
        log.shutdownAndFlush()
        log.append("T", "tarde")
    }

    @Test fun fileDebugLogWritesLinesWithFlushByCount() {
        val dir = tmp.newFolder("e")
        val log = FileDebugLog(dir, 1_000_000, 2, flushMs = 60_000, flushLines = 2, now = { 0L })
        log.append("IX7TX", "a"); log.append("IX7GPS", "b")
        val f = File(dir, "ix7-0.log")
        val deadline = System.currentTimeMillis() + 3000
        while (f.length() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val lines = f.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith(" IX7TX a")); assertTrue(lines[1].endsWith(" IX7GPS b"))
        log.shutdownAndFlush()
    }

    @Test fun flushesByTimeEvenBelowLineThreshold() {
        val dir = tmp.newFolder("f")
        val log = FileDebugLog(dir, 1_000_000, 2, flushMs = 50, flushLines = 1000)
        log.append("T", "so uma")
        val f = File(dir, "ix7-0.log")
        val deadline = System.currentTimeMillis() + 3000
        while (f.length() == 0L && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, f.readLines().size)
        log.shutdownAndFlush()
    }

    @Test fun concurrentAppendsKeepEveryLineIntact() {
        val dir = tmp.newFolder("g")
        val log = FileDebugLog(dir, 10_000_000, 2)
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { t -> pool.execute { repeat(250) { i -> log.append("T$t", "linha $i") } } }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        log.shutdownAndFlush()
        val lines = File(dir, "ix7-0.log").readLines()
        assertEquals(1000, lines.size)
        assertTrue(lines.all { Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} T\d linha \d+""").matches(it) })
    }

    @Test fun releaseCreatesNothing() {
        val dir = tmp.newFolder("h")
        assertEquals(null, FileDebugLog.create(false, dir))
        assertFalse(File(dir, "debuglog").exists())
    }
}
