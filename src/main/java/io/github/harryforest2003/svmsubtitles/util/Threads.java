package io.github.harryforest2003.svmsubtitles.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Background threads are daemons with low priority, so they never keep the game alive or starve it. */
public final class Threads {
	private Threads() {
	}

	public static ThreadFactory factory(String name) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			int n = counter.incrementAndGet();
			Thread thread = new Thread(runnable, n == 1 ? name : name + " #" + n);
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		};
	}

	public static ExecutorService single(String name) {
		return Executors.newSingleThreadExecutor(factory(name));
	}

	public static ScheduledExecutorService scheduler(String name) {
		return Executors.newSingleThreadScheduledExecutor(factory(name));
	}
}
