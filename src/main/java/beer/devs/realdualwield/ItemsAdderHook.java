package beer.devs.realdualwield;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;

/**
 * Optional ItemsAdder support.
 *
 * <p>The hook is resolved by reflection so that the plugin does not need the (unreachable)
 * ItemsAdder maven repository to be built, and so that it keeps working when ItemsAdder changes
 * or is not installed at all.
 */
public final class ItemsAdderHook
{
    private static final String CLASS_NAME = "dev.lone.itemsadder.api.CustomBlock";

    private static Method byItemStack;

    private ItemsAdderHook()
    {
    }

    public static boolean init(PluginManager pluginManager)
    {
        byItemStack = null;

        if (pluginManager.getPlugin("ItemsAdder") == null)
            return false;

        try
        {
            Class<?> customBlock = Class.forName(CLASS_NAME);
            byItemStack = customBlock.getMethod("byItemStack", ItemStack.class);
            return true;
        }
        catch (Throwable t)
        {
            byItemStack = null;
            return false;
        }
    }

    public static boolean isCustomBlock(ItemStack item)
    {
        if (byItemStack == null || item == null)
            return false;

        try
        {
            return byItemStack.invoke(null, item) != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }
}
