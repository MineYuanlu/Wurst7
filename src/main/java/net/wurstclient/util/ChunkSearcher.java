/*
 * Copyright (c) 2014-2021 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import net.minecraft.block.Block;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.wurstclient.WurstClient;
import net.wurstclient.commands.SetSeedCmd;

/**
 * Searches a {@link Chunk} for a particular type of {@link Block}.
 */
public final class ChunkSearcher
{
	private final Chunk chunk;
	private final Block block;
	private final int dimensionId;
	private final ArrayList<BlockPos> matchingBlocks = new ArrayList<>();
	private ChunkSearcher.Status status = Status.IDLE;
	private Future<?> future;
	private SetSeedCmd localSeed = null;

	public ChunkSearcher(Chunk chunk, Block block, int dimensionId)
	{
		this.chunk = chunk;
		this.block = block;
		this.dimensionId = dimensionId;
	}
	
	public void startSearching(ExecutorService pool)
	{
		this.localSeed = WurstClient.INSTANCE.getCmds().setSeedCmd;
		if(status != Status.IDLE)
			throw new IllegalStateException();
		
		status = Status.SEARCHING;
		future = pool.submit(this::searchNow);
	}
	
	private void searchNow()
	{
		if(status == Status.IDLE || status == Status.DONE
			|| !matchingBlocks.isEmpty())
			throw new IllegalStateException();
		
		ChunkPos chunkPos = chunk.getPos();
		ClientWorld world = WurstClient.MC.world;
		
		int minX = chunkPos.getStartX();
		int minY = world.getBottomY();
		int minZ = chunkPos.getStartZ();
		int maxX = chunkPos.getEndX();
		int maxY = world.getTopY();
		int maxZ = chunkPos.getEndZ();
		
		final ServerWorld localWorld = this.localSeed.getWorldById(this.dimensionId);
		final Chunk localChunk;
		if (localWorld != null) {
			//Keep Chunk to prevent unnecessary loading and unloading
			localWorld.setChunkForced(chunkPos.x, chunkPos.z, true);
			localChunk = localWorld.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL);
		} else localChunk = null;
		try {
			for(int y = minY; y <= maxY; y++)//Swap scan order, scan layer by layer
				for(int x = minX; x <= maxX; x++)
					for(int z = minZ; z <= maxZ; z++)
					{
						if(status == Status.INTERRUPTED || Thread.interrupted())
							return;
						
						BlockPos pos = new BlockPos(x, y, z);
						Block block;
						if (localChunk != null && blockisFullyConcealed(pos)) {
							block = localChunk.getBlockState(pos).getBlock();
						}
						else {
							block = BlockUtils.getBlock(pos);
						}
						if(!this.block.equals(block))
							continue;
						
						matchingBlocks.add(pos);
					}
		}finally {
			if (localChunk != null)localWorld.setChunkForced(chunkPos.x, chunkPos.z, false);
		}
			
		status = Status.DONE;
	}

	/**
	 * <p>
	 * Cache for block detection. For each box, it scans itself and the six blocks
	 * surrounding it. Using cache can reduce duplicate detection.
	 * </p>
	 * Cache size: old two layers + one block in the third layer + 8 reserved areas
	 */
	private final BlockCache<Boolean> opaqueCache = new BlockCache<>((16 + 2) * (16 + 2) * 2 + 1 + 8);

	private boolean blockIsOpaque(BlockPos pos) 
	{
		return opaqueCache.computeIfAbsent(pos, this::blockIsOpaque0);
	}

	private Boolean blockIsOpaque0(BlockPos pos) 
	{
		return WurstClient.MC.world.getBlockState(pos).isOpaque() ? Boolean.TRUE : Boolean.FALSE;
	}
	
	private boolean blockisFullyConcealed(BlockPos pos)
	{
		return blockIsOpaque(pos) && blockIsOpaque(pos.up()) && blockIsOpaque(pos.down()) && blockIsOpaque(pos.north()) && blockIsOpaque(pos.south()) && blockIsOpaque(pos.east()) && blockIsOpaque(pos.west());
	}

	public void cancelSearching()
	{
		new Thread(this::cancelNow, "ChunkSearcher-canceller").start();
	}
	
	private void cancelNow()
	{
		if(future != null)
			try
			{
				status = Status.INTERRUPTED;
				future.get();
				
			}catch(InterruptedException | ExecutionException e)
			{
				e.printStackTrace();
			}
		
		matchingBlocks.clear();
		status = Status.IDLE;
	}
	
	public Chunk getChunk()
	{
		return chunk;
	}
	
	public Block getBlock()
	{
		return block;
	}
	
	public int getDimensionId()
	{
		return dimensionId;
	}
	
	public ArrayList<BlockPos> getMatchingBlocks()
	{
		return matchingBlocks;
	}
	
	public ChunkSearcher.Status getStatus()
	{
		return status;
	}
	
	public static enum Status
	{
		IDLE,
		SEARCHING,
		INTERRUPTED,
		DONE;
	}

	private static final class BlockCache<D> extends LinkedHashMap<BlockPos, D> {
		private static final long	serialVersionUID	= 9018949008579317510L;
		private final int			maxSize;

		public BlockCache(int maxSize) {
			super(capacity(maxSize));
			this.maxSize = maxSize;
		}
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<BlockPos, D> eldest) {
			return size()>maxSize;
		}

		static int capacity(int expectedSize) {
			if (expectedSize < 3) return expectedSize + 1;
			if (expectedSize < 1073741824) return (int) (expectedSize / 0.75F + 1.0F);
			return Integer.MAX_VALUE;
		}
	}
}
