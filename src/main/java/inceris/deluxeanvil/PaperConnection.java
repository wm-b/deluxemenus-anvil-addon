package inceris.deluxeanvil;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Paper 1.21.1's Mojang-mapped connection access, isolated from menu logic. */
final class PaperConnection {
    private PaperConnection() {}

    static Channel channel(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object listener = handle.getClass().getField("connection").get(handle);
        Object connection = listener.getClass().getField("connection").get(listener);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    static String renameText(Object packet) throws ReflectiveOperationException {
        if (!packet.getClass().getName().equals(
                "net.minecraft.network.protocol.game.ServerboundRenameItemPacket")) return null;
        return (String) packet.getClass().getMethod("getName").invoke(packet);
    }

    /** Force a single client slot correction even when the server item is unchanged. */
    static void sendSlot(Player player, int slot, ItemStack item) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object menu = handle.getClass().getField("containerMenu").get(handle);
        int containerId = menu.getClass().getField("containerId").getInt(menu);
        int stateId = (int) menu.getClass().getMethod("getStateId").invoke(menu);
        Class<?> stackType = Class.forName("net.minecraft.world.item.ItemStack");
        Class<?> craftStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
        Object stack = craftStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
        Class<?> packetType = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket");
        Object packet = packetType.getConstructor(int.class, int.class, int.class, stackType)
            .newInstance(containerId, stateId, slot, stack);
        Object listener = handle.getClass().getField("connection").get(handle);
        listener.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"))
            .invoke(listener, packet);
    }
}
