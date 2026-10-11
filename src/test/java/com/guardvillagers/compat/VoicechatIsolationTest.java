package com.guardvillagers.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.guardvillagers.voice.VoiceConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The mod must load without Simple Voice Chat, so only {@code com.guardvillagers.compat.voicechat} may
 * reference {@code de.maxhenkel} classes. Scans the compiled class files: class names appear as plain
 * "de/maxhenkel/..." strings in the constant pool of any class that references them.
 */
class VoicechatIsolationTest {
	private static final String ALLOWED = "com/guardvillagers/compat/voicechat/";
	private static final byte[] NEEDLE = "de/maxhenkel".getBytes(StandardCharsets.US_ASCII);

	@Test
	void onlyTheCompatPackageReferencesSimpleVoiceChat() throws IOException, URISyntaxException {
		Path mainClasses = Path.of(VoiceConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertTrue(Files.isDirectory(mainClasses.resolve("com/guardvillagers")), "main classes not found at " + mainClasses);
		List<Path> roots = new ArrayList<>(List.of(mainClasses));
		// Loom splits client classes into a sibling output directory; scan it too when it exists.
		Path client = mainClasses.resolveSibling("client");
		if (Files.isDirectory(client)) roots.add(client);

		List<String> violations = new ArrayList<>();
		int scanned = 0;
		int compatReferences = 0;
		for (Path root : roots) {
			try (Stream<Path> files = Files.walk(root)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
					scanned++;
					String name = root.relativize(file).toString().replace('\\', '/');
					boolean references = contains(Files.readAllBytes(file), NEEDLE);
					if (name.startsWith(ALLOWED)) {
						if (references) compatReferences++;
					} else if (references) {
						violations.add(root.getFileName() + ":" + name);
					}
				}
			}
		}
		assertTrue(scanned > 50, "expected to scan the mod's classes, scanned " + scanned);
		assertTrue(compatReferences > 0, "the scan should see the compat package's SVC references");
		assertTrue(violations.isEmpty(), "classes outside " + ALLOWED + " reference de.maxhenkel: " + violations);
	}

	@Test
	void simpleVoiceChatIsOptionalInModMetadata() throws IOException {
		try (InputStream in = VoiceConfig.class.getClassLoader().getResourceAsStream("fabric.mod.json")) {
			assertTrue(in != null, "fabric.mod.json on the classpath");
			JsonObject mod = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
			assertFalse(mod.getAsJsonObject("depends").has("voicechat"), "SVC must not be required");
			assertFalse(mod.getAsJsonObject("depends").has("voicechat_api"), "SVC API must not be required");
			assertTrue(mod.getAsJsonObject("suggests").has("voicechat"));
			String entry = mod.getAsJsonObject("entrypoints").getAsJsonArray("voicechat").get(0).getAsString();
			assertTrue(entry.startsWith("com.guardvillagers.compat.voicechat."), entry);
			assertFalse(mod.has("jars"), "nothing may be bundled jar-in-jar");
		}
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}
}
