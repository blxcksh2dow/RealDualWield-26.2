package beer.devs.realdualwield.api;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

public class PlayerOffhandPlantBreakEvent extends Event implements Cancellable
{
    private static final HandlerList HANDLERS = new HandlerList();
    private boolean isCancelled;
    private Player player;
    private ItemStack itemInOffhand;
    private Block block;


    public PlayerOffhandPlantBreakEvent(Player player, ItemStack itemInOffhand, Block block)
    {
        this.player = player;
        this.isCancelled = false;
        this.itemInOffhand = itemInOffhand;
        this.block = block;
    }

    @Override
    public boolean isCancelled()
    {
        return this.isCancelled;
    }

    @Override
    public void setCancelled(boolean b)
    {
        this.isCancelled = b;
    }

    public static HandlerList getHandlerList()
    {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers()
    {
        return HANDLERS;
    }

    public ItemStack getItemInOffhand()
    {
        return itemInOffhand;
    }

    public Player getPlayer()
    {
        return player;
    }

    public void setPlayer(Player player)
    {
        this.player = player;
    }

    public Block getBlock()
    {
        return block;
    }
}