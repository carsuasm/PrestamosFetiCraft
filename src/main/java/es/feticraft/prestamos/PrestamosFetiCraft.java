
package es.feticraft.prestamos;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class PrestamosFetiCraft extends JavaPlugin implements Listener {

    private Economy economy;
    private NamespacedKey actionKey;
    private static final String MENU_TITLE = "&8Préstamos de FetiCraft";

    @Override
    public void onEnable() {
        actionKey = new NamespacedKey(this, "menu_action");

        if (!setupEconomy()) {
            getLogger().severe("No se ha encontrado una economía de Vault.");
            getLogger().severe("Comprueba que Vault y Mineconomy estén activos.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("prestamos") != null) {
            getCommand("prestamos").setExecutor(this);
        } else {
            getLogger().severe("No se encuentra el comando prestamos en plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Préstamos de FetiCraft iniciado correctamente.");
        getLogger().info("Economía conectada: " + economy.getName());
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }

        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager().getRegistration(Economy.class);

        if (registration == null) {
            return false;
        }

        economy = registration.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando solo puede usarse dentro del juego.");
            return true;
        }

        if (!player.hasPermission("prestamos.use")) {
            player.sendMessage(color("&cNo tienes permiso para usar este comando."));
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                color(MENU_TITLE)
        );

        addButton(
                inventory, 11, Material.BOOK,
                "&bMercado",
                "Ver objetos disponibles para alquilar.",
                "market"
        );

        addButton(
                inventory, 13, Material.CHEST,
                "&aPublicar objeto",
                "Publicar un objeto para alquilar.",
                "publish"
        );

        addButton(
                inventory, 15, Material.CLOCK,
                "&eMis alquileres",
                "Consultar tus alquileres activos.",
                "rentals"
        );

        addButton(
                inventory, 21, Material.HOPPER,
                "&6Mis devoluciones",
                "Consultar objetos pendientes de recoger.",
                "returns"
        );

        addButton(
                inventory, 23, Material.WRITABLE_BOOK,
                "&dMis anuncios",
                "Administrar tus anuncios.",
                "listings"
        );

        player.openInventory(inventory);
    }

    private void addButton(
            Inventory inventory,
            int slot,
            Material material,
            String title,
            String description,
            String action
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return;
        }

        meta.setDisplayName(color(title));
        meta.setLore(List.of(
                color("&7" + description),
                color("&8Función en desarrollo")
        ));

        meta.getPersistentDataContainer().set(
                actionKey,
                PersistentDataType.STRING,
                action
        );

        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!ChatColor.stripColor(event.getView().getTitle())
                .equals("Préstamos de FetiCraft")) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }

        String action = clicked.getItemMeta()
                .getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);

        if (action == null) {
            return;
        }

        switch (action) {
            case "market" ->
                    player.sendMessage(color("&eEl mercado se añadirá en la siguiente etapa."));
            case "publish" ->
                    player.sendMessage(color("&eLa publicación segura se añadirá en la siguiente etapa."));
            case "rentals" ->
                    player.sendMessage(color("&eTus alquileres estarán disponibles en una siguiente etapa."));
            case "returns" ->
                    player.sendMessage(color("&eLas devoluciones se implementarán en una siguiente etapa."));
            case "listings" ->
                    player.sendMessage(color("&eLa gestión de anuncios se implementará en una siguiente etapa."));
            default -> {
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (ChatColor.stripColor(event.getView().getTitle())
                .equals("Préstamos de FetiCraft")) {
            event.setCancelled(true);
        }
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public void onDisable() {
        getLogger().info("Préstamos de FetiCraft desactivado.");
    }
}
