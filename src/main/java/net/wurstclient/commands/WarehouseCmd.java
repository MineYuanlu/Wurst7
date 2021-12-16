/**
 *
 */
package net.wurstclient.commands;

import java.awt.Color;
import java.io.Serial;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.lwjgl.opengl.GL11;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.systems.RenderSystem;

import io.netty.util.CharsetUtil;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.crash.CrashException;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathPos;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hacks.AutoStealHack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.json.JsonUtils;

/**
 * Warehouse
 *
 * @author yuanlu
 *
 */
public class WarehouseCmd extends Command implements UpdateListener, RenderListener, RightClickListener {

	public static final class Config {
		//

		public static Config deserialize(JsonElement element) {
			Config		conf		= new Config();
			JsonArray	contaioners	= element.getAsJsonObject().getAsJsonArray("contaioners");

			contaioners.forEach(ele -> conf.contaioners.add(ContaionerConfig.deserialize(ele)));

			conf.flush();
			return conf;
		}

		public final ArrayList<ContaionerConfig>							contaioners	= new ArrayList<>();
		public final transient LinkedHashMap<BlockPos, ContaionerConfig>	cacheBlocks	= new LinkedHashMap<>();
		public transient Box												allArea;
		public transient String												name;

		public transient boolean											saved;

		public Config() {
		}

		public Config(String name) {
			this.name = name;
		}

		public void calculateArea() {
			int[] mins = new int[3], maxs = new int[3];

			Arrays.fill(mins, Integer.MAX_VALUE);
			Arrays.fill(maxs, Integer.MIN_VALUE);

			contaioners//
					.stream()//
					.flatMap(cc -> cc.blocks.keySet().stream())//
					.forEach(pos -> {
						mins[0] = Math.min(pos.getX(), mins[0]);
						mins[1] = Math.min(pos.getY(), mins[1]);
						mins[2] = Math.min(pos.getZ(), mins[2]);

						maxs[0] = Math.max(pos.getX(), maxs[0]);
						maxs[1] = Math.max(pos.getY(), maxs[1]);
						maxs[2] = Math.max(pos.getZ(), maxs[2]);
					});
			allArea = new Box(mins[0], mins[1], mins[2], maxs[0] + 1, maxs[1] + 1, maxs[2] + 1);
		}

		public void dumpBlocks() {
			cacheBlocks.clear();
			for (ContaionerConfig cc : contaioners) cc.blocks.keySet().forEach(pos -> cacheBlocks.put(pos, cc));
		}

		/** flush cache */
		public void flush() {
			dumpBlocks();
			calculateArea();
		}

		public JsonElement serialize() {
			JsonObject	obj			= new JsonObject();

			JsonArray	contaioners	= new JsonArray(this.contaioners.size());
			obj.add("contaioners", contaioners);

			this.contaioners.forEach(cc -> contaioners.add(cc.serialize()));

			return obj;
		}

	}

	private static final class ContaionerConfig implements Comparable<ContaionerConfig> {

		public static ContaionerConfig deserialize(JsonElement element) {
			JsonObject			obj		= element.getAsJsonObject();
			ContaionerConfig	cc		= new ContaionerConfig(			//
					ContaionerType.BY_ID[obj.get("t").getAsInt()],		//
					obj.get("w").getAsInt(),							//
					obj.get("a").getAsInt(),							//
					obj.get("c").getAsBoolean()							//
			);
			JsonObject			blocks	= obj.getAsJsonObject("blocks");
			blocks.entrySet().forEach(e -> {
				BlockPos	b	= BlockPos.fromLong(Long.parseUnsignedLong(e.getKey(), Character.MAX_RADIX));
				ItemList	i	= ItemList.deserialize(e.getValue());
				cc.blocks.put(b, i);
			});
			return cc;
		}

		public final LinkedHashMap<BlockPos, ItemList>	blocks	= new LinkedHashMap<>();
		public final ContaionerType						type;
		public final int								weight;
		public final int								amount;

		public final boolean							clear;

		public ContaionerConfig(ContaionerType type, int weight, int amount, boolean clear) {
			this.type	= type;
			this.weight	= weight;
			this.amount	= amount;
			this.clear	= clear;
		}

		@Override
		public int compareTo(ContaionerConfig o) {
			return Integer.compare(weight, o.weight);
		}

		public boolean configEquals(ContaionerConfig cc) {
			return cc.type == type && cc.weight == weight && cc.amount == amount && cc.clear == clear;
		}

		public void mergeTo(ContaionerConfig cc) {
			if (!configEquals(cc)) throw new IllegalArgumentException("config not equal: this=" + this + ", other=" + cc);
			cc.blocks.putAll(this.blocks);
		}

		public JsonElement serialize() {
			JsonObject obj = new JsonObject();
			obj.add("t", new JsonPrimitive(type.ordinal()));
			obj.add("w", new JsonPrimitive(weight));
			obj.add("a", new JsonPrimitive(amount));
			obj.add("c", new JsonPrimitive(clear));
			JsonObject blocks = new JsonObject();
			this.blocks.forEach((b, i) -> blocks.add(Long.toUnsignedString(b.asLong(), Character.MAX_RADIX), i.serialize()));
			obj.add("blocks", blocks);
			return obj;
		}

		@Override
		public String toString() {
			return String.format("CC [t=%c, w=%s, a=%s, c=%s]", type.name().charAt(0), weight, amount, clear ? "T" : "F");
		}
	}

	private static enum ContaionerType {
		/** input */
		INPUT("To remove items from containers", "input", "i", "in", "produce"),
		/** output */
		OUTPUT("To pack items into containers", "output", "o", "out", "storage"),
		/** temp */
		TEMP("Store temporary or not on the list items", "temp", "t", "tmp", "bad");

		private static final ContaionerType[]	BY_ID	= values();
		public static final String				syntax;
		static {
			StringJoiner stringJoiner = new StringJoiner("\n", "Unknown type:\n", "");
			for (ContaionerType type : values()) stringJoiner.add(type.description);
			syntax = stringJoiner.toString();
		}

		static ContaionerType get(String string) {
			for (ContaionerType type : BY_ID) if (hasStr(string, type.matchs)) return type;
			return null;
		}

		private final String	description;

		private final String[]	matchs;

		private ContaionerType(String description, String... matchs) {
			this.description	= String.join("/", matchs) + ": " + description;
			this.matchs			= matchs;
		}
	}

	private static class GoToHelper implements UpdateListener, RenderListener {
		private static final GoToHelper INSTANCE = new GoToHelper();

		public static void goTo(BlockPos goal, Consumer<Boolean> callback) {
			if (INSTANCE.enabled) INSTANCE.disable(false);
			INSTANCE.pathFinder	= new PathFinder(goal);
			INSTANCE.callback	= callback;

			// start
			INSTANCE.enabled = true;
			EVENTS.add(UpdateListener.class, INSTANCE);
			EVENTS.add(RenderListener.class, INSTANCE);
		}

		private PathFinder			pathFinder;
		private PathProcessor		processor;
		private boolean				enabled;

		private Consumer<Boolean>	callback;

		private void disable(boolean success) {
			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);

			pathFinder	= null;
			processor	= null;
			PathProcessor.releaseControls();

			enabled = false;
			callback.accept(success);
		}

		@Override
		public void onRender(MatrixStack matrixStack, float partialTicks) {
			PathCmd pathCmd = WURST.getCmds().pathCmd;
			pathFinder.renderPath(matrixStack, pathCmd.isDebugMode(), pathCmd.isDepthTest());
		}

		@Override
		public void onUpdate() {
			// find path
			if (!pathFinder.isDone() && !pathFinder.isFailed()) {
				PathProcessor.lockControls();

				pathFinder.think();

				if (pathFinder.isDone()) {

					pathFinder.formatPath();

				} else if (pathFinder.isFailed()) {

					ArrayList<PathPos> path = pathFinder.formatPath();

					if (path.isEmpty()) {

						ChatUtils.error("Could not find a path.");
						disable(false);

						return;

					}

					PathPos	last	= path.get(path.size() - 1);

					double	range	= WarehouseCmd.range.getValue();
					if (last.getSquaredDistance(pathFinder.getGoal()) > range * range) {

						ChatUtils.error("Unable to access the destination container");
						disable(false);

						return;

					}

				} else {

					return;

				}

				// set processor
				processor = pathFinder.getProcessor();

				System.out.println("Done");
			}

			// check path
			if (processor != null//
					&& !pathFinder.isPathStillValid(processor.getIndex())) {
				System.out.println("Updating path...");
				pathFinder = new PathFinder(pathFinder.getGoal());
				return;
			}

			// process path
			processor.process();

			if (processor.isDone()) disable(true);
		}

	}

	private static final class ItemList extends LinkedHashMap<String, Integer> {
		@Serial private static final long serialVersionUID = -814964946466372693L;

		public static ItemList deserialize(JsonElement element) {
			ItemList list = new ItemList();
			element.getAsJsonObject().entrySet().forEach(e -> list.put(e.getKey(), e.getValue().getAsInt()));
			return list;
		}

		public void add(ItemStack itemStack) {
			String	name	= Registry.ITEM.getId(itemStack.getItem()).toString();
			int		amount	= itemStack.getCount();
			compute(name, (ignore, old) -> old == null ? amount : (amount + old));
		}

		/**
		 * Check whether there are items in the player's backpack that can be put into
		 * this item list.
		 */
		public boolean hasCanOutput(PlayerInventory inv) {
			System.out.println("hasCanOutput: " + //
					inv.main.stream()//
							.map(ItemStack::getItem)//
							.map(Registry.ITEM::getId)//
							.map(Identifier::toString)//
							.toList()//
			);
			System.out.println(keySet());
			return inv.main.stream()//
					.map(ItemStack::getItem)//
					.map(Registry.ITEM::getId)//
					.map(Identifier::toString)//
					.anyMatch(this::containsKey)//
			;
		}

		public JsonElement serialize() {
			JsonObject obj = new JsonObject();
			forEach((k, v) -> obj.add(k, new JsonPrimitive(v)));
			return obj;
		}
	}

	private static final class SortWarehouse implements UpdateListener {
		private final AutoStealHack								autoSteal	= WURST.getHax().autoStealHack;
		/** configs */
		private final Config									config;
		private final LinkedHashMap<BlockPos, ContaionerConfig>	contaioners;
		private final AtomicReference<Status>					status;

		/** Waiting container */
		private CompletableFuture<ItemStack[]>					waitingChest;
		private Integer											waitingSyncId;
		private int												waitingSize;

		/** Container in use */
		private int												nowSyncId;
		/** next call task */
		private Runnable										next;
		private String											nextName;
		private boolean											stop;

		public SortWarehouse(Config config, AtomicReference<Status> status) {
			if (status.get() != Status.RUNNING) throw new IllegalStateException(status.get().name());
			this.config			= config;
			this.contaioners	= new LinkedHashMap<>();
			this.status			= status;

			ArrayList<ContaionerConfig> contaioners = new ArrayList<>(this.config.contaioners);
			contaioners.sort(Collections.reverseOrder());
			contaioners.forEach(cc -> {
				cc.blocks.keySet().forEach(pos -> this.contaioners.put(pos, cc));
			});

			setNext(this::startOnce, "start");
			new Thread("Warehouse Sorter-" + config.hashCode()) {
				@Override
				public void run() {
					while (!stop && next != null) {

						Runnable task = next;
						next = null;

						System.out.println("Call Task: " + nextName);
						task.run();

					}

					System.out.println("Finish!");
					status.set(Status.IDLE);
				}
			}.start();
		}

		public void callbackInventory(List<ItemStack> items, int syncId) {
			if ((waitingSyncId == null) || (waitingSyncId != syncId)) return;

			ItemStack[] list = items.subList(0, waitingSize).toArray(ItemStack[]::new);

			try {

				nowSyncId = waitingSyncId;
				waitingChest.complete(list);

			} finally {
				waitingChest	= null;
				waitingSyncId	= null;
			}

		}

		public synchronized boolean callbackOpenWindow(int syncId, int size) {
			if (waitingChest == null) return false;

			if (waitingSyncId != null) {

				waitingChest.complete(null);
				waitingSyncId = null;

				MC.player.closeScreen();
				ChatUtils.warning("The last waiting container content was not received.");
				ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");
				return true;
			}

			waitingSyncId	= syncId;
			waitingSize		= size;

			return true;
		}

		private void callCloseScreen() {
			EVENTS.add(UpdateListener.class, this);
		}

		/**
		 * Look for an input container that is not explicitly empty, go to scan, and
		 * then take out the item.
		 */
		private void doInput() {
			status.set(Status.RUNNING);
			Entry<BlockPos, ContaionerConfig> next = contaioners.entrySet().stream()//
					.filter(e -> e.getValue().type == ContaionerType.INPUT)//
					.findFirst().orElse(null);

			if (next == null) {
				if (isInvEmpty(true)) ChatUtils.message("Done.");
				else setNext(this::doOutput, "Output - by No Input");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error

			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) return;

			ContaionerConfig	cc		= next.getValue();
			boolean				isEmpty	= steal(cc.blocks.get(next.getKey()), cc.amount, list);
			callCloseScreen();

			if (isEmpty)// The input container is empty. Ignored in this run.
				contaioners.remove(next.getKey());

			setNext(this::startOnce, "restart - by Input Once");
		}

		private void doOutput() {
			status.set(Status.RUNNING);
			PlayerInventory						inv		= MC.player.getInventory();
			Entry<BlockPos, ContaionerConfig>	next	= contaioners						//
					.entrySet().stream()													//
					.filter(e -> e.getValue().type == ContaionerType.OUTPUT)				//
					.filter(e -> e.getValue().blocks.get(e.getKey()).hasCanOutput(inv))		//
					.findFirst().orElse(null);												//
			// TODO 依据箱子缓存防止重复查找<BlockPos,ItemList>

			if (next == null) {
				setNext(this::doTemp, "Temp - by No Output");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error

			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) return;

			ContaionerConfig cc = next.getValue();
			store(cc.blocks.get(next.getKey()), cc.amount, list);
			callCloseScreen();

			// TODO 放入物品 + 缓存内容
			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Output Finish");
			else setNext(this::doOutput, "Output - by Output Once");

		}

		private void doTemp() {
			status.set(Status.RUNNING);
			Entry<BlockPos, ContaionerConfig> next = contaioners.entrySet().stream()//
					.filter(e -> e.getValue().type == ContaionerType.TEMP)//
					.findFirst().orElse(null);

			if (next == null) {
				ChatUtils.error("All temporary containers are full, cannot continue.");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error
			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) return;

			store(list);
			callCloseScreen();
			// TODO 存放背包所有物品，缓存内容

			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Temp Finish");
			else setNext(this::doTemp, "Temp - by Temp Once");

		}

		private boolean goTo(BlockPos pos) {
			status.set(Status.RUN_MOVING);
			CompletableFuture<Boolean> future = new CompletableFuture<>();
			GoToHelper.goTo(pos, future::complete);
			try {
				return future.get();
			} catch (Throwable e) {
				CrashReport crashReport = CrashReport.create(e, "Goto Future Waiting");
				throw new CrashException(crashReport);
			}
		}

		/**
		 * Check the inventory is empty
		 *
		 * @param isAll If it is true, it needs to be all empty; otherwise, it only
		 *              needs to have at least one empty bit
		 */
		private boolean isInvEmpty(boolean isAll) {
			PlayerInventory inv = MC.player.getInventory();
			if (isAll) {
				for (ItemStack itemStack : inv.main) {
					if (!itemStack.isEmpty()) return false;
				}
			} else {
				for (ItemStack itemStack : inv.main) {
					if (itemStack.isEmpty()) return true;
				}
			}
			return isAll;
		}

		@Override
		public void onUpdate() {
			MC.player.closeScreen();
			EVENTS.remove(UpdateListener.class, this);
		}

		/**
		 * Blocking method. Will try to simulate the player to open the container and
		 * get the list of items in the container.
		 */
		private ItemStack[] openContaioner(BlockPos pos) {
			status.set(Status.RUN_SCANNING);
			try {
				waitingChest = new CompletableFuture<>();

				if (!rightClickBlockSimple(pos)) {
					ChatUtils.error("Can not open Chest");
					return null;
				}
				return waitingChest.get(10, TimeUnit.SECONDS);// TODO 使用settings
			} catch (Throwable e) {
				CrashReport crashReport = CrashReport.create(e, "Opening " + pos.toShortString());
				throw new CrashException(crashReport);
			} finally {
				waitingSyncId	= null;
				waitingChest	= null;
			}
		}

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

		private void setNext(Runnable runnable, String debugName) {
			next		= runnable;
			nextName	= debugName;
		}

		/**
		 * Single process start, cycle start point
		 */
		private void startOnce() {
			if (isInvEmpty(false)) setNext(this::doInput, "Input - by start");
			else setNext(this::doOutput, "Output - by start");
		}

		private boolean steal(ItemList stealList, int stealAmount, ItemStack[] itemList) {

			// need takes
			ItemList takeList = new ItemList();
			for (ItemStack item : itemList) takeList.add(item);
			takeList.keySet().retainAll(stealList.keySet());

			BiFunction<String, Integer, Integer> amountFactory;
			if (stealAmount == 0) {
				// Keep the original quantity of items in the container
				amountFactory = (name, amount) -> amount - stealList.get(name);
			} else if (stealAmount > 0) {
				// Keep the specified amount items in the container
				amountFactory = (name, amount) -> amount - stealAmount;
			} else {
				// Keep the specified group amount items in the container
				amountFactory = (name, amount) -> amount - Registry.ITEM.get(Identifier.tryParse(name)).getMaxCount() * stealAmount;
			}

			for (String name : takeList.keySet().toArray(String[]::new)) takeList.compute(name, amountFactory);

			if (takeList.isEmpty()) return true;
			boolean noTake = true;
			for (Integer count : takeList.values())// check empty take
				if (count != null && count > 0) {
					noTake = false;
					break;
				}
			if (noTake) return true;

			System.out.println("takeList+++ " + takeList);

			// take items
			for (int i = 0; i < itemList.length; i++) {

				ItemStack item = itemList[i];
				if (item == null || item.isEmpty()) continue;

				String	name	= Registry.ITEM.getId(item.getItem()).toString();
				Integer	amount	= takeList.get(name);
				if (amount == null || amount <= 0) continue;

				System.out.println(String.format("steal Item: slot=%d, count=%d, tar=%d", i, item.getCount(), amount));
				if (item.getCount() <= amount) {

					amount -= item.getCount();
					MC.interactionManager.clickSlot(nowSyncId, i, 0, SlotActionType.QUICK_MOVE, MC.player);
					waitForDelay();

					if (amount > 0) takeList.put(name, amount);
					else takeList.remove(name);

				} else {
					// Processing logic for taking some items

//					item 当前物品
					// TODO
//					MC.player.getInventory();

					takeList.remove(name);
				}

			}
			return false;

			// TODO Auto-generated method stub

		}

		private void store(ItemList storeList, int storeAmount, ItemStack[] itemList) {
			ItemList		putList		= new ItemList();
			ItemList		chestList	= new ItemList();
			PlayerInventory	inventory	= MC.player.getInventory();

			for (ItemStack item : inventory.main) putList.add(item);
			for (ItemStack item : itemList) chestList.add(item);
			System.out.println("putList+++1 " + putList);
			System.out.println("putList+++2 " + storeList);
			putList.keySet().retainAll(storeList.keySet());

			Function<String, Integer> targetAmountGetter;
			if (storeAmount == 0) {
				// keep original amount
				targetAmountGetter = storeList::get;
			} else if (storeAmount > 0) {
				// amount
				targetAmountGetter = name -> storeAmount;
			} else {
				// group amount
				targetAmountGetter = name -> Registry.ITEM.get(Identifier.tryParse(name)).getMaxCount() * storeAmount;
			}

			for (Iterator<Entry<String, Integer>> itr = putList.entrySet().iterator(); itr.hasNext();) {
				Entry<String, Integer>	e		= itr.next();
				String					name	= e.getKey();

				if (!storeList.containsKey(name)) {

					itr.remove();
					continue;

				}

				e.setValue(targetAmountGetter.apply(name) - chestList.getOrDefault(name, 0));

			}

			System.out.println("putList+++3 " + putList);

			// take items
			for (int i = 0; i < 36; i++) {

				ItemStack item = inventory.main.get(i);
				if (item == null || item.isEmpty()) continue;

				String	name	= Registry.ITEM.getId(item.getItem()).toString();
				Integer	amount	= putList.get(name);
				if (amount == null || amount <= 0) continue;

				int slotId = itemList.length + (i < 9 ? (i + 27) : (i - 9));

				System.out.printf("store Item: slot=%d, count=%d, tar=%d\n", slotId, item.getCount(), amount);
				if (item.getCount() <= amount) {

					MC.interactionManager.clickSlot(nowSyncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
					waitForDelay();
					amount -= item.getCount();
//
					if (amount > 0) putList.put(name, amount);
					else putList.remove(name);

				} else {
					// Processing logic for putting some items

//					item 当前物品
					// TODO
//					MC.player.getInventory();

					putList.remove(name);
				}

			}

			// TODO Auto-generated method stub

		}

		private void store(ItemStack[] itemList) {
			List<ItemStack>	inventory	= MC.player.getInventory().main;
			int				i			= 0;
			for (ItemStack itemStack : inventory) {

				if (itemStack == null || itemStack.isEmpty()) continue;

				MC.interactionManager.clickSlot(nowSyncId, itemList.length + i, 0, SlotActionType.QUICK_MOVE, MC.player);
				++i;

			}
		}

		private void waitForDelay() {
			try {
				Thread.sleep(autoSteal.getDelay());
			} catch (InterruptedException e) {
				CrashReport crashReport = CrashReport.create(e, "Thread Sleep");
				throw new CrashException(crashReport);
			}
		}

	}

	public enum Status {
		IDLE("idle"), //
		SIGN("signing container"), //
		IO("File reading or writing"), //
		RUNNING("Internal calculation in progress"), //
		RUN_MOVING("Moving to the next container"), //
		RUN_SCANNING("Scanning container"), //
		RUN_PICKING("Removing items from container"), //
		RUN_PUTTING("Putting items into container");

		public final String description;

		Status(String description) {
			this.description = Objects.requireNonNull(description);
		}

	}

	private static final SliderSetting	range			= new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);

	private static final Gson			GSON			= new GsonBuilder().setPrettyPrinting().create();

	private static final String			DEF_GAME_FOLDER	= "default";

	private static final Path			FOLDER			= WURST.getWurstFolder().resolve("warehouse");

	private static final Color			COLOR			= Color.BLUE;

	private static final float[]		COLOR_ARR		= new float[] { COLOR.getRed() / 255F, COLOR.getGreen() / 255F, COLOR.getBlue() / 255F };

	private static Box getBox(BlockEntity blockEntity) {

		if (blockEntity instanceof TrappedChestBlockEntity) {

			return getBoxFromChest((ChestBlockEntity) blockEntity);

		} else if (blockEntity instanceof ChestBlockEntity) {

			return getBoxFromChest((ChestBlockEntity) blockEntity);

		} else if (blockEntity instanceof EnderChestBlockEntity) {

			BlockPos pos = blockEntity.getPos();
			if (!BlockUtils.canBeClicked(pos)) return null;

			return BlockUtils.getBoundingBox(pos);

		} else if (blockEntity instanceof ShulkerBoxBlockEntity) {

			BlockPos pos = blockEntity.getPos();
			if (!BlockUtils.canBeClicked(pos)) return null;

			return BlockUtils.getBoundingBox(pos);

		} else if (blockEntity instanceof BarrelBlockEntity) {

			BlockPos pos = blockEntity.getPos();
			if (!BlockUtils.canBeClicked(pos)) return null;

			return BlockUtils.getBoundingBox(pos);
		}
		return null;
	}

	private static Box getBoxFromChest(ChestBlockEntity chestBE) {
		BlockState state = chestBE.getCachedState();
		if (!state.contains(ChestBlock.CHEST_TYPE)) return null;

		ChestType chestType = state.get(ChestBlock.CHEST_TYPE);

		// ignore other block in double chest
//		if (chestType == ChestType.LEFT) return null;

		BlockPos pos = chestBE.getPos();
		if (!BlockUtils.canBeClicked(pos)) return null;

		Box box = BlockUtils.getBoundingBox(pos);

		// larger box for double chest
		if (chestType != ChestType.SINGLE) {
			BlockPos pos2 = pos.offset(ChestBlock.getFacing(state));

			if (BlockUtils.canBeClicked(pos2)) {
				Box box2 = BlockUtils.getBoundingBox(pos2);
				box = box.union(box2);
			}
		}

		return box;
	}

	private static boolean hasStr(String arg, String... match) {
		for (String m : match) if (arg.equalsIgnoreCase(m)) return true;
		return false;
	}

	/** Merge String */
	private static String merge(String[] args, int startIndex) throws CmdException {

		StringJoiner sj = new StringJoiner(" ");
		while (startIndex < args.length) sj.add(args[startIndex++]);
		return sj.toString();
	}

	private final AtomicReference<Status>	status	= new AtomicReference<>(Status.IDLE);

	private Config							config;
	/** RENDER */
	private VertexBuffer					solidBox;

	private VertexBuffer					outlinedBox;
	/** signing config */
	private ContaionerConfig				signing;
	/** ChestESP status keep */
	private boolean							signing_ChestESP_enable;
	private Box								signing_lookingBox;
	private BlockPos						signing_waitingChest;
	private Integer							signing_waitingSyncId;

	private int								signing_waitingSize;

	/** sort helper */
	private SortWarehouse					sorting;

	public WarehouseCmd() {
		super("warehouse", //
				"Automatically organize the boxes near you and summarize all kinds of items into their places", //
				".warehouse new <name> §7Create a warehouse configuration", //
				".warehouse load <name> §7Load a warehouse configuration", //
				".warehouse save [name] §7Save a warehouse configuration", //
				".warehouse sign <type> [w] [a] [c] §7Enable container tagging", //
				".warehouse run §7Start moving items", //
				".warehouse summary §7Displays the current warehouse summary"//
		);
	}

	@Override
	public void call(String[] args) throws CmdException {
		if (args.length == 0) throw new CmdSyntaxError();
		switch (args[0]) {
		case "new" -> createConf(merge(args, 1));
		case "load" -> {
			if (args.length <= 1) throw new CmdSyntaxError("Missing parameter");
			loadConf(merge(args, 1));
		}
		case "save" -> saveConf(merge(args, 1));
		case "sign" -> {
			if (status.get() == Status.SIGN) {
				sign(null, 0, 0, false);
				return;
			}
			/*
			 * 限制数量： 对于input型，将会保留指定数量的物品 对于output型，将最多存放指定数量的物品 对于数量:
			 * 正数表示限制N个，负数表示限制N组，0表示保持物品原数目
			 */
			if (args.length <= 1) throw new CmdSyntaxError("Missing parameter:\n"//
					+ ".warehouse sign <type> [w] [a] [c] §7Enable container tagging\n" //
					+ "type - §7Type of container: produce (input) / storage (output)\n"//
					+ "w - §7Container priority\n"//
					+ "a - §7Limit quantity:\n"//
					+ "  §7- For input type, the specified number of items will be retained\n"//
					+ "  §7- For output type, the specified number of items will be stored at most\n"//
					+ "  §7- For this quantity:\n"//
					+ "  §7- A positive number means to limit n items\n"//
					+ "  §7- A negative number means to limit n groups\n"//
					+ "  §7- And 0 means to keep the original number of items\n"//
					+ "c - §7For output type, whether to take out items that do not belong to this box"//
			);

			ContaionerType type = ContaionerType.get(args[1]);
			if (type == null) throw new CmdSyntaxError(ContaionerType.syntax);

			int weight = 0;
			if (args.length > 2) try {
				weight = Integer.parseInt(args[2]);
			} catch (NumberFormatException e) {
				throw new CmdSyntaxError("Invalid weight: " + args[2]);
			}

			int amount = 0;
			if (args.length > 3) try {
				amount = Integer.parseInt(args[3]);
			} catch (NumberFormatException e) {
				throw new CmdSyntaxError("Invalid amount: " + args[3]);
			}

			boolean clear = false;
			if (args.length > 4) clear = hasStr(args[4], "true", "ture", "t", "yes", "y");
			sign(type, weight, amount, clear);
		}
		case "run" -> run();
		default -> throw new CmdSyntaxError("Unknown subcommand: " + args[0]);
		}
	}

	public synchronized void callbackClickBlock(BlockPos pos) {
		if (status.get() != Status.SIGN) return;

		BlockEntity blockEntity = MC.world.getBlockEntity(pos);
		if (!(blockEntity instanceof ChestBlockEntity //
				|| blockEntity instanceof EnderChestBlockEntity//
				|| blockEntity instanceof ShulkerBoxBlockEntity //
				|| blockEntity instanceof BarrelBlockEntity))
			return;

		if (signing_waitingChest != null) {

			signing_waitingChest = null;
			ChatUtils.warning("The last waiting container content was not received.");
			ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");

			return;
		}

		if (config.cacheBlocks.containsKey(pos)) {
			ContaionerConfig cc = config.cacheBlocks.get(pos);

			cc.blocks.remove(pos);
			config.cacheBlocks.remove(pos);

			ChatUtils.message("A container has been removed from " + (cc == signing ? "now signing" : cc));

			return;
		}

		signing_waitingChest = pos;
		ChatUtils.message("Wait for the container to open...");
	}

	public synchronized void callbackInventory(List<ItemStack> items, int syncId) {
		switch (status.get()) {
		case SIGN -> {
			if ((signing_waitingSyncId == null) || (signing_waitingSyncId != syncId)) return;

			ItemList list = new ItemList();

			items.stream()//
					.limit(signing_waitingSize)//
					.filter(stack -> !stack.isEmpty())//
					.forEach(list::add);

			signing.blocks.put(signing_waitingChest, list);
			config.cacheBlocks.put(signing_waitingChest, signing);

			signing_waitingChest	= null;
			signing_waitingSyncId	= null;
			MC.player.closeScreen();
			ChatUtils.message("Container scanned successfully");
		}
		case RUN_SCANNING -> sorting.callbackInventory(items, syncId);
		default -> {
		}
		}
	}

	public synchronized boolean callbackOpenWindow(int syncId, int size) {
		return switch (status.get()) {
		case SIGN -> {
			if (signing_waitingChest == null) yield false;

			if (signing_waitingSyncId != null) {

				signing_waitingChest	= null;
				signing_waitingSyncId	= null;

				MC.player.closeScreen();
				ChatUtils.warning("The last waiting container content was not received.");
				ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");
				yield true;
			}

			signing_waitingSyncId	= syncId;
			signing_waitingSize		= size;

			yield true;
		}
		case RUN_SCANNING -> sorting.callbackOpenWindow(syncId, size);
		default -> false;
		};
	}

	/**
	 * new config
	 *
	 * @param confName config name
	 */
	private void createConf(String confName) throws CmdException {
		if (!status.compareAndSet(Status.IDLE, Status.IO)) throw new CmdError("Busy");
		try {
			if (config != null && !config.saved) ChatUtils.warning("Unsaved configuration discarded: " + (config.name == null ? "Unnamed" : config.name));
			config = new Config(confName);
		} finally {
			status.set(Status.IDLE);
		}
		ChatUtils.message("Create a new Config: " + confName);
	}

	/**
	 * load config
	 *
	 * @param confName config name
	 */
	private void loadConf(String confName) throws CmdException {
		if (!status.compareAndSet(Status.IDLE, Status.IO)) throw new CmdError("Busy");
		try {
			ServerInfo	server	= MC.getCurrentServerEntry();
			Path		path	= FOLDER.resolve(server == null ? DEF_GAME_FOLDER :				//
					(server.online && server.address != null ? server.address : server.name));
			path = path.resolve(confName);
			Config conf;
			try {
				conf		= Config.deserialize(JsonUtils.parseFile(path));
				conf.name	= confName;
				conf.flush();
			} catch (Throwable e) {
				e.printStackTrace();
				throw new CmdError("Can not load config: " + e);
			}
			config = conf;
			ChatUtils.message("Config loaded: " + confName);
		} finally {
			status.set(Status.IDLE);
		}
	}

	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks) {
		switch (status.get()) {
		case SIGN: {
			if (signing_lookingBox == null) break;
			// GL settings
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
			GL11.glEnable(GL11.GL_LINE_SMOOTH);
			GL11.glEnable(GL11.GL_CULL_FACE);
			GL11.glDisable(GL11.GL_DEPTH_TEST);

			matrixStack.push();
			RenderUtils.applyRegionalRenderOffset(matrixStack);

			BlockPos	camPos	= RenderUtils.getCameraBlockPos();
			int			regionX	= (camPos.getX() >> 9) * 512;
			int			regionZ	= (camPos.getZ() >> 9) * 512;

			RenderSystem.setShader(GameRenderer::getPositionShader);
			renderBoxes(matrixStack, COLOR_ARR, regionX, regionZ, signing_lookingBox);

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
			break;
		}
		default:
			break;
		}
	}

	@Override
	public void onRightClick(RightClickEvent event) {
		if (MC.crosshairTarget instanceof BlockHitResult) {
			callbackClickBlock(((BlockHitResult) MC.crosshairTarget).getBlockPos());
		}
	}

	@Override
	public void onUpdate() {
		switch (status.get()) {
		case SIGN: {
			signing_lookingBox = null;
			if (MC.crosshairTarget instanceof BlockHitResult) {

				BlockEntity blockEntity = MC.world.getBlockEntity(((BlockHitResult) MC.crosshairTarget).getBlockPos());

				if (blockEntity == null) return;

				signing_lookingBox = getBox(blockEntity);
			}
			break;
		}
		default:
			break;
		}
	}

	private void renderBoxes(MatrixStack matrixStack, float[] colorF, int regionX, int regionZ, Box... boxes) {
		for (Box box : boxes) {
			matrixStack.push();

			matrixStack.translate(box.minX - regionX, box.minY, box.minZ - regionZ);

			matrixStack.scale((float) (box.maxX - box.minX), (float) (box.maxY - box.minY), (float) (box.maxZ - box.minZ));

			RenderSystem.setShaderColor(colorF[0], colorF[1], colorF[2], 0.25F);

			Matrix4f	viewMatrix	= matrixStack.peek().getPositionMatrix();
			Matrix4f	projMatrix	= RenderSystem.getProjectionMatrix();
			Shader		shader		= RenderSystem.getShader();
			solidBox.setShader(viewMatrix, projMatrix, shader);

			RenderSystem.setShaderColor(colorF[0], colorF[1], colorF[2], 0.5F);
			outlinedBox.setShader(viewMatrix, projMatrix, shader);

			matrixStack.pop();
		}
	}

	private void renderInit(boolean enable) {

		Stream.of(solidBox, outlinedBox).filter(Objects::nonNull).forEach(VertexBuffer::close);
		if (enable) {
			solidBox	= new VertexBuffer();
			outlinedBox	= new VertexBuffer();
		}
	}

	private void run() throws CmdException {
		if (config == null) throw new CmdError("Empty configuration");
		if (!status.compareAndSet(Status.IDLE, Status.RUNNING)) throw new CmdError("Busy");

		config.flush();

		ChatUtils.message("Start sorting out warehouse items in " + config.allArea);

		sorting = new SortWarehouse(config, status);
	}

	/**
	 * save config
	 *
	 * @param confName config name
	 */
	private void saveConf(String confName) throws CmdException {
		if (!status.compareAndSet(Status.IDLE, Status.IO)) throw new CmdError("Busy");
		try {
			if (config == null) throw new CmdError("No configuration file was loaded");
			ServerInfo	server	= MC.getCurrentServerEntry();
			Path		path	= FOLDER.resolve(server == null ? DEF_GAME_FOLDER :				//
					(server.online && server.address != null ? server.address : server.name));

			try {
				Files.createDirectories(path);
			} catch (Throwable e) {
				e.printStackTrace();
				throw new CmdError("Unable to create folder: " + e);
			}

			path = path.resolve(confName != null && !confName.isEmpty() ? confName : //
					(config.name == null ? String.format("Unnamed-%s", System.currentTimeMillis()) : config.name));

			try (Writer writer = Files.newBufferedWriter(path, CharsetUtil.UTF_8)) {

				GSON.toJson(config.serialize(), writer);

			} catch (Throwable e) {
				e.printStackTrace();
				throw new CmdError("Can not save config: " + e);
			}

			config.saved = true;
			ChatUtils.message("Config is saved in: " + MC.runDirectory.toPath().relativize(path));
		} finally {
			status.set(Status.IDLE);
		}
	}

	/**
	 * Marking containers
	 *
	 * @param type   Represents the container type
	 * @param weight priority
	 * @param amount Maximum put in quantity (when putting in) / minimum reserved
	 *               quantity (when extracting)<br>
	 *               When the number is negative, the unit is group
	 * @param clear  When the type is output container, do you want to remove other
	 *               items not in this container classification list
	 */
	private void sign(ContaionerType type, int weight, int amount, boolean clear) throws CmdException {
		if (status.compareAndSet(Status.IDLE, Status.SIGN)) {
			if (config == null) throw new CmdError("Empty configuration");

			signing					= new ContaionerConfig(type, weight, amount, clear);
			signing_ChestESP_enable	= WURST.getHax().chestEspHack.isEnabled();
			WURST.getHax().chestEspHack.setEnabled(false);

			EVENTS.add(UpdateListener.class, this);
			EVENTS.add(RenderListener.class, this);
			EVENTS.add(RightClickListener.class, this);

			renderInit(true);

			ChatUtils.message("Enter sign mode");
		} else if (status.get() == Status.SIGN) {
			try {

				EVENTS.remove(UpdateListener.class, this);
				EVENTS.remove(RenderListener.class, this);
				EVENTS.remove(RightClickListener.class, this);

				renderInit(false);

				config.contaioners.stream()//
						.filter(signing::configEquals)//
						.findAny()//
						.ifPresentOrElse(signing::mergeTo, () -> config.contaioners.add(signing));
				config.flush();

				if (signing_ChestESP_enable) WURST.getHax().chestEspHack.setEnabled(true);

				ChatUtils.message("Exit sign mode " + (signing_ChestESP_enable ? ", ChestESP resumed" : ""));
			} finally {
				status.set(Status.IDLE);
			}
		} else throw new CmdError("Busy");
	}

}
