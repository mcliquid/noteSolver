package org.openstreetmap.josm.plugins.notesolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

	@Test
	void redactsCredentialLikeFragments() {
		String in = "failed Authorization: Bearer secret-token-12345";
		String out = LogSanitizer.sanitize(in);
		assertFalse(out.contains("secret-token"));
		assertTrue(out.toLowerCase().contains("authorization"));
	}

	@Test
	void redactsLongHexTokens() {
		String out = LogSanitizer.sanitize("id abcdef0123456789abcdef0123456789 trailing");
		assertTrue(out.contains("[redacted-token]"));
	}

	@Test
	void leavesBenignMessages() {
		assertEquals("noteId=42 outcome=FAILED", LogSanitizer.sanitize("noteId=42 outcome=FAILED"));
	}
}
