package com.guardvillagers.command.order;

import com.guardvillagers.entity.FormationType;
import java.util.ArrayList;
import java.util.List;

/**
 * Formation posts relative to the speaker, in whole blocks: {@code forward} along the direction the speaker faces,
 * {@code right} to the speaker's right. Posts are two blocks apart so block-snapped guards never share a block.
 *
 * <ul>
 * <li>LINE: swordsmen in the front rank 3 blocks ahead, bowmen in a rank 2 blocks behind them; at most 8 per row,
 * further rows step away from the speaker (front) or toward it (back).</li>
 * <li>SQUARE: a filled square grid in front of the speaker, swordsmen first.</li>
 * <li>CIRCLE: concentric rings around the speaker.</li>
 * </ul>
 */
public final class FormationSlots {
	public static final int SPACING = 2;
	public static final int LINE_FRONT_DISTANCE = 3;
	public static final int MAX_PER_ROW = 8;

	public record Post(int forward, int right) {
	}

	private FormationSlots() {
	}

	/**
	 * @param melee  number of front-line guards (swordsmen); they get the first {@code melee} posts
	 * @param ranged number of bowmen; they get the posts after the melee ones
	 */
	public static List<Post> posts(FormationType type, int melee, int ranged) {
		int total = Math.max(0, melee) + Math.max(0, ranged);
		List<Post> posts = new ArrayList<>(total);
		switch (type) {
			case LINE, FOLLOW -> {
				// Bowmen rows sit between the speaker and the melee rows; nobody ends up behind the speaker.
				int backRows = rowsFor(ranged);
				addRows(posts, melee, LINE_FRONT_DISTANCE + backRows * SPACING, 1);
				addRows(posts, ranged, LINE_FRONT_DISTANCE + (backRows - 1) * SPACING, -1);
			}
			case SQUARE -> {
				int side = Math.max(1, (int) Math.ceil(Math.sqrt(total)));
				int near = LINE_FRONT_DISTANCE;
				for (int i = 0; i < total; i++) {
					int row = i / side;
					int column = i % side;
					// Melee fill the rows nearest the enemy (farthest from the speaker) first.
					int forward = near + (side - 1 - row) * SPACING;
					posts.add(new Post(forward, column * SPACING - (side - 1)));
				}
			}
			case CIRCLE -> {
				int placed = 0;
				int radius = 4;
				while (placed < total) {
					int capacity = Math.max(1, (int) Math.floor(2 * Math.PI * radius / 3.0D));
					int count = Math.min(capacity, total - placed);
					for (int i = 0; i < count; i++) {
						double angle = 2 * Math.PI * i / count;
						posts.add(new Post((int) Math.round(Math.cos(angle) * radius),
								(int) Math.round(Math.sin(angle) * radius)));
					}
					placed += count;
					radius += 3;
				}
			}
		}
		return posts;
	}

	private static int rowsFor(int count) {
		return (count + MAX_PER_ROW - 1) / MAX_PER_ROW;
	}

	private static void addRows(List<Post> posts, int count, int firstForward, int rowDirection) {
		int placed = 0;
		int row = 0;
		while (placed < count) {
			int inRow = Math.min(MAX_PER_ROW, count - placed);
			int forward = firstForward + rowDirection * row * SPACING;
			for (int i = 0; i < inRow; i++) {
				posts.add(new Post(forward, i * SPACING - (inRow - 1)));
			}
			placed += inRow;
			row++;
		}
	}
}
