/**
 *
 */
package net.wurstclient.commands;

import java.awt.Color;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serial;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.lwjgl.opengl.GL11;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.systems.RenderSystem;

import io.netty.util.CharsetUtil;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.Material;
import net.minecraft.block.PressurePlateBlock;
import net.minecraft.block.TripwireBlock;
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
import net.minecraft.item.Item;
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
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.registry.Registry;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.json.JsonException;
import net.wurstclient.util.json.JsonUtils;

/**
 * Warehouse
 *
 * @author yuanlu
 *
 */
@SearchTags({ "warehouse", "sort", "chest", "ware", "item" })
public class WarehouseCmd extends Command {
	/**
	 * Collection of all container configurations
	 */
	private static final class Config {

		public static Config deserialize(JsonElement element) {
			Config		conf		= new Config();
			JsonArray	contaioners	= element.getAsJsonObject().getAsJsonArray("contaioners");

			contaioners.forEach(ele -> conf.contaioners.add(ContaionerConfig.deserialize(ele)));

			conf.flush();
			return conf;
		}

		public final ArrayList<ContaionerConfig>							contaioners	= new ArrayList<>();
		public transient final LinkedHashMap<BlockPos, ContaionerConfig>	cacheBlocks	= new LinkedHashMap<>();
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

		public void calculateOutputList() {
			Supplier<ItemList> itemlist = new Supplier<>() {
				private ItemList item;

				@Override
				public ItemList get() {
					if (item != null) return item;
					item = new ItemList();
					contaioners.stream()//
							.filter(cc -> cc.type != ContaionerType.OUTPUT)//
							.flatMap(cc -> cc.blocks.values().stream())//
							.forEach(item::putAll);
					;
					item.normalize();
					return item;
				}
			};

			contaioners.forEach(cc -> cc.flush(itemlist));
		}

		public void dumpBlocks() {
			cacheBlocks.clear();
			for (ContaionerConfig cc : contaioners) cc.blocks.keySet().forEach(pos -> cacheBlocks.put(pos, cc));
		}

		/** flush cache */
		public void flush() {
			dumpBlocks();
			calculateArea();
			calculateOutputList();
		}

		public JsonElement serialize() {
			JsonObject	obj			= new JsonObject();

			JsonArray	contaioners	= new JsonArray(this.contaioners.size());
			obj.add("contaioners", contaioners);

			this.contaioners.forEach(cc -> contaioners.add(cc.serialize()));

			return obj;
		}

	}

	/**
	 * Single container configuration
	 */
	private static final class ContaionerConfig implements Comparable<ContaionerConfig> {

		public static ContaionerConfig deserialize(JsonElement element) {
			JsonObject			obj		= element.getAsJsonObject();
			ContaionerConfig	cc		= new ContaionerConfig(					//
					ContaionerType.valueOf(obj.get("type").getAsString()),		//
					obj.get("weight").getAsInt(),								//
					InOutType.valueOf(obj.get("io").getAsString()),				//
					obj.get("amount").getAsInt(),								//
					obj.get("clear").getAsBoolean()								//
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
		public final InOutType							ioType;
		public final int								amount;

		public final boolean							clear;

		public ContaionerConfig(ContaionerType type, int weight, InOutType ioType, int amount, boolean clear) {
			this.type	= type;
			this.weight	= weight;
			this.ioType	= ioType == InOutType.ALL && type == ContaionerType.OUTPUT ? InOutType.COUNT_LIST : ioType;
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

		/**
		 * 
		 */
		private void flush(Supplier<ItemList> itemSupplier) {
			if (ioType == InOutType.OUTPUT_LIST) {

				ItemList itemList = itemSupplier.get();
				blocks.replaceAll((pos, old) -> itemList);

			}
		}

		public void mergeTo(ContaionerConfig cc) {
			if (!configEquals(cc)) throw new IllegalArgumentException("config not equal: this=" + this + ", other=" + cc);
			cc.blocks.putAll(this.blocks);
		}

		public JsonElement serialize() {
			JsonObject obj = new JsonObject();
			obj.add("type", new JsonPrimitive(type.name()));
			obj.add("weight", new JsonPrimitive(weight));
			obj.add("amount", new JsonPrimitive(amount));
			obj.add("clear", new JsonPrimitive(clear));
			obj.add("io", new JsonPrimitive(ioType.name()));
			JsonObject blocks = new JsonObject();
			this.blocks.forEach((b, i) -> blocks.add(Long.toUnsignedString(b.asLong(), Character.MAX_RADIX), i.serialize()));
			obj.add("blocks", blocks);
			return obj;
		}

		@Override
		public String toString() {
			return String.format("CC [t=%c, w=%s, io=%s, a=%s, c=%s]", type.name().charAt(0), weight, ioType, amount, clear ? "T" : "F");
		}
	}

	/**
	 * Container type, which determines the operation when interacting with it
	 */
	private static enum ContaionerType {
		/** input */
		INPUT(Color.green, "To remove items from containers", "input", "i", "in", "produce"),
		/** output */
		OUTPUT(Color.pink, "To pack items into containers", "output", "o", "out", "storage"),
		/** temp */
		TEMP(Color.orange, "Store temporary or not on the list items", "temp", "t", "tmp", "bad");

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

		private final ColorSetting	colorSetting;

		private final String		description;

		private final String[]		matchs;

		ContaionerType(Color color, String description, String... matchs) {
			this.description	= String.join("/", matchs) + ": " + description;
			this.matchs			= matchs;
			this.colorSetting	= new ColorSetting(name(), color);
		}
	}

	/**
	 * Go to the target location
	 */
	private static class GoToHelper implements UpdateListener, RenderListener {
		/** Just get close to the target */
		private static final class NearFinder extends PathFinder {

			private final boolean invulnerable = WurstClient.MC.player.getAbilities().creativeMode;

			public NearFinder(BlockPos goal) {
				super(goal);
			}

			@SuppressWarnings("deprecation")
			private boolean canGoThrough(BlockPos pos) {
				// check if loaded
				if (!WurstClient.MC.world.isChunkLoaded(pos)) return false;

				// check if solid
				Material	material	= BlockUtils.getState(pos).getMaterial();
				Block		block		= BlockUtils.getBlock(pos);
				if (material.blocksMovement() && !(block instanceof AbstractSignBlock)) return false;

				// check if trapped
				if (block instanceof TripwireBlock || block instanceof PressurePlateBlock) return false;

				// check if safe
				if (!invulnerable && (material == Material.LAVA || material == Material.FIRE)) return false;

				return true;
			}

			@Override
			protected boolean checkDone() {
				if (!canGoThrough(current)) return false;
				if (accurate) return super.checkDone();
				double	range		= Math.max(WarehouseCmd.RANGE.getValue() - 2, 2);
				double	eyeY		= MC.player.getEyeHeight(MC.player.getPose());
				Vec3i	currentEye	= new Vec3i(current.getX(), (int) (current.getY() + eyeY), current.getZ());
				double	disSqu		= currentEye.getSquaredDistance(getGoal());
				return done = disSqu < range * range;
			}

		}

		private static final CheckboxSetting USE_FLY = new CheckboxSetting("use fly", "Use flying instead of walking", false);

		private static final GoToHelper	INSTANCE	= new GoToHelper();
		private static boolean			flightEnabled;
		private static boolean			accurate;

		public static void goTo(BlockPos goal, Consumer<Boolean> callback, boolean accurate) {
			if (INSTANCE.enabled) INSTANCE.disable(false);
			INSTANCE.pathFinder	= new NearFinder(goal);
			INSTANCE.callback	= callback;
			GoToHelper.accurate	= accurate;

			// reset
			INSTANCE.updateCount = 0;

			// flight
			flightEnabled = WURST.getHax().flightHack.isEnabled();
			if (USE_FLY.isChecked()) WURST.getHax().flightHack.setEnabled(true);

			// start
			INSTANCE.enabled = true;
			EVENTS.add(UpdateListener.class, INSTANCE);
			EVENTS.add(RenderListener.class, INSTANCE);
		}

		public static void stop() {
			INSTANCE.disable(false);
		}

		private PathFinder			pathFinder;
		private PathProcessor		processor;

		private boolean				enabled;

		private Consumer<Boolean>	callback;

		private int					updateCount	= 0;

		private void disable(boolean success) {
			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);

			pathFinder	= null;
			processor	= null;
			PathProcessor.releaseControls();

			enabled = false;
			if (USE_FLY.isChecked()) WURST.getHax().flightHack.setEnabled(flightEnabled);
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

					double	range	= WarehouseCmd.RANGE.getValue();
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

				if (updateCount++ > 30) {
					ChatUtils.warning("Pathfinding failed: too many updates.");
					disable(false);
				} else {
					pathFinder = new NearFinder(pathFinder.getGoal());
				}

				return;
			}

			// process path
			processor.process();

			if (processor.isDone()) disable(true);
		}
	}

	/**
	 * Input / output type, which determines how many items should be taken out /
	 * put in.
	 */
	private static enum InOutType {
		/**
		 * Take out all, <b>OUTPUT container type is not supported</b>
		 * <p>
		 * <i>For the "OUTPUT" container, the use of the "ALL" type will cause all items
		 * to be stored in that container, which is unable to achieve the purpose of
		 * classification. Please use the "TEMP" container type.</i>
		 */
		ALL(false, ContaionerType.OUTPUT, "Access all item", "all", "a"),
		/**
		 * Use the list of items at the time of scanning and take out / store according
		 * to the additional specified amount
		 */
		ITEM_LIST(true, (ContaionerType) null, "Use list as item list", "item", "i", "il"),
		/**
		 * Use the list of items at the time of scanning and its count, which will keep
		 * the number of items in the list at the time of scanning.
		 */
		COUNT_LIST(true, (ContaionerType) null, "Use list as item and count list", "count", "c", "cl"),
		/**
		 * Scan all available output containers and automatically calculate the list of
		 * items to be taken (but the quantity will not be calculated)
		 */
		OUTPUT_LIST(true, new ContaionerType[] { ContaionerType.TEMP, ContaionerType.OUTPUT }, "Use a list of storable items", "output", "o", "ol");
		;

		public static final String		syntax;
		public static final InOutType[]	BY_ID	= values();
		static {
			StringJoiner stringJoiner = new StringJoiner("\n", "Unknown type:\n", "");
			for (InOutType type : values()) stringJoiner.add(type.description);
			syntax = stringJoiner.toString();
		}

		static InOutType get(String string) {
			for (InOutType type : BY_ID) if (hasStr(string, type.matchs)) return type;
			return null;
		}

		private final boolean	useList;
		private final boolean[]	reject;
		private final String	description;
		private final String[]	matchs;

		InOutType(boolean useList, ContaionerType reject, String description, String... matchs) {
			this(useList, reject == null ? new ContaionerType[0] : new ContaionerType[] { reject }, description, matchs);
		}

		InOutType(boolean useList, ContaionerType[] reject, String description, String... matchs) {
			this.useList	= useList;
			this.reject		= new boolean[ContaionerType.BY_ID.length];
			for (ContaionerType ct : reject) this.reject[ct.ordinal()] = true;
			this.description	= String.join("/", matchs) + ": " + description;
			this.matchs			= matchs;
		}

		public boolean reject(ContaionerType type) {
			return reject[type.ordinal()];
		}

	}

	/**
	 * Record item information (only save item name + item count)
	 */
	private static final class ItemList extends LinkedHashMap<String, Integer> implements Cloneable {
		@Serial private static final long serialVersionUID = -814964946466372693L;

		public static ItemList deserialize(JsonElement element) {
			ItemList list = new ItemList();
			element.getAsJsonObject().entrySet().forEach(e -> list.put(e.getKey(), e.getValue().getAsInt()));
			return list;
		}

		/**
		 * Gets a list of all item amounts that have been set to 1
		 * 
		 * @return itemList
		 */
		public static ItemList normalize(Collection<String> items) {
			ItemList itemList = new ItemList();
			items.forEach(item -> itemList.put(item, 1));
			return itemList;
		}

		/**
		 * Count the number of items
		 *
		 * @param filterEmpty Filter empty items
		 */
		public static ItemList of(Collection<ItemStack> list, boolean filterEmpty) {
			ItemList itemList = new ItemList();
			for (ItemStack item : list) {

				if (filterEmpty && (item == null || item.isEmpty())) continue;

				itemList.add(item);

			}
			return itemList;
		}

		/**
		 * Count the number of items
		 *
		 * @param filterEmpty Filter empty items
		 */
		public static ItemList of(ItemStack[] list, boolean filterEmpty) {
			ItemList itemList = new ItemList();
			for (ItemStack item : list) {

				if (filterEmpty && (item == null || item.isEmpty())) continue;

				itemList.add(item);

			}
			return itemList;
		}

		/**
		 * Count the number of items that can be put in
		 *
		 * @param addEmpty Allow empty slots to be placed
		 * @param allItems Collection of all item names
		 */
		public static ItemList ofFree(ItemStack[] list, boolean addEmpty, Set<String> allItems) {
			ItemList	itemList	= new ItemList();
			int			emptyCount	= 0;
			for (ItemStack item : list) {

				if (item == null || item.isEmpty()) {
					emptyCount++;
					continue;
				}

				int		empty	= Math.max(0, item.getMaxCount() - item.getCount());
				String	name	= Registry.ITEM.getId(item.getItem()).toString();
				itemList.compute(name, (name0, count) -> count == null ? empty : (count + empty));

			}
			if (allItems != null) for (String name : allItems) itemList.putIfAbsent(name, 0);

			if (addEmpty && emptyCount > 0) {

				final int								EmptyCount			= emptyCount;
				BiFunction<String, Integer, Integer>	addEmptyFunction	= (name, count) ->		//
				Registry.ITEM.get(Identifier.tryParse(name)).getMaxCount() * EmptyCount + count;

				for (String name : itemList.keySet().toArray(String[]::new)) itemList.compute(name, addEmptyFunction);

				itemList.put(null, EmptyCount);
			}
			return itemList;
		}

		public void add(ItemStack itemStack) {
			String	name	= Registry.ITEM.getId(itemStack.getItem()).toString();
			int		amount	= itemStack.getCount();
			compute(name, (ignore, old) -> old == null ? amount : (amount + old));
		}

		@Override
		public ItemList clone() {
			ItemList itemList = new ItemList();
			forEach(itemList::put);
			return itemList;
		}

		public boolean hasCanInput(PlayerInventory inv) {

			return inv.main.stream()//
					.map(ItemStack::getItem)//
					.map(Registry.ITEM::getId)//
					.map(Identifier::toString)//
					.map(this::get)//
					.anyMatch(count -> count != null && count > 0)//
			;

		}

		/**
		 * Check whether there are items in the player's backpack that can be put into
		 * this item list.
		 *
		 * @param cache Recorded contents of containers
		 */
		public boolean hasCanOutput(PlayerInventory inv, ItemList cache) {
			Stream<String> stream = inv.main.stream()//
					.map(ItemStack::getItem)//
					.map(Registry.ITEM::getId)//
					.map(Identifier::toString)//
			;

			if (cache != null) stream = stream.filter(name -> Objects.requireNonNullElse(cache.get(name), 0) > 0);

			return stream.anyMatch(this::containsKey);
		}

		/**
		 * Set all item amounts in the list to 1
		 */
		public void normalize() {
			replaceAll((item, amount) -> 1);
		}

		public void retainAll(ItemList itemList) {
			keySet().retainAll(itemList.keySet());
		}

		public JsonElement serialize() {
			JsonObject obj = new JsonObject();
			forEach((k, v) -> obj.add(k, new JsonPrimitive(v)));
			return obj;
		}
	}

	/**
	 * Sign Helper
	 *
	 * @author yuanlu
	 */
	private static final class SignWarehouse implements UpdateListener, RenderListener, RightClickListener {
		private static final ColorSetting		SEE_COLOR	= new ColorSetting("see color",
				"During signing, the identification color of the container location of what you see", Color.LIGHT_GRAY);

		private final Config					config;
		/** signing config */
		private final ContaionerConfig			contaionerConfig;
		/** ChestESP status keep */
		private final boolean					ChestESP_enable;
		private Box								lookingBox;
		private BlockPos						waitingChest;
		private Integer							waitingSyncId;
		private int								waitingSize;

		private final AtomicReference<Status>	status;

		private final ItemList					template;

		/**
		 * Marking containers
		 *
		 * @param config   Overall config
		 * @param status   status
		 * @param template Item list template to use
		 * @param type     Represents the container type
		 * @param weight   priority
		 * @param amount   Maximum put in quantity (when putting in) / minimum reserved
		 *                 quantity (when extracting)<br>
		 *                 When the number is negative, the unit is grouped
		 * @param clear    When the type is output container, do you want to remove
		 *                 other items not in this container classification list
		 * @param ioType
		 */
		public SignWarehouse(Config config, AtomicReference<Status> status, TemplateWarehouse template, ContaionerType type, int weight, InOutType ioType,
				int amount, boolean clear) {

			this.config			= Objects.requireNonNull(config, "config");
			this.status			= Objects.requireNonNull(status, "status");

			this.template		= template == null ? null : template.toItemList();

			contaionerConfig	= new ContaionerConfig(type, weight, ioType, amount, clear);
			ChestESP_enable		= WURST.getHax().chestEspHack.isEnabled();
			WURST.getHax().chestEspHack.setEnabled(false);

			EVENTS.add(UpdateListener.class, this);
			EVENTS.add(RenderListener.class, this);
			EVENTS.add(RightClickListener.class, this);

			ChatUtils.message("Enter sign mode");
		}

		private void addChest(BlockPos pos, ItemList itemList) {

			contaionerConfig.blocks.put(waitingChest, itemList);
			config.cacheBlocks.put(waitingChest, contaionerConfig);

			if (summary != null) summary.update();

		}

		public synchronized void callbackInventory(List<ItemStack> items, int syncId) {
			if ((waitingSyncId == null) || (waitingSyncId != syncId)) return;

			ItemList list = new ItemList();

			items.stream()//
					.limit(waitingSize)//
					.filter(stack -> !stack.isEmpty())//
					.forEach(list::add);

			addChest(waitingChest, list);

			waitingChest	= null;
			waitingSyncId	= null;
			MC.player.closeScreen();
			ChatUtils.message("Container scanned successfully");
		}

		public synchronized boolean callbackOpenWindow(int syncId, int size) {
			if (waitingChest == null) return false;

			if (waitingSyncId != null) {

				waitingChest	= null;
				waitingSyncId	= null;

				MC.player.closeScreen();
				ChatUtils.warning("The last waiting container content was not received.");
				ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");
				return true;
			}

			waitingSyncId	= syncId;
			waitingSize		= size;

			return true;
		}

		public void exit() {

			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);
			EVENTS.remove(RightClickListener.class, this);

			config.contaioners.stream()//
					.filter(contaionerConfig::configEquals)//
					.findAny()//
					.ifPresentOrElse(contaionerConfig::mergeTo, () -> config.contaioners.add(contaionerConfig));
			config.flush();

			if (ChestESP_enable) WURST.getHax().chestEspHack.setEnabled(true);

			ChatUtils.message("Exit sign mode " + (ChestESP_enable ? "§7, ChestESP resumed" : ""));
		}

		@Override
		public void onRender(MatrixStack matrixStack, float partialTicks) {
			if (lookingBox == null) return;
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
			renderBoxes(matrixStack, SEE_COLOR.getColorF(), regionX, regionZ, lookingBox);

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		@Override
		public void onRightClick(RightClickEvent event) {
			if ((status.get() != Status.SIGN) || !(MC.crosshairTarget instanceof BlockHitResult bhr)) return;
			BlockPos	pos			= bhr.getBlockPos();

			BlockEntity	blockEntity	= MC.world.getBlockEntity(pos);
			if (!(blockEntity instanceof ChestBlockEntity //
					|| blockEntity instanceof EnderChestBlockEntity//
					|| blockEntity instanceof ShulkerBoxBlockEntity //
					|| blockEntity instanceof BarrelBlockEntity))
				return;

			if (template != null) {

				addChest(pos, template.clone());
				ChatUtils.message("Successfully marked this container with the template.");

				return;

			}

			if (waitingChest != null) {

				waitingChest = null;
				ChatUtils.warning("The last waiting container content was not received.");
				ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");

				return;
			}

			if (config.cacheBlocks.containsKey(pos)) {
				ContaionerConfig cc = config.cacheBlocks.get(pos);

				cc.blocks.remove(pos);
				config.cacheBlocks.remove(pos);

				ChatUtils.message("A container has been removed from " + (cc == contaionerConfig ? "now signing" : cc));

				if (summary != null) summary.update();

				return;
			}

			waitingChest = pos;
			ChatUtils.message("Wait for the container to open...");
		}

		@Override
		public void onUpdate() {
			lookingBox = null;
			if (MC.crosshairTarget instanceof BlockHitResult) {

				BlockEntity blockEntity = MC.world.getBlockEntity(((BlockHitResult) MC.crosshairTarget).getBlockPos());

				if (blockEntity == null) return;

				lookingBox = getBox(blockEntity);
			}
		}

	}

	/**
	 * Sort Helper
	 *
	 * @author yuanlu
	 */
	private static final class SortWarehouse implements UpdateListener, RenderListener {
		private static final ColorSetting			GOAL_COLOR			= new ColorSetting("goal color",
				"During sorting, the identification color of the container location of the next interaction", Color.blue);
		private static final CheckboxSetting		SKIP_INVALID		= new CheckboxSetting("skip invalid chest",
				"Whether to skip the target container when it cannot be opened.", false);
		private static final CheckboxSetting		GO_BACK				= new CheckboxSetting("go back",
				"Whether to return to the starting point after sorting.", false);
		/** Compare the distance between the pos and the player */
		private static final Comparator<BlockPos>	COMPARATOR_DISTANCE	=													//
				Comparator.comparingDouble(pos -> pos.getSquaredDistance(MC.player.getPos(), false));

		/**
		 * Construct a function to return the required quantity of a specified item in
		 * the container according to the configuration.
		 */
		private static Function<String, Integer> getAmountFactory(ItemList configList, ContaionerConfig cc) {
			int configAmount = cc.amount;
			switch (cc.ioType) {
			case ALL:
			case ITEM_LIST:
			case OUTPUT_LIST:
				if (configAmount > 0) {
					// amount
					return name -> configAmount;
				} else {
					// group amount
					return name -> Registry.ITEM.get(Identifier.tryParse(name)).getMaxCount() * -configAmount;
				}
			case COUNT_LIST:
				// keep original amount
				return configList::get;
			default:
				throw new InternalError("Uncapped enumeration types:" + cc.ioType);
			}
		}

		private final AutoStealHack								autoSteal		= WURST.getHax().autoStealHack;
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

		/** close callback */
		private CompletableFuture<Boolean>						screenCloseListener;
		/** The number of items need to stored in the specified container. */
		private final HashMap<BlockPos, ItemList>				posCache		= Maps.newHashMap();
		/** Container content cache */
		private final Map<BlockPos, ItemList>					containerCache	= Maps.newLinkedHashMap();

		private Box												goalBox;

		private long											renderSwitchLast;

		private boolean											renderSwitch;

		public SortWarehouse(Config config, AtomicReference<Status> status) {
			if (status.get() != Status.RUNNING) throw new IllegalStateException(status.get().name());
			/** configs */
			this.contaioners	= new LinkedHashMap<>();
			this.status			= status;

			ArrayList<ContaionerConfig> contaioners = new ArrayList<>(config.contaioners);
			contaioners.sort(Collections.reverseOrder());
			contaioners.forEach(cc -> {
				cc.blocks.keySet().forEach(pos -> this.contaioners.put(pos, cc));
			});

			final BlockPos startPos = MC.player.getBlockPos();

			WarehouseCmd.containerCache = containerCache;

			setNext(this::startOnce, "start");
			new Thread("Warehouse Sorter-" + config.hashCode()) {
				@Override
				public void run() {
					try {
						while (!stop && next != null) {

							Runnable task = next;
							next = null;

							System.out.println("Call Task: " + nextName);
							task.run();

						}
						if (!stop && GO_BACK.isChecked()) {
							ChatUtils.message("Done. Now go back to the starting point.");
							goTo(startPos, true);
						} else {
							ChatUtils.message("Done.");
						}
						System.out.println("Finish!");
					} catch (Throwable e) {
						ChatUtils.error("An error occurred: " + e);
						e.printStackTrace();
					} finally {
						status.set(Status.IDLE);
					}

				}
			}.start();
		}

		public void callbackInventory(List<ItemStack> items, int syncId) {
			if (stop) return;
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
			if (stop) return false;
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

		/** close screen in main thread */
		private void callCloseScreen() {
			if (stop) return;
			screenCloseListener = new CompletableFuture<>();
			EVENTS.add(UpdateListener.class, this);
			try {
				screenCloseListener.get();
				waitForDelay();
			} catch (Throwable e) {
				CrashReport crashReport = CrashReport.create(e, "Close Screen Future Waiting");
				throw new CrashException(crashReport);
			}
		}

		/**
		 * Look for an input container that is not explicitly empty, go to scan, and
		 * then take out the items.
		 */
		private void doInput() {
			if (stop) return;
			status.set(Status.RUNNING);
			var	stream	= contaioners											//
					.entrySet().stream()										//
					.filter(e -> e.getValue().type == ContaionerType.INPUT)		//
			;
			var	next	= getSorted(stream)										//
					.findFirst().orElse(null);

			if (next == null) {
				if (!isInvEmpty(true)) setNext(this::doOutput, "Output - by No Input");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error

			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) {

				if (SKIP_INVALID.isChecked()) {

					contaioners.remove(next.getKey());
					setNext(this::startOnce, "restart - by invalid input");

				}

				return;

			}

			ContaionerConfig	cc		= next.getValue();
			boolean				isEmpty	= steal(cc.blocks.get(next.getKey()), cc, list);
			callCloseScreen();

			if (isEmpty)// The input container is empty. Ignored in this run.
				contaioners.remove(next.getKey());

			setNext(this::startOnce, "restart - by Input Once");
		}

		/**
		 * Find a container that matches the items in the inventory, go to scan, and
		 * then put in the items.
		 */
		private void doOutput() {
			if (stop) return;
			status.set(Status.RUNNING);
			PlayerInventory	inv		= MC.player.getInventory();

			var				stream	= contaioners																	//
					.entrySet().stream()																			//
					.filter(e -> e.getValue().type == ContaionerType.OUTPUT)										//
					.filter(e -> e.getValue().blocks.get(e.getKey()).hasCanOutput(inv, posCache.get(e.getKey())))	//
			;
			var				next	= getSorted(stream)																//
					.findFirst().orElse(null);																		//

			if (next == null) {
				setNext(this::doTemp, "Temp - by No Output");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error

			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) {

				if (SKIP_INVALID.isChecked()) {

					contaioners.remove(next.getKey());
					setNext(this::startOnce, "restart - by invalid output");

				}

				return;

			}

			ContaionerConfig	cc			= next.getValue();
			ItemList			configList	= cc.blocks.get(next.getKey());
			store(configList, cc, list);
			callCloseScreen();

			if (!scanChest(next.getKey(), configList, cc)) return;

			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Output Finish");
			else setNext(this::doOutput, "Output - by Output Once");

		}

		/**
		 * Put all items that cannot be stored in the inventory into a temporary
		 * container with an empty space.
		 */
		private void doTemp() {
			if (stop) return;
			status.set(Status.RUNNING);
			PlayerInventory	inv		= MC.player.getInventory();

			var				stream	= contaioners																	//
					.entrySet().stream()																			//
					.filter(e -> e.getValue().type == ContaionerType.TEMP)											//
					.filter(e -> posCache.get(e.getKey()) == null || posCache.get(e.getKey()).hasCanInput(inv))		//
			;
			var				next	= getSorted(stream)																//
					.findFirst().orElse(null);

			if (next == null) {
				ChatUtils.error("All temporary containers are full, cannot continue.");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error
			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) {

				if (SKIP_INVALID.isChecked()) {

					contaioners.remove(next.getKey());
					setNext(this::startOnce, "restart - by invalid temp");

				}

				return;

			}

			store(list);
			callCloseScreen();

			if (!scanChest(next.getKey())) return;

			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Temp Finish");
			else setNext(this::doTemp, "Temp - by Temp Once");

		}

		public void exit() {
			stop = true;
			GoToHelper.stop();
		}

		private Stream<Entry<BlockPos, ContaionerConfig>> getSorted(Stream<Entry<BlockPos, ContaionerConfig>> stream) {
			return stream.sorted((l, r) -> {
				int ccCmp = r.getValue().compareTo(l.getValue());// 反向排序 Reverse sort
				if (ccCmp != 0) return ccCmp;
				return COMPARATOR_DISTANCE.compare(l.getKey(), r.getKey());
			});
		}

		/** @return success */
		private boolean goTo(BlockPos pos) {
			return goTo(pos, false);
		}

		/**
		 * @param accurate The target location must be reached
		 * @return success
		 */
		private boolean goTo(BlockPos pos, boolean accurate) {
			if (stop) return false;
			status.set(Status.RUN_MOVING);
			if (pos.getSquaredDistance(MC.player.getBlockPos()) < Math.pow(RANGE.getValue(), 2)) return true;

			goalBox = getBoundingBox(pos);
			EVENTS.add(RenderListener.class, this);

			CompletableFuture<Boolean> future = new CompletableFuture<>();
			GoToHelper.goTo(pos, future::complete, accurate);
			try {
				return future.get() && !stop;
			} catch (Throwable e) {
				CrashReport crashReport = CrashReport.create(e, "Goto Future Waiting");
				throw new CrashException(crashReport);
			} finally {
				goalBox = null;
				EVENTS.remove(RenderListener.class, this);
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
		public void onRender(MatrixStack matrixStack, float partialTicks) {
			if (goalBox == null) return;
			long flashSpeed = FLASH_SPEED.getValueI();
			if (flashSpeed > 0) {

				long nowTime = System.currentTimeMillis();
				if (nowTime - renderSwitchLast > flashSpeed) {
					renderSwitch		= !renderSwitch;
					renderSwitchLast	= nowTime;
				}
				if (!renderSwitch) return;

			}

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
			renderBoxes(matrixStack, GOAL_COLOR.getColorF(), regionX, regionZ, goalBox);

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		@Override
		public void onUpdate() {
			if (screenCloseListener != null) {

				MC.player.closeScreen();

				screenCloseListener.complete(Boolean.TRUE);

			}
			EVENTS.remove(UpdateListener.class, this);
		}

		/**
		 * Blocking method. Will try to simulate the player to open the container and
		 * get the list of items in the container.
		 */
		private ItemStack[] openContaioner(BlockPos pos) {
			if (stop) return null;
			status.set(Status.RUN_SCANNING);
			try {
				waitingChest = new CompletableFuture<>();

				if (!rightClickBlockSimple(pos)) {
					ChatUtils.error("Can not open Chest");
					return null;
				}
				return waitingChest.get(TIME_OUT.getValueI(), TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				ChatUtils.error("Opening Chest timed out");
				return null;
			} catch (Throwable e) {
				CrashReport crashReport = CrashReport.create(e, "Opening " + pos.toShortString());
				throw new CrashException(crashReport);
			} finally {
				waitingSyncId	= null;
				waitingChest	= null;
			}
		}

		/**
		 * Pick up a specified number of items in a slot with the least number of times.
		 *
		 * <p>
		 * Pick up all / half first, and then put down some items
		 */
		private void pickUpItem(int slot, int nowCount, int needCount) {
			if (nowCount <= needCount) throw new IllegalArgumentException("Bad Count: " + nowCount + " <= " + needCount);

			int halfNowCount = nowCount / 2 + (nowCount & 1);

			if (needCount <= halfNowCount) {

				MC.interactionManager.clickSlot(nowSyncId, slot, 1, SlotActionType.PICKUP, MC.player);
				waitForDelay();

				needCount = halfNowCount - needCount;

			} else {

				MC.interactionManager.clickSlot(nowSyncId, slot, 0, SlotActionType.PICKUP, MC.player);
				waitForDelay();

				needCount = nowCount - needCount;

			}

			while (needCount-- > 0) {

				MC.interactionManager.clickSlot(nowSyncId, slot, 1, SlotActionType.PICKUP, MC.player);
				waitForDelay();

			}

		}

		/**
		 * Put the picked up items into the specified grid, which can be combined with
		 * the picked up items.
		 * <p>
		 * For a single slot, simply right-click to drop it. For multiple slots, use the
		 * drag mode
		 */
		private void putDownItem(ArrayList<Integer> clickSlots) {
			if (clickSlots.size() == 1) {

				MC.interactionManager.clickSlot(nowSyncId, clickSlots.get(0), 0, SlotActionType.PICKUP, MC.player);
				waitForDelay();

				return;

			}

			MC.interactionManager.clickSlot(nowSyncId, clickSlots.get(0), 0, SlotActionType.QUICK_CRAFT, MC.player);
			waitForDelay();

			for (int i = 1; i < clickSlots.size(); i++) {

				MC.interactionManager.clickSlot(nowSyncId, clickSlots.get(i), 1, SlotActionType.QUICK_CRAFT, MC.player);
				waitForDelay();

			}

			MC.interactionManager.clickSlot(nowSyncId, -1, 2, SlotActionType.QUICK_CRAFT, MC.player);
			waitForDelay();

		}

		/**
		 * The logic that interacts with the right button of the box is copied from
		 * {@code TillauraHack}
		 */
		private boolean rightClickBlockSimple(BlockPos pos) {
			if (stop) return false;
			Vec3d	eyesPos				= RotationUtils.getEyesPos();
			Vec3d	posVec				= Vec3d.ofCenter(pos);
			double	distanceSqPosVec	= eyesPos.squaredDistanceTo(posVec);
			double	rangeSq				= Math.pow(RANGE.getValue(), 2);

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

		/**
		 * Scan the container (temporary type). When the container has any empty slot,
		 * set the cache to null (representing that at least one item of any type can be
		 * stored). Only when all slots in the container are occupied, the type and
		 * quantity of items that will be stored (the slot that are less than a group
		 * but occupy one slot) will be recorded
		 */
		private boolean scanChest(BlockPos pos) {

			if (!goTo(pos)) return false;// Not Found or Error

			ItemStack[] list = openContaioner(pos);
			if (list == null) {

				if (SKIP_INVALID.isChecked()) contaioners.remove(pos);
				posCache.remove(pos);
				return !SKIP_INVALID.isChecked();

			}

			try {

				for (ItemStack itemStack : list) if (itemStack == null || itemStack.isEmpty()) {

					posCache.remove(pos);
					return true;

				}

				ItemList freeList = ItemList.ofFree(list, true, null);// 当前可插入数量
				posCache.put(pos, freeList);

			} finally {
				callCloseScreen();
			}

			return true;

		}

		/**
		 * Scan containers and update the contents of container items to prevent finding
		 * invalid output containers
		 */
		private boolean scanChest(BlockPos pos, ItemList configList, ContaionerConfig cc) {

			if (!goTo(pos)) return false;// Not Found or Error

			ItemStack[] list = openContaioner(pos);
			if (list == null) {

				if (SKIP_INVALID.isChecked()) contaioners.remove(pos);
				posCache.remove(pos);
				return !SKIP_INVALID.isChecked();

			}

			try {

				ItemList itemList = ItemList.of(list, true);// 记录目前有的数量
				itemList.retainAll(configList);

				ItemList freeList = ItemList.ofFree(list, true, configList.keySet());// 当前可插入数量
				freeList.retainAll(configList);

				Function<String, Integer> amountFactory = getAmountFactory(configList, cc);// 所需数量

				for (String name : configList.keySet()) {

					int	needCount	= Math.max(amountFactory.apply(name) - itemList.getOrDefault(name, 0), 0);
					int	canCount	= Math.min(needCount, freeList.get(name));

					itemList.put(name, canCount);

				}
				System.out.println("scan Cache: pos=" + pos + ", list=" + itemList);
				posCache.put(pos, itemList);

			} finally {
				callCloseScreen();
			}
			return true;

		}

		private void setNext(Runnable runnable, String debugName) {
			if (stop) return;
			next		= runnable;
			nextName	= debugName;
		}

		/**
		 * Single process start, cycle start point
		 */
		private void startOnce() {
			if (stop) return;
			if (isInvEmpty(false)) setNext(this::doInput, "Input - by start");
			else setNext(this::doOutput, "Output - by start");
		}

		/**
		 * According to the configuration, take the specified number of items from the
		 * opened container
		 *
		 * @param stealList Specified steal list
		 * @param cc        Specified config
		 * @param itemList  Container contents
		 * @return all clear
		 */
		private boolean steal(ItemList stealList, ContaionerConfig cc, ItemStack[] itemList) {

			// need takes
			List<ItemStack>	inventory	= MC.player.getInventory().main;
			ItemList		takeList	= ItemList.of(itemList, true);

			if (cc.ioType.useList) takeList.retainAll(stealList);

			Function<String, Integer> targetAmountGetter = getAmountFactory(stealList, cc);

			for (Entry<String, Integer> e : takeList.entrySet()) {

				String name = e.getKey();

				e.setValue(e.getValue() - targetAmountGetter.apply(name));

			}

			// check empty take
			if (takeList.isEmpty()) return true;
			boolean noTake = true;
			for (Integer count : takeList.values()) {

				if (count != null && count > 0) {
					noTake = false;
					break;
				}

			}

			if (noTake) return true;

			// take items
			boolean notEmpty = false;
			for (int i = 0; i < itemList.length; i++) {

				ItemStack item = itemList[i];
				if (item == null || item.isEmpty()) continue;

				String	name	= Registry.ITEM.getId(item.getItem()).toString();
				Integer	amount	= takeList.get(name);
				if (amount == null || amount <= 0) continue;

				if (item.getCount() <= amount) {
					// take all items in this slot
					amount -= item.getCount();
					MC.interactionManager.clickSlot(nowSyncId, i, 0, SlotActionType.QUICK_MOVE, MC.player);

					if (item.getCount() > 0) notEmpty = true;
					waitForDelay();

					if (amount > 0) takeList.put(name, amount);
					else takeList.remove(name);

				} else {
					// Processing logic for taking some items
					Item				itemType	= item.getItem();
					int					canPutCount	= 0;
					ArrayList<Integer>	putSlots	= new ArrayList<>();

					for (int j = 0; j < 36 && canPutCount < amount; j++) {

						ItemStack invItemStack = inventory.get(j);

						if (!invItemStack.isOf(itemType) || (invItemStack.getCount() >= invItemStack.getMaxCount())) continue;

						canPutCount += itemType.getMaxCount() - invItemStack.getCount();
						putSlots.add(itemList.length + (j < 9 ? (j + 27) : (j - 9)));

					}
					for (int j = 0; j < 36 && canPutCount < amount; j++) {

						if (!inventory.get(j).isEmpty()) continue;

						canPutCount += itemType.getMaxCount();
						putSlots.add(itemList.length + (j < 9 ? (j + 27) : (j - 9)));

					}

					System.out.println("[部分物品] " + canPutCount + ", " + putSlots);

					if (canPutCount <= 0 || putSlots.isEmpty()) continue;

					pickUpItem(i, item.getCount(), Math.min(amount, canPutCount));
					putDownItem(putSlots);

					if (canPutCount >= amount) takeList.remove(name);
				}

			}
			return !notEmpty;

		}

		/**
		 * According to the configuration, put the specified number of items to the
		 * opened container
		 *
		 * @param storeList   Specified store list
		 * @param storeAmount Specified store amount
		 * @param itemList    Container contents
		 */
		private void store(ItemList storeList, ContaionerConfig cc, ItemStack[] itemList) {

			// need puts
			PlayerInventory	inventory	= MC.player.getInventory();
			ItemList		putList		= ItemList.of(inventory.main, true);
			ItemList		chestList	= ItemList.of(itemList, true);

			putList.retainAll(storeList);

			Function<String, Integer> targetAmountGetter = getAmountFactory(storeList, cc);

			for (Entry<String, Integer> e : putList.entrySet()) {

				String name = e.getKey();

				e.setValue(targetAmountGetter.apply(name) - chestList.getOrDefault(name, 0));

			}

			// put items
			for (int i = 0; i < 36; i++) {

				ItemStack item = inventory.main.get(i);
				if (item == null || item.isEmpty()) continue;

				String	name	= Registry.ITEM.getId(item.getItem()).toString();
				Integer	amount	= putList.get(name);
				if (amount == null || amount <= 0) continue;

				int slotId = itemList.length + (i < 9 ? (i + 27) : (i - 9));

				System.out.printf("store Item: slot=%d, count=%d, tar=%d\n", slotId, item.getCount(), amount);
				if (item.getCount() <= amount) {

					amount -= item.getCount();
					MC.interactionManager.clickSlot(nowSyncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
					waitForDelay();

					if (amount > 0) putList.put(name, amount);
					else putList.remove(name);

				} else {
					// Processing logic for putting some items

					Item				itemType	= item.getItem();
					int					canPutCount	= 0;
					ArrayList<Integer>	putSlots	= new ArrayList<>();

					for (int j = 0; j < itemList.length && canPutCount < amount; j++) {

						ItemStack invItemStack = itemList[j];

						if (!invItemStack.isOf(itemType) || (invItemStack.getCount() >= invItemStack.getMaxCount())) continue;

						canPutCount += itemType.getMaxCount() - invItemStack.getCount();
						putSlots.add(j);
					}

					for (int j = 0; j < itemList.length && canPutCount < amount; j++) {

						ItemStack invItemStack = itemList[j];

						if (!invItemStack.isEmpty()) continue;

						canPutCount += itemType.getMaxCount();
						putSlots.add(j);

					}

					System.out.println("[部分物品] " + canPutCount + ", " + putSlots);

					if (canPutCount <= 0 || putSlots.isEmpty()) continue;

					pickUpItem(slotId, item.getCount(), Math.min(amount, canPutCount));
					putDownItem(putSlots);

					if (canPutCount >= amount) putList.remove(name);

					putList.remove(name);
				}

			}

		}

		/**
		 * Store all the items in the inventory into the open container
		 */
		private void store(ItemStack[] itemList) {
			List<ItemStack> inventory = MC.player.getInventory().main;

			for (int i = 0; i < 36; i++) {

				ItemStack itemStack = inventory.get(i);
				if (itemStack == null || itemStack.isEmpty()) continue;

				int slotId = itemList.length + (i < 9 ? (i + 27) : (i - 9));
				MC.interactionManager.clickSlot(nowSyncId, slotId, 0, SlotActionType.QUICK_MOVE, MC.player);
				waitForDelay();

			}
		}

		/**
		 * When operating in the container, execute the function of single delay and use
		 * the setting of {@link AutoStealHack} to prevent too fast operation.
		 */
		private void waitForDelay() {
			long delay = autoSteal.getDelay();
			if (delay > 0) try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				CrashReport crashReport = CrashReport.create(e, "Thread Sleep");
				throw new CrashException(crashReport);
			}
		}
	}

	/** Running status */
	public enum Status {
		IDLE("idle"), //
		SIGN("signing container"), //
		IO("File reading or writing"), //
		RUNNING("Internal calculation in progress"), //
		RUN_MOVING("Moving to the next container"), //
		RUN_SCANNING("Scanning container"), //
		RUN_PICKING("Removing items from container"), //
		RUN_PUTTING("Putting items into container");

		public final String		description;
		public final boolean	runStatus	= name().toLowerCase().indexOf("run") >= 0;

		Status(String description) {
			this.description = Objects.requireNonNull(description);
		}

		boolean isRunStatus() {
			return runStatus;
		}

	}

	/**
	 * Summarize the warehouse (currently under development)
	 * <p>
	 * Container classification display has been implemented
	 * <p>
	 * TODO:
	 * <li>Display status</li>
	 * <li>Display container status (for scanned containers, display items and
	 * quantity that can be stored or removed)</li>
	 * <li>Warehouse list page</li>
	 * <li>Operation progress</li>
	 */
	private static final class SummaryWarehouse implements RenderListener {
		private final EnumMap<ContaionerType, ArrayList<Box>>	chests	= new EnumMap<>(ContaionerType.class);
		private final Config									config;

		public SummaryWarehouse(Config config, boolean silent) {

			config.flush();
			this.config = config;

			for (ContaionerType ct : ContaionerType.BY_ID) {

				chests.put(ct, new ArrayList<>());

			}
			update();

			EVENTS.add(RenderListener.class, this);

			if (!silent) ChatUtils.message("Enter summary mode");
		}

		public void exit(boolean silent) {
			EVENTS.remove(RenderListener.class, this);

			if (!silent) ChatUtils.message("Exit summary mode");
		}

		@Override
		public void onRender(MatrixStack matrixStack, float partialTicks) {
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
			for (ContaionerType ct : ContaionerType.BY_ID) {

				renderBoxes(matrixStack, ct.colorSetting.getColorF(), regionX, regionZ, chests.get(ct));

			}

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		private void update() {

			chests.values().forEach(ArrayList::clear);

			for (var e : config.cacheBlocks.entrySet()) {

				chests.get(e.getValue().type).add(getBoundingBox(e.getKey()));

			}

		}

	}

	/**
	 * Template warehouse<br>
	 * Preset a variety of item lists
	 */
	private static final class TemplateWarehouse {
		private static final TreeMap<String, TemplateWarehouse>	DEFAULT	= new TreeMap<>();
		private static final TreeMap<String, TemplateWarehouse>	USER	= new TreeMap<>();
		private static final Path								FOLDER	= WarehouseCmd.FOLDER.resolve("template");

		static {
			/** TODO add default template here */
			addDefault("stone", //
					"stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite", "cobblestone");

		}
		/** Used to save the default template sample */
		static {
			Path path = FOLDER;
			try {
				if (!Files.exists(FOLDER)) {

					Files.createDirectories(FOLDER);

					for (var e : DEFAULT.entrySet()) {

						path = FOLDER.resolve(e.getKey() + ".json");

						try (var in = Files.newBufferedWriter(path)) {
							GSON.toJson(e.getValue().itemList, in);
						}

					}

				}
			} catch (Throwable e) {
				System.err.println("Could not access File/Folder: " + path);
				e.printStackTrace();
			}
		}

		private static void addDefault(String name, String... items) {
			DEFAULT.put(name, new TemplateWarehouse(items));

		}

		private static void addUser(String name, ArrayList<String> items) {
			USER.put(name, new TemplateWarehouse(items.toArray(String[]::new)));
		}

		public static TemplateWarehouse getTemplate(String name) {
			if (!USER.containsKey(name)) {

				var path = FOLDER.resolve(name + ".json");
				try {
					var json = JsonUtils.parseFileToArray(path);
					addUser(name, json.getAllStrings());
				} catch (FileNotFoundException e) {
				} catch (IOException | JsonException e) {
					ChatUtils.error("Couldn't load " + MC.runDirectory.toPath().relativize(path));
					e.printStackTrace();
				}
			}
			var user = USER.get(name);
			return user == null ? DEFAULT.get(name) : user;
		}

		private final Set<String> itemList;

		private TemplateWarehouse(String... items) {
			itemList = Arrays.stream(items)//
					.filter(Objects::nonNull)//
					.map(s -> s.indexOf(':') < 0 ? "minecraft:" + s : s)//
					.collect(Collectors.toSet());
		}

		public ItemList toItemList() {
			return ItemList.normalize(itemList);
		}

	}

	/**
	 * Where Helper
	 * 
	 * @author yuanlu
	 */
	private static final class WhereWarehouse implements UpdateListener, RenderListener {
		private static final ColorSetting					RULE_COLOR		= new ColorSetting("where (from rules)",
				"Color used for marking when displaying hand-held items\n§6Specify by config file", Color.blue);
		private static final ColorSetting					CACHE_COLOR		= new ColorSetting("where (from cache)",
				"Color used for marking when displaying hand-held items\n§6Specify by sorting cache", Color.red);

		private final HashMap<String, ArrayList<BlockPos>>	itemWhere		= new HashMap<>();

		private ItemStack									lastItemStack	= ItemStack.EMPTY;

		private final ArrayList<Box>						renderBoxsConf	= new ArrayList<>();
		private final ArrayList<Box>						renderBoxsCache	= new ArrayList<>();

		private long										renderSwitchLast;
		private boolean										renderSwitch;

		public WhereWarehouse(Config config, boolean silent) {

			config.flush();

			for (var cc : config.contaioners) {

				for (var e : cc.blocks.entrySet()) {

					BlockPos pos = e.getKey();
					for (var item : e.getValue().keySet()) {

						itemWhere.computeIfAbsent(item, k -> new ArrayList<>())//
								.add(pos);

					}

				}

			}

			EVENTS.add(RenderListener.class, this);
			EVENTS.add(UpdateListener.class, this);

			if (!silent) ChatUtils.message("Enter where mode");
		}

		public void exit(boolean silent) {
			EVENTS.remove(RenderListener.class, this);
			EVENTS.remove(UpdateListener.class, this);

			if (!silent) ChatUtils.message("Exit where mode");
		}

		@Override
		public void onRender(MatrixStack matrixStack, float partialTicks) {
			if (renderBoxsCache.isEmpty() && renderBoxsConf.isEmpty()) return;
			long flashSpeed = FLASH_SPEED.getValueI();
			if (flashSpeed > 0) {

				long nowTime = System.currentTimeMillis();
				if (nowTime - renderSwitchLast > flashSpeed) {
					renderSwitch		= !renderSwitch;
					renderSwitchLast	= nowTime;
				}
				if (!renderSwitch) return;

			}

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
			renderBoxes(matrixStack, RULE_COLOR.getColorF(), regionX, regionZ, renderBoxsConf);
			renderBoxes(matrixStack, CACHE_COLOR.getColorF(), regionX, regionZ, renderBoxsCache);

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		@Override
		public void onUpdate() {

			ItemStack itemStack = MC.player.getMainHandStack();

			if (itemStack == null) return;
			if (ItemStack.areEqual(lastItemStack, itemStack)) return;
			lastItemStack = itemStack;

			renderBoxsConf.clear();
			renderBoxsCache.clear();

			if (itemStack.isEmpty()) return;

			var	name			= Registry.ITEM.getId(itemStack.getItem()).toString();
			var	containerCache	= WarehouseCmd.containerCache;

			if (containerCache != null) {

				containerCache.entrySet().stream()//
						.filter(e -> e.getValue().containsKey(name))//
						.map(e -> e.getKey())//
						.map(WarehouseCmd::getBoundingBox)//
						.forEach(renderBoxsCache::add);

			}
			var byConfList = itemWhere.get(name);
			if (byConfList != null) {

				var stream = byConfList.stream();
				if (containerCache != null) stream = stream.filter(Predicate.not(containerCache::containsKey));
				stream.map(WarehouseCmd::getBoundingBox).forEach(renderBoxsConf::add);

			}

		}

	}

	private static final SliderSetting		RANGE		= new SliderSetting("Range", "Maximum radius to interact with the block", 5, 1, 6, 0.05,
			ValueDisplay.DECIMAL);

	private static final SliderSetting		TIME_OUT	= new SliderSetting("Time Out", "Maximum delay allowed for interactive operation (unit: ms)", 3000, 100,
			1000 * 20, 1, ValueDisplay.INTEGER);

	private static final SliderSetting		FLASH_SPEED	= new SliderSetting("flash", "Flashing speed of identification location", 500, 0, 1000, 1,
			ValueDisplay.INTEGER);

	private static final Gson				GSON		= new GsonBuilder().setPrettyPrinting().create();

	private static final String				DEF_DIR		= "default";

	private static final Path				FOLDER		= WURST.getWurstFolder().resolve("warehouse");

	private static Map<BlockPos, ItemList>	containerCache;

	/** RENDER */
	private static VertexBuffer				solidBox;

	private static VertexBuffer				outlinedBox;

	/** sort helper */
	private static SortWarehouse			sorting;

	/** sign helper */
	private static SignWarehouse			signing;

	/** summary helper */
	private static SummaryWarehouse			summary;

	/** where helper */
	private static WhereWarehouse			where;

	private static Box getBoundingBox(BlockPos pos) {
		return new Box(pos.getX(), pos.getY(), pos.getZ(), //
				pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
	}

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
	private static String merge(String[] args, int startIndex) {

		StringJoiner sj = new StringJoiner(" ");
		while (startIndex < args.length) sj.add(args[startIndex++]);
		return sj.toString();
	}

	private static void renderBoxes(MatrixStack matrixStack, float[] colorF, int regionX, int regionZ, ArrayList<Box> boxes) {

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

	private static void renderBoxes(MatrixStack matrixStack, float[] colorF, int regionX, int regionZ, Box box) {

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

	private static void renderInit() {

		if (solidBox != null) return;
		synchronized (WarehouseCmd.class) {

			if (solidBox != null) return;

			solidBox	= new VertexBuffer();
			outlinedBox	= new VertexBuffer();

			Box box = new Box(BlockPos.ORIGIN);
			RenderUtils.drawSolidBox(box, solidBox);
			RenderUtils.drawOutlinedBox(box, outlinedBox);
		}
	}

	/** Command running status */
	private final AtomicReference<Status>	status	= new AtomicReference<>(Status.IDLE);

	/** config */
	private Config							config;

	public WarehouseCmd() {
		super("warehouse", //
				"Automatically organize the boxes near you and summarize all kinds of items into their places", //
				".warehouse new <name> §7Create a warehouse configuration", //
				".warehouse load <name> §7Load a warehouse configuration", //
				".warehouse save [name] §7Save a warehouse configuration", //
//				".warehouse sign <type> [w] [a] [c] §7Enable container tagging", //
				".warehouse markmode <type> [priority] [maxAmountMode] [maxAmount] §7Go into marking mode", //
				".warehouse template <template> <type> [priority] [maxAmountMode] [maxAmount] §7Go into marking mode", //
				".warehouse run §7Start moving items", //
				".warehouse summary §7Displays the current warehouse summary", //
				".warehouse where §7Displays which container has items similar to hand item", //
				"§7https://github.com/MineYuanlu/Wurst7/wiki/Warehouse"//
		);

		addSetting(RANGE);
		addSetting(TIME_OUT);
		addSetting(SortWarehouse.SKIP_INVALID);
		addSetting(SortWarehouse.GO_BACK);
		addSetting(GoToHelper.USE_FLY);
		for (ContaionerType ct : ContaionerType.values()) addSetting(ct.colorSetting);
		addSetting(SignWarehouse.SEE_COLOR);
		addSetting(SortWarehouse.GOAL_COLOR);
		addSetting(WhereWarehouse.CACHE_COLOR);
		addSetting(WhereWarehouse.RULE_COLOR);
		addSetting(FLASH_SPEED);
	}

	@Override
	public void call(String[] args) throws CmdException {
		if (args.length == 0) throw new CmdSyntaxError();
		renderInit();
		switch (args[0].toLowerCase()) {
		case "new", "n" -> createConf(merge(args, 1));
		case "load", "l" -> {
			if (args.length <= 1) throw new CmdSyntaxError("Missing parameter");
			loadConf(merge(args, 1));
		}
		case "save", "s" -> saveConf(merge(args, 1));
		case "markmode", "m", "sign" -> sign(args, false);
		case "template", "t", "temp" -> sign(args, true);
		case "run", "r" -> run();
		case "summary" -> summary();
		case "where", "item" -> where();
		default -> throw new CmdSyntaxError("Unknown subcommand: " + args[0]);
		}
	}

	public synchronized void callbackInventory(List<ItemStack> items, int syncId) {
		switch (status.get()) {
		case SIGN -> signing.callbackInventory(items, syncId);
		case RUN_SCANNING -> sorting.callbackInventory(items, syncId);
		default -> {
		}
		}
	}

	public synchronized boolean callbackOpenWindow(int syncId, int size) {
		return switch (status.get()) {
		case SIGN -> signing.callbackOpenWindow(syncId, size);
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

	@Override
	public void doPrimaryAction() {

		WURST.getCmdProcessor().process(getName() + " run");

	}

	@Override
	public String getPrimaryAction() {
		return "Start Sorting Warehouse";
	}

	/** @return the status */
	public Status getStatus() {
		return status.get();
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
			Path		path	= FOLDER.resolve(server == null ? DEF_DIR :						//
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

			SummaryWarehouse summaryWarehouse = WarehouseCmd.summary;
			if (summaryWarehouse != null) {
				summary = null;
				summaryWarehouse.exit(true);
				summary = new SummaryWarehouse(conf, true);
			}

			WhereWarehouse whereWarehouse = WarehouseCmd.where;
			if (whereWarehouse != null) {
				where = null;
				whereWarehouse.exit(true);
				where = new WhereWarehouse(conf, true);
			}

		} finally {
			containerCache = null;
			status.set(Status.IDLE);
		}
	}

	private void run() throws CmdException {
		Status nowStatus = this.status.get();
		if (nowStatus == Status.IDLE) {
			if (config == null) throw new CmdError("Empty configuration");
			if (!status.compareAndSet(Status.IDLE, Status.RUNNING)) throw new CmdError("Busy");

			config.flush();

			ChatUtils.message("Start sorting out warehouse items in " + config.allArea);

			sorting = new SortWarehouse(config, status);
		} else if (nowStatus.isRunStatus()) {
			SortWarehouse sortWarehouse = sorting;
			sorting = null;
			status.set(Status.IDLE);
			try {
				sortWarehouse.exit();
			} finally {
				ChatUtils.message("Exit sort mode.");
			}
		} else {
			throw new CmdError("Busy: " + nowStatus);
		}
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
			Path		path	= FOLDER.resolve(server == null ? DEF_DIR :						//
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
	 * @see SignWarehouse
	 * @see TemplateWarehouse
	 */
	private void sign(String[] args, boolean useTemplate) throws CmdException {
		if (status.compareAndSet(Status.IDLE, Status.SIGN)) {
			try {
				if (config == null) throw new CmdError("Empty configuration");

				if (args.length <= 1) throw new CmdError(String.format("Missing parameter:\n"//
						+ "§r.warehouse %s <type> [priority] [maxAmountMode] [maxAmount] §7Go into marking mode\n" //
						+ "%s"//
						+ "§rtype - §7Type of container: produce§a(I)§7/storage§a(O)§7/other§a(T)§7\n"//
						+ "§rpriority - §7Container priority\n"//
						+ "§rmaxAmountMode - §7Limit quantity type: all/itemList/countList/outputList\n"//
						+ "§rmaxAmount - §7Limit quantity"//
						+ "§7https://github.com/MineYuanlu/Wurst7/wiki/Warehouse#how-to-use-sign-mode"
//						+ "c - §7For output type, whether to take out items that do not belong to this box"//
						, useTemplate ? args[0] + " <template>" : args[0], useTemplate ? //
								"§rtemplate - §7Name of template item list" : ""));

				if (useTemplate) {// Move the array to align the parameter positions

					String[] args0 = new String[args.length - 1];
					System.arraycopy(args, 1, args0, 0, args0.length);
					args = args0;

				}

				TemplateWarehouse	templateWarehouse	= useTemplate ? TemplateWarehouse.getTemplate(args[0]) : null;

				ContaionerType		type				= ContaionerType.get(args[1]);
				if (type == null) throw new CmdError(ContaionerType.syntax);

				int weight = 0;
				if (args.length > 2) try {
					weight = Integer.parseInt(args[2]);
				} catch (NumberFormatException e) {
					throw new CmdSyntaxError("Invalid weight: " + args[2]);
				}

				InOutType ioType = InOutType.ITEM_LIST;
				if (args.length > 3) {
					ioType = InOutType.get(args[3]);
					if (ioType == null) throw new CmdError(InOutType.syntax);
				}

				if (ioType.reject(type)) {

					throw new CmdError("Cannot use \"" + ioType + "\" io type on \"" + type + "\" type container!");

				}

				int amount = type == ContaionerType.OUTPUT ? -54 : 0;
				if (args.length > 4) try {
					amount = Integer.parseInt(args[4]);
				} catch (NumberFormatException e) {
					throw new CmdSyntaxError("Invalid amount: " + args[4]);
				}

				boolean clear = false;
				if (args.length > 5) clear = hasStr(args[5], "true", "ture", "t", "yes", "y");

				signing = new SignWarehouse(config, status, templateWarehouse, type, weight, ioType, amount, clear);

			} catch (CmdException e) {
				status.set(Status.IDLE);
				throw e;
			}
		} else if (status.get() == Status.SIGN) {

			try {
				signing.exit();
			} finally {
				status.set(Status.IDLE);
			}

		} else throw new CmdError("Busy");
	}

	private void summary() throws CmdException {
		SummaryWarehouse summaryWarehouse = summary;
		if (summaryWarehouse != null) {

			summary = null;
			summaryWarehouse.exit(false);

		} else {

			Config config = this.config;
			if (config == null) throw new CmdError("Empty configuration");

			summary = new SummaryWarehouse(config, false);

		}
	}

	private void where() throws CmdException {
		if (where != null) {

			WhereWarehouse whereWarehouse = where;
			where = null;
			whereWarehouse.exit(false);

		} else {

			Config config = this.config;
			if (config == null) throw new CmdError("Empty configuration");

			where = new WhereWarehouse(config, false);

		}
	}

}
