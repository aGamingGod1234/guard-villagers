package com.guardvillagers.gametest.perf;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** Marks the measured window of one benchmark phase in a JFR recording so samples can be split per scenario. */
@Name("guardvillagers.PerfPhase")
@Label("Guard Perf Phase")
@Category("Guard Villagers")
final class PerfPhaseEvent extends Event {
	@Label("Phase")
	String phase;
}
