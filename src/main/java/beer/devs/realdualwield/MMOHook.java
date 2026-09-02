package beer.devs.realdualwield;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional support for <b>MMOItems</b> (items/stats) and <b>MMOCore</b> (mana &amp; stamina).
 *
 * <p>Everything is resolved by reflection, so:
 * <ul>
 *     <li>the plugin builds without access to the (premium) MMOItems/MMOCore jars;</li>
 *     <li>nothing breaks when MMOItems/MMOCore are not installed or when their API changes;</li>
 *     <li>every call is wrapped in try/catch: a missing method only disables the feature it
 *     belongs to, and the reason is printed in the console when {@code debug} is on.</li>
 * </ul>
 *
 * <p>API used (matches the one MMOItems itself uses internally):
 * <ul>
 *     <li>{@code io.lumine.mythic.lib.api.item.NBTItem.get(ItemStack)} then
 *         {@code NBTItem#getStat(String)}, {@code #getBoolean(String)}, {@code #hasType()},
 *         {@code #getType()}, {@code #getItem()}</li>
 *     <li>{@code net.Indyuce.mmoitems.ItemStats.TWO_HANDED} / {@code HANDWORN} and
 *         {@code ItemStat#getNBTPath()}</li>
 *     <li>{@code net.Indyuce.mmoitems.api.player.PlayerData#get(OfflinePlayer)} and
 *         {@code #isEncumbered()} - the very same check MMOItems runs before letting a player
 *         use a weapon ("hands too charged")</li>
 *     <li>{@code net.Indyuce.mmoitems.api.Type.get(ItemStack)} and {@code Type#isWeapon()}</li>
 *     <li>{@code net.Indyuce.mmocore.api.player.PlayerData#get(OfflinePlayer)},
 *         {@code #getMana()}, {@code #giveMana(double)}, {@code #getStamina()},
 *         {@code #giveStamina(double)}</li>
 * </ul>
 */
public final class MMOHook
{
    private static final String NBT_ITEM = "io.lumine.mythic.lib.api.item.NBTItem";
    private static final String MI_TYPE = "net.Indyuce.mmoitems.api.Type";
    private static final String MI_PLAYER_DATA = "net.Indyuce.mmoitems.api.player.PlayerData";
    private static final String MI_ITEM_STATS = "net.Indyuce.mmoitems.ItemStats";
    private static final String MC_PLAYER_DATA = "net.Indyuce.mmocore.api.player.PlayerData";

    private static boolean hasMMOItems;
    private static boolean hasMMOCore;

    // NBTItem
    private static Method nbtGet;
    private static Method nbtGetStat;
    private static Method nbtGetBoolean;
    private static Method nbtHasType;
    private static Method nbtGetItem;

    // MMOItems
    private static Method miTypeGet;
    private static Method miTypeIsWeapon;
    private static Method miPlayerDataGet;
    private static Method miIsEncumbered;
    private static String twoHandedPath;
    private static String handwornPath;

    // MMOCore
    private static Method mcPlayerDataGet;
    private static Method mcGetMana;
    private static Method mcGiveMana;
    private static Method mcGetStamina;
    private static Method mcGiveStamina;

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

    public static boolean init(PluginManager pluginManager)
    {
        hasMMOItems = false;
        hasMMOCore = false;
        nbtGet = nbtGetStat = nbtGetBoolean = nbtHasType = nbtGetItem = null;
        miTypeGet = miTypeIsWeapon = miPlayerDataGet = miIsEncumbered = null;
        twoHandedPath = handwornPath = null;
        mcPlayerDataGet = mcGetMana = mcGiveMana = mcGetStamina = mcGiveStamina = null;

        boolean mmoItems = pluginManager.getPlugin("MMOItems") != null;
        boolean mmoCore = pluginManager.getPlugin("MMOCore") != null;

        if (mmoItems)
        {
            try
            {
                Class<?> nbtItem = Class.forName(NBT_ITEM);
                nbtGet = nbtItem.getMethod("get", ItemStack.class);
                nbtGetStat = nbtItem.getMethod("getStat", String.class);
                nbtGetBoolean = nbtItem.getMethod("getBoolean", String.class);
                nbtHasType = nbtItem.getMethod("hasType");
                try
                {
                    nbtGetItem = nbtItem.getMethod("getItem");
                }
                catch (Throwable ignored)
                {
                }

                Class<?> type = Class.forName(MI_TYPE);
                miTypeGet = type.getMethod("get", ItemStack.class);
                miTypeIsWeapon = type.getMethod("isWeapon");

                Class<?> playerData = Class.forName(MI_PLAYER_DATA);
                miPlayerDataGet = playerData.getMethod("get", org.bukkit.OfflinePlayer.class);
                try
                {
                    miIsEncumbered = playerData.getMethod("isEncumbered");
                }
                catch (Throwable ignored)
                {
                    // Older builds expose it as areHandsFull()
                    try
                    {
                        miIsEncumbered = playerData.getMethod("areHandsFull");
                    }
                    catch (Throwable ignored2)
                    {
                    }
                }

                twoHandedPath = nbtPath("TWO_HANDED");
                handwornPath = nbtPath("HANDWORN");

                if (nbtGetStat == null)
                    Debug.log("NBTItem#getStat(String) is missing: mana/stamina costs and attack speed are disabled.");

                hasMMOItems = true;
            }
            catch (Throwable t)
            {
                Debug.log("MMOItems found but its API could not be resolved: " + t);
            }
        }

        if (mmoCore && mmoItems)
        {
            try
            {
                Class<?> playerData = Class.forName(MC_PLAYER_DATA);
                mcPlayerDataGet = playerData.getMethod("get", org.bukkit.OfflinePlayer.class);
                mcGetMana = playerData.getMethod("getMana");
                mcGetStamina = playerData.getMethod("getStamina");
                mcGiveMana = playerData.getMethod("giveMana", double.class);
                mcGiveStamina = playerData.getMethod("giveStamina", double.class);
                hasMMOCore = true;
            }
            catch (Throwable t)
            {
                Debug.log("MMOCore found but its API could not be resolved: " + t);
            }
        }

        return hasMMOItems || hasMMOCore;
    }

    private static String nbtPath(String statName)
    {
        try
        {
            Class<?> itemStats = Class.forName(MI_ITEM_STATS);
            Field field = itemStats.getField(statName);
            Object stat = field.get(null);
            Method path = stat.getClass().getMethod("getNBTPath");
            Object value = path.invoke(stat);
            return value instanceof String s ? s : statName;
        }
        catch (Throwable t)
        {
            return statName;
        }
    }

    // ------------------------------------------------------------------ NBT

    /** The MMOItems/MythicLib NBT view of an item, or null when it is not available. */
    private static Object nbt(ItemStack item)
    {
        if (!hasMMOItems || nbtGet == null || item == null || item.getType() == Material.AIR)
            return null;

        try
        {
            return nbtGet.invoke(null, item);
        }
        catch (Throwable t)
        {
            return null;
        }
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
        if (!hasMMOItems || miTypeGet == null || item == null)
            return false;

        try
        {
            Object type = miTypeGet.invoke(null, item);
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
        if (nbtItem == null || nbtGetStat == null)
            return 0;

        try
        {
            Object result = nbtGetStat.invoke(nbtItem, id);
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

        var modifiers = meta.getAttributeModifiers().get(org.bukkit.attribute.Attribute.ATTACK_SPEED);
        if (modifiers == null)
            return 0;

        double total = 4; // vanilla base attack speed
        for (org.bukkit.attribute.AttributeModifier modifier : modifiers)
            total += modifier.getAmount();

        return total > 0 ? total : 0;
    }

    // -------------------------------------------------------- two handed (encumbered)

    /**
     * True when the player holds a two-handed item in one hand and something else in the other
     * (the MMOItems "hands too charged" state).
     *
     * <p>MMOItems' own {@code PlayerData#isEncumbered()} is used when available; the fallback
     * reproduces exactly the same calculation reading the TWO_HANDED/HANDWORN NBT tags, so that
     * the behaviour is identical even if that method changes name.
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

        if (miPlayerDataGet != null && miIsEncumbered != null)
        {
            try
            {
                Object data = miPlayerDataGet.invoke(null, player);
                Object result = miIsEncumbered.invoke(data);
                if (result instanceof Boolean b)
                    return b;
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
        if (!hasMMOCore || mcPlayerDataGet == null || player == null)
            return null;

        try
        {
            return mcPlayerDataGet.invoke(null, player);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    public static double getMana(Player player)
    {
        try
        {
            Object data = mmoCoreData(player);
            if (data == null)
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
            if (data == null)
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
            if (data != null)
                mcGiveMana.invoke(data, amount);
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
            if (data != null)
                mcGiveStamina.invoke(data, amount);
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
