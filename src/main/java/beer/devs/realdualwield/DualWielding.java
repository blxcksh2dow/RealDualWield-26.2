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

    private final HashMap<Player, Wielder> wielders = new HashMap<>();

    /** Adventure components for the cooldown bar, or null when they cannot be built. */
    private static final Component[] COOLDOWN_ANIM;
    /** Legacy (§ codes) version of the same bar, used if Adventure is unavailable at runtime. */
    private static final String[] COOLDOWN_ANIM_LEGACY;
    private static final int COOLDOWN_STEPS = 8;
    private static final int COOLDOWN_BARS = 9;
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

        PluginCommand command = Main.inst.getCommand("rdwreload");
        if (command != null)
            command.setExecutor(this);
    }

    void loadConfiguration()
    {
        Main.inst.getConfig().options().copyDefaults(true);
        Main.inst.saveConfig();
        Main.inst.getConfig().options().copyDefaults(false);
    }

    void initConfig()
    {
        config = Main.inst.getConfig();
        DUAL_WIELD_ENABLED_MATERIALS = config.getStringList("dual_wield_enabled.materials");
        DENY_LONGPRESS_RIGHTCLICK = config.getBoolean("deny-longpress-rightclick");
        BREAK_PLANTS_BARE_HAND = config.getBoolean("break_plants_bare_hand");

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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command cmd, @NotNull String label, String[] args)
    {
        if (cmd.getName().equalsIgnoreCase("rdwreload"))
        {
            if (sender.hasPermission("rdw.reload"))
            {
                Main.inst.reloadConfig();
                initConfig();
                sendMessage(sender, "[RealDualWield] Reloaded config.", NamedTextColor.GREEN, SECTION + "a");
            }
            else
            {
                sendMessage(sender, "[RealDualWield] You do not have permission to do that.", NamedTextColor.RED, SECTION + "c");
            }
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
        if (config.getBoolean("deny-longpress-rightclick") && wielder.isHoldingRightClick())
            return;

        ItemStack weapon = e.getPlayer().getInventory().getItemInOffHand();
        if (damaged.isInvulnerable() || damaged.isDead() || !Utils.canDamage(player, damaged) || isIgnorable(damaged) || !isEnabled(weapon))
            return;

        wielder.setTimeNow();

        @Nullable Integer delay = wielder.getDelay();
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

        PlayerDamageEntityWithOffhandEvent apiDamageEvent = new PlayerDamageEntityWithOffhandEvent(e, getDamage(weapon, player, damaged, critical, delay));
        if (Events.call(apiDamageEvent))
        {
            if (!damaged.isInvulnerable())
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

                if (weapon.containsEnchantment(Enchantment.FIRE_ASPECT))
                    damaged.setFireTicks(80 * weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT));

                double damage = apiDamageEvent.getDamage();
                damaged.damage(damage, player);
            }
        }

        if (Events.call(new PlayerOffhandDelayEvent(e)))
        {
            if (!wielder.isUsingLeftWeapon())
            {
                wielder.setUsingLeftWeapon(true);
                playCooldownAnimation(wielder);
            }
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
        wielder.setTimeNow();

        if (!e.isCancelled())
        {
            if (config.getBoolean("break-plants"))
                wielder.instamine(block, e.getItem());
        }

        // Handle blocks interaction particle.
        if (!player.hasPermission("rdw.use") || wielder.isUsingLeftWeapon())
            return;

        if (config.getBoolean("deny-longpress-rightclick") && e.getAction().equals(Action.RIGHT_CLICK_AIR) && wielder.isHoldingRightClick())
            return;

        if (!Events.call(new PlayerOffhandAnimationEvent(e)))
            return;

        ItemStack itemMainHand = player.getInventory().getItemInMainHand();
        ItemStack itemOffHand = player.getInventory().getItemInOffHand();

        if (e.getAction().equals(Action.RIGHT_CLICK_BLOCK) || e.getAction().equals(Action.RIGHT_CLICK_AIR))
        {
            if (itemMainHand.getType().isBlock() || (Main.HAS_ITEMSADDER && ItemsAdderHook.isCustomBlock(itemMainHand)))
                return;

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
     * Consumes one durability point of the off-hand weapon, breaking it when it is the last one.
     *
     * <p>Migrated from the (deprecated) {@code ItemStack#getDurability()} to the
     * {@link Damageable} item meta, which is the supported API on modern versions.
     */
    private void damageWeapon(Player player, ItemStack weapon)
    {
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

    private void playCooldownAnimation(Wielder wielder)
    {
        if (wielder.getDelay() != null)
            return;

        new BukkitRunnable()
        {
            int count = 0;
            int animIndex = 0;

            public void run()
            {
                count += 1;
                // 1 : 12 = x : dualWieldDelays.get(player)
                // dualWieldDelays.get(player) * 1 / 12
                if (12 - count > 0)
                    wielder.setDelay(12 - count);

                if (count <= COOLDOWN_STEPS)
                {
                    if (config.getBoolean("show-cooldown-bar"))
                    {
                        showCooldownBar(wielder.getPlayer(), animIndex);
                        animIndex++;
                    }
                }
                else if (count > 12)
                {
                    if (config.getBoolean("show-cooldown-bar"))
                        clearCooldownBar(wielder.getPlayer());

                    wielder.setUsingLeftWeapon(false);
                    wielder.setDelay(null);
                    this.cancel();
                }
                // Between the 9th and the 12th tick nothing is sent: the last bar stays on screen
                // because it was shown with a stay time of 500 ticks.
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

        return DUAL_WIELD_ENABLED_MATERIALS.contains(offHand.getType().toString());
    }

    boolean isIgnorable(Entity entity)
    {
        return entity.getType() == EntityType.ARMOR_STAND || entity.getType() == EntityType.ITEM_FRAME;
    }

    public static double getDamage(ItemStack item, Player player, LivingEntity target, boolean critical)
    {
        double damage = getMaterialAttackDamage(item.getType());

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.getAttributeModifiers() != null)
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

        if (item.containsEnchantment(Enchantment.SHARPNESS))
        {
            float damageAllValue = 1;
            if (item.getEnchantmentLevel(Enchantment.SHARPNESS) > 1)
                damageAllValue += (item.getEnchantmentLevel(Enchantment.SHARPNESS) - 1) * 0.5f;
            damage += damageAllValue;
        }

        if (critical)
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

    static double getDamage(ItemStack item, Player player, LivingEntity damaged, boolean critical, @Nullable Integer delay)
    {
        if (delay != null)
            return getDamage(item, player, damaged, critical) * (delay * 1.0f / 12.0f);
        return getDamage(item, player, damaged, critical);
    }
}
