package beer.devs.realdualwield;

import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Optional support for <b>MMOItems</b> (items, stats, two handed weapons) and <b>MMOCore</b>
 * (mana &amp; stamina).
 *
 * <p>Everything is resolved by reflection, so:
 * <ul>
 *     <li>the plugin builds without access to the (premium) MMOItems/MMOCore jars;</li>
 *     <li>nothing breaks when MMOItems/MMOCore are not installed or when their API changes;</li>
 *     <li>every lookup has more than one candidate name, so a renamed method only disables the
 *     feature it belongs to.</li>
 * </ul>
 *
 * <p>{@code /rdwdebug} prints {@link #describe()}: which plugin was found and which methods
 * resolved, so a broken integration can be fixed without guessing.
 */
public final class MMOHook
{
    // Candidate class names: the NBTItem class lives in MythicLib since MMOItems 6.7, but it used
    // to be inside MMOItems itself.
    private static final String[] NBT_ITEM_CLASSES = {
            "io.lumine.mythic.lib.api.item.NBTItem",
            "net.Indyuce.mmoitems.api.item.NBTItem"
    };
    private static final String MI_TYPE = "net.Indyuce.mmoitems.api.Type";
    private static final String MI_PLAYER_DATA = "net.Indyuce.mmoitems.api.player.PlayerData";
    private static final String MI_ITEM_STATS = "net.Indyuce.mmoitems.ItemStats";
    private static final String MC_PLAYER_DATA = "net.Indyuce.mmocore.api.player.PlayerData";

    private static boolean hasMMOItems;
    private static boolean hasMMOCore;
    private static final List<String> REPORT = new ArrayList<>();

    // NBTItem
    private static Method nbtGet;         // static NBTItem.get(ItemStack)
    private static Method nbtWrap;        // VersionWrapper#getNBTItem(ItemStack) (MythicLib)
    private static Object versionWrapper;
    private static Method nbtGetStat;     // NBTItem#getStat(String)
    private static Method nbtGetDouble;   // older name of the same thing
    private static Method nbtGetBoolean;
    private static Method nbtHasType;
    private static Method nbtGetType;     // NBTItem#getType() -> String id
    private static Method nbtGetItem;

    // MMOItems
    private static Method miTypeGetItem;  // Type.get(ItemStack)
    private static Method miTypeGetId;    // Type.get(String)
    private static Method miTypeIsWeapon;
    private static Method miPlayerDataGetPlayer;
    private static Method miPlayerDataGetUuid;
    private static Method miIsEncumbered;
    private static String twoHandedPath;
    private static String handwornPath;

    // MMOCore
    private static Method mcGetPlayer;
    private static Method mcGetUuid;
    private static Method mcGetMana;
    private static Method mcGiveMana;
    private static Method mcGiveManaReason;
    private static Object mcReasonOther;
    private static Method mcGetStamina;
    private static Method mcGiveStamina;
    private static Method mcGiveStaminaReason;

    private MMOHook()
    {
    }

    public static boolean hasMMOItems()
    {
        return hasMMOItems;
    }

    public static boolean hasMMOCore()
    {
        return hasMMOCore;
    }

    // ------------------------------------------------------------------ init

    public static boolean init(PluginManager pluginManager)
    {
        reset();

        Plugin mmoItems = pluginManager.getPlugin("MMOItems");
        Plugin mmoCore = pluginManager.getPlugin("MMOCore");

        REPORT.add("MMOItems: " + (mmoItems == null ? "not installed" : "v" + mmoItems.getPluginMeta().getVersion()));
        REPORT.add("MMOCore: " + (mmoCore == null ? "not installed" : "v" + mmoCore.getPluginMeta().getVersion()));

        if (mmoItems != null)
            initMMOItems();
        if (mmoCore != null)
            initMMOCore();

        return hasMMOItems || hasMMOCore;
    }

    private static void initMMOItems()
    {
        Class<?> nbtItem = null;
        for (String name : NBT_ITEM_CLASSES)
        {
            nbtItem = findClass(name);
            if (nbtItem != null)
            {
                report("NBTItem class", name, true);
                break;
            }
        }
        if (nbtItem == null)
        {
            report("NBTItem class", NBT_ITEM_CLASSES[0] + " / " + NBT_ITEM_CLASSES[1], false);
            return;
        }

        nbtGet = findMethod(nbtItem, "get", ItemStack.class);
        report("NBTItem.get(ItemStack)", nbtItem.getName(), nbtGet != null);

        nbtGetStat = findMethod(nbtItem, "getStat", String.class);
        nbtGetDouble = findMethod(nbtItem, "getDouble", String.class);
        report("NBTItem#getStat(String)", nbtItem.getName(), nbtGetStat != null || nbtGetDouble != null);

        nbtGetBoolean = findMethod(nbtItem, "getBoolean", String.class);
        report("NBTItem#getBoolean(String)", nbtItem.getName(), nbtGetBoolean != null);

        nbtHasType = findMethod(nbtItem, "hasType");
        report("NBTItem#hasType()", nbtItem.getName(), nbtHasType != null);

        nbtGetType = findMethod(nbtItem, "getType");
        report("NBTItem#getType()", nbtItem.getName(), nbtGetType != null);

        nbtGetItem = findMethod(nbtItem, "getItem");

        // MythicLib fallback to obtain an NBTItem: MythicLib.plugin.getVersion().getWrapper().getNBTItem(stack)
        try
        {
            Class<?> mythicLib = findClass("io.lumine.mythic.lib.MythicLib");
            if (mythicLib != null)
            {
                Object plugin = mythicLib.getField("plugin").get(null);
                Object version = plugin.getClass().getMethod("getVersion").invoke(plugin);
                versionWrapper = version.getClass().getMethod("getWrapper").invoke(version);
                nbtWrap = findMethod(versionWrapper.getClass(), "getNBTItem", ItemStack.class);
            }
        }
        catch (Throwable ignored)
        {
        }
        report("MythicLib wrapper fallback", "getNBTItem(ItemStack)", nbtWrap != null);

        Class<?> type = findClass(MI_TYPE);
        if (type != null)
        {
            miTypeGetItem = findMethod(type, "get", ItemStack.class);
            miTypeGetId = findMethod(type, "get", String.class);
            miTypeIsWeapon = findMethod(type, "isWeapon");
            report("Type.get(ItemStack)", MI_TYPE, miTypeGetItem != null);
            report("Type#isWeapon()", MI_TYPE, miTypeIsWeapon != null);
        }
        else
        {
            report("Type", MI_TYPE, false);
        }

        Class<?> playerData = findClass(MI_PLAYER_DATA);
        if (playerData != null)
        {
            miPlayerDataGetPlayer = findMethod(playerData, "get", org.bukkit.OfflinePlayer.class);
            miPlayerDataGetUuid = findMethod(playerData, "get", java.util.UUID.class);
            miIsEncumbered = findMethod(playerData, "isEncumbered");
            if (miIsEncumbered == null)
                miIsEncumbered = findMethod(playerData, "areHandsFull");
            report("PlayerData#get(OfflinePlayer)", MI_PLAYER_DATA, miPlayerDataGetPlayer != null);
            report("PlayerData#isEncumbered()", MI_PLAYER_DATA, miIsEncumbered != null);
        }
        else
        {
            report("PlayerData", MI_PLAYER_DATA, false);
        }

        twoHandedPath = nbtPath("TWO_HANDED");
        handwornPath = nbtPath("HANDWORN");
        report("ItemStats.TWO_HANDED path", String.valueOf(twoHandedPath), twoHandedPath != null);
        report("ItemStats.HANDWORN path", String.valueOf(handwornPath), handwornPath != null);

        hasMMOItems = true;
    }

    private static void initMMOCore()
    {
        Class<?> playerData = findClass(MC_PLAYER_DATA);
        if (playerData == null)
        {
            report("MMOCore PlayerData", MC_PLAYER_DATA, false);
            return;
        }

        mcGetPlayer = findMethod(playerData, "get", org.bukkit.OfflinePlayer.class);
        mcGetUuid = findMethod(playerData, "get", java.util.UUID.class);
        mcGetMana = findMethod(playerData, "getMana");
        mcGetStamina = findMethod(playerData, "getStamina");
        mcGiveMana = findMethod(playerData, "giveMana", double.class);
        mcGiveStamina = findMethod(playerData, "giveStamina", double.class);

        // Newer builds deprecated giveMana(double) in favour of giveMana(double, UpdateReason).
        if (mcGiveMana == null || mcGiveStamina == null)
        {
            Class<?> reason = findClass("net.Indyuce.mmocore.api.event.PlayerResourceUpdateEvent$UpdateReason");
            if (reason != null && reason.isEnum())
            {
                for (Object constant : reason.getEnumConstants())
                    if (String.valueOf(constant).equals("OTHER"))
                        mcReasonOther = constant;

                mcGiveManaReason = findMethod(playerData, "giveMana", double.class, reason);
                mcGiveStaminaReason = findMethod(playerData, "giveStamina", double.class, reason);
            }
        }

        report("PlayerData#getMana()", MC_PLAYER_DATA, mcGetMana != null);
        report("PlayerData#getStamina()", MC_PLAYER_DATA, mcGetStamina != null);
        report("PlayerData#giveMana(-x)", MC_PLAYER_DATA, mcGiveMana != null || mcGiveManaReason != null);
        report("PlayerData#giveStamina(-x)", MC_PLAYER_DATA, mcGiveStamina != null || mcGiveStaminaReason != null);

        hasMMOCore = (mcGetMana != null && (mcGiveMana != null || mcGiveManaReason != null))
                || (mcGetStamina != null && (mcGiveStamina != null || mcGiveStaminaReason != null));
    }

    private static void reset()
    {
        hasMMOItems = false;
        hasMMOCore = false;
        REPORT.clear();

        nbtGet = nbtWrap = nbtGetStat = nbtGetDouble = nbtGetBoolean = nbtHasType = nbtGetType = nbtGetItem = null;
        versionWrapper = null;
        miTypeGetItem = miTypeGetId = miTypeIsWeapon = miPlayerDataGetPlayer = miPlayerDataGetUuid = miIsEncumbered = null;
        twoHandedPath = handwornPath = null;
        mcGetPlayer = mcGetUuid = mcGetMana = mcGiveMana = mcGiveManaReason = mcGetStamina = mcGiveStamina = mcGiveStaminaReason = null;
        mcReasonOther = null;
    }

    /** Multi line report of what has been resolved: printed by {@code /rdwdebug}. */
    public static List<String> describe()
    {
        List<String> lines = new ArrayList<>(REPORT);
        lines.add("features: two handed = " + (hasMMOItems && twoHandedPath != null)
                + ", mana/stamina = " + (hasMMOItems && hasMMOCore && nbtGetStat != null)
                + ", attack speed = " + (hasMMOItems && (nbtGetStat != null || nbtGetDouble != null))
                + ", all MMOItems weapons = " + (hasMMOItems && miTypeIsWeapon != null));
        return lines;
    }

    // ------------------------------------------------------------- reflection

    private static Class<?> findClass(String name)
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

    private static Method findMethod(Class<?> owner, String name, Class<?>... params)
    {
        try
        {
            Method method = owner.getMethod(name, params);
            method.setAccessible(true);
            return method;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static void report(String what, String where, boolean ok)
    {
        REPORT.add((ok ? "  [OK]      " : "  [MISSING] ") + what + "  (" + where + ")");
    }

    private static String nbtPath(String statName)
    {
        try
        {
            Class<?> itemStats = Class.forName(MI_ITEM_STATS);
            Field field = itemStats.getField(statName);
            Object stat = field.get(null);
            Method path = findMethod(stat.getClass(), "getNBTPath");
            if (path == null)
                path = findMethod(stat.getClass(), "getId");
            Object value = path == null ? null : path.invoke(stat);
            return value instanceof String s ? s : statName;
        }
        catch (Throwable t)
        {
            // Not fatal: the raw stat id is the NBT path on every MMOItems build we know of.
            return statName;
        }
    }

    // ------------------------------------------------------------------ NBT

    /** The MMOItems/MythicLib NBT view of an item, or null when it cannot be built. */
    private static Object nbt(ItemStack item)
    {
        if (!hasMMOItems || item == null || item.getType() == Material.AIR)
            return null;

        if (nbtGet != null)
        {
            try
            {
                Object result = nbtGet.invoke(null, item);
                if (result != null)
                    return result;
            }
            catch (Throwable ignored)
            {
            }
        }

        if (nbtWrap != null && versionWrapper != null)
        {
            try
            {
                return nbtWrap.invoke(versionWrapper, item);
            }
            catch (Throwable ignored)
            {
            }
        }

        return null;
    }

    /** True when the item was made by MMOItems. */
    public static boolean isMMOItem(ItemStack item)
    {
        Object nbtItem = nbt(item);
        if (nbtItem == null || nbtHasType == null)
            return false;

        try
        {
            Object result = nbtHasType.invoke(nbtItem);
            return result instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** True when MMOItems considers the item a weapon of any type (sword, dagger, staff...). */
    public static boolean isWeapon(ItemStack item)
    {
        if (!hasMMOItems || miTypeIsWeapon == null || item == null)
            return false;

        try
        {
            Object type = null;

            if (miTypeGetItem != null)
                type = miTypeGetItem.invoke(null, item);

            if (type == null && nbtGetType != null && miTypeGetId != null)
            {
                Object nbtItem = nbt(item);
                if (nbtItem != null)
                {
                    Object id = nbtGetType.invoke(nbtItem);
                    if (id instanceof String s)
                        type = miTypeGetId.invoke(null, s);
                }
            }

            if (type == null)
                return false;

            Object result = miTypeIsWeapon.invoke(type);
            return result instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** Reads a numeric MMOItems stat from the item (0 when the item has no such stat). */
    public static double stat(ItemStack item, String id)
    {
        Object nbtItem = nbt(item);
        if (nbtItem == null)
            return 0;

        Method reader = nbtGetStat != null ? nbtGetStat : nbtGetDouble;
        if (reader == null)
            return 0;

        try
        {
            Object result = reader.invoke(nbtItem, id);
            return result instanceof Number n ? n.doubleValue() : 0;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    private static boolean nbtBoolean(ItemStack item, String path)
    {
        Object nbtItem = nbt(item);
        if (nbtItem == null || nbtGetBoolean == null || path == null)
            return false;

        try
        {
            Object result = nbtGetBoolean.invoke(nbtItem, path);
            return result instanceof Boolean b && b;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static boolean isTwoHanded(ItemStack item)
    {
        return isMMOItem(item) && nbtBoolean(item, twoHandedPath);
    }

    public static boolean isHandworn(ItemStack item)
    {
        return isMMOItem(item) && nbtBoolean(item, handwornPath);
    }

    public static double manaCost(ItemStack item)
    {
        return stat(item, "MANA_COST");
    }

    public static double staminaCost(ItemStack item)
    {
        return stat(item, "STAMINA_COST");
    }

    /**
     * Attacks per second of an MMOItems weapon, or 0 when the item does not define it.
     *
     * <p>MMOItems writes {@code attack-speed} both as an NBT stat and as the vanilla attribute;
     * the NBT stat is read first because it is the value MMOItems itself uses.
     */
    public static double attackSpeed(ItemStack item)
    {
        double speed = stat(item, "ATTACK_SPEED");
        if (speed > 0)
            return speed;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getAttributeModifiers() == null)
            return 0;

        Collection<AttributeModifier> modifiers = meta.getAttributeModifiers().get(Attribute.ATTACK_SPEED);
        if (modifiers == null)
            return 0;

        // MMOItems stores the apparent value as an offset from the vanilla base (4):
        // apparent = base + modifier.
        double total = 4;
        for (AttributeModifier modifier : modifiers)
            total += modifier.getAmount();

        return total > 0 ? total : 0;
    }

    // -------------------------------------------------------- two handed (encumbered)

    /**
     * True when the player holds a two handed item in one hand and something else in the other
     * (the MMOItems "hands too charged" state).
     *
     * <p>MMOItems' own {@code PlayerData#isEncumbered()} is used when available; the fallback
     * reproduces exactly the same calculation reading the TWO_HANDED/HANDWORN NBT tags, so the
     * behaviour is identical even when that method changes name or disappears.
     */
    public static boolean isEncumbered(Player player)
    {
        if (!hasMMOItems || player == null)
            return false;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();

        // Cheap exit: being encumbered always requires something in both hands. This keeps the
        // check out of the way for the (very common) case of a player holding a single item.
        if (isEmpty(mainHand) || isEmpty(offHand))
            return false;

        if (miPlayerDataGetPlayer != null && miIsEncumbered != null)
        {
            try
            {
                Object data = miPlayerDataGetPlayer.invoke(null, player);
                if (data == null && miPlayerDataGetUuid != null)
                    data = miPlayerDataGetUuid.invoke(null, player.getUniqueId());
                if (data != null)
                {
                    Object result = miIsEncumbered.invoke(data);
                    if (result instanceof Boolean b)
                        return b;
                }
            }
            catch (Throwable t)
            {
                Debug.log("isEncumbered() failed (" + t + "), falling back on the NBT check");
            }
        }

        boolean mainTwoHanded = isTwoHanded(mainHand);
        boolean offTwoHanded = isTwoHanded(offHand);
        boolean mainEncumbering = !isHandworn(mainHand);
        boolean offEncumbering = !isHandworn(offHand);

        return (mainTwoHanded && offEncumbering) || (mainEncumbering && offTwoHanded);
    }

    private static boolean isEmpty(ItemStack item)
    {
        return item == null || item.getType() == Material.AIR;
    }

    // ------------------------------------------------------------- MMOCore mana

    private static Object mmoCoreData(Player player)
    {
        if (!hasMMOCore || player == null)
            return null;

        try
        {
            if (mcGetPlayer != null)
            {
                Object data = mcGetPlayer.invoke(null, player);
                if (data != null)
                    return data;
            }
            if (mcGetUuid != null)
                return mcGetUuid.invoke(null, player.getUniqueId());
        }
        catch (Throwable t)
        {
            Debug.log("MMOCore PlayerData.get() failed: " + t);
        }

        return null;
    }

    public static double getMana(Player player)
    {
        try
        {
            Object data = mmoCoreData(player);
            if (data == null || mcGetMana == null)
                return -1;

            Object result = mcGetMana.invoke(data);
            return result instanceof Number n ? n.doubleValue() : -1;
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    public static double getStamina(Player player)
    {
        try
        {
            Object data = mmoCoreData(player);
            if (data == null || mcGetStamina == null)
                return -1;

            Object result = mcGetStamina.invoke(data);
            return result instanceof Number n ? n.doubleValue() : -1;
        }
        catch (Throwable t)
        {
            return -1;
        }
    }

    private static void giveMana(Player player, double amount)
    {
        try
        {
            Object data = mmoCoreData(player);
            if (data == null)
                return;

            if (mcGiveMana != null)
                mcGiveMana.invoke(data, amount);
            else if (mcGiveManaReason != null)
                mcGiveManaReason.invoke(data, amount, mcReasonOther);
        }
        catch (Throwable t)
        {
            Debug.log("could not give " + amount + " mana: " + t);
        }
    }

    private static void giveStamina(Player player, double amount)
    {
        try
        {
            Object data = mmoCoreData(player);
            if (data == null)
                return;

            if (mcGiveStamina != null)
                mcGiveStamina.invoke(data, amount);
            else if (mcGiveStaminaReason != null)
                mcGiveStaminaReason.invoke(data, amount, mcReasonOther);
        }
        catch (Throwable t)
        {
            Debug.log("could not give " + amount + " stamina: " + t);
        }
    }

    /**
     * Applies the mana/stamina cost of an MMOItems weapon, exactly like MMOItems does
     * ({@code Weapon#applyWeaponCosts}).
     *
     * @return true when the player had enough mana and stamina, false otherwise (the attack must
     *         be cancelled and nothing has been consumed).
     */
    public static boolean consumeWeaponCosts(Player player, ItemStack weapon)
    {
        double mana = manaCost(weapon);
        double stamina = staminaCost(weapon);

        if (mana <= 0 && stamina <= 0)
            return true;

        if (hasMMOCore)
        {
            if (mana > 0 && getMana(player) < mana)
                return false;
            if (stamina > 0 && getStamina(player) < stamina)
                return false;

            if (mana > 0)
                giveMana(player, -mana);
            if (stamina > 0)
                giveStamina(player, -stamina);
            return true;
        }

        // No MMOCore: the cost cannot be paid, so it is simply ignored instead of blocking the hit.
        Debug.log("the weapon costs " + mana + " mana / " + stamina + " stamina but MMOCore is not available");
        return true;
    }
}
