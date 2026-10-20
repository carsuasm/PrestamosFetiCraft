
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

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class PrestamosFetiCraft extends JavaPlugin
        implements Listener {

    private Economy economy;
    private NamespacedKey actionKey;
    private Connection database;

    private static final String MENU_TITLE =
            "&8Préstamos de FetiCraft";

    @Override
    public void onEnable() {
        actionKey = new NamespacedKey(this, "menu_action");

        if (!setupEconomy()) {
            getLogger().severe(
                    "No se ha encontrado la economía de Vault."
            );
            getLogger().severe(
                    "Comprueba que Vault y Mineconomy estén activos."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        if (!openDatabase()) {
            getLogger().severe(
                    "No se pudo inicializar SQLite. Plugin desactivado."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);

        if (getCommand("prestamos") == null) {
            getLogger().severe(
                    "No se encuentra prestamos en plugin.yml."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getCommand("prestamos").setExecutor(this);

        getLogger().info(
                "Préstamos de FetiCraft iniciado correctamente."
        );
        getLogger().info(
                "Economía conectada: " + economy.getName()
        );
        getLogger().info(
                "Base de datos SQLite inicializada correctamente."
        );
        getLogger().info(
                "La gestión de alquileres todavía no está activada."
        );
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }

        RegisteredServiceProvider<Economy> registration =
                getServer().getServicesManager()
                        .getRegistration(Economy.class);

        if (registration == null) {
            return false;
        }

        economy = registration.getProvider();
        return economy != null;
    }

    private boolean openDatabase() {
        try {
            File folder = getDataFolder();

            if (!folder.exists() && !folder.mkdirs()) {
                getLogger().severe(
                        "No se pudo crear la carpeta del plugin."
                );
                return false;
            }

            File databaseFile = new File(folder, "prestamos.db");

            database = DriverManager.getConnection(
                    "jdbc:sqlite:" + databaseFile.getAbsolutePath()
            );

            try (Statement statement = database.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS listings (
                        id TEXT PRIMARY KEY,
                        owner_uuid TEXT NOT NULL,
                        owner_name TEXT NOT NULL,
                        item_data BLOB NOT NULL,
                        price REAL NOT NULL CHECK(price > 0),
                        duration_seconds INTEGER NOT NULL
                            CHECK(duration_seconds > 0),
                        status TEXT NOT NULL
                            CHECK(status IN (
                                'AVAILABLE',
                                'RENTED',
                                'RETURN_PENDING',
                                'CLAIMED',
                                'CANCELLED',
                                'RECOVERY_REQUIRED'
                            )),
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        version INTEGER NOT NULL DEFAULT 0
                    )
                    """);

                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_listings_status_created
                    ON listings(status, created_at)
                    """);

                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_listings_owner
                    ON listings(owner_uuid, status)
                    """);

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS rentals (
                        id TEXT PRIMARY KEY,
                        listing_id TEXT NOT NULL,
                        owner_uuid TEXT NOT NULL,
                        renter_uuid TEXT NOT NULL,
                        price REAL NOT NULL CHECK(price > 0),
                        started_at INTEGER NOT NULL,
                        expires_at INTEGER NOT NULL,
                        status TEXT NOT NULL
                            CHECK(status IN (
                                'ACTIVE',
                                'EXPIRED',
                                'RETURN_PENDING',
                                'RETURNED',
                                'RECOVERY_REQUIRED'
                            )),
                        FOREIGN KEY(listing_id)
                            REFERENCES listings(id)
                    )
                    """);

                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                    idx_rentals_renter_status
                    ON rentals(renter_uuid, status)
                    """);

                statement.execute("""
                    CREATE TABLE IF NOT EXISTS operations (
                        id TEXT PRIMARY KEY,
                        operation_type TEXT NOT NULL,
                        listing_id TEXT,
                        player_uuid TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN (
                                'PREPARED',
                                'COMMITTED',
                                'FAILED',
                                'RECOVERY_REQUIRED'
                            )),
                        details TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
            }

            return true;
        } catch (SQLException exception) {
            getLogger().severe(
                    "Error inicializando SQLite: "
                            + exception.getMessage()
            );

            closeDatabase();
            return false;
        }
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    "Este comando solo puede usarse dentro del juego."
            );
            return true;
        }

        if (!player.hasPermission("prestamos.use")) {
            player.sendMessage(color(
                    "&cNo tienes permiso para usar este comando."
            ));
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private void openMainMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(
                null, 27, color(MENU_TITLE)
        );

        addButton(inventory, 11, Material.BOOK,
                "&bMercado",
                "Ver objetos disponibles para alquilar.",
                "market");

        addButton(inventory, 13, Material.CHEST,
                "&aPublicar objeto",
                "Publicar un objeto para alquilar.",
                "publish");

        addButton(inventory, 15, Material.CLOCK,
                "&eMis alquileres",
                "Consultar tus alquileres activos.",
                "rentals");

        addButton(inventory, 21, Material.HOPPER,
                "&6Mis devoluciones",
                "Consultar objetos pendientes de recoger.",
                "returns");

        addButton(inventory, 23, Material.WRITABLE_BOOK,
                "&dMis anuncios",
                "Administrar tus anuncios.",
                "listings");

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

        if (slot < 0 ||
                slot >= event.getView().getTopInventory().getSize()) {
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
                    player.sendMessage(color(
                            "&eEl mercado estará disponible en otra etapa."
                    ));
            case "publish" ->
                    player.sendMessage(color(
                            "&eLa publicación segura aún no está activada."
                    ));
            case "rentals" ->
                    player.sendMessage(color(
                            "&eLos alquileres aún no están activados."
                    ));
            case "returns" ->
                    player.sendMessage(color(
                            "&eLas devoluciones aún no están activadas."
                    ));
            case "listings" ->
                    player.sendMessage(color(
                            "&eLa gestión de anuncios aún no está activada."
                    ));
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

    private void closeDatabase() {
        if (database == null) {
            return;
        }

        try {
            database.close();
        } catch (SQLException exception) {
            getLogger().severe(
                    "Error cerrando SQLite: " + exception.getMessage()
            );
        } finally {
            database = null;
        }
    }

    @Override
    public void onDisable() {
        closeDatabase();
        getLogger().info(
                "Préstamos de FetiCraft desactivado."
        );
    }
}
