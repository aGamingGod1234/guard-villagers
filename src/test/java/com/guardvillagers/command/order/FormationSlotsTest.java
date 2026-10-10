package com.guardvillagers.command.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.guardvillagers.entity.FormationType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FormationSlotsTest {
	@Test
	void lineKeepsBowmenBehindSwordsmenAndAheadOfSpeaker() {
		List<FormationSlots.Post> posts = FormationSlots.posts(FormationType.LINE, 4, 3);
		assertEquals(7, posts.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(5, posts.get(i).forward(), "melee rank");
		}
		for (int i = 4; i < 7; i++) {
			assertEquals(3, posts.get(i).forward(), "bow rank");
		}
		assertDistinct(posts);
		assertEquals(List.of(-3, -1, 1, 3), posts.subList(0, 4).stream().map(FormationSlots.Post::right).toList());
	}

	@Test
	void longLinesWrapIntoRowsAwayFromSpeaker() {
		List<FormationSlots.Post> posts = FormationSlots.posts(FormationType.LINE, 12, 0);
		assertDistinct(posts);
		posts.forEach(post -> assertTrue(post.forward() >= FormationSlots.LINE_FRONT_DISTANCE));
		assertEquals(8, posts.stream().filter(post -> post.forward() == 3).count());
	}

	@Test
	void squareAndCircleNeverShareABlock() {
		for (int n = 1; n <= 16; n++) {
			assertDistinct(FormationSlots.posts(FormationType.SQUARE, n, 0));
			assertDistinct(FormationSlots.posts(FormationType.CIRCLE, n / 2, n - n / 2));
			FormationSlots.posts(FormationType.CIRCLE, n, 0).forEach(post ->
					assertTrue(post.forward() * post.forward() + post.right() * post.right() >= 4, "circle post too close"));
		}
	}

	private static void assertDistinct(List<FormationSlots.Post> posts) {
		Set<FormationSlots.Post> seen = new HashSet<>(posts);
		assertEquals(posts.size(), seen.size(), "posts overlap: " + posts);
	}
}
