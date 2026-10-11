package com.guardvillagers.entity.ai;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Predicate;

/** Fine horizontal buckets with exact 3D bounds checks. Nodes survive refreshes and movement. */
public final class EntitySpatialIndex<T> {
	private static final double CELL_SIZE = 4;
	private final IdentityHashMap<T, Node<T>> nodes = new IdentityHashMap<>();
	private final Long2ObjectOpenHashMap<Node<T>> buckets = new Long2ObjectOpenHashMap<>();
	private long generation;
	private double extentX;
	private double extentZ;

	private static final class Node<T> {
		final T value;
		Node<T> previous, next;
		long cell, seen;
		double minX, minY, minZ, maxX, maxY, maxZ;
		Node(T value) { this.value = value; }
	}

	public void beginRefresh() {
		this.generation++;
		this.extentX = 0;
		this.extentZ = 0;
	}

	public void endRefresh() {
		var iterator = this.nodes.values().iterator();
		while (iterator.hasNext()) {
			Node<T> node = iterator.next();
			if (node.seen != this.generation) {
				this.unlink(node);
				iterator.remove();
			}
		}
	}

	public void update(T value, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		long cell = key(cell((minX + maxX) * 0.5), cell((minZ + maxZ) * 0.5));
		Node<T> node = this.nodes.get(value);
		if (node == null) {
			node = new Node<>(value);
			this.nodes.put(value, node);
			this.link(node, cell);
		} else if (node.cell != cell) {
			this.unlink(node);
			this.link(node, cell);
		}
		node.minX = minX; node.minY = minY; node.minZ = minZ;
		node.maxX = maxX; node.maxY = maxY; node.maxZ = maxZ;
		node.seen = this.generation;
		this.extentX = Math.max(this.extentX, (maxX - minX) * 0.5);
		this.extentZ = Math.max(this.extentZ, (maxZ - minZ) * 0.5);
	}

	public boolean contains(T value) { return this.nodes.containsKey(value); }

	public void remove(T value) {
		Node<T> node = this.nodes.remove(value);
		if (node != null) this.unlink(node);
	}

	public void query(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
			Predicate<? super T> predicate, List<T> output) {
		int fromX = cell(minX - this.extentX), toX = cell(maxX + this.extentX);
		int fromZ = cell(minZ - this.extentZ), toZ = cell(maxZ + this.extentZ);
		// Very large boxes are cheaper to answer by visiting occupied nodes than empty cells.
		if ((double) ((long) toX - fromX + 1) * ((long) toZ - fromZ + 1) > this.buckets.size() * 4.0 + 16) {
			for (Node<T> node : this.nodes.values()) {
				if (intersects(node, minX, minY, minZ, maxX, maxY, maxZ) && predicate.test(node.value)) output.add(node.value);
			}
			return;
		}
		for (int x = fromX; x <= toX; x++) {
			for (int z = fromZ; z <= toZ; z++) {
				for (Node<T> node = this.buckets.get(key(x, z)); node != null; node = node.next) {
					if (intersects(node, minX, minY, minZ, maxX, maxY, maxZ) && predicate.test(node.value)) output.add(node.value);
				}
			}
		}
	}

	private static boolean intersects(Node<?> n, double x, double y, double z, double xx, double yy, double zz) {
		return n.minX < xx && n.maxX > x && n.minY < yy && n.maxY > y && n.minZ < zz && n.maxZ > z;
	}

	private void link(Node<T> node, long cell) {
		node.cell = cell;
		node.next = this.buckets.put(cell, node);
		if (node.next != null) node.next.previous = node;
	}

	private void unlink(Node<T> node) {
		if (node.previous == null) {
			if (node.next == null) this.buckets.remove(node.cell);
			else this.buckets.put(node.cell, node.next);
		} else node.previous.next = node.next;
		if (node.next != null) node.next.previous = node.previous;
		node.previous = null;
		node.next = null;
	}

	private static int cell(double coordinate) { return (int) Math.floor(coordinate / CELL_SIZE); }
	private static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }
}
