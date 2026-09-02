package beer.devs.realdualwield;

import beer.devs.realdualwield.api.Events;
import beer.devs.realdualwield.api.PlayerDamageEntityWithOffhandEvent;
import beer.devs.realdualwield.api.PlayerOffhandAnimationEvent;
import beer.devs.realdualwield.api.PlayerOffhandDelayEvent;
import beer.devs.realdualwield.api.PlayerOffhandReduceDurabilityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attributable;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DualWielding implements Listener, CommandExecutor
{
    public static List<String> DUAL_WIELD_ENABLED_MATERIALS = new ArrayList<>();
    public static boolean DENY_LONGPRESS_RIGHTCLICK;
    public static boolean BREAK_PLANTS_BARE_HAND;

    private FileConfiguration config;
    private String NEXO_CHECK = "block";

    // MMOItems / MMOCore
    private boolean MMO_ENABLED = true;
    private boolean MMO_TWO_HANDED = true;
    private boolean MMO_BLOCK_TWO_HANDED_OFFHAND = true;
    private boolean MMO_CANCEL_MAIN_HAND = true;
    private boolean MMO_COSTS = true;
    private boolean MMO_NOTIFY = true;
    private String MMO_NO_MANA = "&cNot enough mana!";
    private String MMO_NO_STAMINA = "&cNot enough stamina!";
    private boolean MMO_ATTACK_SPEED = true;
    private boolean MMO_ENFORCE_COOLDOWN = true;
    private static boolean MMO_EXTRA_DAMAGE = true;
    private int MMO_MIN_COOLDOWN = 4;
    private int MMO_MAX_COOLDOWN = 40;
    private boolean MMO_ALL_WEAPONS = true;
    private boolean MMO_DURABILITY = false;
    private boolean OFFHAND_KNOCKBACK = false;
    private boolean OFFHAND_IGNORE_NO_DAMAGE_TICKS = true;

    /** Players whose damage is being applied by us right now (so the two-handed filter ignores it). */
    private final java.util.Set<java.util.UUID> applyingOffhandDamage = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final HashMap<Player, Wielder> wielders = new HashMap<>();

    /** Adventure components for the cooldown bar, or null when they cannot be built. */
    private static final Component[] COOLDOWN_ANIM;
    /** Legacy (§ codes) version of the same bar, used if Adventure is unavailable at runtime. */
    private static final String[] COOLDOWN_ANIM_LEGACY;
    private static final int COOLDOWN_STEPS = 8;
    private static final int COOLDOWN_BARS = 9;
    /** Cooldown used when the weapon does not define one (MMOItems attack speed). */
    private static final int DEFAULT_COOLDOWN = 12;
    private static final String SECTION = "\u00A7";

    static
    {
        @SuppressWarnings("UnnecessaryUnicodeEscape")
        String DOT = "\u00B7"; // MIDDLE DOT

        COOLDOWN_ANIM_LEGACY = new String[COOLDOWN_STEPS];
        for (int step = 0; step < COOLDOWN_STEPS; step++)
        {
            StringBuilder bar = new StringBuilder(COOLDOWN_BARS * 2);
            for (int i = 0; i < COOLDOWN_BARS; i++)
                bar.append(i <= step ? SECTION + "7" : SECTION + "8").append(DOT);
            COOLDOWN_ANIM_LEGACY[step] = bar.toString();
        }

        Component[] anim = null;
        try
        {
            anim = new Component[COOLDOWN_STEPS];
            for (int step = 0; step < COOLDOWN_STEPS; step++)
            {
                Component bar = Component.empty();
                for (int i = 0; i < COOLDOWN_BARS; i++)
                    bar = bar.append(Component.text(DOT, i <= step ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY));
                anim[step] = bar;
            }
        }
        catch (Throwable t)
        {
            anim = null;
            if (Main.inst != null)
                Main.inst.getLogger().warning("[RealDualWield] Adventure components are not available (" + t + "): the cooldown bar will use the legacy title API.");
        }
        COOLDOWN_ANIM = anim;
    }

    /** Base attack damage per material name (vanilla values, "HAND" defaults to 1). */
    private static final Map<String, Double> MATERIAL_DAMAGE = new HashMap<>();

    static
    {
        registerDamage(8, "NETHERITE_SWORD");
        registerDamage(7, "NETHERITE_AXE", "DIAMOND_SWORD", "GOLDEN_AXE", "WOODEN_AXE");
        registerDamage(6, "NETHERITE_HOE", "NETHERITE_SHOVEL", "NETHERITE_PICKAXE", "IRON_SWORD");
        registerDamage(5, "STONE_SWORD", "DIAMOND_PICKAXE");
        registerDamage(4, "WOODEN_SWORD", "GOLDEN_SWORD", "IRON_PICKAXE");
        registerDamage(3, "STONE_PICKAXE");
        registerDamage(2, "WOODEN_PICKAXE", "GOLDEN_PICKAXE");
        registerDamage(9, "STONE_AXE", "IRON_AXE", "DIAMOND_AXE", "TRIDENT");
        registerDamage(5.5d, "DIAMOND_SHOVEL");
        registerDamage(4.5d, "IRON_SHOVEL");
        registerDamage(3.5, "STONE_SHOVEL");
        registerDamage(2.5d, "WOODEN_SHOVEL", "GOLDEN_SHOVEL");
        registerDamage(1, "WOODEN_HOE", "GOLDEN_HOE", "STONE_HOE", "IRON_HOE", "DIAMOND_HOE");
    }

    public DualWielding()
    {
        loadConfiguration();
        initConfig();

        Bukkit.getServer().getPluginManager().registerEvents(this, Main.inst);

        registerCommand("rdwreload");
        registerCommand("rdwdebug");
    }

    private void registerCommand(String name)
    {
        PluginCommand command = Main.inst.getCommand(name);
        if (command == null)
        {
            Main.inst.getLogger().warning("[RealDualWield] the command /" + name
                    + " is not declared in plugin.yml: restart the server (a plugin manager or /reload does not register new commands).");
            return;
        }

        command.setExecutor(this);
    }

    /** Bumped whenever the default value of an existing option changes. */
    private static final int CONFIG_VERSION = 2;

    void loadConfiguration()
    {
        FileConfiguration file = Main.inst.getConfig();
        int version = file.getInt("config-version", 1);

        if (version < 2)
        {
            // 1.5.0 capped the off-hand cooldown at 40 ticks (2s), which truncated every MMOItems
            // weapon slower than that (a 5s greatsword recharged in 2s).
            if (file.getInt("mmoitems.max-cooldown", 40) <= 40)
                file.set("mmoitems.max-cooldown", 200);

            file.set("config-version", CONFIG_VERSION);
            Main.inst.getLogger().info("[RealDualWield] configuration updated to version " + CONFIG_VERSION + ".");
        }

        file.options().copyDefaults(true);
        Main.inst.saveConfig();
        file.options().copyDefaults(false);
    }

    void initConfig()
    {
        config = Main.inst.getConfig();
        DUAL_WIELD_ENABLED_MATERIALS = config.getStringList("dual_wield_enabled.materials");
        DENY_LONGPRESS_RIGHTCLICK = config.getBoolean("deny-longpress-rightclick");
        BREAK_PLANTS_BARE_HAND = config.getBoolean("break_plants_bare_hand");
        NEXO_CHECK = config.getString("nexo-check", "block");

        MMO_ENABLED = config.getBoolean("mmoitems.enabled", true);
        MMO_TWO_HANDED = config.getBoolean("mmoitems.two-handed", true);
        MMO_BLOCK_TWO_HANDED_OFFHAND = config.getBoolean("mmoitems.block-two-handed-off-hand", true);
        MMO_CANCEL_MAIN_HAND = config.getBoolean("mmoitems.cancel-main-hand-attack", true);
        MMO_COSTS = config.getBoolean("mmoitems.apply-weapon-costs", true);
        MMO_NOTIFY = config.getBoolean("mmoitems.notify-not-enough-mana", true);
        MMO_NO_MANA = config.getString("mmoitems.not-enough-mana-message", "&cNot enough mana!");
        MMO_NO_STAMINA = config.getString("mmoitems.not-enough-stamina-message", "&cNot enough stamina!");
        MMO_ATTACK_SPEED = config.getBoolean("mmoitems.use-attack-speed", true);
        MMO_ENFORCE_COOLDOWN = config.getBoolean("mmoitems.enforce-cooldown", true);
        MMO_EXTRA_DAMAGE = config.getBoolean("mmoitems.apply-crit-and-enchants", true);
        MMO_MIN_COOLDOWN = config.getInt("mmoitems.min-cooldown", 4);
        MMO_MAX_COOLDOWN = config.getInt("mmoitems.max-cooldown", 40);
        MMO_ALL_WEAPONS = config.getBoolean("mmoitems.all-mmoitems-weapons", true);
        MMO_DURABILITY = config.getBoolean("mmoitems.apply-durability", false);
        OFFHAND_KNOCKBACK = config.getBoolean("offhand-knockback", false);
        OFFHAND_IGNORE_NO_DAMAGE_TICKS = config.getBoolean("offhand-ignore-no-damage-ticks", true);

        Debug.setEnabled(config.getBoolean("debug", false));

        String animationMethod = config.getString("offhand-animation-method", "auto");
        OffhandAnimation.Mode mode;
        switch (animationMethod == null ? "auto" : animationMethod.toLowerCase(Locale.ROOT))
        {
            case "packet", "protocollib", "protocol" -> mode = OffhandAnimation.Mode.PACKET;
            case "api", "swing", "bukkit", "paper" -> mode = OffhandAnimation.Mode.API;
            default -> mode = OffhandAnimation.Mode.AUTO;
        }
        OffhandAnimation.setMode(mode);
    }

    private Wielder getPlayerData(Player player)
    {
        return wielders.computeIfAbsent(player, Wielder::new);
    }

    /**
     * What the plugin managed to hook into: printed by {@code /rdwdebug} and (when {@code debug}
     * is on) at startup. It is the fastest way to see why a feature is silent on a given server.
     */
    private static List<String> integrationReport()
    {
        List<String> lines = new ArrayList<>();
        lines.add("version " + (Main.inst == null ? "?" : Main.inst.getPluginMeta().getVersion())
                + " on Minecraft " + Bukkit.getServer().getMinecraftVersion());
        lines.add("off-hand animation: " + OffhandAnimation.describe());
        lines.add("ProtocolLib: " + (OffhandAnimation.isProtocolLibEnabled() ? "found" : "not found"));
        lines.add("Nexo: " + (Main.HAS_NEXO ? "hooked" : "not found"));
        lines.addAll(MMOHook.describe());
        return lines;
    }

    /** Logs the integration report (used at startup when debug is enabled). */
    static void logIntegrationReport()
    {
        if (Main.inst == null)
            return;

        Main.inst.getLogger().info("[RealDualWield] integration report:");
        for (String line : integrationReport())
            Main.inst.getLogger().info("[RealDualWield] " + line);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command cmd, @NotNull String label, String[] args)
    {
        if (cmd.getName().equalsIgnoreCase("rdwdebug"))
        {
            if (!sender.hasPermission("rdw.reload"))
            {
                sendMessage(sender, "[RealDualWield] You do not have permission to do that.", NamedTextColor.RED, SECTION + "c");
                return true;
            }

            for (String line : integrationReport())
                sendMessage(sender, "[RealDualWield] " + line, NamedTextColor.GRAY, SECTION + "7");

            return true;
        }

        if (cmd.getName().equalsIgnoreCase("rdwreload"))
        {
            if (!sender.hasPermission("rdw.reload"))
            {
                sendMessage(sender, "[RealDualWield] You do not have permission to do that.", NamedTextColor.RED, SECTION + "c");
                return true;
            }

            // /rdwreload debug: same report as /rdwdebug, for the servers that cannot register
            // the new command without a full restart (plugin managers, /reload...).
            if (args.length > 0 && args[0].equalsIgnoreCase("debug"))
            {
                for (String line : integrationReport())
                    sendMessage(sender, "[RealDualWield] " + line, NamedTextColor.GRAY, SECTION + "7");
                return true;
            }

            Main.inst.reloadConfig();
            initConfig();
            sendMessage(sender, "[RealDualWield] Reloaded config.", NamedTextColor.GREEN, SECTION + "a");
        }
        return true;
    }

    @EventHandler
    private void onPlayerQuit(PlayerQuitEvent e)
    {
        wielders.remove(e.getPlayer());
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent e)
    {
        if (Utils.isOffHand(e))
            return;

        if (!(e.getRightClicked() instanceof LivingEntity damaged) || !e.getPlayer().hasPermission("rdw.use"))
            return;

        Player player = e.getPlayer();
        Wielder wielder = getPlayerData(player);
        ItemStack weapon = e.getPlayer().getInventory().getItemInOffHand();

        Debug.log("interact entity: player=" + player.getName() + " target=" + damaged.getType()
                + " hand=" + e.getHand() + " weapon=" + weapon.getType() + " enabled=" + isEnabled(weapon));

        // Must be computed before updating the timestamp: two events closer than the threshold mean
        // the button was not released.
        boolean holding = wielder.isHoldingRightClick();
        wielder.markEntityInteract();

        if (config.getBoolean("deny-longpress-rightclick") && holding)
        {
            Debug.log("attack skipped: right click held (deny-longpress-rightclick)");
            return;
        }

        if (damaged.isInvulnerable() || damaged.isDead() || isIgnorable(damaged))
            return;

        if (!isEnabled(weapon))
        {
            Debug.log("attack skipped: " + weapon.getType() + " is not in dual_wield_enabled.materials");
            return;
        }

        // Two handed weapons.
        if (MMO_ENABLED && MMO_TWO_HANDED)
        {
            // A two handed weapon is never usable in the off hand, whatever the main hand holds.
            if (MMO_BLOCK_TWO_HANDED_OFFHAND && MMOHook.isTwoHanded(weapon))
            {
                Debug.log("attack skipped: " + weapon.getType() + " is a two handed weapon, it cannot be used in the off hand");
                return;
            }

            // MMOItems itself refuses to use a weapon while "hands are too charged" (a two handed
            // item in one hand + anything else in the other one): nothing at all is done then,
            // no animation, no damage, no knockback, no mana.
            if (MMOHook.isEncumbered(player))
            {
                Debug.log("attack skipped: hands too charged (two handed weapon + " + player.getInventory().getItemInMainHand().getType() + " in the main hand)");
                return;
            }
        }

        if (MMO_ENABLED && Debug.isEnabled() && MMOHook.isMMOItem(weapon))
        {
            Debug.log("mmoitems stats of " + weapon.getType() + ": attack-damage=" + MMOHook.stat(weapon, "ATTACK_DAMAGE")
                    + " attack-speed=" + MMOHook.stat(weapon, "ATTACK_SPEED") + " (cooldown " + cooldownTicks(weapon) + " ticks)"
                    + " mana-cost=" + MMOHook.manaCost(weapon) + " stamina-cost=" + MMOHook.staminaCost(weapon)
                    + " two-handed=" + MMOHook.isTwoHanded(weapon) + " weapon=" + MMOHook.isWeapon(weapon));
        }

        // Mana / stamina cost of the off-hand weapon, exactly like MMOItems does.
        if (MMO_ENABLED && MMO_COSTS && MMOHook.isMMOItem(weapon))
        {
            double mana = MMOHook.manaCost(weapon);
            double stamina = MMOHook.staminaCost(weapon);

            if (mana > 0 || stamina > 0)
            {
                if (MMOHook.hasMMOCore())
                {
                    if (mana > 0 && MMOHook.getMana(player) < mana)
                    {
                        Debug.log("attack skipped: " + MMOHook.getMana(player) + " mana, the weapon needs " + mana);
                        notify(player, MMO_NO_MANA);
                        return;
                    }
                    if (stamina > 0 && MMOHook.getStamina(player) < stamina)
                    {
                        Debug.log("attack skipped: " + MMOHook.getStamina(player) + " stamina, the weapon needs " + stamina);
                        notify(player, MMO_NO_STAMINA);
                        return;
                    }
                }

                MMOHook.consumeWeaponCosts(player, weapon);
                Debug.log("weapon costs applied: " + mana + " mana, " + stamina + " stamina (left: "
                        + MMOHook.getMana(player) + " mana, " + MMOHook.getStamina(player) + " stamina)");
            }
        }

        if (!Utils.canDamage(player, damaged))
        {
            Debug.log("attack skipped: another plugin cancelled the damage on " + damaged.getType());
            return;
        }

        @Nullable Integer delay = wielder.getDelay();
        int cooldownTotal = wielder.getCooldownTotal();

        // MMOItems weapons respect their attack speed: while the cooldown runs the off-hand
        // attack is skipped instead of only being scaled down.
        if (delay != null && MMO_ENABLED && MMO_ENFORCE_COOLDOWN && MMO_ATTACK_SPEED
                && MMOHook.isMMOItem(weapon) && MMOHook.attackSpeed(weapon) > 0)
        {
            Debug.log("attack skipped: the off-hand weapon is recharging (" + delay + "/" + cooldownTotal + " ticks left)");
            return;
        }

        boolean critical = !player.isOnGround() && player.getFallDistance() > 0.0F && !player.hasPotionEffect(PotionEffectType.BLINDNESS) && player.getVehicle() == null;

        if (Events.call(new PlayerOffhandAnimationEvent(e)))
        {
            if (critical)
            {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 10.0F, 1.0F);
                player.spawnParticle(Particle.CRIT, damaged.getLocation().getX(), damaged.getLocation().getY() + 1, damaged.getLocation().getZ(), 10, 0.5, 0.5, 0.5);
            }
            else
            {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 10.0F, 1.0F);

                wielder.offhandAnimation();

                if (Utils.isASword(weapon))
                {
                    Location particleLoc = player.getLocation().toVector().add(player.getLocation().getDirection().multiply(1.5f)).toLocation(player.getWorld());
                    particleLoc.setY(particleLoc.getY() + player.getEyeHeight());
                    player.spawnParticle(Particle.SWEEP_ATTACK, particleLoc.getX(), particleLoc.getY(), particleLoc.getZ(), 1, 0, 0, 0);
                    player.spawnParticle(Particle.DAMAGE_INDICATOR, damaged.getLocation().getX(), damaged.getLocation().getY(), damaged.getLocation().getZ() + 0.5f, (int) Utils.randomNumber(1, 4), 0.1, 0.1, 1, 0.2);
                }
            }
        }

        if (Events.call(new PlayerOffhandReduceDurabilityEvent(e)))
            damageWeapon(player, weapon);

        double computed = getDamage(weapon, player, damaged, critical, delay, cooldownTotal);
        Debug.log("attack: weapon=" + weapon.getType() + " critical=" + critical + " delay=" + delay + " damage=" + computed);

        PlayerDamageEntityWithOffhandEvent apiDamageEvent = new PlayerDamageEntityWithOffhandEvent(e, computed);
        if (Events.call(apiDamageEvent))
        {
            if (!damaged.isInvulnerable())
            {
                // Off by default: on MMOItems/MMOCore servers the knockback is part of the
                // plugin combat system and pushing the target again would fight with it.
                if (OFFHAND_KNOCKBACK)
                {
                    float multiply = 0.5f;
                    float height = 0.5f;
                    if (weapon.containsEnchantment(Enchantment.KNOCKBACK))
                    {
                        multiply += weapon.getEnchantmentLevel(Enchantment.KNOCKBACK) * 0.5f;
                        height += weapon.getEnchantmentLevel(Enchantment.KNOCKBACK) * 0.3f;
                    }

                    boolean isOnHair = damaged.getLocation().getBlock().getRelative(BlockFace.DOWN).getType() == Material.AIR;

                    if (config.getBoolean("can-attack-mob-in-air") && isOnHair)
                    {
                        Vector direction = player.getLocation().getDirection().multiply(multiply);
                        direction.setY(direction.getY() + height);
                        damaged.setVelocity(direction);
                    }
                    else if (!config.getBoolean("can-attack-mob-in-air") && !isOnHair)
                    {
                        Vector direction = player.getLocation().getDirection().multiply(multiply);
                        direction.setY(direction.getY() + height);
                        damaged.setVelocity(direction);
                    }
                }

                if (weapon.containsEnchantment(Enchantment.FIRE_ASPECT))
                    damaged.setFireTicks(80 * weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT));

                double damage = apiDamageEvent.getDamage();
                double before = damaged.getHealth();
                int noDamageTicks = damaged.getNoDamageTicks();

                try
                {
                    applyingOffhandDamage.add(player.getUniqueId());

                    /*
                     * A mob hit by the main hand is invulnerable for 10 ticks (0.5s): during that
                     * window vanilla drops every damage that is not HIGHER than the previous one.
                     * Without this the off-hand hit that follows a main hand hit silently does
                     * nothing, which is exactly the opposite of what dual wielding is about.
                     */
                    if (OFFHAND_IGNORE_NO_DAMAGE_TICKS && noDamageTicks > 0)
                    {
                        Debug.log("clearing " + noDamageTicks + " no-damage ticks on " + damaged.getType());
                        damaged.setNoDamageTicks(0);
                    }

                    damaged.damage(damage, player);
                }
                catch (Throwable t)
                {
                    Main.inst.getLogger().warning("[RealDualWield] could not damage " + damaged.getType() + ": " + t);
                }
                finally
                {
                    applyingOffhandDamage.remove(player.getUniqueId());
                }
                Debug.log("damage " + damage + " on " + damaged.getType() + ": health " + before + " -> " + damaged.getHealth()
                        + (damaged.getHealth() >= before ? " (the attack did nothing)" : ""));
            }
        }

        if (Events.call(new PlayerOffhandDelayEvent(e)))
        {
            if (!wielder.isUsingLeftWeapon())
            {
                wielder.setUsingLeftWeapon(true);
                int ticks = cooldownTicks(weapon);
                wielder.setCooldownTotal(ticks);
                Debug.log("cooldown: " + ticks + " ticks (weapon " + weapon.getType() + ")");
                playCooldownAnimation(wielder, ticks);
            }
        }
    }

    /**
     * True when the off-hand item cannot be used at all: either it is a two handed weapon, or the
     * player is in the MMOItems "hands too charged" state (two handed in one hand + something in
     * the other one, catalysts excluded).
     */
    private boolean isOffHandBlocked(Player player, ItemStack offHand)
    {
        if (!MMO_ENABLED || !MMO_TWO_HANDED)
            return false;

        if (MMO_BLOCK_TWO_HANDED_OFFHAND && MMOHook.isTwoHanded(offHand))
            return true;

        return MMOHook.isEncumbered(player);
    }

    /**
     * Length of the off-hand cooldown in ticks.
     *
     * <p>For MMOItems weapons the value comes from the item attack speed (attacks per second),
     * clamped between {@code mmoitems.min-cooldown} and {@code mmoitems.max-cooldown}; for every
     * other item the vanilla-ish default of 12 ticks is used.
     */
    private int cooldownTicks(ItemStack weapon)
    {
        if (MMO_ENABLED && MMO_ATTACK_SPEED && MMOHook.isMMOItem(weapon))
        {
            double speed = MMOHook.attackSpeed(weapon);
            if (speed > 0)
            {
                int ticks = (int) Math.round(20.0 / speed);
                int min = MMO_MIN_COOLDOWN > 0 ? MMO_MIN_COOLDOWN : 1;
                int max = MMO_MAX_COOLDOWN >= min ? MMO_MAX_COOLDOWN : min;
                return Math.max(min, Math.min(max, ticks));
            }
        }

        return DEFAULT_COOLDOWN;
    }

    /** Sends a message configured by the admin (supports &amp; colour codes). */
    private void notify(Player player, String message)
    {
        if (!MMO_NOTIFY || message == null || message.isEmpty())
            return;

        sendMessage(player, message.replace('&', SECTION.charAt(0)), NamedTextColor.RED, "");
    }

    /**
     * Two handed weapons also disable the MAIN hand while the player is encumbered, so that
     * holding two weapons "does not work at all", like MMOItems' own item restriction.
     */
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e)
    {
        if (!MMO_ENABLED || !MMO_TWO_HANDED || !MMO_CANCEL_MAIN_HAND)
            return;

        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK)
            return;

        if (!(e.getDamager() instanceof Player player))
            return;

        // Never block the off-hand hit we are applying ourselves.
        if (applyingOffhandDamage.contains(player.getUniqueId()))
            return;

        if (MMOHook.isEncumbered(player))
        {
            e.setCancelled(true);
            Debug.log("main hand attack cancelled: " + player.getName() + " is holding a two handed weapon and something in the other hand");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e)
    {
        if (Utils.isOffHand(e))
            return;

        Player player = e.getPlayer();
        Block block = e.getClickedBlock();
        Wielder wielder = getPlayerData(player);

        boolean holding = wielder.isHoldingInteract();
        wielder.markInteract();

        if (!e.isCancelled())
        {
            if (config.getBoolean("break-plants"))
                wielder.instamine(block, e.getItem());
        }

        // Handle blocks interaction particle.
        if (!player.hasPermission("rdw.use") || wielder.isUsingLeftWeapon())
            return;

        // Two handed: no off-hand swing at all while the hands are "too charged", and never for a
        // two handed weapon sitting in the off hand.
        if (isOffHandBlocked(player, player.getInventory().getItemInOffHand()))
            return;

        if (config.getBoolean("deny-longpress-rightclick") && e.getAction().equals(Action.RIGHT_CLICK_AIR) && holding)
        {
            Debug.log("interact skipped: right click held (deny-longpress-rightclick)");
            return;
        }

        if (!Events.call(new PlayerOffhandAnimationEvent(e)))
            return;

        ItemStack itemMainHand = player.getInventory().getItemInMainHand();
        ItemStack itemOffHand = player.getInventory().getItemInOffHand();

        if (e.getAction().equals(Action.RIGHT_CLICK_BLOCK) || e.getAction().equals(Action.RIGHT_CLICK_AIR))
        {
            if (itemMainHand.getType().isBlock() || isNexoBlockInMainHand(itemMainHand))
            {
                Debug.log("interact skipped: the main hand holds a placeable block (" + itemMainHand.getType() + ")");
                return;
            }

            if (isEnabled(itemOffHand) && !wielder.isUsingLeftWeapon())
            {
                wielder.offhandAnimation();

                if (player.getGameMode() == GameMode.ADVENTURE)
                    return;

                if (block == null)
                    return;

                Block target = block.getRelative(e.getBlockFace());
                Location loc = target.getLocation();

                Vector offset = new Vector(0, 0, 0);
                switch (e.getBlockFace())
                {
                    case UP:
                        loc.setZ(loc.getZ() + 0.5f);
                        loc.setX(loc.getX() + 0.5f);
                        offset.setZ(0.3f);
                        offset.setX(0.3f);
                        break;
                    case EAST:
                        loc.setZ(loc.getZ() + 0.5f);
                        loc.setY(loc.getY() + 0.5f);
                        offset.setZ(0.3f);
                        offset.setY(0.3f);
                        break;
                    case WEST:
                        loc.setZ(loc.getZ() + 0.5f);
                        loc.setX(loc.getX() + 1f);
                        loc.setY(loc.getY() + 0.5f);
                        offset.setZ(0.3f);
                        offset.setX(0.3f);
                        offset.setY(0.3f);
                        break;
                    case NORTH:
                        loc.setZ(loc.getZ() + 1f);
                        loc.setX(loc.getX() + 0.5f);
                        loc.setY(loc.getY() + 0.5f);
                        offset.setZ(0.3f);
                        offset.setX(0.3f);
                        offset.setY(0.3f);
                        break;
                    case SOUTH:
                        loc.setZ(loc.getZ());
                        loc.setX(loc.getX() + 0.5f);
                        loc.setY(loc.getY() + 0.5f);
                        offset.setZ(0.3f);
                        offset.setX(0.3f);
                        offset.setY(0.3f);
                        break;
                    case DOWN:
                        loc.setZ(loc.getZ() + 0.5f);
                        loc.setX(loc.getX() + 0.5f);
                        loc.setY(loc.getY() + 1f);
                        offset.setZ(0.3f);
                        offset.setX(0.3f);
                        offset.setY(0.3f);
                        break;
                    default:
                        break;
                }

                player.spawnParticle(Particle.BLOCK, loc.getX(), loc.getY(), loc.getZ(), 2, offset.getX(), offset.getY(), offset.getZ(), block.getType().createBlockData());
            }
        }
    }

    /**
     * Nexo support: when the main hand holds a Nexo block the right click would place it, so the
     * off-hand swing is skipped, exactly like for vanilla blocks.
     *
     * <p>{@code nexo-check} in config.yml:
     * <ul>
     *     <li>{@code block} (default): only Nexo custom blocks are skipped</li>
     *     <li>{@code item}: every Nexo item held in the main hand is skipped</li>
     *     <li>{@code false}: the check is disabled</li>
     * </ul>
     */
    private boolean isNexoBlockInMainHand(ItemStack item)
    {
        if (!Main.HAS_NEXO || NEXO_CHECK == null)
            return false;

        String mode = NEXO_CHECK.toLowerCase(Locale.ROOT);
        if (mode.equals("false") || mode.equals("none") || mode.equals("off") || mode.equals("disabled"))
            return false;

        if (mode.equals("item") || mode.equals("any"))
            return NexoHook.idFromItem(item) != null;

        return NexoHook.isCustomBlock(item);
    }

    /**
     * Consumes one durability point of the off-hand weapon, breaking it when it is the last one.
     *
     * <p>Migrated from the (deprecated) {@code ItemStack#getDurability()} to the
     * {@link Damageable} item meta, which is the supported API on modern versions.
     */
    private void damageWeapon(Player player, ItemStack weapon)
    {
        // MMOItems has its own durability system and many textured items rely on a fixed item
        // damage value (texture by durability): vanilla durability is left alone by default.
        if (MMO_ENABLED && !MMO_DURABILITY && MMOHook.isMMOItem(weapon))
        {
            Debug.log("durability skipped: MMOItems item (mmoitems.apply-durability is false)");
            return;
        }

        ItemMeta meta = weapon.getItemMeta();
        if (!(meta instanceof Damageable damageable) || meta.isUnbreakable() || !damageable.hasMaxDamage())
            return;

        int maxDamage = damageable.getMaxDamage();
        int damage = damageable.getDamage();

        if (damage + 1 < maxDamage)
        {
            damageable.setDamage(damage + 1);
            weapon.setItemMeta(damageable);
            player.getInventory().setItemInOffHand(weapon);
        }
        else
        {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 10.0F, 1.0F);
            player.getInventory().setItemInOffHand(null);
        }
    }

    private void playCooldownAnimation(Wielder wielder, int ticks)
    {
        if (wielder.getDelay() != null)
            return;

        final int total = Math.max(1, ticks);
        final boolean bar = config.getBoolean("show-cooldown-bar");

        new BukkitRunnable()
        {
            int count = 0;
            int lastIndex = -1;

            public void run()
            {
                count += 1;
                // The damage of the next hit is scaled by the remaining cooldown.
                if (total - count > 0)
                    wielder.setDelay(total - count);

                if (bar && count <= total)
                {
                    // The bar is spread over the WHOLE cooldown, whatever its length: on a 5
                    // seconds weapon it fills in 5 seconds instead of freezing after 8 ticks.
                    int index = Math.min(COOLDOWN_STEPS - 1, (int) Math.floor((count - 1) * (double) COOLDOWN_STEPS / total));

                    // The title is sent again only when the bar actually changes: its stay time
                    // (500 ticks) already keeps it on screen in the meantime.
                    if (index != lastIndex)
                    {
                        lastIndex = index;
                        showCooldownBar(wielder.getPlayer(), index);
                    }
                }

                if (count > total)
                {
                    if (bar)
                        clearCooldownBar(wielder.getPlayer());

                    wielder.setUsingLeftWeapon(false);
                    wielder.setDelay(null);
                    this.cancel();
                }
            }
            // The titles are player API calls: they must run on the main thread.
        }.runTaskTimer(Main.inst, 0, 1);
    }

    /**
     * Sends a message using Adventure, falling back on the legacy colour codes if the Adventure
     * API is not the one this plugin was compiled against.
     */
    private static void sendMessage(CommandSender sender, String text, NamedTextColor color, String legacyColor)
    {
        try
        {
            sender.sendMessage(Component.text(text, color));
        }
        catch (Throwable t)
        {
            sender.sendMessage(legacyColor + text);
        }
    }

    /** Shows the cooldown bar (title) for the given step, Adventure first, legacy API as backup. */
    private static void showCooldownBar(Player player, int index)
    {
        try
        {
            player.showTitle(Title.title(Component.empty(), COOLDOWN_ANIM[index], 0, 500, 0));
        }
        catch (Throwable t)
        {
            player.sendTitle(" ", COOLDOWN_ANIM_LEGACY[index], 0, 500, 0);
        }
    }

    private static void clearCooldownBar(Player player)
    {
        try
        {
            player.clearTitle();
        }
        catch (Throwable t)
        {
            player.sendTitle(" ", " ", 0, 500, 0);
        }
    }

    boolean isEnabled(ItemStack offHand)
    {
        if (offHand == null || offHand.getType() == Material.AIR)
            return false;

        if (DUAL_WIELD_ENABLED_MATERIALS.contains(offHand.getType().toString()))
            return true;

        // MMOItems weapons are enabled even when their vanilla material is not in the list:
        // textured items are often based on paper, sticks, bones... and MMOItems already knows
        // which of its items are weapons.
        if (MMO_ENABLED && MMO_ALL_WEAPONS && MMOHook.isWeapon(offHand))
            return true;

        return false;
    }

    boolean isIgnorable(Entity entity)
    {
        return entity.getType() == EntityType.ARMOR_STAND || entity.getType() == EntityType.ITEM_FRAME;
    }

    public static double getDamage(ItemStack item, Player player, LivingEntity target, boolean critical)
    {
        double damage = getMaterialAttackDamage(item.getType());
        boolean mmoDamage = false;

        /*
         * Since MMOItems 6.7 the attribute stats (attack damage, attack speed) are handled by
         * MythicLib and are NOT written in the vanilla item attributes, so reading the ItemMeta
         * would only give the damage of the vanilla material the item is based on. The value is
         * read from the item NBT with the very same call MMOItems uses for its own stats.
         */
        if (MMOHook.isMMOItem(item))
        {
            double stat = MMOHook.stat(item, "ATTACK_DAMAGE");
            if (stat > 0)
            {
                damage = stat;
                mmoDamage = true;
            }
        }

        ItemMeta meta = item.getItemMeta();
        if (!mmoDamage && meta != null && meta.getAttributeModifiers() != null)
        {
            for (Map.Entry<Attribute, AttributeModifier> entry : meta.getAttributeModifiers().entries())
            {
                if (entry.getKey() == Attribute.ATTACK_DAMAGE)
                {
                    AttributeModifier.Operation operation = entry.getValue().getOperation();
                    if (operation.equals(AttributeModifier.Operation.ADD_NUMBER))
                        damage += entry.getValue().getAmount();
                    else if (operation.equals(AttributeModifier.Operation.ADD_SCALAR))
                        damage += entry.getValue().getAmount() * 1.6;
                    else if (operation.equals(AttributeModifier.Operation.MULTIPLY_SCALAR_1))
                        damage *= entry.getValue().getAmount();
                }
            }
        }

        if (player.hasPotionEffect(PotionEffectType.STRENGTH))
        {
            Collection<PotionEffect> pe = player.getActivePotionEffects();
            for (PotionEffect effect : pe)
            {
                if (effect.getType().equals(PotionEffectType.STRENGTH))
                {
                    if (effect.getAmplifier() == 0)
                        damage += 3;
                    if (effect.getAmplifier() == 1)
                        damage += 6;
                }
            }
        }

        // For MMOItems weapons the criticals, the enchantment scaling and the elemental damage
        // belong to MMOItems/MythicLib: apply-crit-and-enchants: false leaves them all to it.
        boolean extras = !mmoDamage || MMO_EXTRA_DAMAGE;

        if (extras && item.containsEnchantment(Enchantment.SHARPNESS))
        {
            float damageAllValue = 1;
            if (item.getEnchantmentLevel(Enchantment.SHARPNESS) > 1)
                damageAllValue += (item.getEnchantmentLevel(Enchantment.SHARPNESS) - 1) * 0.5f;
            damage += damageAllValue;
        }

        if (extras && critical)
            damage *= 1.5;

        damage = applyArmor(damage, target);

        if (target.hasPotionEffect(PotionEffectType.RESISTANCE))
        {
            int resistanceLevel = target.getPotionEffect(PotionEffectType.RESISTANCE).getAmplifier();
            damage *= 1 - (0.2 * (resistanceLevel + 1));
        }

        return Math.max(damage, 0);
    }

    /**
     * Vanilla damage reduction, null-safe: some entity types do not have the armor attributes.
     */
    private static double applyArmor(double damage, LivingEntity target)
    {
        if (!(target instanceof Attributable attributable))
            return damage;

        AttributeInstance armor = attributable.getAttribute(Attribute.ARMOR);
        AttributeInstance toughness = attributable.getAttribute(Attribute.ARMOR_TOUGHNESS);

        double armorPoints = armor == null ? 0 : armor.getValue();
        double armorToughness = toughness == null ? 0 : toughness.getValue();

        return damage * (1 - Math.min(20.0, armorPoints / (5 + armorToughness / 2)) / 25.0);
    }

    static double getMaterialAttackDamage(Material material)
    {
        // A map keyed by the material name instead of a switch on the enum: it does not depend on
        // every single Material constant existing at runtime.
        return MATERIAL_DAMAGE.getOrDefault(material.name(), 1D);
    }

    private static void registerDamage(double damage, String... names)
    {
        for (String name : names)
            MATERIAL_DAMAGE.put(name, damage);
    }

    static double getDamage(ItemStack item, Player player, LivingEntity damaged, boolean critical, @Nullable Integer delay, int cooldownTotal)
    {
        if (delay != null)
        {
            int total = cooldownTotal <= 0 ? DEFAULT_COOLDOWN : cooldownTotal;
            return getDamage(item, player, damaged, critical) * (delay * 1.0f / total);
        }
        return getDamage(item, player, damaged, critical);
    }
}
