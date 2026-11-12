package br.dev.evaldo.meuscooter

import java.io.File
import java.util.Calendar
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun firstWriteAfterReopenIncludesIncomingUtf8BytesInLimit() {
        val dir = tmp.newFolder()
        File(dir, "ix7-0.log").writeText("12345678\n") // 9 bytes, limite 10
        RotatingLogFiles(dir, 10, 2).apply { write("éé"); close() } // 4 bytes UTF-8 + LF
        assertEquals("éé\n", File(dir, "ix7-0.log").readText())
        assertEquals("12345678\n", File(dir, "ix7-1.log").readText())
    }

    @Test fun firstWriteAfterReopenAtExactLimitDoesNotRotate() {
        val dir = tmp.newFolder()
        File(dir, "ix7-0.log").writeText("1234\n")
        RotatingLogFiles(dir, 10, 2).apply { write("5678"); close() }
        assertEquals("1234\n5678\n", File(dir, "ix7-0.log").readText())
        assertFalse(File(dir, "ix7-1.log").exists())
    }

    @Test fun deleteFailureAbortsRotationAndBoundsCurrentFile() {
        assertRotationFailurePreservesCurrent("delete ix7-2.log")
    }

    @Test fun archiveRenameFailureAbortsBeforeMovingCurrentFile() {
        assertRotationFailurePreservesCurrent("rename ix7-1.log")
    }

    @Test fun currentRenameFailureBoundsFileWithoutTruncation() {
        assertRotationFailurePreservesCurrent("rename ix7-0.log")
    }

    private fun assertRotationFailurePreservesCurrent(failingOperation: String) {
        val dir = tmp.newFolder()
        File(dir, "ix7-0.log").writeText("1111\n2222\n")
        File(dir, "ix7-1.log").writeText("archive\n")
        File(dir, "ix7-2.log").writeText("oldest\n")
        val operations = mutableListOf<String>()
        var failed = false
        val io = object : LogFileOperations by DiskLogFileOperations {
            override fun delete(file: File): Boolean {
                val operation = "delete ${file.name}"
                operations += operation
                if (operation == failingOperation) { failed = true; return false }
                check(!failed) { "Rotation continued after failure" }
                return DiskLogFileOperations.delete(file)
            }

            override fun rename(source: File, target: File): Boolean {
                val operation = "rename ${source.name}"
                operations += operation
                if (operation == failingOperation) { failed = true; return false }
                check(!failed) { "Rotation continued after failure" }
                return DiskLogFileOperations.rename(source, target)
            }
        }
        val rotating = RotatingLogFiles(dir, 10, 3, io)
        rotating.write("3333"); rotating.close()
        assertTrue(failed)
        assertEquals(1L, rotating.rotationDropped)
        assertEquals(failingOperation, operations.last())
        assertEquals("1111\n2222\n", File(dir, "ix7-0.log").readText())
        if (failingOperation == "delete ix7-2.log") {
            assertEquals("archive\n", File(dir, "ix7-1.log").readText())
            assertEquals("oldest\n", File(dir, "ix7-2.log").readText())
        }
    }

    @Test fun singleFileRotationHandlesSuccessfulAndFailedDeletion() {
        for (deleteSucceeds in listOf(false, true)) {
            val dir = tmp.newFolder()
            File(dir, "ix7-0.log").writeText("1111\n2222\n")
            val io = object : LogFileOperations by DiskLogFileOperations {
                override fun delete(file: File) = deleteSucceeds && DiskLogFileOperations.delete(file)
            }
            RotatingLogFiles(dir, 10, 1, io).apply { write("3333"); close() }
            val expected = if (deleteSucceeds) "3333\n" else "1111\n2222\n"
            assertEquals(expected, File(dir, "ix7-0.log").readText())
        }
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

    @Test fun blockedIoBoundsQueueDropsNewestAndReportsCountWhenDrainingResumes() {
        val dir = tmp.newFolder()
        val enteredIo = CountDownLatch(1)
        val releaseIo = CountDownLatch(1)
        val io = object : LogFileOperations by DiskLogFileOperations {
            override fun openAppend(file: File): java.io.BufferedWriter {
                enteredIo.countDown()
                check(releaseIo.await(5, TimeUnit.SECONDS)) { "Blocked I/O was not released" }
                return DiskLogFileOperations.openAppend(file)
            }
        }
        val log = FileDebugLog(dir, flushLines = 1, now = { 0L }, queueCapacity = 3, fileOperations = io)
        val producer = Executors.newSingleThreadExecutor()
        try {
            log.append("T", "in-flight")
            assertTrue(enteredIo.await(2, TimeUnit.SECONDS))
            // A E/S continua bloqueada enquanto o produtor enche a fila e excede a capacidade.
            producer.submit {
                repeat(3) { log.append("T", "queued $it") }
                repeat(7) { log.append("T", "discarded $it") }
            }.get(1, TimeUnit.SECONDS)
            releaseIo.countDown()
            val file = File(dir, "ix7-0.log")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((!file.exists() || file.readLines().size < 5) && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(5, file.readLines().size)
            log.append("T", "recovered")
        } finally {
            releaseIo.countDown()
            producer.shutdownNow()
            log.shutdownAndFlush()
        }
        assertEquals(
            listOf("T in-flight", "IX7APP dropped=7", "T queued 0", "T queued 1", "T queued 2", "T recovered"),
            File(dir, "ix7-0.log").readLines().map { it.substring(24) },
        )
    }

    @Test fun fileDebugLogWritesLinesWithFlushByCount() {
        val dir = tmp.newFolder("e")
        val log = FileDebugLog(dir, 1_000_000, 2, flushMs = 60_000, flushLines = 2, now = { 0L })
        try {
            log.append("IX7TX", "a"); log.append("IX7GPS", "b")
            val f = File(dir, "ix7-0.log")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (f.length() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
            val lines = f.readLines()
            assertEquals(2, lines.size)
            assertTrue(lines[0].endsWith(" IX7TX a")); assertTrue(lines[1].endsWith(" IX7GPS b"))
        } finally {
            log.shutdownAndFlush()
        }
    }

    @Test fun flushesByTimeEvenBelowLineThreshold() {
        val dir = tmp.newFolder("f")
        val opened = CountDownLatch(1)
        val io = object : LogFileOperations by DiskLogFileOperations {
            override fun openAppend(file: File) = DiskLogFileOperations.openAppend(file).also { opened.countDown() }
        }
        val log = FileDebugLog(dir, 1_000_000, 2, flushMs = 50, flushLines = 1000, fileOperations = io)
        try {
            log.append("T", "so uma")
            assertTrue("Executor deve iniciar a E/S", opened.await(2, TimeUnit.SECONDS))
            val f = File(dir, "ix7-0.log")
            // 750 ms tolera o escalonamento da JVM, mas uma mutação de 50 ms para 2 s deve falhar.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750)
            while (f.length() == 0L && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Flush de 50 ms deve ficar visível em menos de 750 ms", f.length() > 0)
            assertEquals(1, f.readLines().size)
        } finally {
            log.shutdownAndFlush()
        }
    }

    @Test fun concurrentAppendsKeepEveryLineIntact() {
        val dir = tmp.newFolder("g")
        val log = FileDebugLog(dir, 10_000_000, 2)
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(4) { t -> pool.execute { repeat(250) { i -> log.append("T$t", "linha $i") } } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
            log.shutdownAndFlush()
        }
        val lines = File(dir, "ix7-0.log").readLines()
        assertEquals(1000, lines.size)
        assertTrue(lines.all { Regex("""\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3} T\d linha \d+""").matches(it) })
        val expectedIds = (0 until 4).flatMap { t -> (0 until 250).map { i -> "T$t linha $i" } }.toSet()
        val actualIds = lines.map { it.substring(24) }
        assertEquals(expectedIds, actualIds.toSet())
        assertEquals("Nenhum id pode se repetir", actualIds.size, actualIds.toSet().size)
    }

    @Test fun releaseCreatesNothing() {
        val dir = tmp.newFolder("h")
        assertEquals(null, FileDebugLog.shared(false, dir))
        assertFalse(File(dir, "debuglog").exists())
    }

    @Test fun sharedInstanceSurvivesRepeatedAndConcurrentAcquisition() {
        val dir = tmp.newFolder("process")
        val pool = Executors.newFixedThreadPool(4)
        try {
            val requests = (0 until 20).map { pool.submit<FileDebugLog?> { FileDebugLog.shared(true, dir) } }
            val log = requests.first().get(2, TimeUnit.SECONDS)!!
            try {
                requests.forEach { assertSame(log, it.get(2, TimeUnit.SECONDS)) }
                log.append("T", "antes da recriacao")
                val recreated = FileDebugLog.shared(true, dir)
                assertSame(log, recreated)
                recreated!!.append("T", "depois da recriacao")
                // Mesmo com instância debug já criada, o gate de release continua fechado.
                assertEquals(null, FileDebugLog.shared(false, dir))
            } finally {
                log.shutdownAndFlush()
            }
            assertEquals(
                listOf("T antes da recriacao", "T depois da recriacao"),
                File(dir, "debuglog/ix7-0.log").readLines().map { it.substring(24) },
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
