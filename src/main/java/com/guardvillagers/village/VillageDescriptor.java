package com.guardvillagers.village;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

public record VillageDescriptor(String id, BoundingBox bounds, BlockPos center, int horizontalRadius) {
	public AABB toEntityBox(int expansion) {
		return new AABB(
			this.bounds.minX() - expansion,
			this.bounds.minY() - 4,
			this.bounds.minZ() - expansion,
			this.bounds.maxX() + expansion + 1,
			this.bounds.maxY() + 8,
			this.bounds.maxZ() + expansion + 1
		);
	}
}
