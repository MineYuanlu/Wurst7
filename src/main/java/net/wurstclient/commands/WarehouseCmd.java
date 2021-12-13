/**
 * 
 */
package net.wurstclient.commands;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL11;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;

import io.netty.util.CharsetUtil;
import net.minecraft.class_6148;
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
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

/**
 * Warehouse
 * 
 * @author yuanlu
 *
 */
public class WarehouseCmd extends Command implements UpdateListener, RenderListener {
	public static final class Config {
		//

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
			int mins[] = new int[3], maxs[] = new int[3];
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

	}

	private static final class ItemList extends LinkedHashMap<String, Integer> {
		private static final long serialVersionUID = -814964946466372693L;

		public void add(ItemStack itemStack) {
			String	name	= Registry.ITEM.getId(itemStack.getItem()).toString();
			int		amount	= itemStack.getCount();
			compute(name, (ignore, old) -> old == null ? amount : (amount + old));
		}
	}

	private static final class ContaionerConfig {

		public final LinkedHashMap<BlockPos, ItemList>	blocks	= new LinkedHashMap<>();
		public final boolean							isInput;
		public final int								weight;
		public final int								amount;
		public final boolean							clear;

		public ContaionerConfig(boolean isInput, int weight, int amount, boolean clear) {
			this.isInput	= isInput;
			this.weight		= weight;
			this.amount		= amount;
			this.clear		= clear;
		}

		public boolean configEquals(ContaionerConfig cc) {
			return cc.isInput == isInput && cc.weight == weight && cc.amount == amount && cc.clear == clear;
		}

		public boolean mergeTo(ContaionerConfig cc) {
			if (!configEquals(cc)) return false;
			cc.blocks.putAll(this.blocks);
			return true;
		}
	}

	public static enum Status {
		IDLE("idle"), //
		SIGN("signing container"), //
		IO("File reading or writing"), //
		RUN_MOVING("Moving to the next container"), //
		RUN_SCANNING("Scanning container"), //
		RUN_PICKING("Removing items from container"), //
		RUN_PUTTING("Putting items into container");

		public final String description;

		private Status(String description) {
			this.description = Objects.requireNonNull(description);
		}

	}

	private static final Gson	GSON			= new GsonBuilder().setPrettyPrinting().create();

	private static final String	DEF_GAME_FOLDER	= Long.toHexString(System.currentTimeMillis());
	private static final Path	FOLDER			= WURST.getWurstFolder().resolve("warehouse");

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

	/**
	 * @param name
	 * @param description
	 * @param syntax
	 */
	public WarehouseCmd() {
		super("warehouse", //
				"Automatically organize the boxes near you and summarize all kinds of items into their places", //
				".warehouse load <name> §7Load a warehouse configuration", //
				".warehouse load <name> §7Load a warehouse configuration", //
				".warehouse save [name] §7Save a warehouse configuration", //
				".warehouse sign <type> [w] [a] [c] §7Enable container tagging", //
				".warehouse summary §7Displays the current warehouse summary"//
		);
	}

	@Override
	public void call(String[] args) throws CmdException {
		if (args.length == 0) throw new CmdSyntaxError();
		switch (args[0]) {
		case "new": {
			createConf(merge(args, 1));
			break;
		}
		case "load": {
			if (args.length <= 1) throw new CmdSyntaxError("Missing parameter");
			loadConf(merge(args, 1));
			break;
		}
		case "save": {
			saveConf(merge(args, 1));
			break;
		}
		case "sign": {
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
					+ "  §7- And 0 means to keep the original number of items"//
					+ "c - §7For output type, whether to take out items that do not belong to this box"//
			);
			boolean isInput;
			if (hasStr(args[1], "input", "i", "in", "produce")) isInput = true;
			else if (hasStr(args[1], "output", "o", "out", "storage")) isInput = false;
			else throw new CmdSyntaxError("unknown type:\n"//
					+ "input(i/in): To remove items from containers\n"//
					+ "output(o/out): To pack items into containers");
			int weight = 0;
			if (args.length > 2) try {
				weight = Integer.parseInt(args[2]);
			} catch (NumberFormatException e) {
				throw new CmdSyntaxError("Invalid weight: " + args[2]);
			}
			int amount = isInput ? 0 : Integer.MAX_VALUE;
			if (args.length > 3) try {
				amount = Integer.parseInt(args[3]);
			} catch (NumberFormatException e) {
				throw new CmdSyntaxError("Invalid amount: " + args[3]);
			}
			boolean clear = false;
			if (args.length > 4) clear = hasStr(args[4], "true", "ture", "t", "yes", "y");
			sign(isInput, weight, amount, clear);
		}
		default:
			throw new CmdSyntaxError("Unknown subcommand: " + args[0]);
		}
	}

	/**
	 * load config
	 * 
	 * @param confName
	 * 
	 */
	private void loadConf(String confName) throws CmdException {
		if (!status.compareAndSet(Status.IDLE, Status.IO)) throw new CmdError("Busy");
		try {
			ServerInfo	server	= MC.getCurrentServerEntry();
			Path		path	= FOLDER.resolve(server == null ? DEF_GAME_FOLDER :				//
					(server.online && server.address != null ? server.address : server.name));
			path = path.resolve(confName);
			Config conf;
			try (Reader reader = Files.newBufferedReader(path, CharsetUtil.UTF_8)) {
				conf		= GSON.fromJson(reader, Config.class);
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

	/**
	 * new config
	 * 
	 * @param confName
	 */
	private void createConf(String confName) throws CmdException {
		if (!status.compareAndSet(Status.IDLE, Status.IO)) throw new CmdError("Busy");
		try {
			if (config != null && !config.saved) ChatUtils.warning("Unsaved configuration discarded: " + (config.name == null ? "Unnamed" : config.name));
			config = new Config(confName);
		} finally {
			status.set(Status.IDLE);
		}
	}

	/**
	 * save config
	 * 
	 * @param confName
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
				GSON.toJson(config, writer);
			} catch (Throwable e) {
				e.printStackTrace();
				throw new CmdError("Can not save config: " + e);
			}
			config.saved = true;
			ChatUtils.message("Config is saved in: " + path.relativize(MC.runDirectory.toPath()));
			ChatUtils.message("Config saved: " + confName);
		} finally {
			status.set(Status.IDLE);
		}
	}

	/**
	 * Marking containers
	 * 
	 * @param isInput Is the marked container an extraction mode
	 * @param weight  priority
	 * @param amount  Maximum put in quantity (when putting in) / minimum reserved
	 *                quantity (when extracting)<br>
	 *                When the number is negative, the unit is group
	 * @param clear   When the type is output container, do you want to remove other
	 *                items not in this container classification list
	 */
	private void sign(boolean isInput, int weight, int amount, boolean clear) throws CmdException {
		if (status.compareAndSet(Status.IDLE, Status.SIGN)) {
			if (config == null) throw new CmdError("Empty configuration");

			signing					= new ContaionerConfig(isInput, weight, amount, clear);
			signing_ChestESP_enable	= WURST.getHax().chestEspHack.isEnabled();

			EVENTS.add(UpdateListener.class, this);
			EVENTS.add(RenderListener.class, this);

			renderInit(true);

			ChatUtils.message("Enter sign mode");
		} else if (status.get() == Status.SIGN) {
			try {

				EVENTS.remove(UpdateListener.class, this);
				EVENTS.remove(RenderListener.class, this);

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

	private void renderInit(boolean enable) {

		Stream.of(solidBox, outlinedBox).filter(Objects::nonNull).forEach(VertexBuffer::close);
		if (enable) {
			solidBox	= new VertexBuffer();
			outlinedBox	= new VertexBuffer();
		}
	}

	private ContaionerConfig	signing;
	private boolean				signing_ChestESP_enable;
	private Box					signing_lookingBox;

	private VertexBuffer		solidBox;
	private VertexBuffer		outlinedBox;

	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks) {
		switch (status.get()) {
		case SIGN: {
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
			renderBoxes(matrixStack, new float[] {}, regionX, regionZ, signing_lookingBox);

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

	@Override
	public void onUpdate() {
		switch (status.get()) {
		case SIGN: {
			// TODO Auto-generated method stub
			if (MC.crosshairTarget != null && MC.crosshairTarget instanceof BlockHitResult) {

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

	private BlockPos signing_waitingChest;

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

	public synchronized boolean callbackOpenWindow(Stream<Slot> slots) {
		if (signing_waitingChest == null) return false;

		ItemList list = new ItemList();

		slots.filter(Slot::hasStack)//
				.map(Slot::getStack)//
				.forEach(list::add);

		signing.blocks.put(signing_waitingChest, list);
		config.cacheBlocks.put(signing_waitingChest, signing);

		signing_waitingChest = null;
		MC.player.closeScreen();
		ChatUtils.message("Container scanned successfully");
		return true;
	}
}
