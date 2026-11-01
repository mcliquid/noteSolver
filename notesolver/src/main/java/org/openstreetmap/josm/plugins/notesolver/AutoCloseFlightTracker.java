package org.openstreetmap.josm.plugins.notesolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-note reservations for in-flight auto-close uploads (atomic try/release). */
final class AutoCloseFlightTracker {

	static final class Flight {
		final long noteId;
		final String correlationId;
		final int changesetId;

		Flight(long noteId, String correlationId, int changesetId) {
			this.noteId = noteId;
			this.correlationId = correlationId;
			this.changesetId = changesetId;
		}
	}

	private final Map<Long, Flight> inFlight = new LinkedHashMap<>();
	private final Object lock = new Object();

	boolean tryReserve(long noteId, String correlationId, int changesetId) {
		synchronized (lock) {
			if (inFlight.containsKey(noteId)) {
				return false;
			}
			inFlight.put(noteId, new Flight(noteId, correlationId, changesetId));
			return true;
		}
	}

	void release(long noteId) {
		synchronized (lock) {
			inFlight.remove(noteId);
		}
	}

	boolean isInFlight(long noteId) {
		synchronized (lock) {
			return inFlight.containsKey(noteId);
		}
	}

	boolean hasAnyInFlight() {
		synchronized (lock) {
			return !inFlight.isEmpty();
		}
	}

	int inFlightCount() {
		synchronized (lock) {
			return inFlight.size();
		}
	}

	List<Flight> snapshot() {
		synchronized (lock) {
			return Collections.unmodifiableList(new ArrayList<>(inFlight.values()));
		}
	}

	Collection<Flight> drainAll() {
		synchronized (lock) {
			List<Flight> all = new ArrayList<>(inFlight.values());
			inFlight.clear();
			return all;
		}
	}

	void resetForTests() {
		synchronized (lock) {
			inFlight.clear();
		}
	}
}
