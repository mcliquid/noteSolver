package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticLogTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp() {
		DiagnosticLog.resetForTests();
	}

	@AfterEach
	void tearDown() {
		DiagnosticLog.resetForTests();
	}

	@Test
	void writesInfoLineToDailyFile() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.info("hello diagnostic");
		log.flushPendingWritesForTests();
		Path file = log.currentLogFile();
		assertNotNull(file);
		assertTrue(Files.exists(file));
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("[INFO]"));
		assertTrue(text.contains("hello diagnostic"));
		assertTrue(text.contains("T"));
	}

	@Test
	void debugRespectsVerboseFlag() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.debug("hidden");
		log.flushPendingWritesForTests();
		Path file = log.currentLogFile();
		assertTrue(file == null || !Files.exists(file));

		DiagnosticLog.resetForTests();
		DiagnosticLog.installForTests(tempDir, () -> true);
		log = DiagnosticLog.get();
		log.debug("visible");
		log.flushPendingWritesForTests();
		String text = Files.readString(log.currentLogFile(), StandardCharsets.UTF_8);
		assertTrue(text.contains("[DEBUG]"));
		assertTrue(text.contains("visible"));
	}

	@Test
	void logsStackTraceOnError() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.error("boom", new IllegalStateException("kaputt"));
		log.flushPendingWritesForTests();
		String text = Files.readString(log.currentLogFile(), StandardCharsets.UTF_8);
		assertTrue(text.contains("IllegalStateException"));
		assertTrue(text.contains("kaputt"));
	}

	@Test
	void concurrentWritesAreSerialized() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			for (int i = 0; i < 200; i++) {
				final int n = i;
				pool.submit(() -> log.info("line-" + n));
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		log.flushPendingWritesForTests();
		List<String> lines = Files.readAllLines(log.currentLogFile(), StandardCharsets.UTF_8);
		assertEquals(200, lines.size());
	}

	@Test
	void rotationDeletesOldFiles() throws Exception {
		Path old = tempDir.resolve("notesolver-diagnostic-2000-01-01.log");
		Files.writeString(old, "old", StandardCharsets.UTF_8);
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog.get().enqueueMaintenance();
		DiagnosticLog.get().flushPendingWritesForTests();
		assertFalse(Files.exists(old));
	}

	@Test
	void totalSizeTrimRemovesOldestFirst() throws Exception {
		Path d1 = tempDir.resolve("notesolver-diagnostic-2020-01-01.log");
		Path d2 = tempDir.resolve("notesolver-diagnostic-2020-01-02.log");
		byte[] chunk = new byte[12 * 1024 * 1024];
		Files.write(d1, chunk);
		Files.write(d2, chunk);
		List<Path> files = DiagnosticLog.listLogFilesIn(tempDir);
		DiagnosticLog.trimTotalSize(files);
		assertFalse(Files.exists(d1));
		assertTrue(Files.exists(d2));
	}

	@Test
	void writeFailureDoesNotThrow() throws Exception {
		Path readOnlyDir = tempDir.resolve("ro");
		Files.createDirectories(readOnlyDir);
		readOnlyDir.toFile().setWritable(false);
		DiagnosticLog.installForTests(readOnlyDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.info("should not throw");
		log.flushPendingWritesForTests();
		readOnlyDir.toFile().setWritable(true);
	}

	@Test
	void restartAppendsSameDayFile() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.info("first");
		log.flushPendingWritesForTests();
		Path file = log.currentLogFile();
		DiagnosticLog.resetForTests();
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog.get().info("second");
		DiagnosticLog.get().flushPendingWritesForTests();
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("first"));
		assertTrue(text.contains("second"));
	}

	@Test
	void correlationAppearsInAutoCloseEvent() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		String cid = AutoCloseDiagnostics.newCorrelationId();
		AutoCloseDiagnostics.noteUpload(99L, 42, cid, AutoCloseOutcome.STARTED, null);
		DiagnosticLog.get().flushPendingWritesForTests();
		String text = Files.readString(DiagnosticLog.get().currentLogFile(), StandardCharsets.UTF_8);
		assertTrue(text.contains("correlationId=" + cid));
		assertTrue(text.contains("outcome=STARTED"));
		assertFalse(text.toLowerCase().contains("password"));
	}

	@Test
	void diagnosticLogRedactsSecretsInErrors() throws Exception {
		DiagnosticLog.installForTests(tempDir, () -> false);
		DiagnosticLog log = DiagnosticLog.get();
		log.error("auth failure password=sekret");
		log.flushPendingWritesForTests();
		String text = Files.readString(log.currentLogFile(), StandardCharsets.UTF_8);
		assertFalse(text.contains("sekret"));
	}

	@Test
	void parseDayFromName() {
		assertEquals(LocalDate.of(2026, 11, 1),
				DiagnosticLog.parseDayFromName("notesolver-diagnostic-2026-11-01.log"));
	}
}
