package com.guardvillagers.gametest.perf;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Summarises a benchmark JFR recording per phase. Standalone, not part of the game tests:
 *
 * <pre>java src/gametest/java/com/guardvillagers/gametest/perf/JfrReport.java build/perf/guard-perf.jfr [phasePrefix] [topN]</pre>
 *
 * For each guardvillagers.PerfPhase window it reports, for Server thread CPU samples (jdk.ExecutionSample) and
 * allocation samples (jdk.ObjectAllocationSample, weighted by bytes):
 * - share of samples with any com.guardvillagers frame on the stack (mod-attributable cost)
 * - inclusive cost per mod method
 * - cost by the innermost mod frame (method:line), with the most common mod call path and vanilla leaf
 * - for allocations, also the allocated class
 */
public final class JfrReport {
	private static final String MOD = "com.guardvillagers.";
	private static final String HARNESS = "com.guardvillagers.gametest.";

	record Window(String phase, Instant start, Instant end) {
	}

	static final class Stats {
		double total;
		double mod;
		final Map<String, Double> inclusive = new HashMap<>();
		final Map<String, Double> innermost = new HashMap<>();
		final Map<String, Map<String, Double>> paths = new HashMap<>();
		final Map<String, Double> selfTop = new HashMap<>();
		final Map<String, Double> allocClass = new HashMap<>();
	}

	public static void main(String[] args) throws Exception {
		Path file = Path.of(args[0]);
		String prefix = args.length > 1 ? args[1] : "";
		int top = args.length > 2 ? Integer.parseInt(args[2]) : 15;

		List<Window> windows = new ArrayList<>();
		for (RecordedEvent e : RecordingFile.readAllEvents(file)) {
			if (e.getEventType().getName().equals("guardvillagers.PerfPhase")) {
				String phase = e.getString("phase");
				if (!phase.startsWith("jit_warmup") && phase.startsWith(prefix)) {
					windows.add(new Window(phase, e.getStartTime(), e.getEndTime()));
				}
			}
		}

		Map<String, Stats> cpu = new LinkedHashMap<>();
		Map<String, Stats> alloc = new LinkedHashMap<>();
		windows.forEach(w -> {
			cpu.put(w.phase(), new Stats());
			alloc.put(w.phase(), new Stats());
		});

		try (RecordingFile rf = new RecordingFile(file)) {
			while (rf.hasMoreEvents()) {
				RecordedEvent e = rf.readEvent();
				String type = e.getEventType().getName();
				boolean isCpu = type.equals("jdk.ExecutionSample");
				boolean isAlloc = type.equals("jdk.ObjectAllocationSample");
				if (!isCpu && !isAlloc) {
					continue;
				}
				var thread = isCpu ? e.getThread("sampledThread") : e.getThread();
				if (thread == null || !"Server thread".equals(thread.getJavaName())) {
					continue;
				}
				Window w = find(windows, e.getStartTime());
				if (w == null) {
					continue;
				}
				// Upkeep is deliberately outside the tick timer; exclude spawning/teardown from the profile too.
				if (e.getStackTrace() != null && e.getStackTrace().getFrames().stream()
						.anyMatch(frame -> frame.getMethod().getType().getName().equals(HARNESS + "perf.PerfRunner"))) {
					continue;
				}
				double weight = isAlloc ? e.getLong("weight") : 1.0;
				String allocClass = isAlloc && e.getClass("objectClass") != null ? e.getClass("objectClass").getName() : null;
				account((isCpu ? cpu : alloc).get(w.phase()), e.getStackTrace(), weight, allocClass);
			}
		}

		for (Window w : windows) {
			System.out.println("=====================================================================");
			System.out.printf("PHASE %s (%.1f s)%n", w.phase(), (w.end().toEpochMilli() - w.start().toEpochMilli()) / 1000.0);
			print("CPU samples (Server thread)", cpu.get(w.phase()), top, false);
			print("Allocation samples (bytes, Server thread)", alloc.get(w.phase()), top, true);
		}
	}

	private static Window find(List<Window> windows, Instant t) {
		for (Window w : windows) {
			if (!t.isBefore(w.start()) && !t.isAfter(w.end())) {
				return w;
			}
		}
		return null;
	}

	private static String method(RecordedFrame f) {
		String name = f.getMethod().getName();
		// Mixin handlers are merged into vanilla classes; report them under the mod namespace.
		if (name.contains("guardvillagers$")) {
			return MOD + "mixin." + f.getMethod().getType().getName().substring(f.getMethod().getType().getName().lastIndexOf('.') + 1) + "." + name;
		}
		return f.getMethod().getType().getName() + "." + name;
	}

	private static String shortName(String fqn) {
		String s = fqn.startsWith(MOD) ? fqn.substring(MOD.length()) : fqn;
		int lastDot = s.lastIndexOf('.');
		int classDot = s.lastIndexOf('.', lastDot - 1);
		return fqn.startsWith(MOD) ? s : s.substring(classDot + 1);
	}

	private static void account(Stats stats, RecordedStackTrace trace, double weight, String allocClass) {
		stats.total += weight;
		if (trace == null || trace.getFrames().isEmpty()) {
			return;
		}
		List<RecordedFrame> frames = trace.getFrames(); // index 0 = innermost
		stats.selfTop.merge(shortName(method(frames.get(0))), weight, Double::sum);

		int innermostMod = -1;
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < frames.size(); i++) {
			String m = method(frames.get(i));
			if (m.startsWith(MOD) && !m.startsWith(HARNESS)) {
				if (innermostMod < 0) {
					innermostMod = i;
				}
				if (seen.add(m)) {
					stats.inclusive.merge(shortName(m), weight, Double::sum);
				}
			}
		}
		if (innermostMod < 0) {
			return;
		}
		stats.mod += weight;
		RecordedFrame inner = frames.get(innermostMod);
		String key = shortName(method(inner)) + ":" + inner.getLineNumber();
		stats.innermost.merge(key, weight, Double::sum);
		if (allocClass != null) {
			stats.allocClass.merge(key + " -> " + allocClass, weight, Double::sum);
		}

		// Call path: up to 4 enclosing mod frames (outer first) plus the vanilla leaf below the innermost mod frame.
		List<String> modChain = new ArrayList<>();
		for (int i = innermostMod; i < frames.size() && modChain.size() < 4; i++) {
			String m = method(frames.get(i));
			if (m.startsWith(MOD) && !m.startsWith(HARNESS)) {
				modChain.add(0, shortName(m) + ":" + frames.get(i).getLineNumber());
			}
		}
		String leaf = innermostMod > 0 ? " => " + shortName(method(frames.get(0))) : "";
		String path = String.join(" > ", modChain) + leaf;
		stats.paths.computeIfAbsent(key, k -> new HashMap<>()).merge(path, weight, Double::sum);
	}

	private static void print(String title, Stats s, int top, boolean bytes) {
		System.out.printf("--- %s: total=%s, with mod frame on stack=%s (%.1f%%)%n", title, fmt(s.total, bytes), fmt(s.mod, bytes),
				s.total == 0 ? 0 : 100.0 * s.mod / s.total);
		System.out.println("  Inclusive by mod method:");
		printTop(s.inclusive, s.total, top, bytes, null);
		System.out.println("  By innermost mod frame (method:line), top call path:");
		printTop(s.innermost, s.total, top, bytes, s.paths);
		if (bytes) {
			System.out.println("  By innermost mod frame and allocated class:");
			printTop(s.allocClass, s.total, top, bytes, null);
		} else {
			System.out.println("  Self time (top frame, all code):");
			printTop(s.selfTop, s.total, top, bytes, null);
		}
	}

	private static void printTop(Map<String, Double> map, double total, int top, boolean bytes, Map<String, Map<String, Double>> paths) {
		map.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(top).forEach(e -> {
			System.out.printf("    %6.2f%%  %10s  %s%n", total == 0 ? 0 : 100.0 * e.getValue() / total, fmt(e.getValue(), bytes), e.getKey());
			if (paths != null && paths.containsKey(e.getKey())) {
				paths.get(e.getKey()).entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(2)
						.forEach(p -> System.out.printf("              %5.1f%% of row: %s%n", 100.0 * p.getValue() / e.getValue(), p.getKey()));
			}
		});
	}

	private static String fmt(double v, boolean bytes) {
		return bytes ? String.format("%.1fMB", v / (1024 * 1024)) : String.format("%.0f", v);
	}
}
