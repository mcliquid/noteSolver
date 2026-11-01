package org.openstreetmap.josm.plugins.notesolver;

import java.util.regex.Pattern;

/** Redacts sensitive fragments before writing diagnostic log lines. */
final class LogSanitizer {

	private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+\\S+");
	private static final Pattern SENSITIVE = Pattern.compile(
			"(?i)(password|passwd|oauth|token|authorization|cookie|set-cookie)\\s*[:=]\\s*\\S+");
	private static final Pattern LONG_HEX = Pattern.compile("\\b[a-f0-9]{32,}\\b", Pattern.CASE_INSENSITIVE);

	private LogSanitizer() {
	}

	static String sanitize(String message) {
		if (message == null || message.isEmpty()) {
			return message;
		}
		String s = BEARER.matcher(message).replaceAll("Bearer [redacted]");
		s = SENSITIVE.matcher(s).replaceAll("$1=[redacted]");
		s = LONG_HEX.matcher(s).replaceAll("[redacted-token]");
		return s;
	}

	static String safeDetail(Throwable t) {
		if (t == null) {
			return "";
		}
		String msg = t.getMessage();
		if (msg == null || msg.isEmpty()) {
			msg = t.getClass().getSimpleName();
		}
		return sanitize(msg);
	}
}
