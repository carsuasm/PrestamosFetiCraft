```java
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
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

public final class PrestamosFetiCraft extends JavaPlugin
        implements Listener {

    private Economy economy;
    private NamespacedKey actionKey;
    private Connection database;

    private static final String MENU_TITLE =
            "&8Préstamos de FetiCraft";

    private static final long MAX_DURATION_MINUTES = 43200;

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
            closeDatabase();
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
                "Publicación de objetos habilitada en fase inicial."
        );
        getLogger().warning(
                "Alquileres y recuperación automática aún no implementados."
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
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = FULL");

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

        if (args.length > 0 && args[0].equalsIgnoreCase("publicar")) {
            if (args.length != 3) {
                player.sendMessage(color(
                        "&eUso: /prestamos publicar <precio> <minutos>"
                ));
                player.sendMessage(color(
                        "&7Ejemplo: /prestamos publicar 500 60"
                ));
                return true;
            }

            publishItem(player, args[1], args[2]);
            return true;
        }

        openMainMenu(player);
        return true;
    }

    private void publishItem(
            Player player,
            String priceText,
            String durationText
    ) {
        double price;
        long durationMinutes;

        try {
            price = Double.parseDouble(priceText);
            durationMinutes = Long.parseLong(durationText);
        } catch (NumberFormatException exception) {
            player.sendMessage(color(
                    "&cEl precio y la duración deben ser números válidos."
            ));
            return;
        }

        if (!Double.isFinite(price) || price <= 0) {
            player.sendMessage(color(
                    "&cEl precio debe ser mayor que cero."
            ));
            return;
        }

        if (durationMinutes < 1 ||
                durationMinutes > MAX_DURATION_MINUTES) {
            player.sendMessage(color(
                    "&cLa duración debe estar entre 1 minuto y 30 días."
            ));
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType() == Material.AIR || hand.getAmount() <= 0) {
            player.sendMessage(color(
                    "&cDebes tener en la mano el objeto que quieres publicar."
            ));
            return;
        }

        if (database == null) {
            player.sendMessage(color(
                    "&cLa base de datos no está disponible."
            ));
            return;
        }

        ItemStack storedItem = hand.clone();
        byte[] itemData;

        try {
            itemData = serializeItem(storedItem);
        } catch (IOException exception) {
            getLogger().severe(
                    "No se pudo serializar el objeto de "
                            + player.getName() + ": "
                            + exception.getMessage()
            );
            player.sendMessage(color(
                    "&cNo se pudo guardar el objeto. No se ha retirado."
            ));
            return;
        }

        String listingId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long durationSeconds = durationMinutes * 60L;

        /*
         * Fase 1:
         * Guardar el objeto y la operación como recuperación requerida.
         * El anuncio todavía NO puede alquilarse.
         */
        try {
            database.setAutoCommit(false);

            try {
                try (PreparedStatement listing = database.prepareStatement("""
                    INSERT INTO listings (
                        id, owner_uuid, owner_name, item_data,
                        price, duration_seconds, status,
                        created_at, updated_at, version
                    ) VALUES (?, ?, ?, ?, ?, ?, 'RECOVERY_REQUIRED',
                              ?, ?, 0)
                    """)) {
                    listing.setString(1, listingId);
                    listing.setString(2, player.getUniqueId().toString());
                    listing.setString(3, player.getName());
                    listing.setBytes(4, itemData);
                    listing.setDouble(5, price);
                    listing.setLong(6, durationSeconds);
                    listing.setLong(7, now);
                    listing.setLong(8, now);
                    listing.executeUpdate();
                }

                try (PreparedStatement operation =
                             database.prepareStatement("""
                    INSERT INTO operations (
                        id, operation_type, listing_id, player_uuid,
                        status, details, created_at, updated_at
                    ) VALUES (?, 'PUBLISH', ?, ?, 'PREPARED', ?, ?, ?)
                    """)) {
                    operation.setString(1, operationId);
                    operation.setString(2, listingId);
                    operation.setString(
                            3, player.getUniqueId().toString()
                    );
                    operation.setString(
                            4, "Publicación en preparación; anuncio bloqueado."
                    );
                    operation.setLong(5, now);
                    operation.setLong(6, now);
                    operation.executeUpdate();
                }

                database.commit();
            } catch (SQLException exception) {
                try {
                    database.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                database.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            getLogger().severe(
                    "No se pudo preparar la publicación "
                            + listingId + ": " + exception.getMessage()
            );
            player.sendMessage(color(
                    "&cNo se pudo preparar el anuncio. "
                            + "Tu objeto no se ha retirado."
            ));
            return;
        }

        /*
         * Fase 2:
         * Retirar exactamente el objeto que se guardó.
         * El código se ejecuta en el hilo principal de Bukkit.
         */
        ItemStack currentHand =
                player.getInventory().getItemInMainHand();

        if (!storedItem.equals(currentHand)) {
            player.sendMessage(color(
                    "&cEl objeto de tu mano ha cambiado. "
                            + "El anuncio queda bloqueado para revisión."
            ));
            markRecoveryRequired(
                    operationId, listingId,
                    "El objeto cambió antes de retirarse del inventario."
            );
            return;
        }

        player.getInventory().setItemInMainHand(null);

        /*
         * Fase 3:
         * Solo ahora se activa el anuncio.
         * Si la base de datos falla, el anuncio permanece bloqueado
         * y el objeto guardado no se entrega a ningún alquilador.
         */
        try {
            database.setAutoCommit(false);

            try {
                try (PreparedStatement listing = database.prepareStatement("""
                    UPDATE listings
                    SET status = 'AVAILABLE',
                        updated_at = ?,
                        version = version + 1
                    WHERE id = ? AND status = 'RECOVERY_REQUIRED'
                    """)) {
                    listing.setLong(1, System.currentTimeMillis());
                    listing.setString(2, listingId);

                    if (listing.executeUpdate() != 1) {
                        throw new SQLException(
                                "No se pudo activar el anuncio."
                        );
                    }
                }

                try (PreparedStatement operation =
                             database.prepareStatement("""
                    UPDATE operations
                    SET status = 'COMMITTED',
                        details = ?,
                        updated_at = ?
                    WHERE id = ? AND status = 'PREPARED'
                    """)) {
                    operation.setString(
                            1, "Objeto guardado y anuncio activado."
                    );
                    operation.setLong(2, System.currentTimeMillis());
                    operation.setString(3, operationId);

                    if (operation.executeUpdate() != 1) {
                        throw new SQLException(
                                "No se pudo confirmar la operación."
                        );
                    }
                }

                database.commit();
            } catch (SQLException exception) {
                try {
                    database.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                database.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            getLogger().severe(
                    "PUBLICACIÓN BLOQUEADA. ID: " + listingId
                            + ". Revisar antes de recuperar el objeto. "
                            + exception.getMessage()
            );

            markRecoveryRequired(
                    operationId, listingId,
                    "Error al confirmar la publicación: "
                            + exception.getMessage()
            );

            player.sendMessage(color(
                    "&cHa ocurrido un error al finalizar la publicación."
            ));
            player.sendMessage(color(
                    "&eNo vuelvas a publicar ese objeto. "
                            + "Contacta con un administrador indicando el ID:"
            ));
            player.sendMessage(color("&7" + listingId));
            return;
        }

        player.sendMessage(color(
                "&aAnuncio publicado correctamente."
        ));
        player.sendMessage(color(
                "&7ID: &f" + listingId
        ));
        player.sendMessage(color(
                "&7Precio: &f" + price + " monedas"
        ));
        player.sendMessage(color(
                "&7Duración: &f" + durationMinutes + " minutos"
        ));
        player.sendMessage(color(
                "&eEl objeto está guardado en la base de datos."
        ));

        getLogger().info(
                "Anuncio " + listingId + " creado por "
                        + player.getName() + ". Precio: "
                        + price + ", duración: "
                        + durationMinutes + " minutos."
        );
    }

    private byte[] serializeItem(ItemStack item) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             BukkitObjectOutputStream output =
                     new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
            output.flush();
            return bytes.toByteArray();
        }
    }

    private void markRecoveryRequired(
            String operationId,
            String listingId,
            String details
    ) {
        if (database == null) {
            return;
        }

        try {
            database.setAutoCommit(false);

            try {
                try (PreparedStatement listing = database.prepareStatement("""
                    UPDATE listings
                    SET status = 'RECOVERY_REQUIRED',
                        updated_at = ?,
                        version = version + 1
                    WHERE id = ?
                    """)) {
                    listing.setLong(1, System.currentTimeMillis());
                    listing.setString(2, listingId);
                    listing.executeUpdate();
                }

                try (PreparedStatement operation =
                             database.prepareStatement("""
                    UPDATE operations
                    SET status = 'RECOVERY_REQUIRED',
                        details = ?,
                        updated_at = ?
                    WHERE id = ?
                    """)) {
                    operation.setString(1, details);
                    operation.setLong(2, System.currentTimeMillis());
                    operation.setString(3, operationId);
                    operation.executeUpdate();
                }

                database.commit();
            } catch (SQLException exception) {
                try {
                    database.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                database.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            getLogger().severe(
                    "CRÍTICO: no se pudo registrar la recuperación "
                            + "del anuncio " + listingId + ": "
                            + exception.getMessage()
            );
        }
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
                "Usa /prestamos publicar <precio> <minutos>.",
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
                            "&eUsa: /prestamos publicar <precio> <minutos>"
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
```
