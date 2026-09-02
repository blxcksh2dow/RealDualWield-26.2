package beer.devs.realdualwield;

import org.bukkit.plugin.java.JavaPlugin;

public class Main extends JavaPlugin
{
    /** True when Nexo is installed and its API is usable. */
    static boolean HAS_NEXO = false;

    public static Main inst;

    @Override
    public void onEnable()
    {
        inst = this;

        saveDefaultConfig();
        Debug.setEnabled(getConfig().getBoolean("debug", false));

        HAS_NEXO = NexoHook.init(getServer().getPluginManager());
        if (HAS_NEXO)
            getLogger().info("Nexo detected: Nexo blocks held in the main hand will not trigger the off-hand swing.");

        MMOHook.init(getServer().getPluginManager());
        if (MMOHook.hasMMOItems())
            getLogger().info("MMOItems detected: two handed weapons are blocked, weapon mana/stamina costs and attack speed are applied to the off hand.");
        if (MMOHook.hasMMOCore())
            getLogger().info("MMOCore detected: the mana/stamina cost of the off-hand weapon is taken from the player resources.");

        new DualWielding();

        if (Debug.isEnabled())
            DualWielding.logIntegrationReport();

        try
        {
            getLogger().info("RealDualWield v" + getPluginMeta().getVersion() + " enabled on Minecraft " +
                    getServer().getMinecraftVersion() + " (ProtocolLib " +
                    (OffhandAnimation.isProtocolLibEnabled() ? "found" : "not found") + ").");
        }
        catch (Throwable t)
        {
            getLogger().info("RealDualWield enabled.");
        }
    }

    @Override
    public void onDisable()
    {
        OffhandRecharge.cancelAll();
        inst = null;
    }
}
