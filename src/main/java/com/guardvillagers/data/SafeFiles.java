package com.guardvillagers.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import org.slf4j.Logger;

/** Crash-safe reads and writes for small text files the mod owns (client config and caches). */
public final class SafeFiles {
	private static final DateTimeFormatter QUARANTINE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

	private SafeFiles() {
	}

	/**
	 * Reads {@code file} and hands its content to {@code parser}. If the parser throws (malformed or unexpected
	 * content), the file is moved aside to {@code <name>.corrupt-<timestamp>} so the caller can start empty without
	 * overwriting what the player had, and so the next launch does not fail the same way.
	 *
	 * @return true if the file existed and parsed; false if it was missing, unreadable or quarantined
	 */
	public static boolean readOrQuarantine(Path file, Logger logger, Consumer<String> parser) {
		if (!Files.exists(file)) {
			return false;
		}
		String content;
		try {
			content = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			// Possibly transient (locked by another program); leave the file where it is.
			logger.error("Failed to read {}", file, e);
			return false;
		}
		try {
			parser.accept(content);
			return true;
		} catch (RuntimeException e) {
			try {
				Path moved = quarantine(file);
				logger.error("{} is malformed; moved it to {} and starting fresh", file, moved.getFileName(), e);
			} catch (IOException moveFailure) {
				e.addSuppressed(moveFailure);
				logger.error("{} is malformed and could not be moved aside; starting fresh", file, e);
			}
			return false;
		}
	}

	/** Moves {@code file} to {@code <name>.corrupt-<timestamp>} in the same folder and returns the new path. */
	public static Path quarantine(Path file) throws IOException {
		Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(QUARANTINE_STAMP));
		return Files.move(file, target);
	}

	/**
	 * Writes {@code content} to a temp file next to {@code target}, flushes it to disk, then moves it over
	 * {@code target} atomically (or with a plain replace where the file system cannot). A crash mid-save leaves
	 * either the old file or the new one, never a truncated mix.
	 */
	public static void writeAtomically(Path target, String content) throws IOException {
		Path directory = target.toAbsolutePath().getParent();
		Files.createDirectories(directory);
		Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
		try {
			try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
				while (buffer.hasRemaining()) {
					channel.write(buffer);
				}
				channel.force(true);
			}
			try {
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
