package me.dontshare.yieldloadtest;

import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * The server internals a bot needs, by reflection - this project compiles
 * against the Paper API only. Every signature here was checked against the
 * 26.2 server jar; if one moves in a later version, {@link #create} fails
 * loudly with the missing name rather than half-working.
 * <p>
 * A bot is a real {@code ServerPlayer} placed through the same
 * {@code PlayerList#placeNewPlayer} a real login ends in, so every plugin
 * sees an ordinary {@link Player}: join and quit events fire, it counts in
 * {@code getOnlinePlayers}, other players see it. Its connection is a Netty
 * channel that goes nowhere (see {@link TrafficSink}).
 */
final class NmsBridge {

    private final Object minecraftServer;
    private final Object playerList;
    private final Constructor<?> serverPlayerCtor;
    private final Constructor<?> connectionCtor;
    private final Constructor<?> gameProfileCtor;
    private final Object serverbound;
    private final Method createDefaultClientInfo;
    private final Method createInitialCookie;
    private final Method placeNewPlayer;
    private final Method removePlayer;
    private final Method getBukkitEntity;
    private final Method doTick;
    private final Field connectionChannel;
    private final Field connectionAddress;

    private NmsBridge() throws ReflectiveOperationException {
        Object craftServer = Bukkit.getServer();
        minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
        playerList = craftServer.getClass().getMethod("getHandle").invoke(craftServer);

        Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
        Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
        Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        Class<?> clientInfoClass = Class.forName("net.minecraft.server.level.ClientInformation");
        Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
        Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
        Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
        Class<?> playerListClass = Class.forName("net.minecraft.server.players.PlayerList");

        serverPlayerCtor = serverPlayerClass.getConstructor(minecraftServerClass, serverLevelClass, gameProfileClass, clientInfoClass);
        connectionCtor = connectionClass.getConstructor(packetFlowClass);
        gameProfileCtor = gameProfileClass.getConstructor(UUID.class, String.class);
        serverbound = packetFlowClass.getField("SERVERBOUND").get(null);
        createDefaultClientInfo = clientInfoClass.getMethod("createDefault");
        createInitialCookie = cookieClass.getMethod("createInitial", gameProfileClass, boolean.class);
        placeNewPlayer = playerListClass.getMethod("placeNewPlayer", connectionClass, serverPlayerClass, cookieClass);
        removePlayer = playerListClass.getMethod("remove", serverPlayerClass);
        getBukkitEntity = serverPlayerClass.getMethod("getBukkitEntity");
        doTick = serverPlayerClass.getMethod("doTick");
        connectionChannel = connectionClass.getField("channel");
        connectionAddress = connectionClass.getField("address");
    }

    static NmsBridge create() {
        try {
            return new NmsBridge();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This server's internals don't match what the load test expects: " + e, e);
        }
    }

    /** A logged-in bot: its Bukkit face and the internal handle to tick and remove it by. */
    record Handle(Player player, Object serverPlayer, Channel channel) {
    }

    /** Main thread only. The caller has already run pre-login, so every store holds this bot's data. */
    Handle join(UUID id, String name, World world, Channel channel, int port) throws ReflectiveOperationException {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object profile = gameProfileCtor.newInstance(id, name);
        Object serverPlayer = serverPlayerCtor.newInstance(minecraftServer, level, profile, createDefaultClientInfo.invoke(null));
        Object connection = connectionCtor.newInstance(serverbound);
        connectionChannel.set(connection, channel);
        connectionAddress.set(connection, new InetSocketAddress("127.0.0.1", port));
        placeNewPlayer.invoke(playerList, connection, serverPlayer, createInitialCookie.invoke(null, profile, false));
        return new Handle((Player) getBukkitEntity.invoke(serverPlayer), serverPlayer, channel);
    }

    /** Main thread only. The same removal a disconnect ends in - quit event, saves and all. */
    void leave(Handle handle) throws ReflectiveOperationException {
        removePlayer.invoke(playerList, handle.serverPlayer());
    }

    /** A real client's connection ticks its player; a bot's has nobody to, so the driver does. */
    void tick(Handle handle) throws ReflectiveOperationException {
        doTick.invoke(handle.serverPlayer());
    }
}
