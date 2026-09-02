package beer.devs.realdualwield;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;

/**
 * Optional Nexo (https://nexomc.com) support.
 *
 * <p>The hook is resolved by reflection, so the plugin does not need the Nexo repository to be
 * built and keeps working when Nexo is not installed or changes its API.
 *
 * <p>API used (see https://jd.nexomc.com):
 * <ul>
 *     <li>{@code com.nexomc.nexo.api.NexoItems#idFromItem(ItemStack)} -> item id or null</li>
 *     <li>{@code com.nexomc.nexo.api.NexoBlocks#isCustomBlock(ItemStack)}</li>
 *     <li>{@code com.nexomc.nexo.api.NexoBlocks#isCustomBlock(String itemId)}</li>
 * </ul>
 */
public final class NexoHook
{
    private static final String NEXO_ITEMS = "com.nexomc.nexo.api.NexoItems";
    private static final String NEXO_BLOCKS = "com.nexomc.nexo.api.NexoBlocks";

    private static Method idFromItem;
    private static Method isCustomBlockItem;
    private static Method isCustomBlockId;

    private NexoHook()
    {
    }

    public static boolean init(PluginManager pluginManager)
    {
        idFromItem = null;
        isCustomBlockItem = null;
        isCustomBlockId = null;

        if (pluginManager.getPlugin("Nexo") == null)
            return false;

        try
        {
            Class<?> items = Class.forName(NEXO_ITEMS);
            try
            {
                idFromItem = items.getMethod("idFromItem", ItemStack.class);
            }
            catch (Throwable ignored)
            {
            }

            Class<?> blocks = Class.forName(NEXO_BLOCKS);
            try
            {
                isCustomBlockItem = blocks.getMethod("isCustomBlock", ItemStack.class);
            }
            catch (Throwable ignored)
            {
            }
            try
            {
                isCustomBlockId = blocks.getMethod("isCustomBlock", String.class);
            }
            catch (Throwable ignored)
            {
            }

            return idFromItem != null || isCustomBlockItem != null || isCustomBlockId != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    public static boolean isHooked()
    {
        return idFromItem != null || isCustomBlockItem != null || isCustomBlockId != null;
    }

    /** Returns the Nexo id of the given item, or null when it is not a Nexo item. */
    public static String idFromItem(ItemStack item)
    {
        if (idFromItem == null || item == null)
            return null;

        try
        {
            Object id = idFromItem.invoke(null, item);
            return id instanceof String s ? s : null;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** True when the item is a Nexo custom block (it would be placed on right click). */
    public static boolean isCustomBlock(ItemStack item)
    {
        if (item == null)
            return false;

        if (isCustomBlockItem != null)
        {
            try
            {
                Object result = isCustomBlockItem.invoke(null, item);
                if (result instanceof Boolean b)
                    return b;
            }
            catch (Throwable ignored)
            {
            }
        }

        String id = idFromItem(item);
        if (id != null && isCustomBlockId != null)
        {
            try
            {
                Object result = isCustomBlockId.invoke(null, id);
                if (result instanceof Boolean b)
                    return b;
            }
            catch (Throwable ignored)
            {
            }
        }

        return false;
    }
}
