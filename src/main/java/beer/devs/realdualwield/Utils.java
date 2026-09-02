package beer.devs.realdualwield;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

public class Utils
{
    static final Random RANDOM = new Random();

    @SuppressWarnings("unused")
    public static float randomNumber(float a, float b)
    {
        return RANDOM.nextFloat() * (b - a) + a;
    }

    public static boolean isOffHand(PlayerInteractEntityEvent event)
    {
        return event.getHand() == EquipmentSlot.OFF_HAND;
    }

    public static boolean isOffHand(PlayerInteractEvent event)
    {
        return event.getHand() == EquipmentSlot.OFF_HAND;
    }

    public static boolean canInstaMine(@Nullable Block block)
    {
        return block != null && block.getType().getHardness() == 0;
    }

    public static boolean isASword(@Nullable ItemStack item)
    {
        return item != null && item.getType().toString().contains("SWORD");
    }

    public static boolean canBreak(Block block, Player player)
    {
        BlockBreakEvent b = new BlockBreakEvent(block, player);
        Bukkit.getPluginManager().callEvent(b);
        boolean can = !b.isCancelled();
        b.setCancelled(true);
        return can;
    }

    /**
     * Asks the other plugins whether an attack is allowed, without dealing any damage.
     *
     * <p>The deprecated {@code DamageModifier} based constructors of
     * {@link EntityDamageByEntityEvent} were replaced by the plain one: with a damage of 0 the
     * result is exactly the same and the call is safe on every version.
     */
    public static boolean canDamage(Player attacker, Entity damaged)
    {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, damaged,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 0.0D);

        Bukkit.getPluginManager().callEvent(event);
        boolean canDamage = !event.isCancelled();
        event.setCancelled(true);
        return canDamage;
    }
}
