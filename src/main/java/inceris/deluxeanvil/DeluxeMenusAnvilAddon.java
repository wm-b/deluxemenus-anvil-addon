package inceris.deluxeanvil;

import com.extendedclip.deluxemenus.menu.Menu;
import com.extendedclip.deluxemenus.menu.MenuHolder;
import com.extendedclip.deluxemenus.menu.MenuItem;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

public final class DeluxeMenusAnvilAddon extends JavaPlugin implements Listener {
    private static final String HANDLER = "deluxemenusanviladdon_input";
    private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, AnvilInput> submissions = new ConcurrentHashMap<>();
    private final Map<Menu, AnvilValidation> validations = new WeakHashMap<>();
    private Expansion expansion;
    private volatile boolean running;

    private record Session(Inventory inventory, AnvilInput input, AnvilValidation validation,
                           AtomicBoolean confirmationRefreshQueued) {}

    @Override
    public void onEnable() {
        running = true;
        expansion = new Expansion();
        if (!expansion.register()) {
            getLogger().severe("Could not register the deluxemenusanviladdon PlaceholderAPI expansion.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            install(player);
            track(player.getUniqueId(), player.getOpenInventory().getTopInventory());
        }
    }

    private void install(Player player) {
        try {
            Channel channel = PaperConnection.channel(player);
            UUID id = player.getUniqueId();
            channels.put(id, channel);
            channel.eventLoop().execute(() -> {
                if (!running || !channel.isActive()) return;
                if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
                channel.pipeline().addBefore("packet_handler", HANDLER, new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
                        Session session = sessions.get(id);
                        if (running && session != null) {
                            try {
                                String text = PaperConnection.renameText(packet);
                                if (text != null) {
                                    session.input().rename(text);
                                    queueConfirmationRefresh(player, id, session);
                                }
                            } catch (ReflectiveOperationException error) {
                                getLogger().log(Level.SEVERE, "Could not read an anvil rename packet.", error);
                            }
                        }
                        // Leave packet handling and all inventory/click behaviour to the server and DM.
                        super.channelRead(context, packet);
                    }
                });
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            getLogger().log(Level.SEVERE,
                "Could not attach anvil input for " + player.getName() + ". This build targets Paper 1.21.1.", error);
        }
    }

    private void queueConfirmationRefresh(Player player, UUID id, Session session) {
        if (!running || !session.confirmationRefreshQueued().compareAndSet(false, true)) return;
        try {
            Bukkit.getScheduler().runTask(this, () -> {
                session.confirmationRefreshQueued().set(false);
                if (!running || !player.isOnline() || sessions.get(id) != session
                        || player.getOpenInventory().getTopInventory() != session.inventory()) return;
                try {
                    // Vanilla recalculates its result locally after each rename and
                    // clears our custom button. Correct only slot 2: updating slot 0
                    // or the full inventory also resets the player's text field.
                    PaperConnection.sendSlot(player, 2, session.inventory().getItem(2));
                } catch (ReflectiveOperationException error) {
                    getLogger().log(Level.SEVERE, "Could not restore anvil confirmation for "
                        + player.getName() + ". This build targets Paper 1.21.1.", error);
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // The add-on can be disabled between receiving a packet and queuing work.
            session.confirmationRefreshQueued().set(false);
        }
    }

    private void track(UUID id, Inventory inventory) {
        sessions.remove(id);
        submissions.remove(id);
        if (inventory.getType() != InventoryType.ANVIL || !(inventory.getHolder() instanceof MenuHolder holder)) return;
        ItemStack left = inventory.getItem(0);
        String initial = "";
        if (left != null && left.hasItemMeta() && left.getItemMeta().hasDisplayName()) {
            initial = PlainTextComponentSerializer.plainText().serialize(left.getItemMeta().displayName());
        }
        AnvilInput input = new AnvilInput(initial);
        AnvilValidation validation = holder.getMenu().map(menu ->
            validations.computeIfAbsent(menu, ignored -> loadValidation(holder)))
            .orElseGet(AnvilValidation::disabled);
        sessions.put(id, new Session(inventory, input, validation, new AtomicBoolean()));
        submissions.put(id, input);
    }

    private AnvilValidation loadValidation(MenuHolder holder) {
        String name = holder.getMenuName();
        var registration = holder.getPlugin().getConfig().getConfigurationSection("gui_menus." + name);
        try {
            if (registration == null) throw new IllegalStateException("Menu registration is missing");
            ConfigurationSection config = registration;
            String file = registration.getString("file");
            if (file != null) {
                var yaml = new YamlConfiguration();
                yaml.load(new File(new File(holder.getPlugin().getDataFolder(), "gui_menus"), file));
                config = yaml;
            }
            if (!config.contains("anvil_input_regex")) return AnvilValidation.disabled();
            var regexConfig = config.getConfigurationSection("anvil_input_regex");
            if (regexConfig == null) {
                getLogger().severe("Menu " + name + ": anvil_input_regex must be a section; confirmation is blocked.");
                return AnvilValidation.invalid(AnvilValidation.DEFAULT_ERROR);
            }
            String errorMessage = regexConfig.getString("error-message", AnvilValidation.DEFAULT_ERROR);
            Object expression = regexConfig.get("expression");
            if (!(expression instanceof String)) {
                getLogger().severe("Menu " + name + ": anvil_input_regex.expression must be a string; confirmation is blocked.");
                return AnvilValidation.invalid(errorMessage);
            }
            return AnvilValidation.compile((String) expression, errorMessage,
                error -> getLogger().severe("Menu " + name + ": " + error + "; confirmation is blocked."));
        } catch (Exception error) {
            getLogger().log(Level.SEVERE, "Could not load anvil validation for menu " + name
                + "; confirmation is blocked.", error);
            return AnvilValidation.invalid(AnvilValidation.DEFAULT_ERROR);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        install(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        track(event.getPlayer().getUniqueId(), event.getInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Session session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session == null || session.inventory() != event.getInventory()) return;
        if (event.getRawSlot() == 2) {
            session.input().submit(Bukkit.getCurrentTick());
            if (session.validation().enabled() && session.inventory().getHolder() instanceof MenuHolder holder) {
                MenuItem output = holder.getItem(2);
                if (output != null && !(output instanceof ValidatedConfirmation)) {
                    var items = new HashSet<>(holder.getActiveItems());
                    items.remove(output);
                    items.add(new ValidatedConfirmation(holder, output, session.input(), session.validation()));
                    holder.setActiveItems(items);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        Session session = sessions.get(event.getWhoClicked().getUniqueId());
        if (session != null && session.inventory() == event.getInventory()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Session session = sessions.get(id);
        if (session != null && session.inventory() == event.getInventory()) sessions.remove(id, session);
        // Retain the last output-click snapshot for actions that run after [close].
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        sessions.remove(id);
        submissions.remove(id);
        uninstall(channels.remove(id));
    }

    private void uninstall(Channel channel) {
        if (channel == null) return;
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get(HANDLER) != null) channel.pipeline().remove(HANDLER);
        });
    }

    @Override
    public void onDisable() {
        running = false;
        for (Channel channel : channels.values()) uninstall(channel);
        channels.clear();
        validations.clear();
        sessions.clear();
        submissions.clear();
        if (expansion != null) expansion.unregister();
    }

    private final class Expansion extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "deluxemenusanviladdon"; }
        @Override public String getAuthor() { return "Inceris"; }
        @Override public String getVersion() { return DeluxeMenusAnvilAddon.this.getDescription().getVersion(); }
        @Override public boolean persist() { return true; }
        @Override public List<String> getPlaceholders() {
            return List.of("%deluxemenusanviladdon_text%", "%deluxemenusanviladdon_submitted_text%",
                "%deluxemenusanviladdon_submitted_integer%", "%deluxemenusanviladdon_viewer%");
        }

        @Override
        public String onRequest(OfflinePlayer player, String parameter) {
            if (player == null) return "";
            UUID id = player.getUniqueId();
            if (parameter.equals("viewer")) return player.getName();
            if (parameter.equals("text")) {
                Session session = sessions.get(id);
                return session == null ? "" : session.input().text();
            }
            AnvilInput input = submissions.get(id);
            String submitted = input == null ? "" : input.submitted();
            return switch (parameter) {
                case "submitted_text" -> submitted;
                case "submitted_integer" -> AnvilInput.integer(submitted);
                default -> null;
            };
        }
    }
}
