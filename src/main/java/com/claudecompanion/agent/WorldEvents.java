package com.claudecompanion.agent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** A rolling log of things happening in the world, handed to Claude with every message. */
public final class WorldEvents {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final Deque<String> EVENTS = new ArrayDeque<>();
	private static int unseen;

	private WorldEvents() {}

	public static synchronized void add(String event) {
		EVENTS.addLast(LocalTime.now().format(TIME) + " " + event);
		while (EVENTS.size() > 60) EVENTS.removeFirst();
		unseen = Math.min(unseen + 1, EVENTS.size());
	}

	/** Events since Claude last looked, newest last. */
	public static synchronized List<String> drainNew(int max) {
		List<String> all = new ArrayList<>(EVENTS);
		int n = Math.min(unseen, max);
		unseen = 0;
		return new ArrayList<>(all.subList(all.size() - n, all.size()));
	}

	public static synchronized void clear() {
		EVENTS.clear();
		unseen = 0;
	}
}
