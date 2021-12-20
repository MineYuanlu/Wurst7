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
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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
import net.minecraft.util.registry.Registry;
import net.wurstclient.SearchTags;
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
import net.wurstclient.settings.ColorSetting;
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
@SearchTags({ "warehouse", "sort", "chest", "ware", "item" })
public class WarehouseCmd extends Command {
	/**
	 * Collection of all container configurations
	 */
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

	/**
	 * Single container configuration
	 */
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

	/** 容器类型 */
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

		private ContaionerType(Color color, String description, String... matchs) {
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

			public NearFinder(BlockPos goal) {
				super(goal);
			}

			@Override
			protected boolean checkDone() {
				double	range	= Math.max(WarehouseCmd.range.getValue() / 2, 2);
				double	disSqu	= current.getSquaredDistance(getGoal());
				return done = disSqu < range * range;
			}

		}

		private static final GoToHelper INSTANCE = new GoToHelper();

		public static void goTo(BlockPos goal, Consumer<Boolean> callback) {
			if (INSTANCE.enabled) INSTANCE.disable(false);
			INSTANCE.pathFinder	= new NearFinder(goal);
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
				pathFinder = new NearFinder(pathFinder.getGoal());
				return;
			}

			// process path
			processor.process();

			if (processor.isDone()) disable(true);
		}
	}

	/**
	 * Record item information (only save item name + item count)
	 */
	private static final class ItemList extends LinkedHashMap<String, Integer> {
		@Serial private static final long serialVersionUID = -814964946466372693L;

		public static ItemList deserialize(JsonElement element) {
			ItemList list = new ItemList();
			element.getAsJsonObject().entrySet().forEach(e -> list.put(e.getKey(), e.getValue().getAsInt()));
			return list;
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

		public boolean hasCanInput(PlayerInventory inv) {

			return inv.main.stream()//
					.map(ItemStack::getItem)//
					.map(Registry.ITEM::getId)//
					.map(Identifier::toString)//
					.map(this::get)//
					.anyMatch(count -> count != null && count.intValue() > 0)//
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
		private static final ColorSetting		SEE_COLOR	= new ColorSetting("see color", Color.LIGHT_GRAY);
		/** RENDER */
		private VertexBuffer					solidBox;

		private VertexBuffer					outlinedBox;
		private final Config					config;
		/** signing config */
		private final ContaionerConfig			contaionerConfig;
		/** ChestESP status keep */
		private final boolean					signing_ChestESP_enable;
		private Box								signing_lookingBox;
		private BlockPos						signing_waitingChest;
		private Integer							signing_waitingSyncId;
		private int								signing_waitingSize;

		private final AtomicReference<Status>	status;

		/**
		 * Marking containers
		 *
		 * @param config Overall config
		 * @param status status
		 * @param type   Represents the container type
		 * @param weight priority
		 * @param amount Maximum put in quantity (when putting in) / minimum reserved
		 *               quantity (when extracting)<br>
		 *               When the number is negative, the unit is grouped
		 * @param clear  When the type is output container, do you want to remove other
		 *               items not in this container classification list
		 */
		public SignWarehouse(Config config, AtomicReference<Status> status, ContaionerType type, int weight, int amount, boolean clear) {

			this.config				= Objects.requireNonNull(config, "config");
			this.status				= Objects.requireNonNull(status, "status");

			contaionerConfig		= new ContaionerConfig(type, weight, amount, clear);
			signing_ChestESP_enable	= WURST.getHax().chestEspHack.isEnabled();
			WURST.getHax().chestEspHack.setEnabled(false);

			EVENTS.add(UpdateListener.class, this);
			EVENTS.add(RenderListener.class, this);
			EVENTS.add(RightClickListener.class, this);

			renderInit(true);

			ChatUtils.message("Enter sign mode");
		}

		public synchronized void callbackInventory(List<ItemStack> items, int syncId) {
			if ((signing_waitingSyncId == null) || (signing_waitingSyncId != syncId)) return;

			ItemList list = new ItemList();

			items.stream()//
					.limit(signing_waitingSize)//
					.filter(stack -> !stack.isEmpty())//
					.forEach(list::add);

			contaionerConfig.blocks.put(signing_waitingChest, list);
			config.cacheBlocks.put(signing_waitingChest, contaionerConfig);

			signing_waitingChest	= null;
			signing_waitingSyncId	= null;
			MC.player.closeScreen();
			ChatUtils.message("Container scanned successfully");
		}

		public synchronized boolean callbackOpenWindow(int syncId, int size) {
			if (signing_waitingChest == null) return false;

			if (signing_waitingSyncId != null) {

				signing_waitingChest	= null;
				signing_waitingSyncId	= null;

				MC.player.closeScreen();
				ChatUtils.warning("The last waiting container content was not received.");
				ChatUtils.warning("The last and current wait has been cancelled to prevent content confusion.");
				return true;
			}

			signing_waitingSyncId	= syncId;
			signing_waitingSize		= size;

			return true;
		}

		/**
		 *
		 */
		public void exit() {

			EVENTS.remove(UpdateListener.class, this);
			EVENTS.remove(RenderListener.class, this);
			EVENTS.remove(RightClickListener.class, this);

			renderInit(false);

			config.contaioners.stream()//
					.filter(contaionerConfig::configEquals)//
					.findAny()//
					.ifPresentOrElse(contaionerConfig::mergeTo, () -> config.contaioners.add(contaionerConfig));
			config.flush();

			if (signing_ChestESP_enable) WURST.getHax().chestEspHack.setEnabled(true);

			ChatUtils.message("Exit sign mode " + (signing_ChestESP_enable ? "§7, ChestESP resumed" : ""));
		}

		@Override
		public void onRender(MatrixStack matrixStack, float partialTicks) {
			if (signing_lookingBox == null) return;
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
			renderBoxes(matrixStack, SEE_COLOR.getColorF(), regionX, regionZ, signing_lookingBox);

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		@Override
		public void onRightClick(RightClickEvent event) {
			if ((status.get() != Status.SIGN) || !(MC.crosshairTarget instanceof BlockHitResult)) return;
			BlockPos	pos			= ((BlockHitResult) MC.crosshairTarget).getBlockPos();

			BlockEntity	blockEntity	= MC.world.getBlockEntity(pos);
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

				ChatUtils.message("A container has been removed from " + (cc == contaionerConfig ? "now signing" : cc));

				return;
			}

			signing_waitingChest = pos;
			ChatUtils.message("Wait for the container to open...");
		}

		@Override
		public void onUpdate() {
			signing_lookingBox = null;
			if (MC.crosshairTarget instanceof BlockHitResult) {

				BlockEntity blockEntity = MC.world.getBlockEntity(((BlockHitResult) MC.crosshairTarget).getBlockPos());

				if (blockEntity == null) return;

				signing_lookingBox = getBox(blockEntity);
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

			Stream.of(solidBox, outlinedBox)//
					.filter(Objects::nonNull)//
					.forEach(VertexBuffer::close);
			if (enable) {
				solidBox	= new VertexBuffer();
				outlinedBox	= new VertexBuffer();

				Box box = new Box(BlockPos.ORIGIN);
				RenderUtils.drawSolidBox(box, solidBox);
				RenderUtils.drawOutlinedBox(box, outlinedBox);
			}
		}
	}

	/**
	 * Sort Helper
	 *
	 * @author yuanlu
	 */
	private static final class SortWarehouse implements UpdateListener {
		/**
		 * Construct a function to return the required quantity of a specified item in
		 * the container according to the configuration.
		 */
		private static Function<String, Integer> getAmountFactory(ItemList configList, int configAmount) {
			if (configAmount == 0) {
				// keep original amount
				return configList::get;

			} else if (configAmount > 0) {
				// amount
				return name -> configAmount;

			} else {
				// group amount
				return name -> Registry.ITEM.get(Identifier.tryParse(name)).getMaxCount() * -configAmount;

			}

		}

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

		/** close callback */
		private CompletableFuture<Boolean>						screenCloseListener;

		/** The number of items need to stored in the specified container. */
		private final HashMap<BlockPos, ItemList>				posCache	= Maps.newHashMap();

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

		/** close screen in main thread */
		private void callCloseScreen() {
			screenCloseListener = new CompletableFuture<>();
			EVENTS.add(UpdateListener.class, this);
			try {
				screenCloseListener.get();
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

		/**
		 * Find a container that matches the items in the inventory, go to scan, and
		 * then put in the items.
		 */
		private void doOutput() {
			status.set(Status.RUNNING);
			PlayerInventory						inv		= MC.player.getInventory();
			Entry<BlockPos, ContaionerConfig>	next	= contaioners												//
					.entrySet().stream()																			//
					.filter(e -> e.getValue().type == ContaionerType.OUTPUT)										//
					.filter(e -> e.getValue().blocks.get(e.getKey()).hasCanOutput(inv, posCache.get(e.getKey())))	//
					.findFirst().orElse(null);																		//

			if (next == null) {
				setNext(this::doTemp, "Temp - by No Output");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error

			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) return;

			ContaionerConfig	cc			= next.getValue();
			ItemList			configList	= cc.blocks.get(next.getKey());
			store(configList, cc.amount, list);
			callCloseScreen();

			if (!scanChest(next.getKey(), configList, cc.amount)) return;

			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Output Finish");
			else setNext(this::doOutput, "Output - by Output Once");

		}

		/**
		 * Put all items that cannot be stored in the inventory into a temporary
		 * container with an empty space.
		 */
		private void doTemp() {
			status.set(Status.RUNNING);
			PlayerInventory						inv		= MC.player.getInventory();
			Entry<BlockPos, ContaionerConfig>	next	= contaioners.entrySet().stream()							//
					.filter(e -> e.getValue().type == ContaionerType.TEMP)											//
					.filter(e -> posCache.get(e.getKey()) == null || posCache.get(e.getKey()).hasCanInput(inv))		//
					.findFirst().orElse(null);																		//

			if (next == null) {
				ChatUtils.error("All temporary containers are full, cannot continue.");
				return;
			}

			if (!goTo(next.getKey())) return;// Not Found or Error
			ItemStack[] list = openContaioner(next.getKey());
			if (list == null) return;

			store(list);
			callCloseScreen();

			if (!scanChest(next.getKey())) return;

			if (isInvEmpty(true)) setNext(this::startOnce, "restart - by Temp Finish");
			else setNext(this::doTemp, "Temp - by Temp Once");

		}

		/** @return success */
		private boolean goTo(BlockPos pos) {
			status.set(Status.RUN_MOVING);
			if (pos.getSquaredDistance(MC.player.getBlockPos()) < Math.pow(range.getValue(), 2)) return true;
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
			if (screenCloseListener != null) {

				MC.player.closeScreen();
				EVENTS.remove(UpdateListener.class, this);

				screenCloseListener.complete(Boolean.TRUE);

			}
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
				return waitingChest.get(timeOut.getValueI(), TimeUnit.MILLISECONDS);
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
			if (list == null) return false;

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
		private boolean scanChest(BlockPos pos, ItemList configList, int configAmount) {

			if (!goTo(pos)) return false;// Not Found or Error

			ItemStack[] list = openContaioner(pos);
			if (list == null) return false;

			try {

				ItemList itemList = ItemList.of(list, true);// 记录目前有的数量
				itemList.retainAll(configList);

				ItemList freeList = ItemList.ofFree(list, true, configList.keySet());// 当前可插入数量
				freeList.retainAll(configList);

				Function<String, Integer> amountFactory = getAmountFactory(configList, configAmount);// 所需数量

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

		/**
		 * According to the configuration, take the specified number of items from the
		 * opened container
		 *
		 * @param stealList   Specified steal list
		 * @param stealAmount Specified steal amount
		 * @param itemList    Container contents
		 * @return all clear
		 */
		private boolean steal(ItemList stealList, int stealAmount, ItemStack[] itemList) {

			// need takes
			List<ItemStack>	inventory	= MC.player.getInventory().main;
			ItemList		takeList	= ItemList.of(itemList, true);

			takeList.retainAll(stealList);

			Function<String, Integer> targetAmountGetter = getAmountFactory(stealList, stealAmount);

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
		private void store(ItemList storeList, int storeAmount, ItemStack[] itemList) {

			// need puts
			PlayerInventory	inventory	= MC.player.getInventory();
			ItemList		putList		= ItemList.of(inventory.main, true);
			ItemList		chestList	= ItemList.of(itemList, true);

			putList.retainAll(storeList);

			Function<String, Integer> targetAmountGetter = getAmountFactory(storeList, storeAmount);

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
			try {
				Thread.sleep(autoSteal.getDelay());
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

		public final String description;

		Status(String description) {
			this.description = Objects.requireNonNull(description);
		}

	}

	private static final class SummaryWarehouse implements RenderListener {
		private final EnumMap<ContaionerType, ArrayList<Box>>	chests	= new EnumMap<>(ContaionerType.class);
		private VertexBuffer									solidBox;
		private VertexBuffer									outlinedBox;

		public SummaryWarehouse(Config config) {

			config.flush();

			for (ContaionerType ct : ContaionerType.BY_ID) {

				chests.put(ct, new ArrayList<>());

			}

			for (Entry<BlockPos, ContaionerConfig> e : config.cacheBlocks.entrySet()) {
				chests.get(e.getValue().type).add(BlockUtils.getBoundingBox(e.getKey()));

			}

			solidBox	= new VertexBuffer();
			outlinedBox	= new VertexBuffer();

			Box box = new Box(BlockPos.ORIGIN);
			RenderUtils.drawSolidBox(box, solidBox);
			RenderUtils.drawOutlinedBox(box, outlinedBox);

			EVENTS.add(RenderListener.class, this);

			ChatUtils.message("Enter summary mode");
		}

		public void exit() {
			EVENTS.remove(RenderListener.class, this);
			Stream.of(solidBox, outlinedBox)//
					.filter(Objects::nonNull)//
					.forEach(VertexBuffer::close);
			ChatUtils.message("Exit summary mode");
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

				renderBoxes(matrixStack, chests.get(ct), ct.colorSetting.getColorF(), regionX, regionZ);

			}

			matrixStack.pop();

			// GL resets
			RenderSystem.setShaderColor(1, 1, 1, 1);
			GL11.glEnable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_BLEND);
			GL11.glDisable(GL11.GL_LINE_SMOOTH);
		}

		private void renderBoxes(MatrixStack matrixStack, ArrayList<Box> boxes, float[] colorF, int regionX, int regionZ) {

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

	}

	private static final SliderSetting	range			= new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);

	private static final SliderSetting	timeOut			= new SliderSetting("Time Out", "Maximum delay allowed for interactive operation (unit: ms)", 3000, 100,
			1000 * 20, 1, ValueDisplay.INTEGER);

	private static final Gson			GSON			= new GsonBuilder().setPrettyPrinting().create();

	private static final String			DEF_GAME_FOLDER	= "default";

	private static final Path			FOLDER			= WURST.getWurstFolder().resolve("warehouse");

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
	/** sort helper */
	private SortWarehouse					sorting;
	/** sign helper */
	private SignWarehouse					signing;

	private SummaryWarehouse				summary;

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

		addSetting(range);
		addSetting(timeOut);
		for (ContaionerType ct : ContaionerType.values()) addSetting(ct.colorSetting);
		addSetting(SignWarehouse.SEE_COLOR);
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
		case "sign" -> sign(args);
		case "run" -> run();
		case "summary" -> summary();
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
	 * @see SignWarehouse
	 */
	private void sign(String[] args) throws CmdException {
		if (status.compareAndSet(Status.IDLE, Status.SIGN)) {

			if (config == null) {
				status.set(Status.IDLE);
				throw new CmdError("Empty configuration");
			}

			/*
			 * 限制数量： 对于input型，将会保留指定数量的物品 对于output型，将最多存放指定数量的物品 对于数量:
			 * 正数表示限制N个，负数表示限制N组，0表示保持物品原数目
			 */
			if (args.length <= 1) throw new CmdError("Missing parameter:\n"//
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

			signing = new SignWarehouse(config, status, type, weight, amount, clear);

		} else if (status.get() == Status.SIGN) {

			try {
				signing.exit();
			} finally {
				status.set(Status.IDLE);
			}

		} else throw new CmdError("Busy");
	}

	/**
	 * 
	 */
	private void summary() throws CmdException {
		if (summary != null) {
			SummaryWarehouse summaryWarehouse = summary;
			summary = null;
			summaryWarehouse.exit();
		} else {

			Config config = this.config;
			if (config == null) throw new CmdError("Empty configuration");

			summary = new SummaryWarehouse(config);
		}
	}
}
