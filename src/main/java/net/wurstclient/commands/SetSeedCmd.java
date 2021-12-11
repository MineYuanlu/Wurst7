package net.wurstclient.commands;

import net.wurstclient.command.CmdError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.serialization.Lifecycle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.MinecraftClient.IntegratedResourceManager;
import net.minecraft.client.world.GeneratorType;
import net.minecraft.resource.DataPackSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.UserCache;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.level.LevelProperties;
import net.minecraft.world.level.storage.LevelStorage;

/**
 * Set server seed. Enables {@link net.wurstclient.util.ChunkSearcher ChunkSearcher} to bypass the anti-X-ray plugin
 * */
public final class SetSeedCmd extends Command
{
    private IntegratedServer server = null;
    private long seed = 0l;
    private String worldname = "";
    private AtomicBoolean busy = new AtomicBoolean();
    private Map<Integer, ServerWorld> worldIndex = new HashMap<>();
	public SetSeedCmd()
	{
		super("seed", "Set server seed.",
			".seed <long int>", "Turn off: .seed");
	}
	
	@Override
	public void call(String[] args) throws CmdError
	{

        if(this.server != null)
        {
            disable();
        }
		else
		{
			enable(args);
		}
	}
	
	private void enable(String[] args) throws CmdError
	{
        if (args.length == 1) {
            try {
                this.seed = Long.parseLong(args[0]);
            } catch (NumberFormatException e) {
                throw new CmdError("not a Long Integer");
            }
        }
        else throw new CmdError(".seed <long int>");

        this.worldname = String.format("set-seed-%d", this.seed);
        if (this.busy.getAndSet(true)) throw new CmdError("busy");
        new Thread(this::enable_async, "enable_async").start();
    }

    private void enable_async()
    {
        DynamicRegistryManager.Impl registryTracker = DynamicRegistryManager.create();
        LevelStorage.Session session;
        IntegratedResourceManager resourceManager;
        Object authenticationService = new YggdrasilAuthenticationService(MC.getNetworkProxy());
        MinecraftSessionService sessionService = ((YggdrasilAuthenticationService)authenticationService).createMinecraftSessionService();
        GameProfileRepository profileRepository = ((YggdrasilAuthenticationService)authenticationService).createProfileRepository();
        UserCache userCache = new UserCache((GameProfileRepository)profileRepository, new File(MC.runDirectory, MinecraftServer.USER_CACHE_FILE.getName()));
        try {
            if (MC.getLevelStorage().levelExists(this.worldname)) {
                ChatUtils.message(String.format("loading local world %s ...", worldname));
                session = MC.getLevelStorage().createSession(this.worldname);
                resourceManager = MC.createIntegratedResourceManager(registryTracker, MinecraftClient::loadDataPackSettings, MinecraftClient::createSaveProperties, false, session);
            }
            else {
                ChatUtils.message(String.format("creating local world %s ...", worldname));
                session = MC.getLevelStorage().createSession(this.worldname);
                GameRules gameRules = new GameRules();
                gameRules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, null);
                gameRules.get(GameRules.DO_WEATHER_CYCLE).set(false, null);
                gameRules.get(GameRules.DO_MOB_GRIEFING).set(false, null);
                gameRules.get(GameRules.RANDOM_TICK_SPEED).set(0, null);
                gameRules.get(GameRules.DO_FIRE_TICK).set(false, null);
                gameRules.get(GameRules.DO_MOB_SPAWNING).set(false, null);
                gameRules.get(GameRules.NATURAL_REGENERATION).set(false, null);
                LevelInfo levelInfo = new LevelInfo(this.worldname, GameMode.SPECTATOR, false, Difficulty.PEACEFUL, true, gameRules, DataPackSettings.SAFE_MODE);
                resourceManager = MC.createIntegratedResourceManager(registryTracker, __session -> levelInfo.getDataPackSettings(), (__session, _registryManager, __resourceManager, __dataPackSettings) -> {
                    return new LevelProperties(levelInfo, GeneratorType.DEFAULT.createDefaultOptions(_registryManager, this.seed, true, false), Lifecycle.stable());
                }, false, session);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            ChatUtils.message("Error: game creation failed");
            this.busy.set(false);
            return;
        }
        this.server = MinecraftServer.startServer(
            serverThread -> {
                return new IntegratedServer(serverThread, MC, registryTracker, session, resourceManager.getResourcePackManager(), resourceManager.getServerResourceManager(), resourceManager.getSaveProperties(), sessionService, profileRepository, userCache, WorldGenerationProgressLogger::new);
            }
        );
        while (!this.server.isLoading()) {
            try {
                Thread.sleep(16L);
            }
            catch (InterruptedException e) {
                // empty catch block
            }
        }
        this.dumpServerWorldId();
        ChatUtils.message(String.format("local world %s loaded", worldname));
        this.busy.set(false);
    }
    private void disable() throws CmdError
	{
        if (this.busy.getAndSet(true)) throw new CmdError("busy");
        new Thread(this::disable_async, "disable_async").start();
	}

    private void disable_async()
    {
        ChatUtils.message(String.format("stopping local world %s ...", this.worldname));
        this.worldIndex.clear();
        this.server.stop(true);
        this.server = null;
        ChatUtils.message(String.format("local world %s is stopped", this.worldname));
        this.seed = 0l;
        this.worldname = "";
        this.busy.set(false);
    }

    public IntegratedServer getServer() {
        if (this.busy.get()) return null;
        return this.server;
    }

    private void dumpServerWorldId() {
        this.worldIndex.clear();
        for (ServerWorld serverWorld : this.server.getWorlds()) {
            this.worldIndex.put(serverWorld.getRegistryKey().toString().hashCode(), serverWorld);
        }
    }

    public ServerWorld getWorld(RegistryKey<World> key) {
        IntegratedServer server = this.getServer();
        if (server == null) return null;
        return server.getWorld(key);
    }
    public ServerWorld getOverWorld() {
        return this.getWorld(World.OVERWORLD);
    }
    public ServerWorld getNether() {
        return this.getWorld(World.NETHER);
    }
    public ServerWorld getEnd() {
        return this.getWorld(World.END);
    }
    public ServerWorld getWorldById(int key) {
        return this.worldIndex.getOrDefault(key, null);
    }
}
