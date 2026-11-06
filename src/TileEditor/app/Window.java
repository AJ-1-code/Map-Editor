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
 *   Mouse wheel ......... zoom the map (scroll the sidebar when over it)
 *   B / R / F / V ....... brush / square / fill / select tool
 *   Ctrl+Z / Ctrl+Y ..... undo / redo
 *   0 ................... reset zoom
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
    public boolean drawlines = true;

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

    static class EditOp {
        int layer;
        int[] data = new int[256];
        int size;

        void add(int x, int y, int oldValue, int newValue) {
            if (size + 4 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = x;
            data[size++] = y;
            data[size++] = oldValue;
            data[size++] = newValue;
        }
    }

    static final int TOOL_BRUSH = 0, TOOL_SQUARE = 1, TOOL_FILL = 2, TOOL_SELECT = 3;
    static final int TOOLS_H = 150;
    static final int NONE = Integer.MIN_VALUE;
    static final float MIN_ZOOM = 0.05f, MAX_ZOOM = 10f;

    String[] toolNames = {"Brush", "Square", "Fill", "Select"};
    String[] selectButtons = {"Copy", "Cut", "Paste", "Delete"};
    int tool = TOOL_BRUSH;
    int brushSize = 1;
    boolean brushRound = false;
    boolean squareFilled = true;
    boolean sliderDrag = false;
    float zoom = 1f;

    int strokeLastX = NONE, strokeLastY = NONE;
    int rectStartX, rectStartY;
    boolean rectDragging = false;

    boolean selActive = false;
    int selX, selY, selW, selH;
    int selMode = 0;
    int selAnchorX, selAnchorY;
    int moveOffX, moveOffY;
    int liftX, liftY;
    boolean floatFromPaste = false;
    int[][] floatBuf;
    int[][] clipboard;

    EditOp openOp;
    List<EditOp> undoStack = new ArrayList<>();
    List<EditOp> redoStack = new ArrayList<>();

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
    String[] tabNames = {"Tiles", "Layers", "Entity", "Event", "Collision"};
    int currentTab = TAB_TILES;
    int scroll = 0;

    // camera
    float cameraX = 0, cameraY = 0;
    int cameraSpeed = 8;
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
        resetEditState();
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
        int r = JOptionPane.showConfirmDialog(this, p, "New Map (this clears the current one)",
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

        String in = JOptionPane.showInputDialog(this, "Enter the Tile Size (in pixels):", "16");
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
                    + " layers, " + entities.size() + " entities, " + events.size() + " events, " + collisionLines.size() + " collision lines)");
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

    public void saveMap(File dir, String base) throws IOException {
        commitFloating();
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
        if (!file.exists()) return;

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
        float step = cameraSpeed / zoom;
        if (keyW) cameraY -= step;
        if (keyS) cameraY += step;
        if (keyA) cameraX -= step;
        if (keyD) cameraX += step;
        clampCamera();

        if (!pressed) return;

        if (dragMode == 1) {
            cameraX = (float) ((mx - miniX) / miniScale * tile - viewW / zoom / 2.0);
            cameraY = (float) ((my - miniY) / miniScale * tile - viewH / zoom / 2.0);
            clampCamera();
        } else if (dragMode == 2 && tileTab()) {
            int gx = cellAtX(mx), gy = cellAtY(my);
            boolean inView = mx >= 0 && my >= 0 && mx < viewW && my < viewH;
            if (tool == TOOL_BRUSH) {
                if (!inView) {
                    strokeLastX = NONE;
                    strokeLastY = NONE;
                } else if (button == MouseEvent.BUTTON3) {
                    strokeTo(gx, gy, -1);
                } else if (hasTile()) {
                    strokeTo(gx, gy, selectedTileIndex);
                }
            } else if (tool == TOOL_SELECT && selMode != 0) {
                dragSelection(gx, gy);
            }
        }
    }

    void clampCamera() {
        cameraX = Math.max(0, Math.min(cameraX, mapWidth * tile - viewW / zoom));
        cameraY = Math.max(0, Math.min(cameraY, mapHeight * tile - viewH / zoom));
    }

    boolean inBounds(int gx, int gy) {
        return gx >= 0 && gx < mapWidth && gy >= 0 && gy < mapHeight;
    }

    void paintTile(int gx, int gy, int value) {
        setTile(currentLayer, gx, gy, value);
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

        int c0 = visCol0(), c1 = visCol1(), r0 = visRow0(), r1 = visRow1();

        for (int l = 0; l < layers.size(); l++) {
            if (!layerVisible.get(l)) continue;
            int[][] layer = layers.get(l);
            for (int x = c0; x <= c1; x++) {
                int sx0 = Math.round((x * tile - cameraX) * zoom);
                int sx1 = Math.round(((x + 1) * tile - cameraX) * zoom);
                for (int y = r0; y <= r1; y++) {
                    int id = layer[x][y];
                    if (id < 0 || tiles == null || id >= tiles.length) continue;
                    int sy0 = Math.round((y * tile - cameraY) * zoom);
                    int sy1 = Math.round(((y + 1) * tile - cameraY) * zoom);
                    g2.drawImage(tiles[id].subImage, sx0, sy0, sx1 - sx0, sy1 - sy0, null);
                }
            }
        }

        java.awt.geom.AffineTransform base = g2.getTransform();
        g2.scale(zoom, zoom);
        g2.translate(-cameraX, -cameraY);
        float px = 1f / zoom;

        if (zoom >= 0.4f) {
            g2.setStroke(new BasicStroke(px));
            g2.setColor(new Color(255, 255, 255, 40));
            for (int x = c0; x <= c1; x++) {
                for (int y = r0; y <= r1; y++) {
                    g2.drawRect(x * tile, y * tile, tile, tile);
                }
            }
        }

        g2.setStroke(new BasicStroke(px));
        g2.setColor(Color.RED);
        g2.drawRect(0, 0, mapWidth * tile, mapHeight * tile);

        drawEntities(g2);
        drawEvents(g2);
        drawCollisionLines(g2);
        drawToolOverlay(g2);

        g2.setTransform(base);
        g2.setClip(null);
    }

    void drawEntities(Graphics2D g2) {
        Font small = g2.getFont().deriveFont(Font.BOLD, 10f);
        for (Placed p : entities) {
            int sx = p.x * tile, sy = p.y * tile;
            if (!visibleWorld(sx, sy, tile, tile)) continue;

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
            int sx = p.x * tile, sy = p.y * tile;
            if (!visibleWorld(sx, sy, tile, tile)) continue;

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
        int rw = (int) (Math.min(mapWidth, viewW / zoom / tile) * miniScale);
        int rh = (int) (Math.min(mapHeight, viewH / zoom / tile) * miniScale);
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
        g2.drawString("Layer " + currentLayer + " | Cam " + (int) cameraX + ", " + (int) cameraY
                + " | Zoom " + Math.round(zoom * 100) + "% | Map " + mapWidth + "x" + mapHeight, contentX(), getHeight() - 10);

        g2.setClip(menuStartX, CONTENT_Y, menuWidth, contentBottom - CONTENT_Y);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 16f));
        switch (currentTab) {
            case TAB_TILES: drawTilesTab(g2, bg); break;
            case TAB_LAYERS: drawLayersTab(g2, bg); break;
            case TAB_ENTITY: drawNameList(g2, bg, "Add Entity", entityTypes, selEntity,
                    "Click map: place on layer " + currentLayer + ". Right click: remove."); break;
            case TAB_COLLISION: drawCollisionTab(g2, bg); break;
            default: drawNameList(g2, bg, "Add Event", eventTypes, selEvent,
                    "Click places, right click removes. Options apply on place."); break;
        }
        g2.setClip(null);
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

    int snap(double v) {
        int half = tile / 2;
        return (int) Math.round(v / half) * half;
    }

    int snapX(double screenX) {
        return Math.max(0, Math.min(mapWidth * tile, snap(screenX / zoom + cameraX)));
    }

    int snapY(double screenY) {
        return Math.max(0, Math.min(mapHeight * tile, snap(screenY / zoom + cameraY)));
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
        double wx = mx / zoom + cameraX, wy = my / zoom + cameraY;
        if (wx < -16 || wy < -16 || wx > mapWidth * tile + 16 || wy > mapHeight * tile + 16) return;

        if (remove) {
            if (drawingLine) {
                drawingLine = false;
                return;
            }
            int i = nearestLine(wx, wy, 10 / zoom);
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
        float px = 1f / zoom;
        g2.setStroke(new BasicStroke(3 * px));

        for (int i = 0; i < collisionLines.size(); i++) {

            if(drawlines) {return;}

            CollisionLine line = collisionLines.get(i);

            int x1 = Math.round(line.x1);
            int y1 = Math.round(line.y1);
            int x2 = Math.round(line.x2);
            int y2 = Math.round(line.y2);

            g2.setColor(i == selectedLine ? Color.YELLOW : Color.GREEN);
            g2.drawLine(x1, y1, x2, y2);
            g2.fill(new java.awt.geom.Ellipse2D.Float(x1 - 4 * px, y1 - 4 * px, 8 * px, 8 * px));
            g2.fill(new java.awt.geom.Ellipse2D.Float(x2 - 4 * px, y2 - 4 * px, 8 * px, 8 * px));
        }

        if (currentTab == TAB_COLLISION && mouseInView()) {
            int cx = snapX(mx), cy = snapY(my);
            if (drawingLine) {
                g2.setColor(new Color(255, 255, 255, 160));
                g2.drawLine(lineStartX, lineStartY, cx, cy);
                g2.fill(new java.awt.geom.Ellipse2D.Float(lineStartX - 5 * px, lineStartY - 5 * px, 10 * px, 10 * px));
            }
            g2.setStroke(new BasicStroke(2 * px));
            g2.setColor(Color.CYAN);
            g2.draw(new java.awt.geom.Ellipse2D.Float(cx - 6 * px, cy - 6 * px, 12 * px, 12 * px));
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

    boolean tileTab() {
        return currentTab == TAB_TILES || currentTab == TAB_LAYERS;
    }

    boolean hasTile() {
        return tiles != null && selectedTileIndex >= 0 && selectedTileIndex < tiles.length;
    }

    int cellAtX(double screenX) {
        return (int) Math.floor((screenX / zoom + cameraX) / tile);
    }

    int cellAtY(double screenY) {
        return (int) Math.floor((screenY / zoom + cameraY) / tile);
    }

    int clampCol(int col) {
        return Math.max(0, Math.min(mapWidth - 1, col));
    }

    int clampRow(int row) {
        return Math.max(0, Math.min(mapHeight - 1, row));
    }

    int visCol0() {
        return Math.max(0, (int) (cameraX / tile));
    }

    int visCol1() {
        return Math.min(mapWidth - 1, (int) ((cameraX + viewW / zoom) / tile));
    }

    int visRow0() {
        return Math.max(0, (int) (cameraY / tile));
    }

    int visRow1() {
        return Math.min(mapHeight - 1, (int) ((cameraY + viewH / zoom) / tile));
    }

    boolean visibleWorld(int x, int y, int w, int h) {
        return x + w >= cameraX && x <= cameraX + viewW / zoom
                && y + h >= cameraY && y <= cameraY + viewH / zoom;
    }

    boolean mouseInView() {
        return mx >= 0 && my >= 0 && mx < viewW && my < viewH && !inMini(mx, my);
    }

    void zoomTo(float target, double sx, double sy) {
        float newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, target));
        if (newZoom == zoom) return;
        double wx = sx / zoom + cameraX;
        double wy = sy / zoom + cameraY;
        zoom = newZoom;
        cameraX = (float) (wx - sx / zoom);
        cameraY = (float) (wy - sy / zoom);
        clampCamera();
    }

    void resetEditState() {
        openOp = null;
        undoStack.clear();
        redoStack.clear();
        selActive = false;
        selMode = 0;
        floatBuf = null;
        floatFromPaste = false;
        rectDragging = false;
        strokeLastX = NONE;
        strokeLastY = NONE;
    }

    void setTile(int layerIndex, int x, int y, int value) {
        if (!inBounds(x, y)) return;
        int[][] layer = layers.get(layerIndex);
        int old = layer[x][y];
        if (old == value) return;
        layer[x][y] = value;
        if (openOp != null) openOp.add(x, y, old, value);
        updateMiniPixel(x, y);
    }

    void beginOp() {
        commitFloating();
        openOp = new EditOp();
        openOp.layer = currentLayer;
    }

    void endOp() {
        if (openOp != null && openOp.size > 0) {
            undoStack.add(openOp);
            if (undoStack.size() > 100) undoStack.remove(0);
            redoStack.clear();
        }
        openOp = null;
    }

    void cancelOp() {
        EditOp op = openOp;
        openOp = null;
        if (op == null) return;
        for (int i = op.size - 4; i >= 0; i -= 4) {
            setTile(op.layer, op.data[i], op.data[i + 1], op.data[i + 2]);
        }
    }

    void undo() {
        commitFloating();
        if (undoStack.isEmpty()) return;
        EditOp op = undoStack.remove(undoStack.size() - 1);
        for (int i = op.size - 4; i >= 0; i -= 4) {
            setTile(op.layer, op.data[i], op.data[i + 1], op.data[i + 2]);
        }
        redoStack.add(op);
        selActive = false;
    }

    void redo() {
        commitFloating();
        if (redoStack.isEmpty()) return;
        EditOp op = redoStack.remove(redoStack.size() - 1);
        for (int i = 0; i < op.size; i += 4) {
            setTile(op.layer, op.data[i], op.data[i + 1], op.data[i + 3]);
        }
        undoStack.add(op);
        selActive = false;
    }

    void setTool(int newTool) {
        if (newTool == tool) return;
        commitFloating();
        if (newTool != TOOL_SELECT) selActive = false;
        rectDragging = false;
        tool = newTool;
    }

    boolean inBrush(int x, int y, int x0, int y0) {
        if (!brushRound) return true;
        double r = brushSize / 2.0;
        double dx = x + 0.5 - (x0 + r);
        double dy = y + 0.5 - (y0 + r);
        return dx * dx + dy * dy <= r * r;
    }

    void stampBrush(int cx, int cy, int value) {
        int n = brushSize;
        int x0 = cx - (n - 1) / 2;
        int y0 = cy - (n - 1) / 2;
        for (int x = x0; x < x0 + n; x++) {
            for (int y = y0; y < y0 + n; y++) {
                if (inBrush(x, y, x0, y0)) setTile(currentLayer, x, y, value);
            }
        }
    }

    void strokeTo(int gx, int gy, int value) {
        if (gx == strokeLastX && gy == strokeLastY) return;

        if (strokeLastX == NONE) {
            stampBrush(gx, gy, value);
        } else {
            int x = strokeLastX, y = strokeLastY;
            int dx = Math.abs(gx - x), dy = Math.abs(gy - y);
            int sx = x < gx ? 1 : -1, sy = y < gy ? 1 : -1;
            int err = dx - dy;
            while (true) {
                stampBrush(x, y, value);
                if (x == gx && y == gy) break;
                int e2 = 2 * err;
                if (e2 > -dy) {
                    err -= dy;
                    x += sx;
                }
                if (e2 < dx) {
                    err += dx;
                    y += sy;
                }
            }
        }
        strokeLastX = gx;
        strokeLastY = gy;
    }

    void applyRect(int x0, int y0, int x1, int y1, int value) {
        int minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        int minY = Math.min(y0, y1), maxY = Math.max(y0, y1);
        beginOp();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (squareFilled || x == minX || x == maxX || y == minY || y == maxY) {
                    setTile(currentLayer, x, y, value);
                }
            }
        }
        endOp();
    }

    void floodFill(int sx, int sy, int value) {
        if (!inBounds(sx, sy)) return;
        int[][] layer = layers.get(currentLayer);
        int target = layer[sx][sy];
        if (target == value) return;

        beginOp();
        int[] stack = new int[mapWidth * mapHeight];
        int sp = 0;
        setTile(currentLayer, sx, sy, value);
        stack[sp++] = sy * mapWidth + sx;
        int[] dxs = {1, -1, 0, 0};
        int[] dys = {0, 0, 1, -1};
        while (sp > 0) {
            int p = stack[--sp];
            int x = p % mapWidth, y = p / mapWidth;
            for (int d = 0; d < 4; d++) {
                int nx = x + dxs[d], ny = y + dys[d];
                if (inBounds(nx, ny) && layer[nx][ny] == target) {
                    setTile(currentLayer, nx, ny, value);
                    stack[sp++] = ny * mapWidth + nx;
                }
            }
        }
        endOp();
    }

    int[][] copyRegion(int x, int y, int w, int h) {
        int[][] out = new int[w][h];
        int[][] layer = layers.get(currentLayer);
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                out[i][j] = inBounds(x + i, y + j) ? layer[x + i][y + j] : -1;
            }
        }
        return out;
    }

    int[][] copyBuffer(int[][] source) {
        int[][] out = new int[source.length][];
        for (int i = 0; i < source.length; i++) out[i] = source[i].clone();
        return out;
    }

    boolean insideSelection(int gx, int gy) {
        return selActive && gx >= selX && gx < selX + selW && gy >= selY && gy < selY + selH;
    }

    void selectPress(int gx, int gy, boolean erase) {
        if (erase) {
            commitFloating();
            selActive = false;
            return;
        }

        if (insideSelection(gx, gy)) {
            if (floatBuf == null) {
                beginOp();
                floatBuf = copyRegion(selX, selY, selW, selH);
                liftX = selX;
                liftY = selY;
                floatFromPaste = false;
                for (int i = 0; i < selW; i++) {
                    for (int j = 0; j < selH; j++) setTile(currentLayer, selX + i, selY + j, -1);
                }
            }
            moveOffX = gx - selX;
            moveOffY = gy - selY;
            selMode = 2;
            return;
        }

        commitFloating();
        selActive = true;
        selAnchorX = clampCol(gx);
        selAnchorY = clampRow(gy);
        selX = selAnchorX;
        selY = selAnchorY;
        selW = 1;
        selH = 1;
        selMode = 1;
    }

    void dragSelection(int gx, int gy) {
        if (selMode == 1) {
            int cx = clampCol(gx), cy = clampRow(gy);
            selX = Math.min(selAnchorX, cx);
            selY = Math.min(selAnchorY, cy);
            selW = Math.abs(cx - selAnchorX) + 1;
            selH = Math.abs(cy - selAnchorY) + 1;
        } else if (selMode == 2) {
            selX = gx - moveOffX;
            selY = gy - moveOffY;
        }
    }

    void commitFloating() {
        if (floatBuf == null) return;
        if (!floatFromPaste && selX == liftX && selY == liftY) {
            cancelOp();
        } else {
            for (int i = 0; i < selW; i++) {
                for (int j = 0; j < selH; j++) {
                    if (floatBuf[i][j] != -1) setTile(currentLayer, selX + i, selY + j, floatBuf[i][j]);
                }
            }
            endOp();
        }
        floatBuf = null;
        floatFromPaste = false;
    }

    void cancelFloating() {
        if (floatBuf == null) return;
        cancelOp();
        if (floatFromPaste) {
            selActive = false;
        } else {
            selX = liftX;
            selY = liftY;
        }
        floatBuf = null;
        floatFromPaste = false;
    }

    void copySelection() {
        if (!selActive) return;
        clipboard = floatBuf != null ? copyBuffer(floatBuf) : copyRegion(selX, selY, selW, selH);
    }

    void deleteSelection() {
        if (!selActive) return;
        if (floatBuf != null) {
            endOp();
            floatBuf = null;
            floatFromPaste = false;
            selActive = false;
            return;
        }
        beginOp();
        for (int i = 0; i < selW; i++) {
            for (int j = 0; j < selH; j++) setTile(currentLayer, selX + i, selY + j, -1);
        }
        endOp();
        selActive = false;
    }

    void cutSelection() {
        copySelection();
        deleteSelection();
    }

    void pasteClipboard() {
        if (clipboard == null) return;
        commitFloating();
        beginOp();
        tool = TOOL_SELECT;
        floatBuf = copyBuffer(clipboard);
        selW = clipboard.length;
        selH = clipboard[0].length;
        boolean overMap = mx >= 0 && my >= 0 && mx < viewW && my < viewH;
        selX = clampCol(overMap ? cellAtX(mx) : cellAtX(0) + 1);
        selY = clampRow(overMap ? cellAtY(my) : cellAtY(0) + 1);
        selActive = true;
        floatFromPaste = true;
    }

    void escapeAction() {
        if (rectDragging) {
            rectDragging = false;
        } else if (floatBuf != null) {
            cancelFloating();
        } else {
            selActive = false;
        }
    }

    void startTool() {
        int gx = cellAtX(mx), gy = cellAtY(my);
        boolean erase = button == MouseEvent.BUTTON3;
        switch (tool) {
            case TOOL_BRUSH:
                beginOp();
                strokeLastX = NONE;
                strokeLastY = NONE;
                if (erase) strokeTo(gx, gy, -1);
                else if (hasTile()) strokeTo(gx, gy, selectedTileIndex);
                break;
            case TOOL_SQUARE:
                commitFloating();
                rectStartX = clampCol(gx);
                rectStartY = clampRow(gy);
                rectDragging = true;
                break;
            case TOOL_FILL:
                if (erase) floodFill(gx, gy, -1);
                else if (hasTile()) floodFill(gx, gy, selectedTileIndex);
                break;
            default:
                selectPress(gx, gy, erase);
                break;
        }
    }

    void finishTool() {
        boolean erase = button == MouseEvent.BUTTON3;
        switch (tool) {
            case TOOL_BRUSH:
                endOp();
                strokeLastX = NONE;
                strokeLastY = NONE;
                break;
            case TOOL_SQUARE:
                if (rectDragging) {
                    rectDragging = false;
                    int cx = clampCol(cellAtX(mx)), cy = clampRow(cellAtY(my));
                    if (erase) applyRect(rectStartX, rectStartY, cx, cy, -1);
                    else if (hasTile()) applyRect(rectStartX, rectStartY, cx, cy, selectedTileIndex);
                }
                break;
            case TOOL_SELECT:
                selMode = 0;
                break;
            default:
                break;
        }
    }

    void controlKey(int code, boolean shift) {
        switch (code) {
            case KeyEvent.VK_C: copySelection(); break;
            case KeyEvent.VK_X: cutSelection(); break;
            case KeyEvent.VK_V: pasteClipboard(); break;
            case KeyEvent.VK_Z:
                if (shift) redo();
                else undo();
                break;
            case KeyEvent.VK_Y: redo(); break;
            default: break;
        }
    }

    void toolKey(int code) {
        switch (code) {
            case KeyEvent.VK_B: setTool(TOOL_BRUSH); break;
            case KeyEvent.VK_R: setTool(TOOL_SQUARE); break;
            case KeyEvent.VK_F: setTool(TOOL_FILL); break;
            case KeyEvent.VK_V: setTool(TOOL_SELECT); break;
            case KeyEvent.VK_0: zoomTo(1f, viewW / 2.0, viewH / 2.0); break;
            case KeyEvent.VK_ENTER: commitFloating(); break;
            case KeyEvent.VK_ESCAPE: escapeAction(); break;
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_BACK_SPACE:
                deleteSelection();
                break;
            default: break;
        }
    }

    void drawGhost(Graphics2D g2, int x, int y) {
        if (!hasTile() || !inBounds(x, y)) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
        g2.drawImage(tiles[selectedTileIndex].subImage, x * tile, y * tile, tile, tile, null);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    void drawCellOutline(Graphics2D g2, int gx, int gy, float px) {
        if (!inBounds(gx, gy)) return;
        g2.setStroke(new BasicStroke(2 * px));
        g2.setColor(Color.WHITE);
        g2.drawRect(gx * tile, gy * tile, tile, tile);
    }

    void drawBrushPreview(Graphics2D g2, int gx, int gy, float px) {
        int n = brushSize;
        int x0 = gx - (n - 1) / 2;
        int y0 = gy - (n - 1) / 2;
        for (int x = x0; x < x0 + n; x++) {
            for (int y = y0; y < y0 + n; y++) {
                if (!inBrush(x, y, x0, y0) || !inBounds(x, y)) continue;
                drawGhost(g2, x, y);
                g2.setColor(new Color(255, 255, 255, 50));
                g2.fillRect(x * tile, y * tile, tile, tile);
            }
        }
        if (!brushRound) {
            g2.setStroke(new BasicStroke(2 * px));
            g2.setColor(Color.WHITE);
            g2.drawRect(x0 * tile, y0 * tile, n * tile, n * tile);
        }
    }

    void drawRectPreview(Graphics2D g2, float px) {
        int cx = clampCol(cellAtX(mx)), cy = clampRow(cellAtY(my));
        int minX = Math.min(rectStartX, cx), maxX = Math.max(rectStartX, cx);
        int minY = Math.min(rectStartY, cy), maxY = Math.max(rectStartY, cy);
        boolean erase = button == MouseEvent.BUTTON3;

        int x1 = Math.min(maxX, visCol1()), y1 = Math.min(maxY, visRow1());
        for (int x = Math.max(minX, visCol0()); x <= x1; x++) {
            for (int y = Math.max(minY, visRow0()); y <= y1; y++) {
                if (!squareFilled && x != minX && x != maxX && y != minY && y != maxY) continue;
                if (erase) {
                    g2.setColor(new Color(255, 0, 0, 90));
                    g2.fillRect(x * tile, y * tile, tile, tile);
                } else {
                    drawGhost(g2, x, y);
                }
            }
        }

        g2.setStroke(new BasicStroke(2 * px));
        g2.setColor(Color.YELLOW);
        g2.drawRect(minX * tile, minY * tile, (maxX - minX + 1) * tile, (maxY - minY + 1) * tile);
    }

    void drawSelection(Graphics2D g2, float px) {
        if (floatBuf != null && tiles != null) {
            int i0 = Math.max(0, visCol0() - selX), i1 = Math.min(selW - 1, visCol1() - selX);
            int j0 = Math.max(0, visRow0() - selY), j1 = Math.min(selH - 1, visRow1() - selY);
            for (int i = i0; i <= i1; i++) {
                for (int j = j0; j <= j1; j++) {
                    int id = floatBuf[i][j];
                    if (id >= 0 && id < tiles.length && inBounds(selX + i, selY + j)) {
                        g2.drawImage(tiles[id].subImage, (selX + i) * tile, (selY + j) * tile, tile, tile, null);
                    }
                }
            }
        }

        g2.setColor(new Color(255, 255, 0, 40));
        g2.fillRect(selX * tile, selY * tile, selW * tile, selH * tile);
        g2.setStroke(new BasicStroke(2 * px, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                new float[]{8 * px, 6 * px}, 0f));
        g2.setColor(Color.YELLOW);
        g2.drawRect(selX * tile, selY * tile, selW * tile, selH * tile);
        g2.setStroke(new BasicStroke(1));
    }

    void drawToolOverlay(Graphics2D g2) {
        float px = 1f / zoom;
        if (selActive) drawSelection(g2, px);
        if (!tileTab() || !mouseInView()) return;

        int gx = cellAtX(mx), gy = cellAtY(my);
        switch (tool) {
            case TOOL_BRUSH:
                drawBrushPreview(g2, gx, gy, px);
                break;
            case TOOL_SQUARE:
                if (rectDragging) {
                    drawRectPreview(g2, px);
                } else {
                    drawGhost(g2, gx, gy);
                    drawCellOutline(g2, gx, gy, px);
                }
                break;
            case TOOL_FILL:
                drawGhost(g2, gx, gy);
                drawCellOutline(g2, gx, gy, px);
                break;
            default:
                if (!insideSelection(gx, gy)) drawCellOutline(g2, gx, gy, px);
                break;
        }
        g2.setStroke(new BasicStroke(1));
    }

    java.awt.Rectangle toolRect(int i) {
        return new java.awt.Rectangle(contentX() + i * 91, CONTENT_Y + 6, 86, 28);
    }

    java.awt.Rectangle sliderRect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 44, 250, 26);
    }

    java.awt.Rectangle shapeRect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 78, 190, 28);
    }

    java.awt.Rectangle fillRect() {
        return new java.awt.Rectangle(contentX(), CONTENT_Y + 44, 190, 28);
    }

    java.awt.Rectangle selButtonRect(int i) {
        return new java.awt.Rectangle(contentX() + i * 91, CONTENT_Y + 44, 86, 28);
    }

    void setBrushFromX(int x) {
        java.awt.Rectangle s = sliderRect();
        int size = 1 + (int) Math.round((x - s.x) / (double) s.width * 19);
        brushSize = Math.max(1, Math.min(20, size));
    }

    void drawToolsPanel(Graphics2D g2, Color bg) {
        g2.setColor(bg);
        g2.fillRect(menuStartX, CONTENT_Y, menuWidth, TOOLS_H + 4);

        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        for (int i = 0; i < toolNames.length; i++) {
            java.awt.Rectangle r = toolRect(i);
            drawButton(g2, r, toolNames[i]);
            if (i == tool) {
                g2.setColor(new Color(255, 255, 0, 45));
                g2.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
                g2.setColor(Color.YELLOW);
                g2.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
            }
        }

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 13f));
        if (tool == TOOL_BRUSH) {
            java.awt.Rectangle s = sliderRect();
            int trackY = s.y + s.height / 2;
            g2.setColor(Color.GRAY);
            g2.fillRect(s.x, trackY - 2, s.width, 4);
            int hx = s.x + (int) ((brushSize - 1) / 19.0 * s.width);
            g2.setColor(Color.YELLOW);
            g2.fillOval(hx - 7, trackY - 7, 14, 14);
            g2.setColor(Color.WHITE);
            g2.drawString("Size: " + brushSize, s.x + s.width + 12, trackY + 5);
            drawButton(g2, shapeRect(), "Shape: " + (brushRound ? "Round" : "Square"));
        } else if (tool == TOOL_SQUARE) {
            drawButton(g2, fillRect(), "Fill: " + (squareFilled ? "Filled" : "Outline"));
        } else if (tool == TOOL_SELECT) {
            for (int i = 0; i < selectButtons.length; i++) drawButton(g2, selButtonRect(i), selectButtons[i]);
        }

        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(Color.GRAY);
        String[] hint;
        switch (tool) {
            case TOOL_BRUSH:
                hint = new String[]{"Drag to paint, right-drag to erase.", "Ctrl+Z / Ctrl+Y undo and redo."};
                break;
            case TOOL_SQUARE:
                hint = new String[]{"Drag a rectangle. Right-drag erases it.", "Esc cancels while dragging."};
                break;
            case TOOL_FILL:
                hint = new String[]{"Click to fill a connected area.", "Right-click erases the area."};
                break;
            default:
                hint = new String[]{"Drag to select, drag inside to move.", "Ctrl+C / X / V, Del, Enter drops, Esc cancels."};
                break;
        }
        g2.drawString(hint[0], contentX(), CONTENT_Y + 126);
        g2.drawString(hint[1], contentX(), CONTENT_Y + 142);
    }

    boolean clickTools(int x, int y) {
        if (y >= CONTENT_Y + TOOLS_H) return false;

        for (int i = 0; i < toolNames.length; i++) {
            if (toolRect(i).contains(x, y)) {
                setTool(i);
                return true;
            }
        }

        if (tool == TOOL_BRUSH) {
            if (sliderRect().contains(x, y)) {
                sliderDrag = true;
                setBrushFromX(x);
            } else if (shapeRect().contains(x, y)) {
                brushRound = !brushRound;
            }
        } else if (tool == TOOL_SQUARE) {
            if (fillRect().contains(x, y)) squareFilled = !squareFilled;
        } else if (tool == TOOL_SELECT) {
            if (selButtonRect(0).contains(x, y)) copySelection();
            else if (selButtonRect(1).contains(x, y)) cutSelection();
            else if (selButtonRect(2).contains(x, y)) pasteClipboard();
            else if (selButtonRect(3).contains(x, y)) deleteSelection();
        }
        return true;
    }

    void drawTilesTab(Graphics2D g2, Color bg) {
        int startX = contentX() + 10;
        int startY = CONTENT_Y + TOOLS_H + 10 - scroll;
        if (tiles == null) {
            g2.setColor(Color.GRAY);
            g2.drawString("[ Import a tilesheet (toolbar) ]", contentX(), CONTENT_Y + TOOLS_H + 30);
        } else {
            for (int i = 0; i < tiles.length; i++) {
                int tx = startX + (i % 4) * 72;
                int ty = startY + (i / 4) * 72;
                if (ty + 64 < CONTENT_Y || ty > contentBottom) continue;
                g2.drawImage(tiles[i].subImage, tx, ty, 64, 64, null);
                if (i == selectedTileIndex) {
                    g2.setColor(Color.YELLOW);
                    g2.setStroke(new BasicStroke(3));
                    g2.drawRect(tx - 2, ty - 2, 68, 68);
                    g2.setStroke(new BasicStroke(1));
                }
            }
        }
        drawToolsPanel(g2, bg);
    }

    void clickTiles(int x, int y) {
        if (clickTools(x, y)) return;
        if (tiles == null) return;
        int startX = contentX() + 10;
        int startY = CONTENT_Y + TOOLS_H + 10 - scroll;
        for (int i = 0; i < tiles.length; i++) {
            int tx = startX + (i % 4) * 72;
            int ty = startY + (i / 4) * 72;
            if (x >= tx && x <= tx + 64 && y >= ty && y <= ty + 64) {
                selectedTileIndex = i;
                return;
            }
        }
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
                commitFloating();
                selActive = false;
                rectDragging = false;
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

    void clickLayers(int x, int y) {
        commitFloating();
        selActive = false;
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
                end = CONTENT_Y + TOOLS_H + 10 + (tiles == null ? 0 : (tiles.length + 3) / 4 * 72);
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
        if (tileTab()) startTool();
        if (currentTab == TAB_COLLISION) collisionClick(button == MouseEvent.BUTTON3);
        if (currentTab == TAB_ENTITY || currentTab == TAB_EVENT) {
            int gx = cellAtX(mx);
            int gy = cellAtY(my);
            if (inBounds(gx, gy)) placeOrRemove(gx, gy, button == MouseEvent.BUTTON3);
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (e.getX() < menuStartX) {
            zoomTo((float) (zoom * Math.pow(1.15, -e.getPreciseWheelRotation())), e.getX(), e.getY());
            return;
        }
        scroll = Math.max(0, Math.min(maxScroll(), scroll + e.getWheelRotation() * 40));
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        mx = e.getX();
        my = e.getY();
        if (pressed && dragMode == 2 && tileTab()) finishTool();
        pressed = false;
        dragMode = 0;
        sliderDrag = false;
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        mx = e.getX();
        my = e.getY();
        if (sliderDrag) setBrushFromX(e.getX());
    }

    @Override
    public void keyPressed(KeyEvent e) {
        int code = e.getKeyCode();

        if(code == KeyEvent.VK_TAB) {
            if(currentLayer < layers.size()-1) {
                currentLayer++;
            }
            else{
                currentLayer = 0;
            } 
        }

        setKey(code, true);
        if (e.isControlDown() || e.isMetaDown()) {
            controlKey(code, e.isShiftDown());
        } else {
            toolKey(code);
        }
    }

    @Override public void mouseMoved(MouseEvent e) { mx = e.getX(); my = e.getY(); }
    @Override public void mouseClicked(MouseEvent e) {}
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

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
            frame.setTitle("Expandable Tile Map Editor");

            Window window = new Window(frame);

            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
            toolbar.add(toolbarButton("New Map", window::newMapDialog));
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