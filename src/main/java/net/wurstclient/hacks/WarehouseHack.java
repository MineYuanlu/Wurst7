/**
 * 
 */
package net.wurstclient.hacks;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Runnables;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RotationUtils;

/**
 * Warehouse
 * 
 * @author yuanlu
 *
 */
@DontSaveState
@SearchTags({ "warehouse", "sort", "chest", "ware", "item", "no-conf" })
public class WarehouseHack extends Hack implements UpdateListener {

	private static final class ItemList {
		private static final BiFunction<Integer, Integer, Integer>	add	= (a, b) -> a + b;
		private long												updateTime;
		private int													emptySlots;
		private Object2IntMap<String>								items;

		private Object2IntMap<String>								slotsCount;

		public ItemList() {

		}

		public ItemList(ItemStack[] chest) {
			this();
			update(chest);
		}

		public boolean canStore(ItemStack item, int amount, boolean isGroupAmount) {
			String	id			= Registry.ITEM.getId(item.getItem()).toString();

			int		chestAmount	= items.getInt(id);

			if (chestAmount <= 0) return false;// no such items

			if (isGroupAmount) amount *= item.getMaxCount();

			if (amount - chestAmount <= 0) return false;// Maximum amount reached

			if (emptySlots <= 0 && slotsCount.getInt(id) * item.getMaxCount() <= chestAmount) return false;// No vacancy

			return true;
		}

		/**
		 * Simulate the storage of an item and return the quantity that can be stored
		 */
		public int simulateStore(ItemStack item, int amount, boolean isGroupAmount) {
			if (item == null || item.isEmpty()) return 0;

			String	id			= Registry.ITEM.getId(item.getItem()).toString();

			int		chestAmount	= items.getInt(id);

			if (chestAmount <= 0) return 0;// no such items

			if (isGroupAmount) amount *= item.getMaxCount();

			int needStore = Math.min(amount - chestAmount, item.getCount());
			if (needStore <= 0) return 0;// Maximum amount reached OR no item

			int similarSpare = slotsCount.getInt(id) * item.getMaxCount() - chestAmount;
			if (similarSpare >= needStore) {// Can merge all

				items.merge(id, needStore, add);
				return needStore;

			} else if (emptySlots > 0) {// Need to occupy one more slot

				items.merge(id, needStore, add);
				emptySlots--;
				slotsCount.merge(id, 1, add);
				return needStore;

			} else {// Only a portion can be stored

				if (similarSpare > 0) items.merge(id, similarSpare, add);
				return similarSpare;
				// Can be optimized:
				// When the contents of the box are all full, it can perform the same operation
				// as "merge all", which can be merged by the game to reduce the number of
				// operations
				//
				// The problem is:
				// When the maximum storage limit of items is reached, negative optimization may
				// occur
			}

		}

		public synchronized void update(ItemStack[] chest) {
			updateTime = System.currentTimeMillis();

			if (items == null) items = new Object2IntLinkedOpenHashMap<>();
			else items.clear();
			items.defaultReturnValue(0);

			if (slotsCount == null) slotsCount = new Object2IntOpenHashMap<>();
			else slotsCount.clear();
			slotsCount.defaultReturnValue(0);

			emptySlots = 0;
			for (ItemStack item : chest) {

				if (item == null || item.isEmpty()) emptySlots++;
				else {

					String id = Registry.ITEM.getId(item.getItem()).toString();
					items.merge(id, item.getCount(), add);
					slotsCount.merge(id, 1, add);

				}

			}
			assert items.keySet().equals(slotsCount.keySet()) : "Bad update";
		}

	}

	private enum Status {
		SEARCHING, SLEEPING, OPENING, WAITING, RUNNING, SCANNING;

		public String renderName = name();
	}

	private final SliderSetting					range			= new SliderSetting("Range", "How far Warehouse will reach to blocks.", 5, 1, 6, 0.05,
			ValueDisplay.DECIMAL);

	private final CheckboxSetting				keepCache		= new CheckboxSetting("Keep cache", "Do not clear cache when feature is disabled",
			isSafeToBlock());
	private final SliderSetting					cacheDuration	= new SliderSetting("Cache Duration",
			"The length of time the cache is valid. After expiration, the container will be rescanned\nUnit: seconds (s)", 60, 1, 60 * 60, 0.001,
			ValueDisplay.DECIMAL);
	private final CheckboxSetting				ignoreNS		= new CheckboxSetting("Ignore non-stackable", "Ignore non stackable items", true);
	private final ItemListSetting				ignores			= new ItemListSetting("Ignore list", "Specified ignore item list", "minecraft:dragon_egg");

	private final SliderSetting					amount			= new SliderSetting("Amount", "Specify the maximum storage quantity of similar items.", 54, 1,
			54 * 64, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting				amountWithGroup	= new CheckboxSetting("Amount With Group",
			"When specifying the maximum storage quantity, specify the unit as \"group\" or \"number\"", true);
	private final SliderSetting					timeout			= new SliderSetting("Timeout",
			"Specifies the maximum time (in tick) to wait for interaction with the block", 20 * 5, 1, 20 * 20, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting				lockMove		= new CheckboxSetting("Lock Move",
			"Lock the movement at the necessary stage to prevent accidental errors", false);
	private final CheckboxSetting				useChest		= new CheckboxSetting("Use Chest", true);

	private final CheckboxSetting				useTrapChest	= new CheckboxSetting("Use Trap Chest", false);
	private final CheckboxSetting				useShulker		= new CheckboxSetting("Use Shulker", true);
	private final CheckboxSetting				useBarrel		= new CheckboxSetting("Use Barrel", true);
	private LinkedHashMap<BlockPos, ItemList>	cache;

	private RegistryKey<World>					worldId;
	private Status								status;
	private BlockPos							targetPos;
	/**
	 * handle type<br>
	 * true=run, false=scan
	 */
	private boolean								targetHandleType;
	private long								waitingTick;
	private Integer								chestSyncId;

	private int									chestSize;

	/** Prevent persistent failure */
	private boolean								lastOpenFail;

	public WarehouseHack() {
		super("Warehouse");

		setCategory(Category.ITEMS);

		addSetting(range);
		addSetting(keepCache);
		addSetting(cacheDuration);
		addSetting(ignoreNS);
		addSetting(ignores);
		addSetting(amount);
		addSetting(amountWithGroup);
		addSetting(timeout);
		addSetting(lockMove);

		addSetting(useChest);
		addSetting(useTrapChest);
		addSetting(useShulker);
		addSetting(useBarrel);

		for (var status : Status.values()) status.renderName = String.format("%s [ %s ]", getName(), status.name());
	}

	public synchronized void callbackInventory(List<ItemStack> items, int syncId) {
		System.out.println("WarehouseHack callbackInventory: " + syncId + ", " + items + ", " + status + ", " + chestSyncId);
		if (status != Status.WAITING || syncId != chestSyncId) return;

		chestSyncId = null;
		var itemsArray = items.subList(0, chestSize).toArray(ItemStack[]::new);
		if (targetHandleType) {
			status = Status.RUNNING;
			startStore(itemsArray, syncId);
		} else {
			status = Status.SCANNING;
			scanChest(itemsArray);
		}
	}

	public synchronized boolean callbackOpenWindow(int syncId, int size) {
		System.out.println("WarehouseHack callbackOpenWindow: " + syncId + ", " + size + ", " + status);
		if (status != Status.WAITING) return false;

		if (chestSyncId == null) {
			chestSyncId	= syncId;
			chestSize	= size;
		} else {
			ChatUtils.error("[Warehouse] Multiple containers received!");
			setEnabled(false);
		}

		return true;
	}

	private ItemList getCache(BlockPos pos) {
		var list = cache.get(pos);
		if (list == null) return null;
		long now = System.currentTimeMillis();
		if (now - list.updateTime >= cacheDuration.getValue() * 1000) return null;
		return list;
	}

	private Stream<ItemStack> getItems(boolean ignoreNS, List<String> ignores) {

		Collection<String> ignoresCollection = ignores.size() > 8 ? new HashSet<>(ignores) : ignores;
		//

		var stream = MC.player.getInventory().main.stream() //
				.filter(Predicate.not(ItemStack::isEmpty)) //
				.filter(item -> !ignoresCollection.contains(Registry.ITEM.getId(item.getItem()).toString()))//
		;
		if (ignoreNS) {

			stream = stream.filter(ItemStack::isStackable);

		}
		return stream;
	}

	private Collection<BlockPos> getOthers(BlockPos pos) {
		BlockEntity	chestBE	= MC.world.getBlockEntity(pos);
		BlockState	state	= chestBE.getCachedState();

		if (state.contains(ChestBlock.CHEST_TYPE)) {
			ChestType chestType = state.get(ChestBlock.CHEST_TYPE);
			if (chestType == ChestType.LEFT || chestType == ChestType.RIGHT) {

				BlockPos pos2 = pos.offset(ChestBlock.getFacing(state));
				return Collections.singletonList(pos2);

			}
		}
		return null;
	}

	public String getRenderName() {
		var status = this.status;
		return status == null ? getName() : status.renderName;
	}

	private Stream<BlockPos> getValidBlocks(double range, Predicate<BlockEntity> validator) {
		Vec3d		eyesVec	= RotationUtils.getEyesPos().subtract(0.5, 0.5, 0.5);
		double		rangeSq	= Math.pow(range + 0.5, 2);
		int			rangeI	= (int) Math.ceil(range);

		BlockPos	center	= new BlockPos(RotationUtils.getEyesPos());
		BlockPos	min		= center.add(-rangeI, -rangeI, -rangeI);
		BlockPos	max		= center.add(rangeI, rangeI, rangeI);

		return BlockUtils.getAllInBox(min, max).stream()//
				.filter(pos -> eyesVec.squaredDistanceTo(Vec3d.of(pos)) <= rangeSq)//
				.map(MC.world::getBlockEntity)//
				.filter(Objects::nonNull)//
				.filter(validator)//
				.sorted(Comparator.comparingDouble(e -> eyesVec.squaredDistanceTo(Vec3d.of(e.getPos()))))//
				.map(BlockEntity::getPos);
	}

	private boolean isCorrectBlockEntity(BlockEntity entity) {

		if (useChest.isChecked() && entity instanceof ChestBlockEntity) return true;

		if (useTrapChest.isChecked() && entity instanceof TrappedChestBlockEntity) return true;

		if (useShulker.isChecked() && entity instanceof ShulkerBoxBlockEntity) return true;

		if (useBarrel.isChecked() && entity instanceof BarrelBlockEntity) return true;

		return false;
	}

	private boolean makeCache() {
		var id = MC.world == null ? null : MC.world.getRegistryKey();
		if (cache != null && worldId != null) {
			if (worldId == id) return false;
			if (id != null && worldId.toString().equals(id.toString())) {
				worldId = id;
				return false;
			}
		}

		if (id == null) {
			cache	= null;
			worldId	= null;
			return true;
		}

		final double	r		= range.getValue();
		final int		size	= (int) Math.ceil(r * r * r * Math.PI * 4 / 3);
		cache	= Maps.newLinkedHashMapWithExpectedSize(size);
		worldId	= id;

		return false;
	}

	@Override
	protected void onDisable() {
		EVENTS.remove(UpdateListener.class, this);

		targetPos	= null;
		chestSyncId	= null;
		if (!keepCache.isChecked()) cache = null;
		
		PathProcessor.releaseControls();
	}

	@Override
	protected void onEnable() {
		status		= Status.SEARCHING;
		targetPos	= null;
		chestSyncId	= null;

		EVENTS.add(UpdateListener.class, this);
	}

	@Override
	public void onUpdate() {
		if (makeCache()) return;

		switch (status) {
		case SEARCHING, SLEEPING -> searching();
		case OPENING -> openChest();
		case WAITING -> waitOpenChest();
		case SCANNING, RUNNING -> {
		}
		default -> throw new IllegalArgumentException("Unexpected value: " + status);
		}
	}

	/**
	 * Open the container.<br>
	 * Interact with the block in the world, and try to open it
	 */
	private void openChest() {
		MC.player.closeScreen();
		if (targetPos == null) {
			ChatUtils.error("[Warehouse] Internal error: interaction with empty target.");
			setEnabled(false);

		} else if (rightClickBlockSimple(targetPos)) {
			waitingTick		= 0;
			status			= Status.WAITING;
			lastOpenFail	= false;

		} else {
			ChatUtils.warning("[Warehouse] Unable to reach: " + targetPos.toShortString());
			if (lastOpenFail) {
				setEnabled(false);
			} else {
				lastOpenFail	= true;
				status			= Status.SEARCHING;
			}

		}
		PathProcessor.releaseControls();
	}

	private void openChest(BlockPos pos, boolean isRun) {
		if (lockMove.isChecked()) PathProcessor.lockControls();
		targetPos			= pos;
		targetHandleType	= isRun;
		status				= Status.OPENING;
	}

	/**
	 * The logic that interacts with the right button of the box is copied from
	 * {@code TillauraHack}
	 */
	private boolean rightClickBlockSimple(BlockPos pos) {
		Vec3d	eyesPos				= RotationUtils.getEyesPos();
		Vec3d	posVec				= Vec3d.ofCenter(pos);
		double	distanceSqPosVec	= eyesPos.squaredDistanceTo(posVec);
		double	rangeSq				= Math.pow(range.getValue(), 2);

		for (Direction side : Direction.values()) {
			Vec3d	hitVec				= posVec.add(Vec3d.of(side.getVector()).multiply(0.5));
			double	distanceSqHitVec	= eyesPos.squaredDistanceTo(hitVec);

			// check if hitVec is within range
			// check if side is facing towards player
			if ((distanceSqHitVec > rangeSq) || (distanceSqHitVec >= distanceSqPosVec)) continue;

			IMC.getInteractionManager().rightClickBlock(pos, side, hitVec);
			return true;
		}

		return false;
	}

	private void scanChest(ItemStack[] chest) {
		var targetPos = this.targetPos;
		if (targetPos == null) {
			ChatUtils.error("[Warehouse] Internal error: scanning with empty target.");
			setEnabled(false);
			return;
		}

		var itemList = cache.computeIfAbsent(targetPos, x -> new ItemList());
		itemList.update(chest);

		var others = getOthers(targetPos);
		if (others != null && !others.isEmpty()) others.forEach(pos -> cache.put(pos, itemList));

		MC.player.closeScreen();
		status = Status.SEARCHING;

	}

	/**
	 * Search nearby containers to see if they can be stored, or start building a
	 * cache.
	 */
	private void searching() {

		var			items			= getItems(ignoreNS.isChecked(), ignores.getItemNames());

		var			blocksStream	= getValidBlocks(range.getValue() - 1, this::isCorrectBlockEntity);
		BlockPos[]	blocks			= null;

		for (var itr = items.iterator(); itr.hasNext();) {
			var item = itr.next();

			if (blocks == null) blocks = blocksStream.toArray(BlockPos[]::new);

			boolean allCached = true;
			for (var pos : blocks) {// cache first

				var itemList = getCache(pos);
				if (itemList == null) {
					allCached = false;
					continue;
				}

				if (itemList.canStore(item, amount.getValueI(), amountWithGroup.isChecked())) {
					openChest(pos, true);
					return;
				}
			}

			if (allCached) continue;// The current item cannot be stored

			for (var pos : blocks) {// build cache

				if (getCache(pos) == null) {
					openChest(pos, true);
					return;
				}

			}

		}

		status = Status.SLEEPING;

	}

	public void startStore(ItemStack[] chest, int syncId) {

		var delay = WURST.getHax().autoStealHack.getDelay();
		new Thread(() -> store(chest, syncId, delay, amount.getValueI(), amountWithGroup.isChecked())//
				, "WarehouseHack-store" + chest.hashCode()).start();

	}

	private void store(final ItemStack[] chest, final int syncId, final long delay, final int amount, final boolean isGroupAmount) {
		final ItemList	itemList		= new ItemList(chest);
		final Runnable	sleep			= delay <= 0 ? Runnables.doNothing() :			//
				() -> {
					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						CrashReport crashReport = CrashReport.create(e, "Thread Sleep");
						throw new CrashException(crashReport);
					}
				};
		final int		storeAmount[]	= new int[4 * 9];

		var				inventory		= MC.player.getInventory().main;
		for (int i = 0; i < 36; i++) {
			storeAmount[i] = itemList.simulateStore(inventory.get(i), amount, isGroupAmount);
		}

		for (int i = 0; i < 36; i++) {
			final int storeCount = storeAmount[i];
			if (storeCount <= 0) continue;
			var item = inventory.get(i);
			if (item.getCount() <= 0) continue;
			int slotId = chest.length + (i < 9 ? (i + 27) : (i - 9));

			System.out.println("[WarehouseHack] store " + i + ", " + storeCount + ", " + item.getCount());

			if (item.getCount() <= storeCount) {// Simple move mode

				MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
				sleep.run();

			} else {// Half move mode

				int			moveCount	= 0, allCount = item.getCount();
				final int	t			= allCount <= 4 ? 2 : 4;		// Among 1 ~ 64 items, this is the best threshold
				boolean		curEmpty	= true;

				while (allCount > t && storeCount - moveCount >= allCount / 2) {
					if (!curEmpty) {
						MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, MC.player);
						sleep.run();// put down
					}
					MC.interactionManager.clickSlot(syncId, slotId, 1, SlotActionType.PICKUP, MC.player);
					sleep.run();// pick half
					MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
					sleep.run();// move

					curEmpty	= false;
					moveCount	+= allCount / 2;
					allCount	= allCount - allCount / 2;
				}
				if (storeCount > moveCount) {

					if (curEmpty) {
						MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, MC.player);
						sleep.run();// pick all
					}
					curEmpty = false;

					while (moveCount++ < storeCount) {
						MC.interactionManager.clickSlot(syncId, slotId, 1, SlotActionType.PICKUP, MC.player);
						sleep.run();// put one
					}

					MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
					sleep.run();// move

				}

				if (!curEmpty) {
					MC.interactionManager.clickSlot(syncId, slotId, 0, SlotActionType.PICKUP, MC.player);
					sleep.run();// put down
				}
			}

		}

		// finish
		openChest(targetPos, false);

	}

	private void waitOpenChest() {
		if (waitingTick++ < timeout.getValueI()) return;
		ChatUtils.error("[Warehouse] Wait timeout: " + targetPos + " " + (targetHandleType ? "Run" : "Scan"));
		setEnabled(false);
	}
}
