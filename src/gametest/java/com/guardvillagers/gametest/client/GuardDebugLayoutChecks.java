package com.guardvillagers.gametest.client;

import com.guardvillagers.client.GuardDebugRenderer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Compare the actual optimized layout with the previous all-pairs algorithm, including depth ties. */
final class GuardDebugLayoutChecks {
	static void run() {
		try {
			checkLayouts();
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Cannot inspect debug layout", exception);
		}
	}

	@SuppressWarnings("unchecked")
	private static void checkLayouts() throws ReflectiveOperationException {
		Class<?> renderer = GuardDebugRenderer.class;
		Class<?> labelClass = Class.forName(renderer.getName() + "$GuardLabel");
		var constructor = labelClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		List<Object> pool = (List<Object>) field(renderer, "LABEL_POOL").get(null);
		var layout = renderer.getDeclaredMethod("layoutLabels", CameraRenderState.class, int.class);
		layout.setAccessible(true);
		Random random = new Random(300);
		try {
			for (int trial = 0; trial < 20; trial++) {
				int count = trial % 2 == 0 ? 300 : 1000;
				pool.clear();
				for (int i = 0; i < count; i++) {
					Object label = constructor.newInstance();
					field(labelClass, "x").setDouble(label, random.nextDouble() * 100 - 50);
					field(labelClass, "y").setDouble(label, random.nextDouble() * 100 - 50);
					field(labelClass, "z").setDouble(label, trial % 3 == 0 ? -10 : random.nextInt(100) - 80);
					field(labelClass, "focused").setBoolean(label, i == count / 2);
					field(labelClass, "lineCount").setInt(label, 2 + random.nextInt(4));
					((int[]) field(labelClass, "widths").get(label))[0] = 20 + random.nextInt(200);
					pool.add(label);
				}
				field(renderer, "activeLabelCount").setInt(null, count);
				int visible = (Integer) layout.invoke(null, new CameraRenderState(), 10);
				int[] actual = Arrays.copyOf((int[]) field(renderer, "drawOrder").get(null), visible);
				double[][] bounds = new double[count][5];
				List<Integer> order = new ArrayList<>();
				for (int i = 0; i < count; i++) {
					Object label = pool.get(i);
					if (field(labelClass, "z").getDouble(label) > -.1) continue;
					order.add(i);
					String[] names = {"screenX", "screenY", "halfW", "halfH", "depth"};
					for (int j = 0; j < names.length; j++) bounds[i][j] = field(labelClass, names[j]).getDouble(label);
				}
				order.sort((a, b) -> a == count / 2 ? -1 : b == count / 2 ? 1 : Double.compare(bounds[a][4], bounds[b][4]));
				List<Integer> kept = new ArrayList<>();
				for (int candidate : order) {
					double[] a = bounds[candidate];
					boolean overlaps = false;
					for (int previous : kept) {
						double[] b = bounds[previous];
						if (Math.abs(a[0] - b[0]) < a[2] + b[2] && Math.abs(a[1] - b[1]) < a[3] + b[3]) {
							overlaps = true;
							break;
						}
					}
					if (!overlaps) kept.add(candidate);
				}
				if (!Arrays.equals(actual, kept.stream().mapToInt(Integer::intValue).toArray())) {
					throw new AssertionError("Debug label selection changed in trial " + trial);
				}
			}
		} finally {
			GuardDebugRenderer.reset();
			pool.clear();
		}
		System.out.println("GUARD_CLIENT_LAYOUT_CHECKS passed: 20 scenes of 300/1000 labels match original layout");
	}

	private static Field field(Class<?> type, String name) throws NoSuchFieldException {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
