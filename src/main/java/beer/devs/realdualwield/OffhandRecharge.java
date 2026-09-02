package beer.devs.realdualwield;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Recharge feedback for the off-hand weapon: the "the sword goes down and comes back up while the
 * attack recharges" animation that vanilla only plays for the MAIN hand.
 *
 * <p>Why this class has to exist (read from the 26.2 client source, {@code ItemInHandRenderer}):
 * <pre>
 *   float mainHandTargetHeight = mainHandItem != nextMainHand ? 0.0F : attackAnim * attackAnim * attackAnim;
 *   float offHandTargetHeight  = offHandItem  != nextOffHand  ? 0.0F : 1.0F;
 * </pre>
 * where {@code attackAnim} is {@code Player#getItemSwapScale()}, i.e. the attack cooldown. In other
 * words the client keeps the off hand <b>always raised</b>: there is no off-hand recharge state at
 * all, so no packet and no API can ask for one. The only two things that lower the off-hand item
 * are an item swap (the visible item differs from the one in the inventory) and
 * {@code itemUsed(OFF_HAND)} (the client used the off-hand item), and both bring it back up in
 * ~3 ticks whatever the weapon is.
 *
 * <p>Everything here pivots on that swap animation: when the item in the inventory differs (by
 * content) from the one the client is drawing, the client lowers the weapon to swap it and raises it
 * again (~3 ticks down, ~3 up). That is the only lever a server has on the off-hand weapon.
 *
 * <p><b>hold</b> (default) keeps the weapon down for the WHOLE recharge: while it is down a
 * different copy of it is shown every single tick, and when the recharge is over the real item is
 * sent back and the client raises the weapon in ~3 ticks. The length is the recharge of the weapon,
 * so a slow sword stays down much longer than a fast one, like the main hand does. It has two
 * limits, both structural: (1) a packet has to land inside every single client tick, and when the
 * server hiccups the client raises the weapon by 0.4 and the next packet slams it down again (the
 * visible "up and down" glitch); (2) the profile cannot be the vanilla one, because the main hand
 * follows a cubic curve (it creeps up from the middle of the recharge) while the off hand can only
 * be fully down or fully up, so it stays down and comes up in the last 3 ticks.
 *
 * <p><b>dip</b> sends ONE packet per hit: the weapon goes down and comes back up in ~6 ticks. It is
 * completely deterministic (a single change, then the client does the rest by itself), it costs one
 * packet per hit and it can never glitch, but its length is always the same and does NOT follow the
 * attack speed of the weapon.
 *
 * <p><b>cooldown</b> is the plain vanilla alternative: the off-hand weapon gets a
 * {@code minecraft:use_cooldown} component with its own cooldown group and every hit puts that group
 * on cooldown, so the item shows the white recharge bar in the off-hand slot for exactly the
 * recharge time of the weapon. It is the HUD bar, not the weapon moving in 3D: and since writing
 * the component changes the item, the client plays its swap animation on top of it (one short dip).
 *
 * <p>Nothing of the real item is ever touched by dip/hold: the copies only exist inside the packets,
 * so MMOItems stats, Nexo/MMOItems textures, durability and custom model data are untouched.
 */
public final class OffhandRecharge
{
    public enum Mode
    {
        /** Nothing at all. */
        NONE,
        /** One deterministic dip per hit: ~3 ticks down, ~3 ticks up. */
        DIP,
        /** Lower the off-hand weapon for the whole recharge (one packet per tick). */
        HOLD,
        /** Vanilla item cooldown (white bar) on the off-hand weapon. */
        COOLDOWN
    }

    /** {@code Inventory.SLOT_OFFHAND}, the slot used by ClientboundSetPlayerInventoryPacket. */
    private static final int OFFHAND_SLOT = 40;
    /** Same slot seen by the player inventory menu (ClientboundContainerSetSlotPacket). */
    private static final int OFFHAND_CONTAINER_SLOT = 45;
    /** The client moves the item by 0.4 per tick: 3 ticks to go from 0 to fully raised. */
    private static final int RISE_TICKS = 3;
    /** Below this the effect is not even visible, so it is not worth a single packet. */
    private static final int MIN_TICKS = 4;

    private static volatile Mode mode = Mode.HOLD;
    private static volatile Strategy strategy;
    private static volatile boolean resolved;

    private static final Map<UUID, BukkitTask> RUNNING = new ConcurrentHashMap<>();
    /** Which of the two copies has to be sent next: they have to alternate to be a "change". */
    private static final Map<UUID, Boolean> TOGGLE = new ConcurrentHashMap<>();

    private OffhandRecharge()
    {
    }

    /**
     * Selects the recharge feedback. {@code NONE} also stops every animation still running.
     */
    public static synchronized void setMode(Mode newMode)
    {
        mode = newMode == null ? Mode.HOLD : newMode;
        strategy = null;
        resolved = false;

        if (mode != Mode.HOLD)
            cancelAll();
    }

    public static Mode getMode()
    {
        return mode;
    }

    /** Description of what is in use (shown by {@code /rdwdebug}). */
    public static String describe()
    {
        switch (mode)
        {
            case NONE:
                return "disabled";

            case COOLDOWN:
                return "vanilla item cooldown on the off-hand weapon";

            case DIP:
            {
                Strategy s = strategy;
                return "one dip per hit, ~6 ticks ("
                        + (s == null ? "not resolved yet" : s.describe()) + ")";
            }

            default:
            {
                Strategy s = strategy;
                return "the weapon is lowered for the whole recharge, one packet per tick ("
                        + (s == null ? "not resolved yet" : s.describe()) + ")";
            }
        }
    }

    /**
     * Starts the recharge feedback of the off-hand weapon.
     *
     * @param player the player who just attacked with the off hand.
     * @param ticks  length of the recharge, in ticks (the cooldown of the weapon).
     */
    public static void start(Player player, int ticks)
    {
        if (mode == Mode.NONE || player == null)
            return;

        cancel(player);

        switch (mode)
        {
            case COOLDOWN:
                startCooldown(player, ticks);
                return;

            case DIP:
                startDip(player);
                return;

            default:
                if (ticks >= MIN_TICKS)
                    startHold(player, ticks);
        }
    }

    /** Stops the animation of a player and shows its real off-hand item again. */
    public static void cancel(Player player)
    {
        if (player == null)
            return;

        BukkitTask task = RUNNING.remove(player.getUniqueId());
        if (task == null)
            return;

        task.cancel();
        restore(player);
    }

    /** Stops everything (reload, plugin disable). */
    public static void cancelAll()
    {
        for (UUID id : new ArrayList<>(RUNNING.keySet()))
        {
            BukkitTask task = RUNNING.remove(id);
            if (task != null)
                task.cancel();

            Player player = Bukkit.getPlayer(id);
            if (player != null)
                restore(player);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // dip: one packet per hit, the client plays the whole animation by itself
    // ---------------------------------------------------------------------------------------------

    /**
     * A single, deterministic dip: the client lowers the weapon to "swap" it and raises it again
     * (~3 ticks down, ~3 ticks up) without needing anything else. Nothing can arrive late and
     * break it, and it costs one packet per hit.
     */
    private static void startDip(Player player)
    {
        Strategy s = strategy();
        if (s == null)
            return;

        ItemStack real = player.getInventory().getItemInOffHand();
        if (real == null || real.getType().isAir())
            return;

        UUID id = player.getUniqueId();
        boolean second = Boolean.TRUE.equals(TOGGLE.get(id));
        TOGGLE.put(id, !second);

        int copy = second ? 2 : 1;
        Object packet = s.packet(variant(real, copy));
        if (packet == null)
        {
            Debug.log("off-hand recharge: the slot packet could not be built, the animation is disabled");
            return;
        }

        try
        {
            s.send(player, packet);
            Debug.log("off-hand recharge: dipping " + real.getType() + " (copy " + copy + ")");
        }
        catch (Throwable t)
        {
            log(Level.WARNING, "the off-hand recharge could not be sent: " + t, null);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // hold: experimental, one packet per tick for the whole recharge
    // ---------------------------------------------------------------------------------------------

    private static void startHold(Player player, int ticks)
    {
        Strategy s = strategy();
        if (s == null || Main.inst == null)
            return;

        ItemStack real = player.getInventory().getItemInOffHand();
        if (real == null || real.getType().isAir())
            return;

        ItemStack downA = variant(real, 1);
        ItemStack downB = variant(real, 2);

        Object packetA = s.packet(downA);
        Object packetB = s.packet(downB);
        if (packetA == null || packetB == null)
        {
            Debug.log("off-hand recharge: the slot packets could not be built, the animation is disabled");
            return;
        }

        // The weapon goes down, stays down and comes back up: the rise is left to the client.
        final int hold = Math.max(1, ticks - RISE_TICKS);
        final Material type = real.getType();
        final int amount = real.getAmount();
        final UUID id = player.getUniqueId();

        Debug.log("off-hand recharge: lowering " + type + " for " + hold + " ticks (recharge " + ticks + ")");

        // Start right away, so the weapon goes down with the hit and not one tick later.
        try
        {
            s.send(player, packetA);
        }
        catch (Throwable t)
        {
            log(Level.WARNING, "the off-hand recharge animation could not be started: " + t, null);
            return;
        }

        BukkitTask task = new BukkitRunnable()
        {
            int tick = 1;

            @Override
            public void run()
            {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline())
                {
                    this.cancel();
                    RUNNING.remove(id);
                    return;
                }

                tick += 1;

                ItemStack current = p.getInventory().getItemInOffHand();
                if (current.getType() != type || current.getAmount() != amount)
                {
                    // The weapon changed while it was down: stop and show the real one.
                    Debug.log("off-hand recharge: the off-hand item changed, animation stopped");
                    this.cancel();
                    RUNNING.remove(id);
                    restore(p);
                    return;
                }

                if (tick > hold)
                {
                    this.cancel();
                    RUNNING.remove(id);
                    restore(p);
                    return;
                }

                try
                {
                    // A different copy every tick: the client keeps believing the item is being
                    // swapped and holds the weapon down.
                    s.send(p, tick % 2 == 0 ? packetB : packetA);
                }
                catch (Throwable t)
                {
                    this.cancel();
                    RUNNING.remove(id);
                    log(Level.WARNING, "the off-hand recharge animation stopped: " + t, null);
                    restore(p);
                }
            }
        }.runTaskTimer(Main.inst, 1, 1);

        BukkitTask previous = RUNNING.put(id, task);
        if (previous != null)
            previous.cancel();

        // The task can already be over (player offline, weapon changed): do not keep it around.
        if (task.isCancelled())
            RUNNING.remove(id, task);
    }

    /** Sends the real off-hand item again: the client raises the weapon back up. */
    private static void restore(Player player)
    {
        Strategy s = strategy;
        if (s == null || player == null || !player.isOnline())
            return;

        try
        {
            Object packet = s.packet(player.getInventory().getItemInOffHand());
            if (packet != null)
            {
                s.send(player, packet);
                Debug.log("off-hand recharge: " + player.getInventory().getItemInOffHand().getType() + " is back up");
            }
        }
        catch (Throwable t)
        {
            log(Level.WARNING, "the off-hand item could not be restored: " + t, null);
        }
    }

    /**
     * A copy of the weapon that only differs by {@code minecraft:repair_cost}: an invisible
     * component (it is the anvil penalty) that the client does NOT ignore when it decides whether
     * the item in hand changed ({@code damage} is the only ignored one).
     */
    private static ItemStack variant(ItemStack real, int value)
    {
        ItemStack fake = real.clone();

        int current = 0;
        if (fake.hasData(DataComponentTypes.REPAIR_COST))
        {
            Integer read = fake.getData(DataComponentTypes.REPAIR_COST);
            if (read != null)
                current = read;
        }

        // The copy has to differ from the real item too, or the very first tick does nothing.
        if (current == value)
            value += 2;

        fake.setData(DataComponentTypes.REPAIR_COST, value);
        return fake;
    }

    // ---------------------------------------------------------------------------------------------
    // vanilla item cooldown
    // ---------------------------------------------------------------------------------------------

    private static void startCooldown(Player player, int ticks)
    {
        ItemStack weapon = player.getInventory().getItemInOffHand();
        if (weapon == null || weapon.getType().isAir())
            return;

        Key group = null;

        if (weapon.hasData(DataComponentTypes.USE_COOLDOWN))
            group = weapon.getData(DataComponentTypes.USE_COOLDOWN).cooldownGroup();

        if (group == null)
        {
            // Every weapon gets its own group, so a cooldown never leaks on the other hand.
            group = new NamespacedKey("realdualwield", UUID.randomUUID().toString());

            float seconds = Math.max(0.05f, ticks / 20.0f);
            try
            {
                weapon.setData(DataComponentTypes.USE_COOLDOWN,
                        UseCooldown.useCooldown(seconds).cooldownGroup(group).build());
                player.getInventory().setItemInOffHand(weapon);
            }
            catch (Throwable t)
            {
                Debug.log("off-hand recharge: could not write the use cooldown on " + weapon.getType() + ": " + t);
                return;
            }
        }

        player.setCooldown(group, ticks);
        Debug.log("off-hand recharge: vanilla cooldown of " + ticks + " ticks on " + weapon.getType() + " (group " + group + ")");
    }

    // ---------------------------------------------------------------------------------------------
    // packet plumbing
    // ---------------------------------------------------------------------------------------------

    /** Builds and delivers the packet that shows an item in the off-hand slot. */
    private interface Strategy
    {
        String describe();

        /** The packet carrying the item, or null when it cannot be built. */
        Object packet(ItemStack item);

        void send(Player player, Object packet);
    }

    private static Strategy strategy()
    {
        Strategy s = strategy;
        if (s != null || resolved)
            return s;

        synchronized (OffhandRecharge.class)
        {
            if (strategy == null && !resolved)
            {
                try
                {
                    strategy = SlotSender.probe(Bukkit.getOnlinePlayers().isEmpty()
                            ? null
                            : Bukkit.getOnlinePlayers().iterator().next());
                }
                catch (Throwable t)
                {
                    Debug.log("off-hand recharge: " + t);
                    strategy = null;
                }

                resolved = true;

                if (strategy == null)
                    log(Level.WARNING, "the off-hand recharge animation is not available on this server "
                            + "(no usable inventory packet): set offhand-recharge: cooldown or none", null);
                else
                    log(Level.INFO, "off-hand recharge animation: " + strategy.describe(), null);
            }

            return strategy;
        }
    }

    /**
     * Sends {@code ClientboundSetPlayerInventoryPacket} (a record on 26.x, so it is built by
     * reflection) or, when it does not exist, {@code ClientboundContainerSetSlotPacket}.
     */
    private static final class SlotSender implements Strategy
    {
        private static final String[] PACKET_NAMES =
                {
                        "net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket",
                        "net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket"
                };

        private final Method asNMSCopy;
        private final Constructor<?> constructor;
        private final int arity;
        private final String name;

        private SlotSender(Method asNMSCopy, Constructor<?> constructor, int arity, String name)
        {
            this.asNMSCopy = asNMSCopy;
            this.constructor = constructor;
            this.arity = arity;
            this.name = name;
        }

        static SlotSender probe(Player player)
        {
            Method asNMSCopy = asNMSCopy(player);
            if (asNMSCopy == null)
                return null;

            Class<?> nmsItem = asNMSCopy.getReturnType();

            for (String packetName : PACKET_NAMES)
            {
                Class<?> packetClass = clazz(packetName);
                if (packetClass == null)
                    continue;

                for (Constructor<?> ctor : packetClass.getDeclaredConstructors())
                {
                    Class<?>[] params = ctor.getParameterTypes();
                    SlotSender candidate = null;

                    // (slot, item): the 26.x record that updates a player inventory slot.
                    if (params.length == 2 && params[0] == int.class && params[1] == nmsItem)
                        candidate = new SlotSender(asNMSCopy, ctor, 2, packetClass.getSimpleName());

                    // (containerId, stateId, slot, item): the classic container slot update.
                    if (params.length == 4 && params[0] == int.class && params[1] == int.class
                            && params[2] == int.class && params[3] == nmsItem)
                        candidate = new SlotSender(asNMSCopy, ctor, 4, packetClass.getSimpleName());

                    // Only accept it when a packet can really be built with it.
                    if (candidate != null && candidate.packet(new ItemStack(Material.AIR)) != null)
                        return candidate;
                }
            }

            return null;
        }

        private static Method asNMSCopy(Player player)
        {
            List<String> packages = new ArrayList<>();
            packages.add("org.bukkit.craftbukkit");

            if (player != null)
            {
                Object handle = OffhandAnimation.nmsEntity(player);
                if (handle != null)
                    packages.add(0, handle.getClass().getPackage().getName().replace(".entity", ""));
            }

            for (String base : packages)
            {
                try
                {
                    Class<?> cls = Class.forName(base + ".inventory.CraftItemStack");
                    Method method = cls.getMethod("asNMSCopy", ItemStack.class);
                    method.setAccessible(true);
                    return method;
                }
                catch (Throwable ignored)
                {
                }
            }

            return null;
        }

        private static Class<?> clazz(String name)
        {
            try
            {
                return Class.forName(name);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        @Override
        public String describe()
        {
            return name + " (client side slot update)";
        }

        @Override
        public Object packet(ItemStack item)
        {
            try
            {
                Object nms = asNMSCopy.invoke(null, item == null ? new ItemStack(org.bukkit.Material.AIR) : item);
                if (nms == null)
                    return null;

                if (arity == 2)
                    return constructor.newInstance(OFFHAND_SLOT, nms);

                return constructor.newInstance(0, 0, OFFHAND_CONTAINER_SLOT, nms);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        @Override
        public void send(Player player, Object packet)
        {
            // ProtocolLib first: the packet stays visible to the other plugins.
            if (OffhandAnimation.isProtocolLibEnabled() && ProtocolLibBridge.send(player, packet, name))
                return;

            sendDirect(player, packet);
        }

        /** Last resort: write the packet straight into the connection of the player. */
        private static boolean sendDirect(Player player, Object packet)
        {
            try
            {
                Object handle = OffhandAnimation.nmsEntity(player);
                if (handle == null)
                    return false;

                Object connection = null;
                for (Field field : handle.getClass().getFields())
                {
                    if (field.getName().equals("connection"))
                    {
                        connection = field.get(handle);
                        break;
                    }
                }

                if (connection == null)
                {
                    for (Method method : handle.getClass().getMethods())
                    {
                        if (method.getName().equals("connection") && method.getParameterCount() == 0)
                        {
                            connection = method.invoke(handle);
                            break;
                        }
                    }
                }

                if (connection == null)
                    return false;

                for (Method method : connection.getClass().getMethods())
                {
                    if (method.getName().equals("send") && method.getParameterCount() == 1
                            && method.getParameterTypes()[0].isInstance(packet))
                    {
                        method.invoke(connection, packet);
                        return true;
                    }
                }
            }
            catch (Throwable ignored)
            {
            }

            return false;
        }
    }

    /**
     * Every reference to a ProtocolLib class lives here: the nested class is only loaded (and
     * verified) when ProtocolLib is actually installed and enabled.
     */
    private static final class ProtocolLibBridge
    {
        static boolean send(Player player, Object packet, String packetName)
        {
            try
            {
                com.comphenix.protocol.PacketType type = packetName.contains("SetPlayerInventory")
                        ? com.comphenix.protocol.PacketType.Play.Server.SET_PLAYER_INVENTORY
                        : com.comphenix.protocol.PacketType.Play.Server.SET_SLOT;

                com.comphenix.protocol.events.PacketContainer container =
                        new com.comphenix.protocol.events.PacketContainer(type, packet);

                com.comphenix.protocol.ProtocolLibrary.getProtocolManager().sendServerPacket(player, container);
                return true;
            }
            catch (Throwable t)
            {
                return false;
            }
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
}
