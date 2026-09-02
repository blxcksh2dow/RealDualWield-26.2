package beer.devs.realdualwield;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin
{
    /** Kept for API compatibility with plugins built against RealDualWield 1.2.0. */
    @SuppressWarnings("unused")
    static boolean HAS_ITEMSADDER = false;

    public static Main inst;

    @Override
    public void onEnable()
    {
        inst = this;

        saveDefaultConfig();

        HAS_ITEMSADDER = ItemsAdderHook.init(getServer().getPluginManager());
        if (HAS_ITEMSADDER)
            getLogger().info("ItemsAdder detected: custom blocks are excluded from the off-hand interaction.");

        new DualWielding();

        getLogger().info("RealDualWield v" + getPluginMeta().getVersion() + " enabled on Minecraft " +
                getServer().getMinecraftVersion() + " (ProtocolLib " +
                (OffhandAnimation.isProtocolLibEnabled() ? "found" : "not found") + ").");
    }

    @Override
    public void onDisable()
    {
        inst = null;
    }
}
