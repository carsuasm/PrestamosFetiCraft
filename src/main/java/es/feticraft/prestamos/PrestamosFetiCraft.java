package es.feticraft.prestamos;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PrestamosFetiCraft extends JavaPlugin implements Listener, CommandExecutor {
    private Economy economy;
    private NamespacedKey actionKey;
    private NamespacedKey listingKey;
    private NamespacedKey rentalKey;
    private Connection database;
    private final Map<UUID, String> pendingListings = new HashMap<>();

    private static final String MENU_TITLE = "&8Préstamos de FetiCraft";
    private static final String MARKET_TITLE = "&8Mercado de préstamos";
    private static final String LISTINGS_TITLE = "&8Mis anuncios";
    private static final String CONFIRM_TITLE = "&8Confirmar alquiler";
    private static final String RENTALS_TITLE = "&8Mis alquileres";
    private static final String RETURNS_TITLE = "&8Mis devoluciones";
    private static final long MAX_DURATION_MINUTES = 43200L;

    @Override
    public void onEnable() {
        actionKey = new NamespacedKey(this, "action");
        listingKey = new NamespacedKey(this, "listing_id");
        rentalKey = new NamespacedKey(this, "rental_id");
        if (!setupEconomy() || !openDatabase()) {
            getLogger().severe("No se pudo iniciar Préstamos: revisa Vault, la economía y la base de datos.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("prestamos") == null) {
            getLogger().severe("Falta /prestamos en plugin.yml");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("prestamos").setExecutor(this);
        
        Bukkit.getScheduler().runTaskTimer(this, this::processAllExpiredRentals, 20L, 20L);
        
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                cleanExpiredItemsFromPlayer(p, false);
            }
        }, 60L, 60L);

        getLogger().info("Préstamos de FetiCraft iniciado correctamente.");
        getLogger().info("Economía conectada: " + economy.getName());
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) return false;
        economy = provider.getProvider();
        return economy != null;
    }

    private boolean openDatabase() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return false;
            database = DriverManager.getConnection("jdbc:sqlite:" + new java.io.File(getDataFolder(), "prestamos.db").getAbsolutePath());
            try (Statement st = database.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=FULL");
                st.execute("CREATE TABLE IF NOT EXISTS listings (id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, item_data BLOB NOT NULL, price REAL NOT NULL CHECK(price > 0), duration_seconds INTEGER NOT NULL CHECK(duration_seconds > 0), status TEXT NOT NULL CHECK(status IN ('AVAILABLE','RENTED','RETURN_PENDING','CLAIMED','CANCELLED','RECOVERY_REQUIRED')), created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_listings_status_created ON listings(status, created_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_listings_owner_status ON listings(owner_uuid, status)");
                st.execute("CREATE TABLE IF NOT EXISTS rentals (id TEXT PRIMARY KEY, listing_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, renter_uuid TEXT NOT NULL, price REAL NOT NULL, started_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('ACTIVE','EXPIRED','RETURN_PENDING','RETURNED','RECOVERY_REQUIRED')), FOREIGN KEY(listing_id) REFERENCES listings(id))");
                st.execute("CREATE INDEX IF NOT EXISTS idx_rentals_renter_status ON rentals(renter_uuid, status)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_rentals_expiry ON rentals(status, expires_at)");
                st.execute("CREATE TABLE IF NOT EXISTS operations (id TEXT PRIMARY KEY, operation_type TEXT NOT NULL, listing_id TEXT NOT NULL, player_uuid TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('PREPARED','COMMITTED','FAILED','RECOVERY_REQUIRED')), details TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS returns (rental_id TEXT PRIMARY KEY, listing_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, renter_uuid TEXT NOT NULL, item_data BLOB NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('PENDING','CLAIMED','RECOVERY_REQUIRED')), FOREIGN KEY(rental_id) REFERENCES rentals(id))");
                st.execute("CREATE INDEX IF NOT EXISTS idx_returns_owner_status ON returns(owner_uuid, status)");
            }
            return true;
        } catch (Exception e) {
            getLogger().severe("Error abriendo SQLite: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando solo puede usarlo un jugador.");
            return true;
        }
        if (!player.hasPermission("prestamos.use")) {
            message(player, "&cNo tienes permiso para usar los préstamos.");
            return true;
        }
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("publicar")) {
                if (args.length != 3) {
                    message(player, "&eUso: /prestamos publicar <precio> <minutos>");
                    return true;
                }
                double price;
                long minutes;
                try {
                    price = Double.parseDouble(args[1]);
                    minutes = Long.parseLong(args[2]);
                } catch (NumberFormatException ex) {
                    message(player, "&cEl precio y los minutos deben ser números válidos.");
                    return true;
                }
                publishItem(player, price, minutes);
                return true;
            } else if (args[0].equalsIgnoreCase("devolver")) {
                if (args.length != 2) {
                    message(player, "&eUso: /prestamos devolver <id_alquiler>");
                    return true;
                }
                returnEarly(player, args[1]);
                return true;
            }
        }
        openMainMenu(player);
        return true;
    }

    private void publishItem(Player player, double price, long minutes) {
        if (!Double.isFinite(price) || price <= 0 || minutes < 1 || minutes > MAX_DURATION_MINUTES) {
            message(player, "&cPrecio mayor que 0 y duración entre 1 y 43200 minutos.");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            message(player, "&cDebes tener en la mano el objeto que quieres publicar.");
            return;
        }
        
        if (hasRentalMarker(hand)) {
            String rId = hand.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
            if (isRentalExpiredForPlayer(player, rId)) {
                hand.setAmount(0);
                player.getInventory().setItemInMainHand(null);
                player.updateInventory();
                message(player, "&cEl objeto alquilado que sostenías ya había caducado y ha sido retirado.");
            } else {
                message(player, "&cNo puedes vender o publicar un objeto que estás alquilando.");
            }
            return;
        }

        ItemStack stored = hand.clone();
        String listingId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            byte[] data = serializeItem(stored);
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO listings(id,owner_uuid,owner_name,item_data,price,duration_seconds,status,created_at,updated_at,version) VALUES(?,?,?,?,?,?,'RECOVERY_REQUIRED',?,?,0)")) {
                ps.setString(1, listingId); ps.setString(2, player.getUniqueId().toString()); ps.setString(3, player.getName()); ps.setBytes(4, data); ps.setDouble(5, price); ps.setLong(6, minutes * 60L); ps.setLong(7, now); ps.setLong(8, now); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO operations(id,operation_type,listing_id,player_uuid,status,details,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, operationId); ps.setString(2, "PUBLISH"); ps.setString(3, listingId); ps.setString(4, player.getUniqueId().toString()); ps.setString(5, "PREPARED"); ps.setString(6, "Publicación en curso"); ps.setLong(7, now); ps.setLong(8, now); ps.executeUpdate();
            }
            database.commit();
            database.setAutoCommit(true);

            if (!hand.equals(player.getInventory().getItemInMainHand())) {
                markRecoveryRequired(operationId, listingId, "La mano cambió durante la publicación; revisar manualmente.");
                message(player, "&cPublicación pendiente de revisión. No se ha retirado ningún objeto automáticamente.");
                return;
            }
            player.getInventory().setItemInMainHand(null);
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='AVAILABLE',updated_at=?,version=version+1 WHERE id=? AND status='RECOVERY_REQUIRED'")) {
                ps.setLong(1, System.currentTimeMillis()); ps.setString(2, listingId); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE operations SET status='COMMITTED',details=?,updated_at=? WHERE id=?")) {
                ps.setString(1, "Objeto guardado en el anuncio"); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, operationId); ps.executeUpdate();
            }
            message(player, "&aObjeto publicado. Se ha retirado de tu inventario. &7ID: " + listingId.substring(0, 8));
        } catch (Exception e) {
            getLogger().severe("Error publicando objeto: " + e.getMessage());
            markRecoveryRequired(operationId, listingId, e.toString());
            message(player, "&cNo se pudo completar la publicación. Revisa la consola antes de repetirla.");
        }
    }

    private byte[] serializeItem(ItemStack item) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            out.flush();
            return bytes.toByteArray();
        }
    }

    private ItemStack deserializeItem(byte[] data) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(data); BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            Object obj = in.readObject();
            if (!(obj instanceof ItemStack item)) throw new IOException("El objeto guardado no es un ItemStack");
            return item;
        }
    }

    private void markRecoveryRequired(String operationId, String listingId, String details) {
        try {
            if (database == null || database.isClosed()) return;
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RECOVERY_REQUIRED',updated_at=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis()); ps.setString(2, listingId); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE operations SET status='RECOVERY_REQUIRED',details=?,updated_at=? WHERE id=?")) {
                ps.setString(1, details); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, operationId); ps.executeUpdate();
            }
            database.commit();
            database.setAutoCommit(true);
        } catch (SQLException ex) {
            getLogger().severe("No se pudo marcar una operación para recuperación: " + ex.getMessage());
            try { database.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    private void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, color(MENU_TITLE));
        addButton(inv, 11, Material.EMERALD, "&aMercado", List.of("&7Alquila objetos de otros jugadores."), "market");
        addButton(inv, 13, Material.CHEST, "&ePublicar objeto", List.of("&7Usa &f/prestamos publicar <precio> <minutos>"), "publish_info");
        addButton(inv, 15, Material.CLOCK, "&bMis alquileres", List.of("&7Consulta tus alquileres activos."), "rentals");
        addButton(inv, 21, Material.HOPPER, "&6Mis devoluciones", List.of("&7Recoge los objetos que han vuelto."), "returns");
        addButton(inv, 23, Material.BOOK, "&dMis anuncios", List.of("&7Consulta tus objetos publicados."), "my_listings");
        player.openInventory(inv);
    }

    private void addButton(Inventory inv, int slot, Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        List<String> colored = new ArrayList<>();
        for (String line : lore) colored.add(color(line));
        meta.setLore(colored);
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        inv.setItem(slot, item);
    }

    private void openListings(Player player, boolean ownListings) {
        Inventory inv = Bukkit.createInventory(null, 54, color(ownListings ? LISTINGS_TITLE : MARKET_TITLE));
        String sql = ownListings
                ? "SELECT id,owner_uuid,owner_name,item_data,price,duration_seconds,status FROM listings WHERE owner_uuid=? AND status NOT IN ('CANCELLED','CLAIMED') ORDER BY created_at DESC LIMIT 45"
                : "SELECT id,owner_uuid,owner_name,item_data,price,duration_seconds,status FROM listings WHERE status='AVAILABLE' ORDER BY created_at DESC LIMIT 45";
        try (PreparedStatement ps = database.prepareStatement(sql)) {
            if (ownListings) ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                int slot = 0;
                while (rs.next() && slot < 45) {
                    ItemStack item = deserializeItem(rs.getBytes("item_data"));
                    if (item == null || item.getType().isAir()) continue;
                    ItemMeta meta = item.getItemMeta();
                    List<String> lore = meta != null && meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add(color("&7Propietario: &f" + rs.getString("owner_name")));
                    lore.add(color("&7Precio: &a" + money(rs.getDouble("price"))));
                    lore.add(color("&7Duración: &f" + formatDuration(rs.getLong("duration_seconds"))));
                    lore.add(color("&7Estado: &f" + rs.getString("status")));
                    String listingId = rs.getString("id");
                    lore.add(color("&8ID: " + listingId.substring(0, 8)));
                    
                    if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(item.getType());
                    if (meta != null) {
                        meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, listingId);
                        if (ownListings && "AVAILABLE".equals(rs.getString("status"))) {
                            lore.add(color("&cPulsa para cancelar anuncio"));
                            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "cancel_listing");
                        } else if (!ownListings && "AVAILABLE".equals(rs.getString("status"))) {
                            lore.add(color("&aPulsa para alquilar"));
                        }
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        } catch (Exception e) {
            getLogger().warning("No se pudo cargar el mercado/anuncios: " + e.getMessage());
            message(player, "&cNo se pudieron cargar los anuncios.");
        }
        addButton(inv, 49, Material.ARROW, "&cVolver", List.of(), "back_main");
        player.openInventory(inv);
    }

    private void cancelListing(Player player, String listingId) {
        String operationId = UUID.randomUUID().toString();
        ItemStack item;
        try {
            if (player.getInventory().firstEmpty() == -1) {
                message(player, "&cNecesitas al menos un espacio libre en el inventario para recuperar tu objeto.");
                return;
            }
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("SELECT item_data,status FROM listings WHERE id=? AND owner_uuid=?")) {
                ps.setString(1, listingId);
                ps.setString(2, player.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"AVAILABLE".equals(rs.getString("status"))) {
                        database.rollback(); database.setAutoCommit(true);
                        message(player, "&cEste anuncio ya no está disponible para ser cancelado.");
                        return;
                    }
                    item = deserializeItem(rs.getBytes("item_data"));
                }
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='CANCELLED',updated_at=?,version=version+1 WHERE id=? AND status='AVAILABLE'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, listingId);
                if (ps.executeUpdate() != 1) {
                    database.rollback(); database.setAutoCommit(true);
                    message(player, "&cNo se pudo cancelar el anuncio.");
                    return;
                }
            }
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO operations(id,operation_type,listing_id,player_uuid,status,details,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, operationId);
                ps.setString(2, "CANCEL_LISTING");
                ps.setString(3, listingId);
                ps.setString(4, player.getUniqueId().toString());
                ps.setString(5, "PREPARED");
                ps.setString(6, "Anuncio cancelado; devolviendo objeto");
                long now = System.currentTimeMillis();
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().warning("Error cancelando anuncio: " + e.getMessage());
            message(player, "&cOcurrió un error al cancelar el anuncio.");
            return;
        }

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            message(player, "&cInventario lleno. El objeto se ha guardado en tus devoluciones.");
            try {
                database.setAutoCommit(false);
                try (PreparedStatement ps = database.prepareStatement("INSERT OR IGNORE INTO returns(rental_id,listing_id,owner_uuid,renter_uuid,item_data,created_at,status) VALUES(?,?,?,?,?,?, 'PENDING')")) {
                    ps.setString(1, "cancel_" + listingId);
                    ps.setString(2, listingId);
                    ps.setString(3, player.getUniqueId().toString());
                    ps.setString(4, player.getUniqueId().toString());
                    ps.setBytes(5, serializeItem(item));
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                database.commit(); database.setAutoCommit(true);
            } catch (Exception ex) {
                getLogger().severe("Error guardando objeto cancelado en returns: " + ex.getMessage());
            }
        } else {
            message(player, "&aHas cancelado tu anuncio y recuperado tu objeto.");
        }
        openListings(player, true);
    }

    private void openRentalConfirmation(Player player, String listingId) {
        try (PreparedStatement ps = database.prepareStatement("SELECT owner_uuid,owner_name,item_data,price,duration_seconds,status FROM listings WHERE id=?")) {
            ps.setString(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"AVAILABLE".equals(rs.getString("status"))) {
                    message(player, "&cEste anuncio ya no está disponible.");
                    return;
                }
                if (rs.getString("owner_uuid").equals(player.getUniqueId().toString())) {
                    message(player, "&cNo puedes alquilar tu propio objeto.");
                    return;
                }
                Inventory inv = Bukkit.createInventory(null, 27, color(CONFIRM_TITLE));
                ItemStack item = deserializeItem(rs.getBytes("item_data"));
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                    lore.add(color("&7Propietario: &f" + rs.getString("owner_name")));
                    lore.add(color("&7Coste: &a" + money(rs.getDouble("price"))));
                    lore.add(color("&7Duración: &f" + formatDuration(rs.getLong("duration_seconds"))));
                    item.setItemMeta(meta);
                }
                inv.setItem(13, item);
                addButton(inv, 11, Material.LIME_CONCRETE, "&aConfirmar alquiler", List.of("&7Se cobrará el precio indicado."), "confirm_rental");
                addButton(inv, 15, Material.RED_CONCRETE, "&cCancelar", List.of(), "back_market");
                pendingListings.put(player.getUniqueId(), listingId);
                player.openInventory(inv);
            }
        } catch (Exception e) {
            getLogger().warning("Error preparando alquiler: " + e.getMessage());
            message(player, "&cNo se pudo abrir la confirmación.");
        }
    }

    private void rentListing(Player renter, String listingId) {
        String rentalId = UUID.randomUUID().toString();
        String operationId = UUID.randomUUID().toString();
        String ownerUuid;
        double price;
        long duration;
        ItemStack item;
        long now = System.currentTimeMillis();
        try {
            if (renter.getInventory().firstEmpty() == -1) {
                message(renter, "&cNecesitas un espacio libre en el inventario.");
                return;
            }
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("SELECT owner_uuid,item_data,price,duration_seconds,status FROM listings WHERE id=?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"AVAILABLE".equals(rs.getString("status"))) {
                        database.rollback(); database.setAutoCommit(true);
                        message(renter, "&cEste anuncio ya no está disponible."); return;
                    }
                    ownerUuid = rs.getString("owner_uuid");
                    price = rs.getDouble("price"); duration = rs.getLong("duration_seconds");
                    if (ownerUuid.equals(renter.getUniqueId().toString())) {
                        database.rollback(); database.setAutoCommit(true);
                        message(renter, "&cNo puedes alquilar tu propio objeto."); return;
                    }
                    item = deserializeItem(rs.getBytes("item_data"));
                }
            }
            if (!economy.has(renter, price)) {
                database.rollback(); database.setAutoCommit(true);
                message(renter, "&cNo tienes dinero suficiente."); return;
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RENTED',updated_at=?,version=version+1 WHERE id=? AND status='AVAILABLE'")) {
                ps.setLong(1, now); ps.setString(2, listingId);
                if (ps.executeUpdate() != 1) {
                    database.rollback(); database.setAutoCommit(true);
                    message(renter, "&cOtro jugador acaba de alquilar este objeto."); return;
                }
            }
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO rentals(id,listing_id,owner_uuid,renter_uuid,price,started_at,expires_at,status) VALUES(?,?,?,?,?,?,?,'ACTIVE')")) {
                ps.setString(1, rentalId); ps.setString(2, listingId); ps.setString(3, ownerUuid); ps.setString(4, renter.getUniqueId().toString()); ps.setDouble(5, price); ps.setLong(6, now); ps.setLong(7, now + duration * 1000L); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO operations(id,operation_type,listing_id,player_uuid,status,details,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, operationId); ps.setString(2, "RENT"); ps.setString(3, listingId); ps.setString(4, renter.getUniqueId().toString()); ps.setString(5, "PREPARED"); ps.setString(6, "Reserva creada; pendiente de movimiento de dinero y entrega"); ps.setLong(7, now); ps.setLong(8, now); ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().warning("No se pudo reservar el alquiler: " + e.getMessage());
            message(renter, "&cNo se pudo reservar el anuncio. No repitas el pago; revisa el estado del anuncio.");
            return;
        }

        boolean renterCharged = false;
        boolean ownerPaid = false;
        try {
            if (!economy.withdrawPlayer(renter, price).transactionSuccess()) throw new IllegalStateException("No se pudo cobrar al alquilador");
            renterCharged = true;
            OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid));
            if (!economy.depositPlayer(owner, price).transactionSuccess()) throw new IllegalStateException("No se pudo pagar al propietario");
            ownerPaid = true;
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().set(rentalKey, PersistentDataType.STRING, rentalId);
                item.setItemMeta(meta);
            }
            Map<Integer, ItemStack> leftovers = renter.getInventory().addItem(item);
            if (!leftovers.isEmpty()) throw new IllegalStateException("El inventario ya no tiene espacio para entregar el objeto");
            try (PreparedStatement ps = database.prepareStatement("UPDATE operations SET status='COMMITTED',details=?,updated_at=? WHERE id=?")) {
                ps.setString(1, "Alquiler cobrado y objeto entregado"); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, operationId); ps.executeUpdate();
            }
            message(renter, "&aAlquiler activado. Has pagado &e" + money(price) + "&a y el objeto volverá al propietario en " + formatDuration(duration) + ".");
            Player ownerOnline = Bukkit.getPlayer(UUID.fromString(ownerUuid));
            if (ownerOnline != null) message(ownerOnline, "&a" + renter.getName() + " ha alquilado tu objeto por &e" + money(price) + "&a durante " + formatDuration(duration) + ".");
        } catch (Exception e) {
            getLogger().severe("Error durante el cobro/entrega de alquiler " + rentalId + ": " + e.getMessage());
            boolean compensated = true;
            if (ownerPaid) {
                try { if (!economy.withdrawPlayer(Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid)), price).transactionSuccess()) compensated = false; }
                catch (Exception ex) { compensated = false; }
            }
            if (renterCharged) {
                try { if (!economy.depositPlayer(renter, price).transactionSuccess()) compensated = false; }
                catch (Exception ex) { compensated = false; }
            }
            setRentalRecovery(rentalId, listingId, operationId, "Error en cobro/entrega: " + e.getMessage() + "; compensación=" + compensated);
            message(renter, "&cEl alquiler ha fallado durante el cobro o la entrega. Se ha marcado para revisión.");
        }
    }

    private void setRentalRecovery(String rentalId, String listingId, String operationId, String details) {
        try {
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='RECOVERY_REQUIRED' WHERE id=?")) { ps.setString(1, rentalId); ps.executeUpdate(); }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RECOVERY_REQUIRED',updated_at=? WHERE id=?")) { ps.setLong(1, System.currentTimeMillis()); ps.setString(2, listingId); ps.executeUpdate(); }
            try (PreparedStatement ps = database.prepareStatement("UPDATE operations SET status='RECOVERY_REQUIRED',details=?,updated_at=? WHERE id=?")) { ps.setString(1, details); ps.setLong(2, System.currentTimeMillis()); ps.setString(3, operationId); ps.executeUpdate(); }
            database.commit(); database.setAutoCommit(true);
        } catch (SQLException e) {
            getLogger().severe("No se pudo registrar recuperación de alquiler: " + e.getMessage());
            try { database.setAutoCommit(true); } catch (Exception ignored) {}
        }
    }

    private void openRentals(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, color(RENTALS_TITLE));
        try (PreparedStatement ps = database.prepareStatement("SELECT r.id,r.expires_at,r.status,l.item_data,l.owner_name FROM rentals r JOIN listings l ON l.id=r.listing_id WHERE r.renter_uuid=? AND r.status IN ('ACTIVE','RECOVERY_REQUIRED') ORDER BY r.started_at DESC LIMIT 45")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                int slot = 0;
                while (rs.next() && slot < 45) {
                    ItemStack item = deserializeItem(rs.getBytes("item_data"));
                    ItemMeta meta = item.getItemMeta();
                    String rentalId = rs.getString("id");
                    if (meta != null) {
                        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                        lore.add(color("&7Propietario: &f" + rs.getString("owner_name")));
                        lore.add(color("&7Estado: &f" + rs.getString("status")));
                        long remaining = Math.max(0, (rs.getLong("expires_at") - System.currentTimeMillis() + 999L) / 1000L);
                        lore.add(color("&7Tiempo restante: &e" + formatDuration(remaining)));
                        lore.add(color("&ePulsa para devolver anticipadamente"));
                        meta.getPersistentDataContainer().set(rentalKey, PersistentDataType.STRING, rentalId);
                        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "return_early_gui");
                        meta.setLore(lore);
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        } catch (Exception e) { getLogger().warning("Error mostrando alquileres: " + e.getMessage()); }
        addButton(inv, 49, Material.ARROW, "&cVolver", List.of(), "back_main");
        player.openInventory(inv);
    }

    private void returnEarly(Player player, String rentalId) {
        String listingId;
        String ownerUuid;
        try (PreparedStatement ps = database.prepareStatement("SELECT listing_id,owner_uuid FROM rentals WHERE id=? AND renter_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, rentalId);
            ps.setString(2, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    message(player, "&cNo se ha encontrado un alquiler activo con ese ID.");
                    return;
                }
                listingId = rs.getString("listing_id");
                ownerUuid = rs.getString("owner_uuid");
            }
        } catch (SQLException e) {
            message(player, "&cError al procesar la devolución.");
            return;
        }

        List<Integer> matches = new ArrayList<>();
        ItemStack found = null;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (hasRentalId(stack, rentalId)) {
                matches.add(i);
                found = stack.clone();
            }
        }

        if (matches.size() != 1 || found == null) {
            message(player, "&cDebes llevar el objeto alquilado exacto en tu inventario para devolverlo.");
            return;
        }

        ItemMeta meta = found.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(rentalKey);
            found.setItemMeta(meta);
        }

        try {
            byte[] data = serializeItem(found);
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO returns(rental_id,listing_id,owner_uuid,renter_uuid,item_data,created_at,status) VALUES(?,?,?,?,?,?, 'PENDING')")) {
                ps.setString(1, rentalId);
                ps.setString(2, listingId);
                ps.setString(3, ownerUuid);
                ps.setString(4, player.getUniqueId().toString());
                ps.setBytes(5, data);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='RETURN_PENDING' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId);
                if (ps.executeUpdate() != 1) {
                    database.rollback(); database.setAutoCommit(true);
                    message(player, "&cEl alquiler ya ha sido procesado.");
                    return;
                }
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RETURN_PENDING',updated_at=?,version=version+1 WHERE id=? AND status='RENTED'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, listingId);
                ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);

            player.getInventory().setItem(matches.get(0), null);
            player.updateInventory();
            message(player, "&aHas devuelto el objeto correctamente.");
            Player owner = Bukkit.getPlayer(UUID.fromString(ownerUuid));
            if (owner != null) {
                message(owner, "&aUn jugador ha devuelto tu objeto anticipadamente. Recógelo en Mis devoluciones.");
            }
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().severe("Error en devolución anticipada: " + e.getMessage());
            message(player, "&cOcurrió un error al devolver el objeto.");
        }
    }

    private void openReturns(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, color(RETURNS_TITLE));
        try (PreparedStatement ps = database.prepareStatement("SELECT r.rental_id,r.item_data,r.created_at,l.owner_uuid FROM returns r JOIN listings l ON l.id=r.listing_id WHERE r.owner_uuid=? AND r.status='PENDING' ORDER BY r.created_at ASC LIMIT 45")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                int slot = 0;
                while (rs.next() && slot < 45) {
                    ItemStack item = deserializeItem(rs.getBytes("item_data"));
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.getPersistentDataContainer().remove(rentalKey);
                        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                        lore.add(color("&aObjeto devuelto"));
                        lore.add(color("&7Pulsa para recuperarlo"));
                        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "claim_return");
                        meta.getPersistentDataContainer().set(rentalKey, PersistentDataType.STRING, rs.getString("rental_id"));
                        item.setItemMeta(meta);
                    }
                    inv.setItem(slot++, item);
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error cargando devoluciones: " + e.getMessage());
            message(player, "&cNo se pudieron cargar tus devoluciones.");
        }
        addButton(inv, 49, Material.ARROW, "&cVolver", List.of(), "back_main");
        player.openInventory(inv);
    }

    private void claimReturn(Player owner, String rentalId) {
        if (owner.getInventory().firstEmpty() == -1) {
            message(owner, "&cDeja al menos un espacio libre en tu inventario.");
            return;
        }

        String listingId;
        ItemStack item;
        String operationId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        try {
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("SELECT listing_id,item_data FROM returns WHERE rental_id=? AND owner_uuid=? AND status='PENDING'")) {
                ps.setString(1, rentalId);
                ps.setString(2, owner.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        database.rollback(); database.setAutoCommit(true);
                        message(owner, "&cEsta devolución ya no está disponible.");
                        return;
                    }
                    listingId = rs.getString("listing_id");
                    item = deserializeItem(rs.getBytes("item_data"));
                }
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE returns SET status='RECOVERY_REQUIRED' WHERE rental_id=? AND owner_uuid=? AND status='PENDING'")) {
                ps.setString(1, rentalId);
                ps.setString(2, owner.getUniqueId().toString());
                if (ps.executeUpdate() != 1) {
                    database.rollback(); database.setAutoCommit(true);
                    message(owner, "&cOtro proceso está gestionando esta devolución.");
                    return;
                }
            }
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO operations(id,operation_type,listing_id,player_uuid,status,details,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, operationId);
                ps.setString(2, "CLAIM_RETURN");
                ps.setString(3, listingId);
                ps.setString(4, owner.getUniqueId().toString());
                ps.setString(5, "PREPARED");
                ps.setString(6, "Devolución reservada");
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            message(owner, "&cNo se pudo preparar la devolución.");
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(rentalKey);
            meta.getPersistentDataContainer().remove(actionKey);
            item.setItemMeta(meta);
        }

        Map<Integer, ItemStack> leftovers = owner.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            try {
                database.setAutoCommit(false);
                try (PreparedStatement ps = database.prepareStatement("UPDATE returns SET status='PENDING' WHERE rental_id=? AND status='RECOVERY_REQUIRED'")) {
                    ps.setString(1, rentalId);
                    ps.executeUpdate();
                }
                database.commit(); database.setAutoCommit(true);
            } catch (SQLException ignored) {
                try { database.setAutoCommit(true); } catch (Exception ex2) {}
            }
            message(owner, "&cNo hay espacio en el inventario.");
            return;
        }

        try {
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("UPDATE returns SET status='CLAIMED' WHERE rental_id=? AND status='RECOVERY_REQUIRED'")) {
                ps.setString(1, rentalId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='RETURNED' WHERE id=?")) {
                ps.setString(1, rentalId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='CLAIMED',updated_at=?,version=version+1 WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, listingId);
                ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);
            message(owner, "&aHas recuperado tu objeto devuelto.");
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            message(owner, "&eObjeto entregado, pero el registro requiere revisión administrativa.");
        }
    }

    private void processAllExpiredRentals() {
        if (database == null) return;
        try (PreparedStatement ps = database.prepareStatement("SELECT id,listing_id,renter_uuid,owner_uuid FROM rentals WHERE status='ACTIVE' AND expires_at<=?")) {
            ps.setLong(1, System.currentTimeMillis());
            List<String[]> due = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new String[]{rs.getString("id"), rs.getString("listing_id"), rs.getString("renter_uuid"), rs.getString("owner_uuid")});
                }
            }
            for (String[] row : due) {
                processExpiredRental(row[0], row[1], UUID.fromString(row[2]), UUID.fromString(row[3]));
            }
        } catch (Exception e) {
            getLogger().warning("Error procesando caducidades: " + e.getMessage());
        }
    }

    private void processExpiredRental(String rentalId, String listingId, UUID renterUuid, UUID ownerUuid) {
        try {
            boolean isActive = false;
            try (PreparedStatement ps = database.prepareStatement("SELECT id FROM rentals WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) isActive = true;
                }
            }
            if (!isActive) return;

            ItemStack fallbackItem = null;
            try (PreparedStatement ps = database.prepareStatement("SELECT item_data FROM listings WHERE id=?")) {
                ps.setString(1, listingId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) fallbackItem = deserializeItem(rs.getBytes("item_data"));
                }
            }
            if (fallbackItem == null) return;

            ItemMeta meta = fallbackItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().remove(rentalKey);
                fallbackItem.setItemMeta(meta);
            }
            byte[] data = serializeItem(fallbackItem);

            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO returns(rental_id,listing_id,owner_uuid,renter_uuid,item_data,created_at,status) VALUES(?,?,?,?,?,?, 'PENDING')")) {
                ps.setString(1, rentalId); ps.setString(2, listingId); ps.setString(3, ownerUuid.toString()); ps.setString(4, renterUuid.toString()); ps.setBytes(5, data); ps.setLong(6, System.currentTimeMillis()); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='EXPIRED' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RETURN_PENDING',updated_at=?,version=version+1 WHERE id=? AND status='RENTED'")) {
                ps.setLong(1, System.currentTimeMillis()); ps.setString(2, listingId); ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);

            Player renterOnline = Bukkit.getPlayer(renterUuid);
            if (renterOnline != null) {
                cleanExpiredItemsFromPlayer(renterOnline, true);
            }

            Player ownerOnline = Bukkit.getPlayer(ownerUuid);
            if (ownerOnline != null) {
                message(ownerOnline, "&aTu objeto ha vuelto por caducidad. Recógelo en Mis devoluciones.");
            }
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().warning("Error procesando caducidad para " + rentalId + ": " + e.getMessage());
        }
    }

    private void cleanExpiredItemsFromPlayer(Player player, boolean notify) {
        boolean changed = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.hasItemMeta()) {
                String rId = stack.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
                if (rId != null && isRentalExpiredForPlayer(player, rId)) {
                    player.getInventory().setItem(i, null);
                    changed = true;
                }
            }
        }
        if (changed) {
            player.updateInventory();
            if (notify) {
                message(player, "&c¡Tu alquiler ha caducado y el objeto ha sido retirado!");
            }
        }
    }

    private boolean isRentalExpiredForPlayer(Player player, String rentalId) {
        if (rentalId == null) return false;
        try (PreparedStatement ps = database.prepareStatement("SELECT renter_uuid, status FROM rentals WHERE id=?")) {
            ps.setString(1, rentalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String renterUuid = rs.getString("renter_uuid");
                    String status = rs.getString("status");
                    if (player.getUniqueId().toString().equals(renterUuid)) {
                        return "EXPIRED".equals(status) || "RETURN_PENDING".equals(status) || "RETURNED".equals(status) || "RECOVERY_REQUIRED".equals(status);
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean hasRentalId(ItemStack item, String rentalId) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return rentalId.equals(item.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING));
    }

    private boolean hasRentalMarker(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(rentalKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        checkAndDestroyExpiredItem(player, event.getCurrentItem(), event);
        checkAndDestroyExpiredItem(player, event.getCursor(), event);

        String title = event.getView().getTitle();
        boolean ourMenu = title.equals(color(MENU_TITLE)) || title.equals(color(MARKET_TITLE)) || title.equals(color(LISTINGS_TITLE)) || title.equals(color(CONFIRM_TITLE)) || title.equals(color(RENTALS_TITLE)) || title.equals(color(RETURNS_TITLE));
        if (!ourMenu) {
            if (hasRentalMarker(event.getCurrentItem()) || hasRentalMarker(event.getCursor())) {
                if (event.getClickedInventory() != null && event.getClickedInventory() != event.getView().getBottomInventory()) event.setCancelled(true);
                if (event.isShiftClick() || event.getHotbarButton() >= 0) event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        ItemMeta meta = clicked.getItemMeta();
        String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
        if (action != null) {
            switch (action) {
                case "market" -> openListings(player, false);
                case "publish_info" -> message(player, "&eSostén el objeto y escribe: &f/prestamos publicar <precio> <minutos>");
                case "rentals" -> openRentals(player);
                case "returns" -> openReturns(player);
                case "my_listings" -> openListings(player, true);
                case "back_main" -> openMainMenu(player);
                case "back_market" -> openListings(player, false);
                case "confirm_rental" -> {
                    String id = pendingListings.get(player.getUniqueId());
                    if (id == null) message(player, "&cLa confirmación ha caducado.");
                    else { pendingListings.remove(player.getUniqueId()); player.closeInventory(); rentListing(player, id); }
                }
                case "claim_return" -> {
                    String id = meta.getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
                    if (id != null) { player.closeInventory(); claimReturn(player, id); }
                }
                case "cancel_listing" -> {
                    String id = meta.getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
                    if (id != null) { player.closeInventory(); cancelListing(player, id); }
                }
                case "return_early_gui" -> {
                    String id = meta.getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
                    if (id != null) { player.closeInventory(); returnEarly(player, id); }
                }
            }
            return;
        }
        if (title.equals(color(MARKET_TITLE)) && meta.getPersistentDataContainer().has(listingKey, PersistentDataType.STRING)) {
            String id = meta.getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
            if (id != null) openRentalConfirmation(player, id);
        }
    }

    private void checkAndDestroyExpiredItem(Player player, ItemStack item, InventoryClickEvent event) {
        if (item == null || !item.hasItemMeta()) return;
        String rId = item.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
        if (rId != null && isRentalExpiredForPlayer(player, rId)) {
            item.setAmount(0);
            event.setCancelled(true);
            player.updateInventory();
            message(player, "&cEste objeto alquilado ya caducado ha sido eliminado de tu inventario.");
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.equals(color(MENU_TITLE)) || title.equals(color(MARKET_TITLE)) || title.equals(color(LISTINGS_TITLE)) || title.equals(color(CONFIRM_TITLE)) || title.equals(color(RENTALS_TITLE)) || title.equals(color(RETURNS_TITLE))) {
            event.setCancelled(true); return;
        }
        if (hasRentalMarker(event.getOldCursor())) {
            for (int rawSlot : event.getRawSlots()) if (rawSlot < event.getView().getTopInventory().getSize()) { event.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        if (event.getView().getTitle().equals(color(CONFIRM_TITLE))) pendingListings.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            cleanExpiredItemsFromPlayer(player, true);
        }, 5L);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            cleanExpiredItemsFromPlayer(player, true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            processAllExpiredRentals();
            cleanExpiredItemsFromPlayer(player, true);
        }, 5L);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();
        String rId = item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING) : null;
        if (rId != null && isRentalExpiredForPlayer(player, rId)) {
            event.getItemDrop().remove();
            player.updateInventory();
            return;
        }
        if (hasRentalMarker(item)) {
            event.setCancelled(true);
            player.updateInventory();
            message(player, "&cNo puedes tirar un objeto alquilado.");
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (hasRentalMarker(event.getItem())) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cNo puedes consumir un objeto alquilado.");
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (hasRentalMarker(event.getItemInHand())) {
            event.setCancelled(true);
            message(event.getPlayer(), "&cNo puedes colocar bloques alquilados.");
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (hasRentalMarker(event.getItem().getItemStack()) && event.getEntity() instanceof Player player) {
            event.setCancelled(true);
        }
    }

    // NUEVO: DETECTAR CUANDO SE ROMPE UN OBJETO ALQUILADO
    @EventHandler
    public void onItemBreak(PlayerItemBreakEvent event) {
        ItemStack broken = event.getItemsBreak(); // En algunas versiones es event.getItem()
        // Nota de compatibilidad: Usamos event.getItem() o la revisión segura del item roto
        handleItemBreak(event.getPlayer(), broken);
    }

    private void handleItemBreak(Player player, ItemStack item) {
        if (!hasRentalMarker(item)) return;
        String rentalId = item.getItemMeta().getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
        if (rentalId == null) return;

        // Forzar la devolución inmediata a la base de datos como si hubiera expirado
        try {
            String listingId; String ownerUuid;
            try (PreparedStatement ps = database.prepareStatement("SELECT listing_id, owner_uuid FROM rentals WHERE id=? AND renter_uuid=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId);
                ps.setString(2, player.getUniqueId().toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return;
                    listingId = rs.getString("listing_id");
                    ownerUuid = rs.getString("owner_uuid");
                }
            }

            // Limpiar marca y clonar item roto (o guardarlo tal cual)
            ItemStack cleanItem = item.clone();
            ItemMeta meta = cleanItem.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().remove(rentalKey);
                cleanItem.setItemMeta(meta);
            }
            byte[] data = serializeItem(cleanItem);
            long now = System.currentTimeMillis();

            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("INSERT INTO returns(rental_id,listing_id,owner_uuid,renter_uuid,item_data,created_at,status) VALUES(?,?,?,?,?,?, 'PENDING')")) {
                ps.setString(1, rentalId); ps.setString(2, listingId); ps.setString(3, ownerUuid); ps.setString(4, player.getUniqueId().toString()); ps.setBytes(5, data); ps.setLong(6, now); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='EXPIRED' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RETURN_PENDING',updated_at=? WHERE id=? AND status='RENTED'")) {
                ps.setLong(1, now); ps.setString(2, listingId); ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);

            message(player, "&c¡Has roto la herramienta alquilada! Se ha devuelto automáticamente al propietario.");
            Player owner = Bukkit.getPlayer(UUID.fromString(ownerUuid));
            if (owner != null) {
                message(owner, "&cEl jugador que alquiló tu herramienta la ha roto. Está disponible en Mis devoluciones.");
            }
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().warning("Error procesando objeto alquilado roto: " + e.getMessage());
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> rentalDrops = new ArrayList<>();
        
        for (ItemStack drop : event.getDrops()) {
            if (hasRentalMarker(drop)) rentalDrops.add(drop.clone());
        }
        for (ItemStack content : player.getInventory().getContents()) {
            if (hasRentalMarker(content) && !rentalDrops.contains(content)) {
                rentalDrops.add(content.clone());
            }
        }

        if (rentalDrops.isEmpty()) return;

        for (ItemStack drop : rentalDrops) {
            ItemMeta meta = drop.getItemMeta();
            if (meta == null) continue;
            String rentalId = meta.getPersistentDataContainer().get(rentalKey, PersistentDataType.STRING);
            if (rentalId != null && !isRentalExpiredForPlayer(player, rentalId)) {
                returnRentalOnDeath(player, rentalId, drop);
            }
        }

        event.getDrops().removeIf(this::hasRentalMarker);
        for (int i = 0; i < player.getInventory().getContents().length; i++) {
            if (hasRentalMarker(player.getInventory().getItem(i))) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void returnRentalOnDeath(Player renter, String rentalId, ItemStack item) {
        String listingId; String ownerUuid;
        try (PreparedStatement ps = database.prepareStatement("SELECT listing_id,owner_uuid FROM rentals WHERE id=? AND renter_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, rentalId); ps.setString(2, renter.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                listingId = rs.getString("listing_id"); ownerUuid = rs.getString("owner_uuid");
            }
        } catch (SQLException e) { return; }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.getPersistentDataContainer().remove(rentalKey);
                meta.getPersistentDataContainer().remove(actionKey);
                item.setItemMeta(meta);
            }
            byte[] data = serializeItem(item);
            long now = System.currentTimeMillis();
            database.setAutoCommit(false);
            try (PreparedStatement ps = database.prepareStatement("INSERT OR IGNORE INTO returns(rental_id,listing_id,owner_uuid,renter_uuid,item_data,created_at,status) VALUES(?,?,?,?,?,?, 'PENDING')")) {
                ps.setString(1, rentalId); ps.setString(2, listingId); ps.setString(3, ownerUuid); ps.setString(4, renter.getUniqueId().toString()); ps.setBytes(5, data); ps.setLong(6, now); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE rentals SET status='EXPIRED' WHERE id=? AND status='ACTIVE'")) {
                ps.setString(1, rentalId); ps.executeUpdate();
            }
            try (PreparedStatement ps = database.prepareStatement("UPDATE listings SET status='RETURN_PENDING',updated_at=? WHERE id=? AND status='RENTED'")) {
                ps.setLong(1, now); ps.setString(2, listingId); ps.executeUpdate();
            }
            database.commit(); database.setAutoCommit(true);
            Player owner = Bukkit.getPlayer(UUID.fromString(ownerUuid));
            if (owner != null) message(owner, "&aUn objeto alquilado ha vuelto tras la muerte del jugador. Recógelo en Mis devoluciones.");
        } catch (Exception e) {
            try { database.rollback(); database.setAutoCommit(true); } catch (Exception ignored) {}
            getLogger().warning("Error devolviendo objeto tras muerte: " + e.getMessage());
        }
    }

    private String formatDuration(long seconds) {
        seconds = Math.max(0, seconds);
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        if (days > 0) return days + " d " + hours + " h";
        if (hours > 0) return hours + " h " + minutes + " min";
        if (minutes > 0) return minutes + " min " + secs + " s";
        return secs + " s";
    }

    private String money(double amount) { return String.format(java.util.Locale.US, "%.2f$", amount); }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private void message(Player player, String text) { player.sendMessage(color("&8[&6FetiCraft&8] " + text)); }

    @Override
    public void onDisable() {
        if (database != null) {
            try { if (!database.isClosed()) database.close(); }
            catch (SQLException e) { getLogger().warning("No se pudo cerrar SQLite: " + e.getMessage()); }
        }
    }
}
