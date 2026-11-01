package org.openstreetmap.josm.plugins.notesolver;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Persistent, rotating diagnostic log under the JOSM user data directory ({@code notesolver/logs}).
 * Writes are asynchronous; failures never propagate to callers.
 */
public final class DiagnosticLog {

	public static final String PREF_VERBOSE = "noteSolver.verboseDiagnostics";
	static final int RETENTION_DAYS = 14;
	static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;

	private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
	private static final DiagnosticLog NOOP = new DiagnosticLog(null, null, () -> false);

	private static volatile DiagnosticLog active = NOOP;

	private final Path logDirectory;
	private final ExecutorService writer;
	private final BooleanSupplier verbose;
	private volatile LocalDate currentDay;
	private volatile Path currentFile;

	private DiagnosticLog(Path logDirectory, ExecutorService writer, BooleanSupplier verbose) {
		this.logDirectory = logDirectory;
		this.writer = writer;
		this.verbose = verbose;
	}

	public static DiagnosticLog get() {
		return active;
	}

	public static void start(String pluginVersion, String josmVersion) {
		shutdownInternal();
		Path dir = resolveLogDirectory();
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			active = NOOP;
			return;
		}
		ExecutorService exec = Executors.newSingleThreadExecutor(namedDaemon("notesolver-diagnostic-log"));
		DiagnosticLog log = new DiagnosticLog(dir, exec, () -> Config.getPref().getBoolean(PREF_VERBOSE, false));
		active = log;
		log.enqueueMaintenance();
		log.info("Diagnostic logging started pluginVersion=" + pluginVersion + " josmVersion=" + josmVersion);
	}

	public static void shutdown() {
		DiagnosticLog log = active;
		if (log != NOOP) {
			log.info("Diagnostic logging shutting down");
		}
		shutdownInternal();
	}

	static void installForTests(Path logDirectory, BooleanSupplier verbose) {
		shutdownInternal();
		Path dir = logDirectory;
		try {
			Files.createDirectories(dir);
		} catch (IOException ignored) {
			active = NOOP;
			return;
		}
		ExecutorService exec = Executors.newSingleThreadExecutor(namedDaemon("notesolver-diagnostic-log-test"));
		active = new DiagnosticLog(dir, exec, verbose);
		active.enqueueMaintenance();
	}

	static void resetForTests() {
		shutdownActiveAndAwait();
		active = NOOP;
	}

	private static void shutdownActiveAndAwait() {
		DiagnosticLog prev = active;
		active = NOOP;
		if (prev == null || prev == NOOP || prev.writer == null) {
			return;
		}
		try {
			prev.writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
		} catch (Exception ignored) {
			// best effort drain before shutdown
		}
		prev.writer.shutdown();
		try {
			if (!prev.writer.awaitTermination(10, TimeUnit.SECONDS)) {
				prev.writer.shutdownNow();
				prev.writer.awaitTermination(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			prev.writer.shutdownNow();
		}
		prev.currentDay = null;
		prev.currentFile = null;
	}

	public Path getLogDirectory() {
		return logDirectory;
	}

	public Path currentLogFile() {
		return currentFile;
	}

	public void info(String message) {
		write(Level.INFO, message, null);
	}

	public void warn(String message) {
		write(Level.WARN, message, null);
	}

	public void error(String message) {
		write(Level.ERROR, message, null);
	}

	public void error(String message, Throwable t) {
		write(Level.ERROR, message, t);
	}

	public void debug(String message) {
		if (verbose.getAsBoolean()) {
			write(Level.DEBUG, message, null);
		}
	}

	/** Test-only: wait until queued log lines are written. */
	public void flushPendingWritesForTests() throws Exception {
		if (writer == null) {
			return;
		}
		writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
	}

	enum Level {
		DEBUG, INFO, WARN, ERROR
	}

	void write(Level level, String message, Throwable throwable) {
		if (writer == null) {
			return;
		}
		final String line = formatLine(level, message, throwable);
		writer.execute(() -> appendLine(line));
	}

	static String formatLine(Level level, String message, Throwable throwable) {
		String ts = ZonedDateTime.now(ZoneId.systemDefault()).format(LINE_TIME);
		String body = LogSanitizer.sanitize(message == null ? "" : message);
		StringBuilder sb = new StringBuilder();
		sb.append(ts).append(" [").append(level.name()).append("] [")
				.append(Thread.currentThread().getName()).append("] ").append(body);
		if (throwable != null) {
			sb.append(System.lineSeparator());
			StringWriter sw = new StringWriter();
			throwable.printStackTrace(new PrintWriter(sw));
			sb.append(LogSanitizer.sanitize(sw.toString()));
		}
		return sb.toString();
	}

	private void appendLine(String line) {
		try {
			Path file = fileForToday();
			try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				w.write(line);
				w.newLine();
			}
			runMaintenance();
		} catch (IOException ignored) {
			// Logging must never disturb note uploads or UI.
		}
	}

	Path fileForToday() throws IOException {
		LocalDate today = LocalDate.now(ZoneId.systemDefault());
		if (currentDay == null || !currentDay.equals(today) || currentFile == null) {
			currentDay = today;
			currentFile = logDirectory.resolve("notesolver-diagnostic-" + today + ".log");
		}
		return currentFile;
	}

	void enqueueMaintenance() {
		if (writer == null) {
			return;
		}
		writer.execute(this::runMaintenance);
	}

	void runMaintenance() {
		if (logDirectory == null || !Files.isDirectory(logDirectory)) {
			return;
		}
		LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(RETENTION_DAYS);
		List<Path> files = listLogFiles();
		for (Path p : files) {
			LocalDate day = parseDayFromName(p.getFileName().toString());
			if (day != null && day.isBefore(cutoff)) {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// keep going
				}
			}
		}
		trimTotalSize(listLogFiles());
	}

	static void trimTotalSize(List<Path> files) {
		long total = 0;
		List<PathSize> sized = new ArrayList<>();
		for (Path p : files) {
			try {
				long size = Files.size(p);
				total += size;
				sized.add(new PathSize(p, size));
			} catch (IOException ignored) {
				// skip
			}
		}
		if (total <= MAX_TOTAL_BYTES) {
			return;
		}
		sized.sort(Comparator.comparing(ps -> ps.path.getFileName().toString()));
		for (PathSize ps : sized) {
			if (total <= MAX_TOTAL_BYTES) {
				break;
			}
			try {
				Files.deleteIfExists(ps.path);
				total -= ps.size;
			} catch (IOException ignored) {
				// skip
			}
		}
	}

	static List<Path> listLogFilesIn(Path logDirectory) throws IOException {
		if (!Files.isDirectory(logDirectory)) {
			return List.of();
		}
		List<Path> out = new ArrayList<>();
		try (var stream = Files.list(logDirectory)) {
			stream.filter(p -> p.getFileName().toString().startsWith("notesolver-diagnostic-")
					&& p.getFileName().toString().endsWith(".log"))
					.forEach(out::add);
		}
		return out;
	}

	private List<Path> listLogFiles() {
		try {
			return listLogFilesIn(logDirectory);
		} catch (IOException e) {
			return List.of();
		}
	}

	static LocalDate parseDayFromName(String name) {
		if (name == null || !name.startsWith("notesolver-diagnostic-") || !name.endsWith(".log")) {
			return null;
		}
		String core = name.substring("notesolver-diagnostic-".length(), name.length() - ".log".length());
		try {
			return LocalDate.parse(core);
		} catch (Exception e) {
			return null;
		}
	}

	static Path resolveLogDirectory() {
		try {
			Path base = JosmBaseDirectories.getInstance().getUserDataDirectory(false).toPath();
			return base.resolve("notesolver").resolve("logs");
		} catch (RuntimeException e) {
			return Path.of(System.getProperty("user.home", "."), "notesolver", "logs");
		}
	}

	private static ThreadFactory namedDaemon(String name) {
		AtomicInteger n = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, name + "-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}

	private static void shutdownInternal() {
		shutdownActiveAndAwait();
	}

	private static final class PathSize {
		final Path path;
		final long size;

		PathSize(Path path, long size) {
			this.path = path;
			this.size = size;
		}
	}
}
