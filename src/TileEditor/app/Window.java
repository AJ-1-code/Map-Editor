import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Controls:
 *   WASD / arrow keys ... move the camera
 *   Left mouse .......... draw tile / place entity / place event
 *   Right mouse ......... erase tile / remove entity / remove event
 *   Click the minimap ... jump the camera there
 *   Mouse wheel ......... scroll the sidebar
 */
public class Window extends JPanel
        implements MouseListener, MouseMotionListener, MouseWheelListener, KeyListener {

    int tile = 64;
    int visCols = 13;
    int visRows = 13;
    int viewW = tile * visCols;
    int viewH = tile * visRows;

    int menuStartX = viewW;
    int menuWidth = 400;

    static final int TAB_BAR_H = 32;
    static final int CONTENT_Y = 40;
    int contentBottom = viewH - 30;
    static final int ROW_H = 34;

    int mapWidth = 20;
    int mapHeight = 20;

    List<int[][]> layers = new ArrayList<>();
    List<Boolean> layerVisible = new ArrayList<>();
    int currentLayer = 0;

    public Tile[] tiles;
    int selectedTileIndex = -1;
    File tilesheetFile;
    int tilesheetTileSize = 32;

    static class Placed {
        String name;
        int x, y, layer;
        String args;

        Placed(String name, int x, int y, int layer, String args) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.layer = layer;
            this.args = args;
        }
    }

    static class CollisionLine {
        float x1, y1, x2, y2;
        boolean isoneway;
        int orientation, orientation2;

        CollisionLine(float x1, float y1, float x2, float y2,
                      boolean isoneway, int orientation, int orientation2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.isoneway = isoneway;
            this.orientation = orientation;
            this.orientation2 = orientation2;
        }
    }

    List<CollisionLine> collisionLines = new ArrayList<>();
    int selectedLine = -1;
    int lineStartX, lineStartY;
    boolean drawingLine = false;
    boolean lineOneWay = false;
    int lineOrientation = 0;
    int lineOrientation2 = 0;

    List<Placed> entities = new ArrayList<>();
    List<Placed> events = new ArrayList<>();
    List<String> entityTypes = new ArrayList<>();
    List<String> eventTypes = new ArrayList<>(Arrays.asList("changeLayer"));
    int selEntity = -1;
    int selEvent = -1;

    static final String[] DIRECTIONS = {"any", "up", "down", "left", "right"};
    int evDirection = 0;
    boolean evApplyAll = false;
    boolean evUp = true;

    static final int TAB_TILES = 0, TAB_LAYERS = 1, TAB_ENTITY = 2, TAB_EVENT = 3, TAB_COLLISION = 4;
    String[] tabNames = {
        "Tiles", "Layers", "Entity", "Event", "Collision"
    };
    int currentTab = TAB_TILES;
    int scroll = 0;

    // camera
    int cameraX = 0, cameraY = 0, cameraSpeed = 8;
    boolean keyW, keyA, keyS, keyD;

    // mouse
    volatile boolean pressed;
    volatile double mx, my;
    int button;
    int dragMode; // 0 none, 1 minimap, 2 map

    // minimap
    BufferedImage mini;
    double miniScale;
    int miniX, miniY, miniW, miniH;

    JFrame frame;

    public Window(JFrame frame) {
        this.frame = frame;

        setPreferredSize(new Dimension(viewW + menuWidth, viewH));
        setBackground(Color.BLACK);
        setFocusTraversalKeysEnabled(false);
        setFocusable(true);
        setDoubleBuffered(true);

        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        addKeyListener(this);
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                keyW = keyA = keyS = keyD = false;
            }
        });

        newMap(mapWidth, mapHeight);
    }

    public void newMap(int w, int h) {
        mapWidth = w;
        mapHeight = h;
        layers.clear();
        layerVisible.clear();
        entities.clear();
        events.clear();
        collisionLines.clear();
        selectedLine = -1;
        drawingLine = false;
        addLayer();
        currentLayer = 0;
        cameraX = 0;
        cameraY = 0;
        computeMiniRect();
        rebuildMini();
    }

    public void addLayer() {
        int[][] layer = new int[mapWidth][mapHeight];
        for (int[] column : layer) Arrays.fill(column, -1);
        layers.add(layer);
        layerVisible.add(true);
        currentLayer = layers.size() - 1;
    }

    public void newMapDialog() {
        JTextField w = new JTextField("20", 5);
        JTextField h = new JTextField("20", 5);
        JPanel p = new JPanel();
        p.add(new JLabel("Width (tiles):"));
        p.add(w);
        p.add(new JLabel("Height (tiles):"));
        p.add(h);
        int r = JOptionPane.showConfirmDialog(this, p, "Resize Map (this clears the current one)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        try {
            int nw = Math.max(1, Math.min(500, Integer.parseInt(w.getText().trim())));
            int nh = Math.max(1, Math.min(500, Integer.parseInt(h.getText().trim())));
            newMap(nw, nh);
        } catch (NumberFormatException ex) {
            showError("Those aren't numbers.");
        }
    }

    public void importTilesheetDialog() {
        FileDialog fd = new FileDialog(frame, "Select Tilesheet", FileDialog.LOAD);
        fd.setFile("*.png");
        fd.setVisible(true);
        if (fd.getFile() == null) return;
        File f = new File(fd.getDirectory(), fd.getFile());

        String in = JOptionPane.showInputDialog(this, "Enter the Tile Size (in pixels):", "32");
        if (in == null || in.trim().isEmpty()) return;
        try {
            loadTilesheet(f, Integer.parseInt(in.trim()), true);
        } catch (Exception ex) {
            showError("Couldn't load that tilesheet: " + ex.getMessage());
        }
    }

    public void saveDialog() {
        FileDialog fd = new FileDialog(frame, "Save map (pick a name)", FileDialog.SAVE);
        fd.setFile("map");
        fd.setVisible(true);
        if (fd.getFile() == null) return;
        File chosen = new File(fd.getDirectory(), fd.getFile());
        try {
            saveMap(chosen.getParentFile(), baseOf(chosen));
            JOptionPane.showMessageDialog(this, "Saved " + baseOf(chosen) + " (" + layers.size()
                    + " layers, " + entities.size() + " entities, " + events.size() + " events)");
        } catch (IOException ex) {
            showError("Save failed: " + ex.getMessage());
        }
    }

    public void loadDialog() {
        FileDialog fd = new FileDialog(frame, "Load map (pick the _meta.txt file)", FileDialog.LOAD);
        fd.setFile("*_meta.txt");
        fd.setVisible(true);
        if (fd.getFile() == null) return;
        File chosen = new File(fd.getDirectory(), fd.getFile());
        try {
            loadMap(chosen.getParentFile(), baseOf(chosen));
        } catch (Exception ex) {
            showError("Load failed: " + ex.getMessage());
        }
    }

    void showError(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    String askName(String title) {
        String s = JOptionPane.showInputDialog(this, title);
        if (s == null) return null;
        s = s.trim().replaceAll("\\s+", "_");
        return s.isEmpty() ? null : s;
    }

    public void loadTilesheet(File file, int size, boolean announce) throws IOException {
        if (size <= 0) throw new IOException("tile size must be positive");
        BufferedImage full = ImageIO.read(file);
        if (full == null) throw new IOException("that isn't an image");
        int cols = full.getWidth() / size;
        int rows = full.getHeight() / size;
        if (cols * rows == 0) throw new IOException("tile size is bigger than the image");

        Tile[] loaded = new Tile[cols * rows];
        int index = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                loaded[index] = new Tile(Tile.getTileFromFile(full, size, c, r), index);
                index++;
            }
        }
        tiles = loaded;
        tilesheetFile = file;
        tilesheetTileSize = size;
        selectedTileIndex = -1;
        scroll = 0;
        rebuildMini();
        if (announce) JOptionPane.showMessageDialog(this, "Loaded " + tiles.length + " tiles successfully!");
    }

    static String baseOf(File f) {
        String n = f.getName();
        if (n.endsWith(".txt")) n = n.substring(0, n.length() - 4);
        if (n.endsWith("_meta")) n = n.substring(0, n.length() - 5);
        return n;
    }

    int tileAt(int gx, int gy) {
        if (!inBounds(gx, gy)) return -1;
        return layers.get(currentLayer)[gx][gy];
    }
    
    public int[][] getSurroundingTiles(int gx, int gy) {
        return new int[][]{
            {tileAt(gx - 1, gy - 1), tileAt(gx, gy - 1), tileAt(gx + 1, gy - 1)},
            {tileAt(gx - 1, gy),     tileAt(gx, gy),     tileAt(gx + 1, gy)},
            {tileAt(gx - 1, gy + 1), tileAt(gx, gy + 1), tileAt(gx + 1, gy + 1)}
        };
    }

    public void saveMap(File dir, String base) throws IOException {
        List<String> meta = new ArrayList<>();
        meta.add("width=" + mapWidth);
        meta.add("height=" + mapHeight);
        meta.add("layers=" + layers.size());
        if (tilesheetFile != null) {
            meta.add("tilesheet=" + tilesheetFile.getAbsolutePath());
            meta.add("tileSize=" + tilesheetTileSize);
        }
        write(new File(dir, base + "_meta.txt"), meta);

        for (int l = 0; l < layers.size(); l++) {
            int[][] layer = layers.get(l);
            List<String> lines = new ArrayList<>();
            for (int y = 0; y < mapHeight; y++) {
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < mapWidth; x++) {
                    if (x > 0) sb.append(' ');
                    sb.append(layer[x][y]);
                }
                lines.add(sb.toString());
            }
            write(new File(dir, base + "_layer" + l + ".txt"), lines);
        }

        List<String> ent = new ArrayList<>();
        for (Placed p : entities) ent.add(p.name + " " + p.x + " " + p.y + " " + p.layer);
        write(new File(dir, base + "_entities.txt"), ent);

        List<String> ev = new ArrayList<>();
        for (Placed p : events) ev.add(p.name + " " + p.x + " " + p.y + " " + p.args);
        write(new File(dir, base + "_events.txt"), ev);

        List<String> collisionData = new ArrayList<>();

        for (CollisionLine line : collisionLines) {
            collisionData.add(
                line.x1 + " " + line.y1 + " " +
                line.x2 + " " + line.y2 + " " +
                line.isoneway + " " +
                line.orientation + " " +
                line.orientation2
            );
        }

        write(new File(dir, base + "_collisions.txt"), collisionData);
    }

    void write(File f, List<String> lines) throws IOException {
        Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
    }

    public void loadMap(File dir, String base) throws IOException {
        File metaFile = new File(dir, base + "_meta.txt");
        if (!metaFile.exists()) throw new IOException("can't find " + metaFile.getName());

        int w = 20, h = 20, layerCount = 1, size = 32;
        String sheet = null;
        for (String line : Files.readAllLines(metaFile.toPath(), StandardCharsets.UTF_8)) {
            String[] kv = line.split("=", 2);
            if (kv.length < 2) continue;
            switch (kv[0].trim()) {
                case "width": w = Integer.parseInt(kv[1].trim()); break;
                case "height": h = Integer.parseInt(kv[1].trim()); break;
                case "layers": layerCount = Integer.parseInt(kv[1].trim()); break;
                case "tilesheet": sheet = kv[1].trim(); break;
                case "tileSize": size = Integer.parseInt(kv[1].trim()); break;
                default: break;
            }
        }

        newMap(w, h);
        layers.clear();
        layerVisible.clear();
        for (int l = 0; l < Math.max(1, layerCount); l++) {
            addLayer();
            int[][] layer = layers.get(l);
            File lf = new File(dir, base + "_layer" + l + ".txt");
            if (!lf.exists()) continue;
            List<String> lines = Files.readAllLines(lf.toPath(), StandardCharsets.UTF_8);
            for (int y = 0; y < Math.min(h, lines.size()); y++) {
                String[] tok = lines.get(y).trim().split("\\s+");
                for (int x = 0; x < Math.min(w, tok.length); x++) {
                    layer[x][y] = Integer.parseInt(tok[x]);
                }
            }
        }
        currentLayer = 0;

        File ef = new File(dir, base + "_entities.txt");
        if (ef.exists()) {
            for (String line : Files.readAllLines(ef.toPath(), StandardCharsets.UTF_8)) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 4) continue;
                entities.add(new Placed(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]),
                        Integer.parseInt(t[3]), null));
                if (!entityTypes.contains(t[0])) entityTypes.add(t[0]);
            }
        }
        File vf = new File(dir, base + "_events.txt");
        if (vf.exists()) {
            for (String line : Files.readAllLines(vf.toPath(), StandardCharsets.UTF_8)) {
                String[] t = line.trim().split("\\s+");
                if (t.length < 3) continue;
                events.add(new Placed(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), 0,
                        t.length > 3 ? t[3] : "-"));
                if (!eventTypes.contains(t[0])) eventTypes.add(t[0]);
            }
        }

        if (sheet != null && new File(sheet).exists()) {
            try {
                loadTilesheet(new File(sheet), size, false);
            } catch (IOException ignored) {
            }
        }
        loadCollisionLines(dir, base);
        rebuildMini();
    }

    void loadCollisionLines(File dir, String base) throws IOException {
        collisionLines.clear();
        selectedLine = -1;

        File file = new File(dir, base + "_collisions.txt");
        if (!file.exists()) return; // Older maps can still load.

        List<String> lines = Files.readAllLines(
            file.toPath(), StandardCharsets.UTF_8
        );

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            String[] t = line.trim().split("\\s+");
            if (t.length != 7) {
                throw new IOException("Invalid collision line: " + line);
            }

            collisionLines.add(new CollisionLine(
                Float.parseFloat(t[0]),
                Float.parseFloat(t[1]),
                Float.parseFloat(t[2]),
                Float.parseFloat(t[3]),
                Boolean.parseBoolean(t[4]),
                Integer.parseInt(t[5]),
                Integer.parseInt(t[6])
            ));
        }
    }

    void computeMiniRect() {
        miniScale = Math.min(150.0 / mapWidth, 150.0 / mapHeight);
        miniW = Math.max(1, (int) (mapWidth * miniScale));
        miniH = Math.max(1, (int) (mapHeight * miniScale));
        miniX = viewW - 10 - miniW;
        miniY = 10;
    }

    boolean inMini(double x, double y) {
        return x >= miniX && x <= miniX + miniW && y >= miniY && y <= miniY + miniH;
    }

    void rebuildMini() {
        mini = new BufferedImage(mapWidth, mapHeight, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < mapWidth; x++) {
            for (int y = 0; y < mapHeight; y++) updateMiniPixel(x, y);
        }
    }

    void updateMiniPixel(int x, int y) {
        int rgb = 0x1E1E1E;
        for (int l = layers.size() - 1; l >= 0; l--) {
            if (!layerVisible.get(l)) continue;
            int id = layers.get(l)[x][y];
            if (id >= 0 && tiles != null && id < tiles.length && tiles[id].avg != null) {
                rgb = tiles[id].avg.getRGB();
                break;
            }
        }
        mini.setRGB(x, y, rgb);
    }

    public void update() {
        if (keyW) cameraY -= cameraSpeed;
        if (keyS) cameraY += cameraSpeed;
        if (keyA) cameraX -= cameraSpeed;
        if (keyD) cameraX += cameraSpeed;
        clampCamera();

        if (!pressed) return;

        if (dragMode == 1) {
            cameraX = (int) ((mx - miniX) / miniScale * tile - viewW / 2.0);
            cameraY = (int) ((my - miniY) / miniScale * tile - viewH / 2.0);
            clampCamera();
        } else if (dragMode == 2 && (currentTab == TAB_TILES || currentTab == TAB_LAYERS)) {
            if (mx < 0 || my < 0 || mx >= viewW || my >= viewH) return;
            int gx = (int) Math.floor((mx + cameraX) / tile);
            int gy = (int) Math.floor((my + cameraY) / tile);
            if (!inBounds(gx, gy)) return;
            if (button == MouseEvent.BUTTON3) {
                paintTile(gx, gy, -1);
            } else if (tiles != null && selectedTileIndex >= 0 && selectedTileIndex < tiles.length) {
                paintTile(gx, gy, selectedTileIndex);
            }
        }
    }

    void clampCamera() {
        cameraX = Math.max(0, Math.min(cameraX, mapWidth * tile - viewW));
        cameraY = Math.max(0, Math.min(cameraY, mapHeight * tile - viewH));
    }

    boolean inBounds(int gx, int gy) {
        return gx >= 0 && gx < mapWidth && gy >= 0 && gy < mapHeight;
    }

    void paintTile(int gx, int gy, int value) {
        int[][] layer = layers.get(currentLayer);
        if (layer[gx][gy] != value) {
            layer[gx][gy] = value;
            updateMiniPixel(gx, gy);
        }
    }

    void placeOrRemove(int gx, int gy, boolean remove) {
        if (currentTab == TAB_ENTITY) {
            entities.removeIf(p -> p.x == gx && p.y == gy && p.layer == currentLayer);
            if (!remove && selEntity >= 0 && selEntity < entityTypes.size()) {
                entities.add(new Placed(entityTypes.get(selEntity), gx, gy, currentLayer, null));
            }
        } else if (currentTab == TAB_EVENT) {
            events.removeIf(p -> p.x == gx && p.y == gy);
            if (!remove && selEvent >= 0 && selEvent < eventTypes.size()) {
                String name = eventTypes.get(selEvent);
                String args = "direction=" + DIRECTIONS[evDirection] + ";applicable=" + evApplyAll;
                if (name.equals("changeLayer")) args += ";delta=" + (evUp ? 1 : -1);
                events.add(new Placed(name, gx, gy, 0, args));
            }
        }
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawWorld(g2);
        drawMinimap(g2);
        drawSidebar(g2);
    }

    void drawWorld(Graphics2D g2) {
        g2.setClip(0, 0, viewW, viewH);

        int startCol = cameraX / tile;
        int endCol = Math.min(mapWidth, startCol + visCols + 1);
        int startRow = cameraY / tile;
        int endRow = Math.min(mapHeight, startRow + visRows + 1);

        for (int l = 0; l < layers.size(); l++) {
            if (!layerVisible.get(l)) continue;
            int[][] layer = layers.get(l);
            for (int x = startCol; x < endCol; x++) {
                for (int y = startRow; y < endRow; y++) {
                    int id = layer[x][y];
                    if (id >= 0 && tiles != null && id < tiles.length) {
                        g2.drawImage(tiles[id].subImage, x * tile - cameraX, y * tile - cameraY, tile, tile, null);
                    }
                }
            }
        }

        g2.setStroke(new BasicStroke(1));
        g2.setColor(new Color(255, 255, 255, 40));
        for (int x = startCol; x < endCol; x++) {
            for (int y = startRow; y < endRow; y++) {
                g2.drawRect(x * tile - cameraX, y * tile - cameraY, tile, tile);
            }
        }

        g2.setColor(Color.RED);
        g2.drawRect(-cameraX, -cameraY, mapWidth * tile, mapHeight * tile);

        drawEntities(g2);
        drawEvents(g2);
        drawCollisionLines(g2);
        g2.setClip(null);
    }

    void drawEntities(Graphics2D g2) {
        Font small = g2.getFont().deriveFont(Font.BOLD, 10f);
        for (Placed p : entities) {
            int sx = p.x * tile - cameraX, sy = p.y * tile - cameraY;
            if (sx + tile < 0 || sy + tile < 0 || sx > viewW || sy > viewH) continue;

            boolean faded = currentTab == TAB_ENTITY && p.layer != currentLayer;
            if (faded) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));

            float hue = (p.name.hashCode() & 0x7fffffff) % 360 / 360f;
            Color c = new Color(Color.HSBtoRGB(hue, 0.6f, 0.95f));
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 190));
            g2.fillRoundRect(sx + 8, sy + 8, tile - 16, tile - 16, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawRoundRect(sx + 8, sy + 8, tile - 16, tile - 16, 14, 14);

            Shape old = g2.getClip();
            g2.clipRect(sx + 2, sy + 2, tile - 4, tile - 4);
            g2.setFont(small);
            g2.setColor(Color.BLACK);
            g2.drawString(p.name, sx + 11, sy + 30);
            g2.drawString("L" + p.layer, sx + 11, sy + 48);
            g2.setClip(old);

            g2.setComposite(AlphaComposite.SrcOver);
        }
    }

    void drawEvents(Graphics2D g2) {
        Font small = g2.getFont().deriveFont(Font.BOLD, 10f);
        for (Placed p : events) {
            int sx = p.x * tile - cameraX, sy = p.y * tile - cameraY;
            if (sx + tile < 0 || sy + tile < 0 || sx > viewW || sy > viewH) continue;

            boolean layerEvent = p.name.equals("changeLayer");
            g2.setColor(layerEvent ? new Color(120, 90, 255, 110) : new Color(255, 160, 40, 110));
            g2.fillRect(sx, sy, tile, tile);
            g2.setColor(layerEvent ? new Color(170, 150, 255) : new Color(255, 200, 100));
            g2.setStroke(new BasicStroke(2));
            g2.drawRect(sx + 1, sy + 1, tile - 2, tile - 2);

            boolean all = Boolean.parseBoolean(argOf(p.args, "applicable", "false"));
            Shape old = g2.getClip();
            g2.clipRect(sx + 2, sy + 2, tile - 4, tile - 4);
            g2.setFont(small);
            g2.setColor(Color.WHITE);
            g2.drawString(p.name, sx + 5, sy + 16);
            if (layerEvent) {
                g2.drawString(argOf(p.args, "delta", "1").equals("1") ? "layer +1" : "layer -1", sx + 5, sy + 29);
            }
            g2.drawString("dir:" + argOf(p.args, "direction", "any"), sx + 5, sy + 42);
            g2.drawString(all ? "to all" : "player", sx + 5, sy + 55);
            g2.setClip(old);
        }
        g2.setStroke(new BasicStroke(1));
    }

    String argOf(String args, String key, String fallback) {
        if (args == null) return fallback;
        for (String pair : args.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return fallback;
    }

    void drawMinimap(Graphics2D g2) {
        g2.setClip(null);
        g2.drawImage(mini, miniX, miniY, miniW, miniH, null);
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2));
        g2.drawRect(miniX - 1, miniY - 1, miniW + 1, miniH + 1);

        // where the camera is
        g2.setStroke(new BasicStroke(1));
        g2.setColor(Color.YELLOW);
        int rx = miniX + (int) (cameraX / (double) tile * miniScale);
        int ry = miniY + (int) (cameraY / (double) tile * miniScale);
        int rw = (int) (Math.min(visCols, mapWidth) * miniScale);
        int rh = (int) (Math.min(visRows, mapHeight) * miniScale);
        g2.drawRect(rx, ry, rw, rh);
    }

    int listY() {
        return currentTab == TAB_EVENT ? 240 : 110;
    }

    int contentX() {
        return menuStartX + 20;
    }

    java.awt.Rectangle buttonRect() {
        return new java.awt.Rectangle(contentX(), 50, 200, 32);
    }

    java.awt.Rectangle rowRect(int i) {
        return new java.awt.Rectangle(contentX(), listY() + i * ROW_H - scroll, menuWidth - 40, ROW_H - 4);
    }

    void drawSidebar(Graphics2D g2) {
        g2.setClip(null);
        Color bg = new Color(30, 30, 30);
        g2.setColor(bg);
        g2.fillRect(menuStartX, 0, menuWidth, getHeight());

        // tab bar
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 15f));
        FontMetrics fm = g2.getFontMetrics();
        int tw = menuWidth / tabNames.length;
        for (int i = 0; i < tabNames.length; i++) {
            int x = menuStartX + i * tw;
            g2.setColor(i == currentTab ? new Color(80, 80, 80) : new Color(48, 48, 48));
            g2.fillRect(x, 0, tw, TAB_BAR_H);
            g2.setColor(i == currentTab ? Color.YELLOW : Color.LIGHT_GRAY);
            g2.drawString(tabNames[i], x + (tw - fm.stringWidth(tabNames[i])) / 2, 22);
        }

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
        g2.setColor(Color.GRAY);
        g2.drawString("Layer " + currentLayer + "  |  Camera " + cameraX + ", " + cameraY
                + "  |  Map " + mapWidth + "x" + mapHeight, contentX(), getHeight() - 10);

        g2.setClip(menuStartX, CONTENT_Y, menuWidth, contentBottom - CONTENT_Y);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16f));
        switch (currentTab) {
            case TAB_TILES: drawTilesTab(g2); break;
            case TAB_LAYERS: drawLayersTab(g2, bg); break;
            case TAB_ENTITY: drawNameList(g2, bg, "Add Entity", entityTypes, selEntity,
                    "Click map: place on layer " + currentLayer + ". Right click: remove."); break;
            case TAB_COLLISION: drawCollisionTab(g2, bg);break;
            default: drawNameList(g2, bg, "Add Event", eventTypes, selEvent,
                    "Click places, right click removes. Options apply on place."); break;
                
        }
        g2.setClip(null);
    }

    int snap(double v) {
    int half = tile / 2;
    return (int) Math.round(v / half) * half;
}

int snapX(double screenX) {
    return Math.max(0, Math.min(mapWidth * tile, snap(screenX + cameraX)));
}

int snapY(double screenY) {
    return Math.max(0, Math.min(mapHeight * tile, snap(screenY + cameraY)));
}

int nearestLine(double px, double py, double maxDist) {
    int best = -1;
    double bestDist = maxDist;
    for (int i = 0; i < collisionLines.size(); i++) {
        CollisionLine l = collisionLines.get(i);
        double dx = l.x2 - l.x1, dy = l.y2 - l.y1;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - l.x1) * dx + (py - l.y1) * dy) / len2));
        double d = Math.hypot(px - (l.x1 + t * dx), py - (l.y1 + t * dy));
        if (d <= bestDist) {
            bestDist = d;
            best = i;
        }
    }
    return best;
}

void collisionClick(boolean remove) {
    double wx = mx + cameraX, wy = my + cameraY;
    if (wx < -16 || wy < -16 || wx > mapWidth * tile + 16 || wy > mapHeight * tile + 16) return;

    if (remove) {
        if (drawingLine) {
            drawingLine = false;
            return;
        }
        int i = nearestLine(wx, wy, 10);
        if (i >= 0) {
            collisionLines.remove(i);
            if (selectedLine == i) selectedLine = -1;
            else if (selectedLine > i) selectedLine--;
        }
        return;
    }

    int px = snapX(mx), py = snapY(my);
    if (!drawingLine) {
        lineStartX = px;
        lineStartY = py;
        drawingLine = true;
        return;
    }

    drawingLine = false;
    if (px == lineStartX && py == lineStartY) return;
    collisionLines.add(new CollisionLine(lineStartX, lineStartY, px, py, lineOneWay, lineOrientation, lineOrientation2));
}

void clickCollision(int x, int y) {
    if (collisionOneWayRect().contains(x, y)) {
        lineOneWay = !lineOneWay;
    } else if (collisionOrientationRect().contains(x, y)) {
        lineOrientation = (lineOrientation + 1) % 5;
    } else if (collisionOrientation2Rect().contains(x, y)) {
        lineOrientation2 = (lineOrientation2 + 1) % 5;
    } else {
        int startY = CONTENT_Y + 196;
        int rowHeight = 28;
        if (y < startY) return;
        for (int i = 0; i < collisionLines.size(); i++) {
            int ry = startY + i * rowHeight - scroll;
            if (y >= ry && y < ry + rowHeight - 2) {
                selectedLine = i;
                return;
            }
        }
    }
}

void drawCollisionLines(Graphics2D g2) {
    g2.setStroke(new BasicStroke(3));

    for (int i = 0; i < collisionLines.size(); i++) {
        CollisionLine line = collisionLines.get(i);

        int x1 = Math.round(line.x1) - cameraX;
        int y1 = Math.round(line.y1) - cameraY;
        int x2 = Math.round(line.x2) - cameraX;
        int y2 = Math.round(line.y2) - cameraY;

        g2.setColor(i == selectedLine ? Color.YELLOW : Color.GREEN);
        g2.drawLine(x1, y1, x2, y2);

        g2.fillOval(x1 - 4, y1 - 4, 8, 8);
        g2.fillOval(x2 - 4, y2 - 4, 8, 8);
    }

    if (currentTab == TAB_COLLISION && mx >= 0 && mx < viewW && !inMini(mx, my)) {
        int px = snapX(mx) - cameraX, py = snapY(my) - cameraY;
        if (drawingLine) {
            g2.setColor(new Color(255, 255, 255, 160));
            g2.drawLine(lineStartX - cameraX, lineStartY - cameraY, px, py);
            g2.fillOval(lineStartX - cameraX - 5, lineStartY - cameraY - 5, 10, 10);
        }
        g2.setStroke(new BasicStroke(2));
        g2.setColor(Color.CYAN);
        g2.drawOval(px - 6, py - 6, 12, 12);
    }

    g2.setStroke(new BasicStroke(1));
}

void drawCollisionTab(Graphics2D g2, Color bg) {
    int startY = CONTENT_Y + 196;
    int rowHeight = 28;

    g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
    for (int i = 0; i < collisionLines.size(); i++) {
        int y = startY + i * rowHeight - scroll;

        if (y + rowHeight < CONTENT_Y || y > contentBottom) {
            continue;
        }

        CollisionLine line = collisionLines.get(i);

        g2.setColor(i == selectedLine
                ? new Color(90, 80, 35)
                : new Color(45, 45, 45));

        g2.fillRect(contentX(), y, menuWidth - 40, rowHeight - 2);

        g2.setColor(i == selectedLine ? Color.YELLOW : Color.LIGHT_GRAY);

        String label = "Line " + i + ": ("
                + Math.round(line.x1) + ", " + Math.round(line.y1)
                + ") -> ("
                + Math.round(line.x2) + ", " + Math.round(line.y2) + ")";

        g2.drawString(label, contentX() + 6, y + 18);
    }

    g2.setColor(bg);
    g2.fillRect(menuStartX, CONTENT_Y, menuWidth, startY - CONTENT_Y);

    g2.setColor(Color.WHITE);
    g2.setFont(g2.getFont().deriveFont(Font.BOLD, 14f));
    g2.drawString("Collision Settings", contentX(), CONTENT_Y + 22);

    drawButton(g2, collisionOneWayRect(),
            "One-way: " + (lineOneWay ? "ON" : "OFF"));

    drawButton(g2, collisionOrientationRect(),
            "Orientation: " + orientationName(lineOrientation));

    drawButton(g2, collisionOrientation2Rect(),
            "Orientation 2: " + orientationName(lineOrientation2));

    g2.setColor(Color.GRAY);
    g2.setFont(g2.getFont().deriveFont(12f));
    g2.drawString("Click to start a line, click again to finish it.",
            contentX(), CONTENT_Y + 150);
    g2.drawString("Right-click a line to erase it (or cancel one).",
            contentX(), CONTENT_Y + 168);

    g2.setColor(Color.WHITE);
    g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
    g2.drawString("Collision Lines (" + collisionLines.size() + ")",
            contentX(), CONTENT_Y + 188);
}

    String orientationName(int orientation) {
        switch (orientation) {
            case 0: return "Top";
            case 1: return "Right";
            case 2: return "Bottom";
            case 3: return "Left";
            default: return "None";
        }
    }

    java.awt.Rectangle collisionOneWayRect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 36, 260, 28);
    }

    java.awt.Rectangle collisionOrientationRect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 70, 260, 28);
    }

    java.awt.Rectangle collisionOrientation2Rect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 104, 260, 28);
    }

    void drawTilesTab(Graphics2D g2) {
        int startX = contentX() + 10;
        int startY = CONTENT_Y + 10 - scroll;
        if (tiles == null) {
            g2.setColor(Color.GRAY);
            g2.drawString("[ Import a tilesheet (toolbar) ]", contentX(), CONTENT_Y + 30);
            return;
        }
        for (int i = 0; i < tiles.length; i++) {
            int tx = startX + (i % 4) * 72;
            int ty = startY + (i / 4) * 72;
            g2.drawImage(tiles[i].subImage, tx, ty, 64, 64, null);
            if (i == selectedTileIndex) {
                g2.setColor(Color.YELLOW);
                g2.setStroke(new BasicStroke(3));
                g2.drawRect(tx - 2, ty - 2, 68, 68);
                g2.setStroke(new BasicStroke(1));
            }
        }
    }

    void drawLayersTab(Graphics2D g2, Color bg) {
        for (int i = 0; i < layers.size(); i++) {
            java.awt.Rectangle r = rowRect(i);
            g2.setColor(i == currentLayer ? new Color(80, 80, 40) : new Color(45, 45, 45));
            g2.fillRect(r.x, r.y, r.width, r.height);

            // eye
            boolean vis = layerVisible.get(i);
            int ex = r.x + 6, ey = r.y + r.height / 2;
            g2.setColor(vis ? Color.WHITE : Color.DARK_GRAY);
            g2.drawOval(ex, ey - 7, 22, 14);
            if (vis) g2.fillOval(ex + 7, ey - 4, 8, 8);
            else g2.drawLine(ex, ey + 8, ex + 22, ey - 8);

            g2.setColor(vis ? Color.WHITE : Color.GRAY);
            g2.drawString("Layer " + i, r.x + 44, r.y + 21);
        }
        g2.setColor(bg);
        g2.fillRect(menuStartX, CONTENT_Y, menuWidth, listY() - CONTENT_Y);
        drawButton(g2, buttonRect(), "Add Layer");
        g2.setFont(g2.getFont().deriveFont(12f));
        g2.setColor(Color.GRAY);
        g2.drawString("Click a row to draw on it. Click the eye to hide it.", contentX(), 98);
    }

    void drawNameList(Graphics2D g2, Color bg, String buttonLabel, List<String> names, int sel, String hint) {
        for (int i = 0; i < names.size(); i++) {
            java.awt.Rectangle r = rowRect(i);
            g2.setColor(i == sel ? new Color(80, 80, 40) : new Color(45, 45, 45));
            g2.fillRect(r.x, r.y, r.width, r.height);
            g2.setColor(Color.WHITE);
            g2.drawString(names.get(i), r.x + 10, r.y + 21);
        }
        g2.setColor(bg);
        g2.fillRect(menuStartX, CONTENT_Y, menuWidth, listY() - CONTENT_Y);
        drawButton(g2, buttonRect(), buttonLabel);
        g2.setFont(g2.getFont().deriveFont(12f));
        g2.setColor(Color.GRAY);
        g2.drawString(hint, contentX(), 98);

        if (eventSelected()) {
            g2.setFont(g2.getFont().deriveFont(14f));
            drawButton(g2, facingRect(), "Required direction: " + DIRECTIONS[evDirection].toUpperCase());
            drawButton(g2, whoRect(), "Applicable: " + (evApplyAll ? "TRUE (everything)" : "FALSE (player only)"));
            if (selectedEventIsLayer()) {
                drawButton(g2, layerRect(), "Layer change: " + (evUp ? "UP (+1)" : "DOWN (-1)"));
            }
        }
    }

    boolean eventSelected() {
        return currentTab == TAB_EVENT && selEvent >= 0 && selEvent < eventTypes.size();
    }

    boolean selectedEventIsLayer() {
        return eventSelected() && eventTypes.get(selEvent).equals("changeLayer");
    }

    java.awt.Rectangle facingRect() {
        return new java.awt.Rectangle(contentX(), 112, 340, 30);
    }

    java.awt.Rectangle whoRect() {
        return new java.awt.Rectangle(contentX(), 150, 340, 30);
    }

    java.awt.Rectangle layerRect() {
        return new java.awt.Rectangle(contentX(), 188, 340, 30);
    }

    void drawButton(Graphics2D g2, java.awt.Rectangle r, String label) {
        g2.setColor(new Color(70, 70, 70));
        g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        g2.setColor(Color.LIGHT_GRAY);
        g2.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(Color.WHITE);
        g2.drawString(label, r.x + (r.width - fm.stringWidth(label)) / 2,
                r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2);
    }

    void handleSidebarClick(int x, int y) {
        if (y < TAB_BAR_H) {
            int t = Math.max(0, Math.min(tabNames.length - 1, (x - menuStartX) / (menuWidth / tabNames.length)));
            if (t != currentTab) {
                currentTab = t;
                scroll = 0;
            }
            return;
        }
        if (y < CONTENT_Y || y >= contentBottom) return;

        switch (currentTab) {
            case TAB_TILES: clickTiles(x, y); break;
            case TAB_LAYERS: clickLayers(x, y); break;
            case TAB_COLLISION: clickCollision(x, y); break;
            case TAB_ENTITY:
                if (buttonRect().contains(x, y)) {
                    String n = askName("Entity name (your game uses this to decide what to spawn):");
                    if (n != null) {
                        if (!entityTypes.contains(n)) entityTypes.add(n);
                        selEntity = entityTypes.indexOf(n);
                    }
                } else {
                    selEntity = rowAt(x, y, entityTypes.size(), selEntity);
                }
                break;
            default:
                if (buttonRect().contains(x, y)) {
                    String n = askName("Event name (your event manager uses this to pick a method):");
                    if (n != null) {
                        if (!eventTypes.contains(n)) eventTypes.add(n);
                        selEvent = eventTypes.indexOf(n);
                    }
                } else if (eventSelected() && facingRect().contains(x, y)) {
                    evDirection = (evDirection + 1) % DIRECTIONS.length;
                } else if (eventSelected() && whoRect().contains(x, y)) {
                    evApplyAll = !evApplyAll;
                } else if (selectedEventIsLayer() && layerRect().contains(x, y)) {
                    evUp = !evUp;
                } else {
                    selEvent = rowAt(x, y, eventTypes.size(), selEvent);
                }
                break;
        }
    }

    int rowAt(int x, int y, int count, int current) {
        if (y < listY()) return current;
        for (int i = 0; i < count; i++) {
            if (rowRect(i).contains(x, y)) return i;
        }
        return current;
    }

    void clickTiles(int x, int y) {
        if (tiles == null) return;
        int startX = contentX() + 10;
        int startY = CONTENT_Y + 10 - scroll;
        for (int i = 0; i < tiles.length; i++) {
            int tx = startX + (i % 4) * 72;
            int ty = startY + (i / 4) * 72;
            if (x >= tx && x <= tx + 64 && y >= ty && y <= ty + 64) {
                selectedTileIndex = i;
                return;
            }
        }
    }

    void clickLayers(int x, int y) {
        if (buttonRect().contains(x, y)) {
            addLayer();
            return;
        }
        if (y < listY()) return;
        for (int i = 0; i < layers.size(); i++) {
            java.awt.Rectangle r = rowRect(i);
            if (!r.contains(x, y)) continue;
            if (x < r.x + 36) {
                layerVisible.set(i, !layerVisible.get(i));
                rebuildMini();
            } else {
                currentLayer = i;
            }
            return;
        }
    }

    int maxScroll() {
        int end;
        switch (currentTab) {
            case TAB_TILES:
                end = CONTENT_Y + 10 + (tiles == null ? 0 : (tiles.length + 3) / 4 * 72);
                break;
            case TAB_LAYERS: end = listY() + layers.size() * ROW_H; break;
            case TAB_ENTITY: end = listY() + entityTypes.size() * ROW_H; break;
            case TAB_COLLISION: end = CONTENT_Y + 196 + collisionLines.size() * 28; break;
            default: end = listY() + eventTypes.size() * ROW_H; break;
        }
        return Math.max(0, end - contentBottom + 10);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        mx = e.getX();
        my = e.getY();
        button = e.getButton();

        if (mx >= menuStartX) {
            handleSidebarClick(e.getX(), e.getY());
            pressed = false; // dialogs can swallow the release
            dragMode = 0;
            return;
        }

        pressed = true;
        if (inMini(mx, my)) {
            dragMode = 1;
            return;
        }
        dragMode = 2;

        if (currentTab == TAB_COLLISION) {
            collisionClick(button == MouseEvent.BUTTON3);
        }

        if (currentTab == TAB_ENTITY || currentTab == TAB_EVENT) {
            int gx = (int) Math.floor((mx + cameraX) / tile);
            int gy = (int) Math.floor((my + cameraY) / tile);
            if (inBounds(gx, gy)) placeOrRemove(gx, gy, button == MouseEvent.BUTTON3);
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getX() < menuStartX) return;
        scroll = Math.max(0, Math.min(maxScroll(), scroll + e.getWheelRotation() * 40));
    }

    @Override public void mouseReleased(MouseEvent e) { pressed = false; dragMode = 0; }
    @Override public void mouseDragged(MouseEvent e) { mx = e.getX(); my = e.getY(); }
    @Override public void mouseMoved(MouseEvent e) { mx = e.getX(); my = e.getY(); }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    @Override
    public void keyPressed(KeyEvent e) { setKey(e.getKeyCode(), true); }

    @Override
    public void keyReleased(KeyEvent e) { setKey(e.getKeyCode(), false); }

    @Override public void keyTyped(KeyEvent e) {}

    void setKey(int code, boolean down) {
        if (code == KeyEvent.VK_W || code == KeyEvent.VK_UP) keyW = down;
        if (code == KeyEvent.VK_S || code == KeyEvent.VK_DOWN) keyS = down;
        if (code == KeyEvent.VK_A || code == KeyEvent.VK_LEFT) keyA = down;
        if (code == KeyEvent.VK_D || code == KeyEvent.VK_RIGHT) keyD = down;
    }

    public void startLoop() {
        new Timer(1000 / 60, e -> {
            update();
            repaint();
        }).start();
    }

    public static void main(String[] arg) {
        try {
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // default look and feel is fine
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.setTitle("Map Editor V1");

            Window window = new Window(frame);

            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            toolbar.add(toolbarButton("Resize Map", window::newMapDialog));
            toolbar.add(toolbarButton("Import Tilesheet", window::importTilesheetDialog));
            toolbar.add(toolbarButton("Save", window::saveDialog));
            toolbar.add(toolbarButton("Load", window::loadDialog));

            frame.add(toolbar, java.awt.BorderLayout.NORTH);
            frame.add(window, java.awt.BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            window.startLoop();
            window.requestFocusInWindow();
        });
    }

    static JButton toolbarButton(String label, Runnable action) {
        JButton b = new JButton(label);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }
}