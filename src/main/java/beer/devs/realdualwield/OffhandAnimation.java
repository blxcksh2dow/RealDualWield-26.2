package beer.devs.realdualwield;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Broadcasts the off-hand swing animation (ClientboundAnimatePacket, action 3).
 *
 * <p>Why this class exists: on Minecraft 26.x (and on every version where Mojang turned packets
 * into immutable records) {@code ClientboundAnimatePacket} no longer exposes a default or
 * {@code FriendlyByteBuf} constructor, so ProtocolLib's {@code StructureCache} fails with
 * <pre>IllegalArgumentException: Cannot create instance of class ...ClientboundAnimatePacket</pre>
 * as soon as a plugin calls {@code ProtocolManager#createPacket(PacketType.Play.Server.ANIMATION)}.
 *
 * <p>To keep the animation working no matter what, the strategy is resolved once, at runtime:
 * <ol>
 *     <li><b>PACKET</b> - let ProtocolLib allocate the packet (legacy versions), or allocate the
 *         NMS packet ourselves through reflection (records on 26.x) and hand the instance to
 *         ProtocolLib, which is exactly what the original plugin did.</li>
 *     <li><b>API</b> - {@link LivingEntity#swingOffHand()}, available on Paper and on every
 *         Paper fork (Purpur, Pufferfish, Leaf, ...). No packet and no reflection involved.</li>
 * </ol>
 *
 * <p>The active strategy is logged on the first swing, so it is always visible in the console.
 */
public final class OffhandAnimation
{
    public enum Mode
    {
        /** Packets when they work (ProtocolLib), otherwise the swing API. */
        AUTO,
        /** Only send the animation packet (ProtocolLib required, falls back to the API if it fails). */
        PACKET,
        /** Only use the Bukkit/Paper swing API, no packet is ever created. */
        API
    }

    /** ClientboundAnimatePacket action id for the off-hand swing. */
    private static final int SWING_OFF_HAND = 3;
    /** Vanilla-ish broadcast radius used by the packet strategy. */
    private static final int RANGE = 32;

    private interface Sender
    {
        String describe();

        void send(Player player);
    }

    private static volatile Mode mode = Mode.AUTO;
    private static volatile Sender sender;

    private OffhandAnimation()
    {
    }

    /**
     * Selects how the animation has to be produced. Setting a new mode resets the cached strategy,
     * it is resolved again on the next swing (used by {@code /rdwreload}).
     */
    public static void setMode(Mode newMode)
    {
        mode = newMode == null ? Mode.AUTO : newMode;
        sender = null;
    }

    public static Mode getMode()
    {
        return mode;
    }

    /**
     * Plays the off-hand swing animation for the given player.
     *
     * @param player the player swinging the weapon held in their off hand.
     */
    public static void play(Player player)
    {
        if (player == null)
            return;

        Sender resolved = sender;
        if (resolved == null)
            resolved = resolve(player);

        if (resolved == null)
            return;

        try
        {
            resolved.send(player);
        }
        catch (Throwable t)
        {
            log(Level.WARNING, "the off-hand swing animation could not be played (" + resolved.describe() + ")", t);
            degradeToApi(player);
        }
    }

    /**
     * If the packet strategy stops working after having been accepted (ProtocolLib update, another
     * plugin interfering, ...) switch to the swing API instead of losing the animation entirely.
     */
    private static void degradeToApi(Player player)
    {
        synchronized (OffhandAnimation.class)
        {
            if (!(sender instanceof PacketSender))
                return;

            Sender api = ApiSender.probe();
            if (api == null)
                return;

            log(Level.WARNING, "the packet strategy stopped working: using " + api.describe() + " from now on", null);
            sender = api;

            try
            {
                api.send(player);
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static synchronized Sender resolve(Player player)
    {
        if (sender != null)
            return sender;

        Sender resolved = null;

        if (mode != Mode.API && isProtocolLibEnabled())
            resolved = PacketSender.probe(player);

        if (resolved == null)
        {
            if (mode == Mode.PACKET)
                log(Level.WARNING, "ProtocolLib could not build the animation packet, falling back to the swing API", null);

            resolved = ApiSender.probe();
        }

        if (resolved == null)
        {
            resolved = new NoopSender();
            log(Level.WARNING, "no working off-hand animation method was found: the off-hand swing will not be visible to other players", null);
        }
        else
        {
            log(Level.INFO, "off-hand swing animation: " + resolved.describe(), null);
        }

        sender = resolved;
        return resolved;
    }

    static boolean isProtocolLibEnabled()
    {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Returns the NMS entity behind a Bukkit player, or null when it cannot be resolved.
     * Reflection is used on purpose: it works both on Mojang-mapped and on Spigot-mapped servers.
     */
    static Object nmsEntity(Player player)
    {
        try
        {
            Method getHandle = player.getClass().getMethod("getHandle");
            getHandle.setAccessible(true);
            return getHandle.invoke(player);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void log(Level level, String message, Throwable throwable)
    {
        String prefix = "[RealDualWield] ";
        if (Main.inst != null)
        {
            if (throwable == null)
                Main.inst.getLogger().log(level, prefix + message);
            else
                Main.inst.getLogger().log(level, prefix + message, throwable);
        }
        else
        {
            if (throwable == null)
                Bukkit.getLogger().log(level, prefix + message);
            else
                Bukkit.getLogger().log(level, prefix + message, throwable);
        }
    }

    /**
     * Every reference to a ProtocolLib class lives here: the nested class is only loaded (and
     * verified) when ProtocolLib is actually installed and enabled.
     */
    private static final class PacketSender implements Sender
    {
        private static final com.comphenix.protocol.PacketType ANIMATION = com.comphenix.protocol.PacketType.Play.Server.ANIMATION;

        /** ProtocolLib is able to allocate the packet on its own and we can write the fields. */
        private static final int BUILD_PROTOCOLLIB = 0;
        /** Record-like packet: constructor (int entityId, int action). */
        private static final int BUILD_IDS = 1;
        /** Classic packet: constructor (Entity entity, int action). */
        private static final int BUILD_ENTITY = 2;
        /** Ancient packet: no-arg constructor + int fields. */
        private static final int BUILD_FIELDS = 3;

        private final int build;
        private final Constructor<?> constructor;

        private PacketSender(int build, Constructor<?> constructor)
        {
            this.build = build;
            this.constructor = constructor;
        }

        static PacketSender probe(Player player)
        {
            if (manager() == null || packetClass() == null)
                return null;

            // 1) Let ProtocolLib allocate the packet. This is what RealDualWield always did and it
            //    keeps working on every version where the packet still has an accessible
            //    no-arg / serializer constructor.
            try
            {
                buildWithProtocolLib(player);
                return new PacketSender(BUILD_PROTOCOLLIB, null);
            }
            catch (Throwable ignored)
            {
            }

            // 2) 1.20.5+ (26.x included): the packet is an immutable record with final fields, so
            //    ProtocolLib cannot instantiate it. Build it ourselves and wrap the instance.
            Class<?> packetClass = packetClass();
            Object entity = nmsEntity(player);

            // (int, int) is the canonical record constructor: prefer it when present.
            for (Constructor<?> ctor : packetClass.getDeclaredConstructors())
            {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 2 && params[0] == int.class && params[1] == int.class)
                {
                    Object instance = instantiate(ctor, player.getEntityId(), SWING_OFF_HAND);
                    if (instance != null && carries(instance, player.getEntityId(), SWING_OFF_HAND))
                        return new PacketSender(BUILD_IDS, ctor);
                }
            }

            // (Entity, int): the classic constructor kept by Mojang as a convenience.
            if (entity != null)
            {
                for (Constructor<?> ctor : packetClass.getDeclaredConstructors())
                {
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 2 && params[1] == int.class && params[0].isInstance(entity))
                    {
                        Object instance = instantiate(ctor, entity, SWING_OFF_HAND);
                        if (instance != null && carries(instance, player.getEntityId(), SWING_OFF_HAND))
                            return new PacketSender(BUILD_ENTITY, ctor);
                    }
                }
            }

            // 3) Ancient versions (1.8 - 1.20.4): no-arg constructor and two int fields.
            try
            {
                Constructor<?> ctor = packetClass.getDeclaredConstructor();
                Object instance = instantiate(ctor);
                if (instance != null && writeIntFields(instance, player.getEntityId(), SWING_OFF_HAND))
                    return new PacketSender(BUILD_FIELDS, ctor);
            }
            catch (Throwable ignored)
            {
            }

            return null;
        }

        @Override
        public String describe()
        {
            return switch (build)
            {
                case BUILD_PROTOCOLLIB -> "ProtocolLib packet (created by ProtocolLib)";
                case BUILD_IDS -> "ProtocolLib packet (record constructor: entityId + action)";
                case BUILD_ENTITY -> "ProtocolLib packet (entity constructor)";
                case BUILD_FIELDS -> "ProtocolLib packet (fields)";
                default -> "ProtocolLib packet";
            };
        }

        @Override
        public void send(Player player)
        {
            com.comphenix.protocol.ProtocolManager manager = manager();
            if (manager == null)
                return;

            com.comphenix.protocol.events.PacketContainer packet = build(player);
            if (packet == null)
                return;

            Location origin = player.getLocation();
            int delivered = 0;
            Throwable failure = null;

            for (Player near : origin.getWorld().getPlayers())
            {
                if (near.getWorld() != origin.getWorld())
                    continue;
                if (near.getLocation().distance(origin) > RANGE)
                    continue;

                try
                {
                    manager.sendServerPacket(near, packet);
                    delivered++;
                }
                catch (Throwable t)
                {
                    if (failure == null)
                        failure = t;
                }
            }

            // Nobody received it: let the caller fall back on the swing API.
            if (delivered == 0 && failure != null)
                throw new IllegalStateException("the off-hand animation packet was not delivered to anybody", failure);
        }

        private com.comphenix.protocol.events.PacketContainer build(Player player)
        {
            switch (build)
            {
                case BUILD_PROTOCOLLIB:
                    return buildWithProtocolLib(player);

                case BUILD_IDS:
                    return wrap(instantiate(constructor, player.getEntityId(), SWING_OFF_HAND));

                case BUILD_ENTITY:
                {
                    Object entity = nmsEntity(player);
                    return entity == null ? null : wrap(instantiate(constructor, entity, SWING_OFF_HAND));
                }

                case BUILD_FIELDS:
                {
                    Object instance = instantiate(constructor);
                    return instance != null && writeIntFields(instance, player.getEntityId(), SWING_OFF_HAND)
                            ? wrap(instance)
                            : null;
                }

                default:
                    return null;
            }
        }

        private static com.comphenix.protocol.events.PacketContainer buildWithProtocolLib(Player player)
        {
            com.comphenix.protocol.events.PacketContainer packet = manager().createPacket(ANIMATION, false);
            packet.getIntegers().write(0, player.getEntityId());
            packet.getIntegers().write(1, SWING_OFF_HAND);
            return packet;
        }

        private static com.comphenix.protocol.events.PacketContainer wrap(Object handle)
        {
            if (handle == null)
                return null;

            try
            {
                // Wrapping an existing NMS instance: no allocation is requested to ProtocolLib.
                return new com.comphenix.protocol.events.PacketContainer(ANIMATION, handle);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        private static com.comphenix.protocol.ProtocolManager manager()
        {
            return isProtocolLibEnabled() ? com.comphenix.protocol.ProtocolLibrary.getProtocolManager() : null;
        }

        private static Class<?> packetClass()
        {
            try
            {
                return ANIMATION.getPacketClass();
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        private static Object instantiate(Constructor<?> ctor, Object... args)
        {
            try
            {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        /**
         * Guards against constructors whose parameters are not (entityId, action): without this the
         * packet could animate the wrong entity or play the wrong animation.
         */
        private static boolean carries(Object instance, int entityId, int action)
        {
            List<Field> intFields = intFields(instance.getClass());
            if (intFields.size() < 2)
                return true; // nothing to compare with: assume it is correct

            try
            {
                Field first = intFields.get(0);
                Field second = intFields.get(1);
                first.setAccessible(true);
                second.setAccessible(true);
                return first.getInt(instance) == entityId && second.getInt(instance) == action;
            }
            catch (Throwable t)
            {
                return true;
            }
        }

        private static List<Field> intFields(Class<?> type)
        {
            List<Field> fields = new ArrayList<>();
            for (Field field : type.getDeclaredFields())
            {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType() == int.class)
                    fields.add(field);
            }
            return fields;
        }

        private static boolean writeIntFields(Object instance, int entityId, int action)
        {
            List<Field> intFields = intFields(instance.getClass());

            if (intFields.size() < 2)
                return false;

            try
            {
                Field first = intFields.get(0);
                Field second = intFields.get(1);
                first.setAccessible(true);
                second.setAccessible(true);
                first.setInt(instance, entityId);
                second.setInt(instance, action);
                return true;
            }
            catch (Throwable t)
            {
                return false;
            }
        }
    }

    /**
     * {@link LivingEntity#swingOffHand()} is provided by Paper (and therefore by Purpur and every
     * other Paper fork). It performs the vanilla {@code swing(InteractionHand.OFF_HAND, true)}
     * broadcast, which is the same packet the plugin used to send by hand.
     */
    private static final class ApiSender implements Sender
    {
        static ApiSender probe()
        {
            try
            {
                LivingEntity.class.getMethod("swingOffHand");
                return new ApiSender();
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        @Override
        public String describe()
        {
            return "LivingEntity#swingOffHand() (no packet)";
        }

        @Override
        public void send(Player player)
        {
            player.swingOffHand();
        }
    }

    private static final class NoopSender implements Sender
    {
        @Override
        public String describe()
        {
            return "none";
        }

        @Override
        public void send(Player player)
        {
            // Nothing we can do.
        }
    }
}
