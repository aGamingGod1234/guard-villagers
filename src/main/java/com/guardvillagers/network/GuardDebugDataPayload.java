package com.guardvillagers.network;

import com.guardvillagers.GuardVillagersMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GuardDebugDataPayload(List<GuardDebugEntry> entries) implements CustomPacketPayload {
	private static final int MAX_GUARDS_PER_PACKET = 256;
	private static final int MAX_PATH_NODES = 64;
	public static final Type<GuardDebugDataPayload> ID = new Type<>(GuardVillagersMod.id("debug_data"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GuardDebugDataPayload> CODEC = CustomPacketPayload.codec(
		GuardDebugDataPayload::write,
		GuardDebugDataPayload::new
	);

	public GuardDebugDataPayload {
		entries = List.copyOf(entries);
	}

	private GuardDebugDataPayload(RegistryFriendlyByteBuf buf) {
		this(readEntries(buf));
	}

	private void write(RegistryFriendlyByteBuf buf) {
		int guardCount = Math.min(MAX_GUARDS_PER_PACKET, this.entries.size());
		buf.writeInt(guardCount);
		for (int i = 0; i < guardCount; i++) {
			this.entries.get(i).write(buf);
		}
	}

	private static List<GuardDebugEntry> readEntries(RegistryFriendlyByteBuf buf) {
		int guardCount = Math.max(0, Math.min(MAX_GUARDS_PER_PACKET, buf.readInt()));
		List<GuardDebugEntry> entries = new ArrayList<>(guardCount);
		for (int i = 0; i < guardCount; i++) {
			entries.add(GuardDebugEntry.read(buf));
		}
		return entries;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}

	public record GuardDebugEntry(int entityId, List<BlockPos> pathNodes, int currentPathIndex, int targetEntityId) {
		public GuardDebugEntry {
			pathNodes = List.copyOf(pathNodes);
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeInt(this.entityId);
			int nodeCount = Math.min(MAX_PATH_NODES, this.pathNodes.size());
			buf.writeInt(nodeCount);
			for (int i = 0; i < nodeCount; i++) {
				buf.writeBlockPos(this.pathNodes.get(i));
			}
			buf.writeInt(this.currentPathIndex);
			buf.writeInt(this.targetEntityId);
		}

		private static GuardDebugEntry read(RegistryFriendlyByteBuf buf) {
			int entityId = buf.readInt();
			int nodeCount = Math.max(0, Math.min(MAX_PATH_NODES, buf.readInt()));
			List<BlockPos> nodes = new ArrayList<>(nodeCount);
			for (int i = 0; i < nodeCount; i++) {
				nodes.add(buf.readBlockPos());
			}
			int currentPathIndex = buf.readInt();
			int targetEntityId = buf.readInt();
			return new GuardDebugEntry(entityId, nodes, currentPathIndex, targetEntityId);
		}
	}
}
