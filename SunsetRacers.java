import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SUNSET RACERS - a small, self-contained Java LAN arcade racer.
 *
 * Build: javac SunsetRacers.java
 * Run:   java SunsetRacers
 *
 * - Host a race (even alone), pick how many racers are on the grid, press ENTER.
 * - Empty grid spots are filled with computer racers (bots).
 * - Friends on the same network can find the race automatically or join by IP.
 * - First car to finish 3 laps wins. The host can start a rematch (5..1 countdown).
 *
 * Uses only the Java standard library. The source is pure ASCII, so it compiles the
 * same on every OS / encoding (no more garbled bullet characters).
 */
public class SunsetRacers {
    static final int PORT = 47652, DISCOVERY_PORT = 47653;
    static final int W = 1100, H = 760, MAX_PLAYERS = 4, LAPS = 3;
    static final double COUNTDOWN_SECONDS = 5.0;
    static final String DISCOVERY = "SUNSET_RACERS_DISCOVER";
    static final Color[] CAR_COLORS = {
        new Color(255, 91, 91), new Color(65, 190, 255),
        new Color(255, 204, 65), new Color(105, 230, 155)
    };
    static final Color GOLD = new Color(255, 206, 116);
    static final Color ORANGE = new Color(255, 190, 90);
    static final Color INK = new Color(34, 43, 49);

    static final Map<Integer, Font> FONTS = new HashMap<>();
    static Font font(int style, int size) {
        return FONTS.computeIfAbsent(style * 1000 + size, k -> new Font("SansSerif", style, size));
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new MenuFrame().setVisible(true));
    }

    // =====================================================================
    //  MAIN MENU
    // =====================================================================

    /** Rounded, hover-aware flat button. */
    static class Btn extends JButton {
        final Color base;
        boolean hover;
        Btn(String text, Color base, Color fg, int size) {
            super(text);
            this.base = base;
            setFont(font(Font.BOLD, size));
            setForeground(fg);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                public void mouseExited(MouseEvent e) { hover = false; repaint(); }
            });
        }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = base;
            if (getModel().isPressed()) c = base.darker();
            else if (hover) c = new Color(Math.min(255, base.getRed() + 18), Math.min(255, base.getGreen() + 18), Math.min(255, base.getBlue() + 18));
            g.setColor(c);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    static class Card extends JPanel {
        Card() { setOpaque(false); }
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(6, 20, 32, 190));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 26, 26);
            g.setColor(new Color(255, 255, 255, 28));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    static class MenuPanel extends JPanel {
        protected void paintComponent(Graphics gg) {
            super.paintComponent(gg);
            Graphics2D g = (Graphics2D) gg.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setPaint(new GradientPaint(0, 0, new Color(18, 30, 62), 0, h, new Color(14, 96, 104)));
            g.fillRect(0, 0, w, h);
            // setting sun
            for (int i = 0; i < 9; i++) {
                g.setColor(new Color(255, 170, 90, 22));
                int r = 150 - i * 12;
                g.fillOval(w - 170 - r / 2, 40 - r / 3, r, r);
            }
            g.setFont(font(Font.BOLD, 46));
            String t = "SUNSET RACERS";
            int tw = g.getFontMetrics().stringWidth(t);
            g.setColor(new Color(0, 0, 0, 80)); g.drawString(t, (w - tw) / 2 + 3, 78);
            g.setColor(GOLD); g.drawString(t, (w - tw) / 2, 75);
            g.setFont(font(Font.PLAIN, 14));
            String s = "COASTAL CIRCUIT  \u2022  UP TO 4 RACERS  \u2022  FULL THROTTLE";
            int sw = g.getFontMetrics().stringWidth(s);
            g.setColor(new Color(214, 236, 243)); g.drawString(s, (w - sw) / 2, 105);
            g.dispose();
        }
    }

    static class MenuFrame extends JFrame {
        static class Found { String label; InetSocketAddress addr; long seen; }
        final JTextField nameField = new JTextField("Racer");
        final JTextField ipField = new JTextField("127.0.0.1");
        final DefaultListModel<String> hostModel = new DefaultListModel<>();
        final JList<String> hosts = new JList<>(hostModel);
        final JLabel hint = new JLabel("Searching your network for races...", SwingConstants.CENTER);
        final Map<String, Found> found = new LinkedHashMap<>();
        final Discovery discovery;
        final javax.swing.Timer scanTimer;

        MenuFrame() {
            super("SUNSET RACERS");
            setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            setSize(600, 660);
            setLocationRelativeTo(null);
            setResizable(false);

            MenuPanel root = new MenuPanel();
            root.setLayout(new BorderLayout());
            setContentPane(root);
            JPanel spacer = new JPanel(); spacer.setOpaque(false); spacer.setPreferredSize(new Dimension(10, 130));
            root.add(spacer, BorderLayout.NORTH);

            Card card = new Card();
            card.setLayout(new GridBagLayout());
            card.setBorder(new EmptyBorder(24, 30, 24, 30));
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.setBorder(new EmptyBorder(0, 42, 34, 42));
            wrap.add(card, BorderLayout.CENTER);
            root.add(wrap, BorderLayout.CENTER);

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(3, 0, 3, 0);

            JLabel nameLabel = label("DRIVER NAME", GOLD, 12);
            c.gridy = 0; card.add(nameLabel, c);
            styleField(nameField);
            c.gridy = 1; card.add(nameField, c);

            Btn hostButton = new Btn("HOST A RACE", ORANGE, INK, 17);
            c.gridy = 2; c.ipady = 14; c.insets = new Insets(12, 0, 4, 0); card.add(hostButton, c);
            c.ipady = 0;

            JLabel or = label("- or join a race -", new Color(190, 210, 220), 12);
            or.setHorizontalAlignment(SwingConstants.CENTER);
            c.gridy = 3; c.insets = new Insets(16, 0, 4, 0); card.add(or, c);

            hosts.setFont(font(Font.PLAIN, 14));
            hosts.setFixedCellHeight(26);
            hosts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JScrollPane scroll = new JScrollPane(hosts);
            scroll.setPreferredSize(new Dimension(100, 104));
            scroll.setBorder(new LineBorder(new Color(255, 255, 255, 60)));
            c.gridy = 4; c.insets = new Insets(3, 0, 3, 0); card.add(scroll, c);

            hint.setForeground(new Color(170, 200, 212));
            hint.setFont(font(Font.PLAIN, 12));
            c.gridy = 5; card.add(hint, c);

            JPanel ipRow = new JPanel(new BorderLayout(8, 0));
            ipRow.setOpaque(false);
            ipRow.add(label("IP", GOLD, 12), BorderLayout.WEST);
            styleField(ipField);
            ipRow.add(ipField, BorderLayout.CENTER);
            Btn joinButton = new Btn("JOIN", ORANGE, INK, 14);
            joinButton.setPreferredSize(new Dimension(90, 34));
            ipRow.add(joinButton, BorderLayout.EAST);
            c.gridy = 6; c.insets = new Insets(8, 0, 0, 0); card.add(ipRow, c);

            hostButton.addActionListener(e -> host());
            joinButton.addActionListener(e -> join(ipField.getText().trim()));
            ipField.addActionListener(e -> join(ipField.getText().trim()));
            hosts.addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        Found f = findByLabel(hosts.getSelectedValue());
                        if (f != null) join(f.addr.getAddress().getHostAddress());
                    }
                }
            });

            discovery = new Discovery((addr, hostName, count) -> SwingUtilities.invokeLater(() -> {
                String ip = addr.getAddress().getHostAddress();
                Found f = found.computeIfAbsent(ip, k -> new Found());
                f.addr = addr;
                f.seen = System.currentTimeMillis();
                f.label = hostName + "    " + ip + "    (" + count + "/" + MAX_PLAYERS + " players)";
                refreshList();
            }));
            discovery.start();
            scanTimer = new javax.swing.Timer(2500, e -> {
                long now = System.currentTimeMillis();
                found.values().removeIf(f -> now - f.seen > 8000);
                refreshList();
                discovery.requestScan();
            });
            scanTimer.setInitialDelay(300);
            scanTimer.start();
        }

        JLabel label(String text, Color col, int size) {
            JLabel l = new JLabel(text);
            l.setForeground(col);
            l.setFont(font(Font.BOLD, size));
            return l;
        }

        void styleField(JTextField f) {
            f.setFont(font(Font.PLAIN, 16));
            f.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(255, 255, 255, 70)), new EmptyBorder(6, 10, 6, 10)));
        }

        Found findByLabel(String label) {
            if (label == null) return null;
            for (Found f : found.values()) if (label.equals(f.label)) return f;
            return null;
        }

        void refreshList() {
            List<String> labels = new ArrayList<>();
            for (Found f : found.values()) labels.add(f.label);
            boolean same = labels.size() == hostModel.size();
            for (int i = 0; same && i < labels.size(); i++) if (!labels.get(i).equals(hostModel.get(i))) same = false;
            if (!same) {
                String sel = hosts.getSelectedValue();
                hostModel.clear();
                for (String s : labels) hostModel.addElement(s);
                if (sel != null && labels.contains(sel)) hosts.setSelectedValue(sel, true);
            }
            hint.setText(labels.isEmpty() ? "Searching your network for races..."
                    : labels.size() + " race(s) found - double-click one to join");
        }

        String driverName() {
            String n = nameField.getText().trim().replace('|', ' ').replace(',', ' ');
            if (n.isEmpty()) n = "Racer";
            return n.substring(0, Math.min(12, n.length()));
        }

        void host() {
            try {
                GameServer server = new GameServer();
                server.start();
                connectTo("127.0.0.1", driverName(), server);
            } catch (IOException ex) {
                error("Could not start the race host. Is another race already running on this computer?\n" + ex.getMessage());
            }
        }

        void join(String ip) {
            if (ip.isEmpty()) { error("Enter the host computer's IP address."); return; }
            try { connectTo(ip, driverName(), null); }
            catch (Exception ex) { error("Could not join " + ip + ".\n" + ex.getMessage() + "\n\nCheck the IP address and the host's firewall."); }
        }

        void connectTo(String ip, String name, GameServer hosted) throws IOException {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip, PORT), 2500);
                socket.setTcpNoDelay(true);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                out.println("HELLO|" + name);
                String welcome = in.readLine();
                if (welcome == null || !welcome.startsWith("WELCOME|")) {
                    String msg = welcome == null ? "The host closed the connection."
                            : (welcome.contains("|") ? welcome.substring(welcome.indexOf('|') + 1) : welcome);
                    throw new IOException(msg);
                }
                int id = Integer.parseInt(welcome.split("\\|")[1]);
                scanTimer.stop();
                discovery.close();
                dispose();
                new RaceFrame(socket, in, out, id, name, hosted).setVisible(true);
            } catch (IOException | RuntimeException ex) {
                try { socket.close(); } catch (IOException ignored) {}
                if (hosted != null) hosted.shutdown();
                throw ex;
            }
        }

        void error(String message) {
            JOptionPane.showMessageDialog(this, message, "SUNSET RACERS", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =====================================================================
    //  LAN DISCOVERY (client side)
    // =====================================================================

    static class Discovery extends Thread {
        interface Found { void host(InetSocketAddress address, String name, int count); }
        final Found found;
        volatile boolean scan = true, stop = false;
        Discovery(Found f) { super("LAN discovery"); setDaemon(true); found = f; }
        void requestScan() { scan = true; }
        void close() { stop = true; }
        public void run() {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true); ds.setSoTimeout(700);
                byte[] buf = new byte[512];
                while (!stop) {
                    if (scan) {
                        scan = false;
                        byte[] msg = DISCOVERY.getBytes(StandardCharsets.UTF_8);
                        try {
                            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                                try {
                                    if (!ni.isUp()) continue;
                                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                                        InetAddress bc = ia.getBroadcast();
                                        if (bc != null) ds.send(new DatagramPacket(msg, msg.length, bc, DISCOVERY_PORT));
                                    }
                                } catch (Exception ignored) {}
                            }
                            ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                            ds.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("127.0.0.1"), DISCOVERY_PORT));
                        } catch (Exception ignored) {}
                    }
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (s.startsWith("SUNSET_RACERS_HERE|")) {
                            String[] a = s.split("\\|");
                            found.host(new InetSocketAddress(p.getAddress(), Integer.parseInt(a[1])),
                                    a.length > 2 ? a[2] : "Sunset Host", a.length > 3 ? Integer.parseInt(a[3]) : 1);
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (IOException ignored) {}
        }
    }

    // =====================================================================
    //  CAR + TRACK
    // =====================================================================

    static class Car {
        int id;
        String name;
        double x, y, vx, vy, angle, speed;
        double dist;           // progress in track samples; 0 = start line, LAPS*Track.N = finish
        boolean bot, drift;
        // server-side only
        int idx;
        boolean offroad;
        double lane, botMax, stuck, reverseT;
        Car(int id, String name, boolean bot) { this.id = id; this.name = name; this.bot = bot; }
    }

    static class Track {
        static final double ROAD_W = 104, HALF = ROAD_W / 2, WALL = HALF + 40, STEP = 5;

        // Closed circuit (counter-clockwise on screen). Point 0 is the middle of the start/finish straight.
        static final double[][] CTRL = {
            {430, 625}, {640, 625}, {830, 624}, {950, 565}, {990, 455}, {945, 360},
            {840, 328}, {745, 262}, {665, 165}, {510, 133}, {380, 168}, {270, 245},
            {185, 335}, {155, 440}, {185, 540}, {285, 625}
        };
        static final int N;
        static final double[] X, Y, A, CS;
        static final Path2D.Double PATH = new Path2D.Double();

        static {
            int m = CTRL.length;
            List<double[]> dense = new ArrayList<>();
            for (int i = 0; i < m; i++) {
                double[] p0 = CTRL[(i - 1 + m) % m], p1 = CTRL[i], p2 = CTRL[(i + 1) % m], p3 = CTRL[(i + 2) % m];
                for (int s = 0; s < 40; s++) dense.add(catmull(p0, p1, p2, p3, s / 40.0));
            }
            int dn = dense.size();
            double[] cum = new double[dn + 1];
            for (int i = 0; i < dn; i++) {
                double[] a = dense.get(i), b = dense.get((i + 1) % dn);
                cum[i + 1] = cum[i] + Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            double total = cum[dn];
            int n = (int) Math.round(total / STEP);
            N = n;
            X = new double[n]; Y = new double[n]; A = new double[n]; CS = new double[n];
            int seg = 0;
            for (int k = 0; k < n; k++) {
                double target = total * k / n;
                while (seg < dn - 1 && cum[seg + 1] < target) seg++;
                double[] a = dense.get(seg), b = dense.get((seg + 1) % dn);
                double span = cum[seg + 1] - cum[seg];
                double f = span <= 0 ? 0 : (target - cum[seg]) / span;
                X[k] = a[0] + (b[0] - a[0]) * f;
                Y[k] = a[1] + (b[1] - a[1]) * f;
            }
            for (int i = 0; i < n; i++) A[i] = Math.atan2(Y[(i + 1) % n] - Y[i], X[(i + 1) % n] - X[i]);
            for (int i = 0; i < n; i++) {
                double turn = 0;
                for (int k = 0; k < 30; k++) turn += Math.abs(norm(A[(i + k + 1) % n] - A[(i + k) % n]));
                CS[i] = Math.max(125, Math.min(320, 470 - 190 * turn));
            }
            PATH.moveTo(X[0], Y[0]);
            for (int i = 1; i < n; i++) PATH.lineTo(X[i], Y[i]);
            PATH.closePath();
        }

        static double[] catmull(double[] p0, double[] p1, double[] p2, double[] p3, double t) {
            double t2 = t * t, t3 = t2 * t;
            double[] r = new double[2];
            for (int d = 0; d < 2; d++)
                r[d] = 0.5 * ((2 * p1[d]) + (-p0[d] + p2[d]) * t + (2 * p0[d] - 5 * p1[d] + 4 * p2[d] - p3[d]) * t2
                        + (-p0[d] + 3 * p1[d] - 3 * p2[d] + p3[d]) * t3);
            return r;
        }

        static double norm(double a) { return Math.atan2(Math.sin(a), Math.cos(a)); }

        /** Point on the track at sample index i, offset sideways by lat pixels. */
        static double[] pos(int i, double lat) {
            int k = Math.floorMod(i, N);
            return new double[]{X[k] - Math.sin(A[k]) * lat, Y[k] + Math.cos(A[k]) * lat, A[k]};
        }

        /** Nearest centerline sample to (x,y), searching near a hint. Returns {index, distance}. */
        static double[] nearest(double x, double y, int hint) {
            double best = Double.MAX_VALUE; int bi = hint;
            for (int k = -45; k <= 45; k++) {
                int i = Math.floorMod(hint + k, N);
                double dx = X[i] - x, dy = Y[i] - y, d = dx * dx + dy * dy;
                if (d < best) { best = d; bi = i; }
            }
            if (best > 160 * 160) {
                for (int i = 0; i < N; i++) {
                    double dx = X[i] - x, dy = Y[i] - y, d = dx * dx + dy * dy;
                    if (d < best) { best = d; bi = i; }
                }
            }
            return new double[]{bi, Math.sqrt(best)};
        }

        static double distToCenter(double x, double y) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < N; i++) {
                double dx = X[i] - x, dy = Y[i] - y, d = dx * dx + dy * dy;
                if (d < best) best = d;
            }
            return Math.sqrt(best);
        }

        private static BufferedImage IMG;
        static synchronized BufferedImage image() {
            if (IMG == null) IMG = render(2.0);
            return IMG;
        }

        static BufferedImage render(double sc) {
            BufferedImage img = new BufferedImage((int) (W * sc), (int) (H * sc), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(sc, sc);

            // grass with soft mowing stripes
            g.setPaint(new GradientPaint(0, 0, new Color(48, 158, 118), 0, H, new Color(32, 124, 98)));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(255, 255, 255, 10));
            for (int x = 0; x < W; x += 60) if ((x / 60) % 2 == 0) g.fillRect(x, 0, 60, H);

            // infield lake with a sandy shore
            g.setColor(new Color(238, 208, 146));
            g.fill(new Ellipse2D.Double(350, 305, 360, 205));
            g.setPaint(new GradientPaint(0, 320, new Color(96, 206, 218), 0, 500, new Color(40, 140, 178)));
            g.fill(new Ellipse2D.Double(372, 325, 316, 165));
            g.setColor(new Color(255, 255, 255, 60));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Arc2D.Double(400, 350, 160, 70, 20, 140, Arc2D.OPEN));

            // trees
            Random r = new Random(11);
            for (int n = 0; n < 160; n++) {
                int x = r.nextInt(W - 20) + 10, y = r.nextInt(H - 20) + 10;
                if (distToCenter(x, y) < WALL + 24) continue;
                if (x > 335 && x < 725 && y > 290 && y < 525) continue;
                if (y > 690 && x > 360 && x < 790) continue;
                g.setColor(new Color(10, 70, 55, 90)); g.fillOval(x - 9, y - 4, 24, 16);
                g.setColor(new Color(30, 112, 78)); g.fillOval(x - 12, y - 14, 24, 24);
                g.setColor(new Color(52, 148, 96)); g.fillOval(x - 8, y - 11, 15, 15);
            }

            // barrier, run-off area, kerbs, asphalt
            g.setStroke(new BasicStroke((float) (2 * WALL + 8), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(38, 52, 58)); g.draw(PATH);
            g.setStroke(new BasicStroke((float) (2 * WALL), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(92, 178, 126)); g.draw(PATH);
            float kw = (float) (ROAD_W + 20);
            g.setStroke(new BasicStroke(kw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{12, 12}, 0f));
            g.setColor(new Color(214, 58, 58)); g.draw(PATH);
            g.setStroke(new BasicStroke(kw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{12, 12}, 12f));
            g.setColor(new Color(245, 245, 240)); g.draw(PATH);
            g.setStroke(new BasicStroke((float) ROAD_W, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(240, 238, 228)); g.draw(PATH);
            g.setStroke(new BasicStroke((float) (ROAD_W - 6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(52, 58, 66)); g.draw(PATH);
            g.setStroke(new BasicStroke(34f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(47, 53, 61)); g.draw(PATH);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{16, 16}, 0f));
            g.setColor(new Color(245, 205, 112, 200)); g.draw(PATH);

            // start / finish line, grid boxes, gantry posts and sign
            double[] s0 = pos(0, 0);
            Graphics2D q = (Graphics2D) g.create();
            q.translate(s0[0], s0[1]); q.rotate(s0[2]);
            int rows = (int) (ROAD_W / 8);
            for (int rr = 0; rr < rows; rr++)
                for (int cc = 0; cc < 2; cc++) {
                    q.setColor((rr + cc) % 2 == 0 ? Color.WHITE : new Color(20, 20, 24));
                    q.fillRect(-8 + cc * 8, (int) (-HALF) + 3 + rr * 8, 8, 8);
                }
            q.setColor(new Color(255, 255, 255, 70));
            q.setStroke(new BasicStroke(1.5f));
            for (int slot = 0; slot < MAX_PLAYERS; slot++) {
                int row = slot / 2;
                double lat = slot % 2 == 0 ? -22 : 22;
                q.drawRect((int) (-(10 + row * 16) * STEP) + 14, (int) lat - 8, 34, 16);
            }
            q.setColor(new Color(30, 36, 44));
            q.fillRoundRect(-5, (int) (-HALF - 16), 10, 12, 4, 4);
            q.fillRoundRect(-5, (int) (HALF + 4), 10, 12, 4, 4);
            q.setColor(new Color(255, 206, 116));
            q.fillRoundRect(14, (int) (-WALL + 4), 112, 20, 8, 8);
            q.setColor(INK); q.setFont(font(Font.BOLD, 11));
            q.drawString("START / FINISH", 22, (int) (-WALL + 18));
            // grandstand below the main straight
            int gy = (int) (WALL + 8);
            q.setColor(new Color(56, 62, 74)); q.fillRoundRect(-60, gy, 380, 28, 8, 8);
            q.setColor(new Color(204, 72, 72)); q.fillRect(-60, gy, 380, 5);
            Random cr = new Random(5);
            Color[] fans = {new Color(255, 214, 120), new Color(120, 200, 255), new Color(255, 140, 140), new Color(240, 240, 240), new Color(150, 230, 170)};
            for (int fx = -54; fx < 314; fx += 7)
                for (int fy = 8; fy < 26; fy += 6) {
                    q.setColor(fans[cr.nextInt(fans.length)]);
                    q.fillOval(fx, gy + fy, 4, 4);
                }
            q.dispose();
            g.dispose();
            return img;
        }
    }

    // =====================================================================
    //  SERVER
    // =====================================================================

    static class GameServer {
        // phases: 0 lobby, 1 countdown, 2 racing, 3 finished
        final List<ServerPeer> peers = new CopyOnWriteArrayList<>();
        final List<Car> cars = new CopyOnWriteArrayList<>();
        volatile boolean running = true;
        volatile int phase = 0, tick = 0, winner = -1;
        volatile double countdown = 0, raceTime = 0;
        int totalRacers = 2;
        final ServerSocket server;
        final Random rnd = new Random();

        GameServer() throws IOException {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        }

        void start() {
            Thread acceptor = new Thread(() -> {
                while (running) {
                    try {
                        Socket s = server.accept();
                        s.setTcpNoDelay(true);
                        if (peers.size() >= MAX_PLAYERS) {
                            try {
                                s.getOutputStream().write(
                                        "FULL|The race is full\n".getBytes(StandardCharsets.UTF_8));
                            } finally {
                                s.close();
                            }
                            continue;
                        }
                        ServerPeer p = new ServerPeer(s, this);
                        peers.add(p);
                        p.start();
                    } catch (IOException e) { if (running) e.printStackTrace(); }
                }
            }, "race acceptor");
            acceptor.setDaemon(true); acceptor.start();
            Thread udp = new Thread(this::discoveryLoop, "race discovery responder");
            udp.setDaemon(true); udp.start();
            Thread sim = new Thread(this::simulate, "race simulation");
            sim.setDaemon(true); sim.start();
        }

        void shutdown() {
            running = false;
            try { server.close(); } catch (IOException ignored) {}
            for (ServerPeer p : peers) try { p.socket.close(); } catch (IOException ignored) {}
        }

        int humans() { int n = 0; for (Car c : cars) if (!c.bot) n++; return n; }

        synchronized int addCar(String name) {
            if (phase != 0) return -1;
            for (int i = 0; i < MAX_PLAYERS; i++) {
                boolean used = false;
                for (Car c : cars) if (c.id == i) used = true;
                if (!used) {
                    Car c = new Car(i, name, false);
                    place(c);
                    cars.add(c);
                    return i;
                }
            }
            return -1;
        }

        synchronized void remove(ServerPeer p) {
            peers.remove(p);
            cars.removeIf(c -> !c.bot && c.id == p.id);
        }

        /** Put a car on its starting-grid slot (slot = car id). */
        void place(Car c) {
            int slot = c.id, row = slot / 2;
            int idx0 = -(10 + row * 16);
            double lat = slot % 2 == 0 ? -22 : 22;
            double[] p = Track.pos(idx0, lat);
            c.x = p[0]; c.y = p[1]; c.angle = p[2];
            c.vx = c.vy = c.speed = 0;
            c.dist = idx0; c.idx = Math.floorMod(idx0, Track.N);
            c.drift = false; c.offroad = false; c.stuck = 0; c.reverseT = 0;
            c.lane = c.bot ? (rnd.nextDouble() - 0.5) * 36 : 0;
            c.botMax = 236 + rnd.nextInt(30);
        }

        void prepareGrid() {
            cars.removeIf(c -> c.bot);
            int total = Math.max(2, Math.max(humans(), Math.min(MAX_PLAYERS, totalRacers)));
            totalRacers = total;
            for (int id = 0; id < MAX_PLAYERS && cars.size() < total; id++) {
                boolean used = false;
                for (Car c : cars) if (c.id == id) used = true;
                if (!used) cars.add(new Car(id, "BOT-" + (id + 1), true));
            }
            for (Car c : cars) place(c);
            winner = -1; raceTime = 0;
            countdown = COUNTDOWN_SECONDS;
            phase = 1;
        }

        synchronized void startRace(int total) {
            if (phase != 0) return;
            totalRacers = total;
            prepareGrid();
        }

        synchronized void rematch() {
            if (phase != 3) return;
            prepareGrid();
        }

        void discoveryLoop() {
            try (DatagramSocket ds = new DatagramSocket(null)) {
                ds.setReuseAddress(true);
                ds.bind(new InetSocketAddress(DISCOVERY_PORT));
                ds.setBroadcast(true); ds.setSoTimeout(1000);
                byte[] buf = new byte[512];
                while (running) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length); ds.receive(p);
                        String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (DISCOVERY.equals(s) && phase == 0) {
                            String host;
                            try { host = InetAddress.getLocalHost().getHostName().replace('|', ' '); }
                            catch (Exception e) { host = "Sunset Host"; }
                            byte[] response = ("SUNSET_RACERS_HERE|" + PORT + "|" + host + "|" + Math.max(1, humans()))
                                    .getBytes(StandardCharsets.UTF_8);
                            ds.send(new DatagramPacket(response, response.length, p.getAddress(), p.getPort()));
                        }
                    } catch (SocketTimeoutException ignored) {}
                }
            } catch (IOException ignored) {}
        }

        void simulate() {
            long last = System.nanoTime();
            while (running) {
                long now = System.nanoTime();
                double dt = Math.min(0.04, (now - last) / 1e9); last = now;
                step(dt);
                String snap = snapshot();
                for (ServerPeer p : peers) p.send(snap);
                try { Thread.sleep(16); } catch (InterruptedException ignored) {}
            }
        }

        synchronized void step(double dt) {
            tick++;
            if (phase == 1) {
                countdown -= dt;
                if (countdown <= 0) { phase = 2; countdown = 0; raceTime = 0; }
            } else if (phase == 2 || phase == 3) {
                if (phase == 2) raceTime += dt;
                for (Car c : cars) driveCar(c, dt);
                collisions();
                if (phase == 2) {
                    Car best = null;
                    for (Car c : cars) if (c.dist >= LAPS * Track.N && (best == null || c.dist > best.dist)) best = c;
                    if (best != null) { winner = best.id; phase = 3; }
                }
            }
        }

        ServerPeer peerFor(int id) { for (ServerPeer p : peers) if (p.id == id) return p; return null; }

        void driveCar(Car c, double dt) {
            double throttle = 0, steer = 0; boolean brake = false;
            double fwdNow = c.vx * Math.cos(c.angle) + c.vy * Math.sin(c.angle);
            if (phase == 2) {
                if (c.bot) {
                    double sp = Math.hypot(c.vx, c.vy);
                    int ahead = 7 + (int) (sp / 20);
                    double[] tp = Track.pos(c.idx + ahead, c.lane);
                    double desired = Math.atan2(tp[1] - c.y, tp[0] - c.x);
                    double diff = Track.norm(desired - c.angle);
                    steer = Math.max(-1, Math.min(1, diff * 2.4));
                    double tgt = c.botMax;
                    int look = 10 + (int) (sp / 5);
                    for (int k = 0; k < look; k += 3) tgt = Math.min(tgt, Track.CS[(c.idx + k) % Track.N]);
                    if (Math.abs(diff) > 0.8) tgt = Math.min(tgt, 90);
                    throttle = fwdNow < tgt ? 1 : (fwdNow > tgt + 30 ? -0.8 : 0);
                    if (sp < 12) c.stuck += dt; else c.stuck = 0;
                    if (c.stuck > 1.4) { c.reverseT = 0.9; c.stuck = 0; }
                    if (c.reverseT > 0) { c.reverseT -= dt; throttle = -1; steer = -steer; }
                } else {
                    ServerPeer p = peerFor(c.id);
                    if (p != null) { throttle = p.throttle; steer = p.steer; brake = p.brake; }
                }
            }
            double a = c.angle;
            double fwd = fwdNow;
            double rate = steer * (3.0 - 1.2 * Math.min(1, Math.abs(fwd) / 300)) * Math.min(1, Math.abs(fwd) / 55) * (fwd < 0 ? -1 : 1);
            if (brake) rate *= 1.55;           // handbrake: the rear steps out, the nose rotates faster
            a += rate * dt;
            c.angle = a;
            double fx = Math.cos(a), fy = Math.sin(a), rx = -fy, ry = fx;
            fwd = c.vx * fx + c.vy * fy;
            double lat = c.vx * rx + c.vy * ry;
            if (throttle > 0) fwd += throttle * 190 * dt;
            else if (throttle < 0) fwd += throttle * (fwd > 8 ? 330 : 110) * dt;
            fwd -= (0.0016 * fwd * Math.abs(fwd) + 0.12 * fwd) * dt;
            if (fwd < -90) fwd = -90;
            lat *= Math.exp(-(brake ? 1.3 : 9.0) * dt);   // low grip while handbrake is held = drift
            if (brake) fwd *= Math.exp(-0.95 * dt);
            if (c.offroad) { fwd *= Math.exp(-1.7 * dt); lat *= Math.exp(-2.0 * dt); }
            if (phase == 3) { fwd *= Math.exp(-1.8 * dt); lat *= Math.exp(-2.5 * dt); }
            c.drift = (brake && Math.abs(fwd) > 45) || Math.abs(lat) > 55;
            c.vx = fwd * fx + lat * rx;
            c.vy = fwd * fy + lat * ry;
            c.x += c.vx * dt;
            c.y += c.vy * dt;

            double[] nr = Track.nearest(c.x, c.y, c.idx);
            int ni = (int) nr[0];
            if (nr[1] > Track.WALL) {
                double cx = Track.X[ni], cy = Track.Y[ni];
                double nx = (c.x - cx) / nr[1], ny = (c.y - cy) / nr[1];
                c.x = cx + nx * Track.WALL; c.y = cy + ny * Track.WALL;
                double vn = c.vx * nx + c.vy * ny;
                if (vn > 0) { c.vx -= 1.5 * vn * nx; c.vy -= 1.5 * vn * ny; c.vx *= 0.8; c.vy *= 0.8; }
            }
            int d = ni - c.idx;
            if (d > Track.N / 2) d -= Track.N; else if (d < -Track.N / 2) d += Track.N;
            c.dist += d;
            c.idx = ni;
            c.offroad = nr[1] > Track.HALF - 2;
            c.speed = Math.hypot(c.vx, c.vy);
        }

        void collisions() {
            for (int i = 0; i < cars.size(); i++)
                for (int j = i + 1; j < cars.size(); j++) {
                    Car a = cars.get(i), b = cars.get(j);
                    double dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
                    if (d < 30 && d > 0.001) {
                        double nx = dx / d, ny = dy / d, o = (30 - d) / 2;
                        a.x -= nx * o; a.y -= ny * o; b.x += nx * o; b.y += ny * o;
                        double rv = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                        if (rv < 0) {
                            double j2 = -(1 + 0.5) * rv / 2;
                            a.vx -= j2 * nx; a.vy -= j2 * ny; b.vx += j2 * nx; b.vy += j2 * ny;
                        }
                    }
                }
        }

        synchronized String snapshot() {
            StringBuilder b = new StringBuilder("STATE|").append(tick).append('|').append(phase).append('|')
                    .append((float) countdown).append('|').append((float) raceTime).append('|').append(winner);
            for (Car c : cars) {
                b.append('|').append(c.id).append(',').append((float) c.x).append(',').append((float) c.y).append(',')
                 .append((float) c.angle).append(',').append((float) c.speed).append(',').append((float) c.dist).append(',')
                 .append(c.bot ? 1 : 0).append(',').append(c.drift ? 1 : 0).append(',').append(c.name.replace(',', ' '));
            }
            return b.toString();
        }
    }

    static class ServerPeer extends Thread {
        final Socket socket; final GameServer server;
        BufferedReader in; PrintWriter out;
        volatile double throttle, steer;
        volatile boolean brake, ready;
        volatile int id = -1;
        boolean host;
        ServerPeer(Socket s, GameServer g) { super("player connection"); socket = s; server = g; setDaemon(true); }
        void send(String s) { if (out != null && ready) out.println(s); }
        static boolean isLocal(InetAddress a) {
            try { return a.isLoopbackAddress() || a.isAnyLocalAddress() || NetworkInterface.getByInetAddress(a) != null; }
            catch (Exception e) { return false; }
        }
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                String hello = in.readLine();
                if (hello == null || !hello.startsWith("HELLO|")) return;
                String name = hello.substring(6).replace('|', ' ').replace(',', ' ');
                host = isLocal(socket.getInetAddress());
                id = server.addCar(name);
                if (id < 0) { out.println(server.phase != 0 ? "FULL|A race is already in progress" : "FULL|The race is full"); return; }
                out.println("WELCOME|" + id);
                ready = true;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("START|") && host) {
                        try { server.startRace(Integer.parseInt(line.substring(6).trim())); } catch (NumberFormatException ignored) {}
                    } else if (line.equals("REMATCH") && host) {
                        server.rematch();
                    } else if (line.startsWith("INPUT|")) {
                        String[] a = line.split("\\|");
                        if (a.length >= 3) {
                            try {
                                throttle = Math.max(-1, Math.min(1, Double.parseDouble(a[1])));
                                steer = Math.max(-1, Math.min(1, Double.parseDouble(a[2])));
                                brake = a.length > 3 && "1".equals(a[3]);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                ready = false;
                if (id >= 0) server.remove(this); else server.peers.remove(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    // =====================================================================
    //  CLIENT: RACE WINDOW
    // =====================================================================

    static class RaceFrame extends JFrame {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final int myId;
        final String myName;
        final GameServer hosted;
        final RacePanel panel;
        final Set<Integer> keys = new HashSet<>();
        volatile boolean running = true;
        volatile List<Car> cars = new ArrayList<>();
        volatile int phase = 0, winner = -1, humans = 0;
        volatile double countdown = 0, raceTime = 0;
        boolean setupOpen = false;
        int setupTotal = 2;
        final KeyEventDispatcher dispatcher;
        final javax.swing.Timer timer;

        RaceFrame(Socket socket, BufferedReader in, PrintWriter out, int id, String name, GameServer hosted) {
            super("SUNSET RACERS - Coastal Circuit");
            this.socket = socket; this.in = in; this.out = out; this.myId = id; this.myName = name; this.hosted = hosted;
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setSize(W, H); setMinimumSize(new Dimension(900, 650)); setLocationRelativeTo(null);
            panel = new RacePanel(this);
            setContentPane(panel);

            dispatcher = ev -> {
                if (!isActive()) return false;
                int k = ev.getKeyCode();
                if (ev.getID() == KeyEvent.KEY_PRESSED) { if (keys.add(k)) onKeyPressed(k); }
                else if (ev.getID() == KeyEvent.KEY_RELEASED) keys.remove(k);
                return false;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent e) { cleanup(); System.exit(0); }
                public void windowDeactivated(WindowEvent e) { keys.clear(); }
            });

            Thread reader = new Thread(this::readServer, "race state reader");
            reader.setDaemon(true); reader.start();

            timer = new javax.swing.Timer(16, e -> {
                double throttle = 0, steer = 0; boolean brake = false;
                if (phase == 2) {
                    throttle = (keys.contains(KeyEvent.VK_W) || keys.contains(KeyEvent.VK_UP) ? 1 : 0)
                             - (keys.contains(KeyEvent.VK_S) || keys.contains(KeyEvent.VK_DOWN) ? 1 : 0);
                    steer = (keys.contains(KeyEvent.VK_D) || keys.contains(KeyEvent.VK_RIGHT) ? 1 : 0)
                          - (keys.contains(KeyEvent.VK_A) || keys.contains(KeyEvent.VK_LEFT) ? 1 : 0);
                    brake = keys.contains(KeyEvent.VK_SPACE);
                }
                out.println("INPUT|" + throttle + "|" + steer + "|" + (brake ? 1 : 0));
                panel.tick();
                panel.repaint();
            });
            timer.start();
        }

        int minTotal() { return Math.max(2, humans); }

        void onKeyPressed(int k) {
            if (k == KeyEvent.VK_ESCAPE) {
                if (setupOpen) setupOpen = false; else leave();
                return;
            }
            if (hosted == null) return;
            if (phase == 0) {
                if (k == KeyEvent.VK_ENTER) {
                    if (!setupOpen) openSetup(); else startRace();
                } else if (setupOpen) {
                    if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A || k == KeyEvent.VK_DOWN || k == KeyEvent.VK_MINUS || k == KeyEvent.VK_SUBTRACT) changeTotal(-1);
                    else if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D || k == KeyEvent.VK_UP || k == KeyEvent.VK_EQUALS || k == KeyEvent.VK_PLUS || k == KeyEvent.VK_ADD) changeTotal(1);
                    else if (k >= KeyEvent.VK_2 && k <= KeyEvent.VK_4) setupTotal = Math.max(minTotal(), k - KeyEvent.VK_0);
                }
            } else if (phase == 3 && k == KeyEvent.VK_ENTER) {
                out.println("REMATCH");
            }
        }

        void openSetup() { setupOpen = true; setupTotal = Math.max(setupTotal, minTotal()); }
        void changeTotal(int d) { setupTotal = Math.max(minTotal(), Math.min(MAX_PLAYERS, setupTotal + d)); }
        void startRace() { setupOpen = false; out.println("START|" + Math.max(minTotal(), setupTotal)); }

        void onClick(double x, double y) {
            if (hosted == null) return;
            if (phase == 0) {
                if (!setupOpen) { if (RacePanel.LOBBY_BTN.contains(x, y)) openSetup(); }
                else if (RacePanel.MINUS.contains(x, y)) changeTotal(-1);
                else if (RacePanel.PLUS.contains(x, y)) changeTotal(1);
                else if (RacePanel.BACK.contains(x, y)) setupOpen = false;
                else if (RacePanel.GO.contains(x, y)) startRace();
            } else if (phase == 3 && RacePanel.REMATCH.contains(x, y)) {
                out.println("REMATCH");
            }
        }

        void readServer() {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (!line.startsWith("STATE|")) continue;
                    String[] a = line.split("\\|");
                    if (a.length < 6) continue;
                    List<Car> next = new ArrayList<>();
                    int hum = 0;
                    for (int i = 6; i < a.length; i++) {
                        String[] v = a[i].split(",", 9);
                        if (v.length < 9) continue;
                        Car c = new Car(Integer.parseInt(v[0]), v[8], "1".equals(v[6]));
                        c.x = Double.parseDouble(v[1]); c.y = Double.parseDouble(v[2]);
                        c.angle = Double.parseDouble(v[3]); c.speed = Double.parseDouble(v[4]);
                        c.dist = Double.parseDouble(v[5]); c.drift = "1".equals(v[7]);
                        if (!c.bot) hum++;
                        next.add(c);
                    }
                    humans = hum;
                    countdown = Double.parseDouble(a[3]);
                    raceTime = Double.parseDouble(a[4]);
                    winner = Integer.parseInt(a[5]);
                    cars = next;
                    phase = Integer.parseInt(a[2]);
                }
            } catch (Exception ignored) {
            } finally {
                boolean unexpected = running;
                running = false;
                if (unexpected) SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Connection to the race host was lost.", "SUNSET RACERS", JOptionPane.INFORMATION_MESSAGE);
                    backToMenu();
                });
            }
        }

        void cleanup() {
            running = false;
            timer.stop();
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
            try { socket.close(); } catch (Exception ignored) {}
            if (hosted != null) hosted.shutdown();
        }

        void backToMenu() {
            cleanup();
            dispose();
            new MenuFrame().setVisible(true);
        }

        void leave() { backToMenu(); }
    }

    // =====================================================================
    //  CLIENT: DRAWING
    // =====================================================================

    static class RacePanel extends JPanel {
        static final Rectangle LOBBY_BTN = new Rectangle(W / 2 - 140, H / 2 + 80, 280, 56);
        static final Rectangle MINUS = new Rectangle(W / 2 - 170, H / 2 - 80, 70, 70);
        static final Rectangle PLUS = new Rectangle(W / 2 + 100, H / 2 - 80, 70, 70);
        static final Rectangle BACK = new Rectangle(W / 2 - 230, H / 2 + 105, 150, 52);
        static final Rectangle GO = new Rectangle(W / 2 - 60, H / 2 + 105, 290, 52);
        static final Rectangle REMATCH = new Rectangle(W / 2 - 140, H / 2 + 150, 280, 54);

        final RaceFrame game;
        double sc = 1, ox = 0, oy = 0;
        final List<double[]> smoke = new ArrayList<>();
        final List<double[]> confetti = new ArrayList<>();
        final Random rnd = new Random();

        RacePanel(RaceFrame g) {
            game = g;
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    computeScale();
                    game.onClick((e.getX() - ox) / sc, (e.getY() - oy) / sc);
                }
            });
        }

        void computeScale() {
            sc = Math.min(getWidth() / (double) W, getHeight() / (double) H);
            ox = (getWidth() - W * sc) / 2; oy = (getHeight() - H * sc) / 2;
        }

        /** Per-frame particle update (smoke from drifting cars, confetti on a win). */
        void tick() {
            for (Car c : game.cars) {
                if (c.drift && Math.abs(c.speed) > 40) {
                    double bx = c.x - Math.cos(c.angle) * 13, by = c.y - Math.sin(c.angle) * 13;
                    smoke.add(new double[]{bx + rnd.nextGaussian() * 3, by + rnd.nextGaussian() * 3, 1.0});
                }
            }
            for (Iterator<double[]> it = smoke.iterator(); it.hasNext();) {
                double[] s = it.next();
                s[2] -= 0.035;
                if (s[2] <= 0) it.remove();
            }
            if (game.phase == 3 && game.winner == game.myId) {
                if (confetti.size() < 160)
                    for (int i = 0; i < 4; i++)
                        confetti.add(new double[]{rnd.nextDouble() * W, -10, rnd.nextGaussian() * 1.2, 2 + rnd.nextDouble() * 3, rnd.nextDouble() * 6, rnd.nextInt(CAR_COLORS.length)});
                for (Iterator<double[]> it = confetti.iterator(); it.hasNext();) {
                    double[] c = it.next();
                    c[0] += c[2]; c[1] += c[3]; c[4] += 0.2;
                    if (c[1] > H + 10) it.remove();
                }
            } else confetti.clear();
        }

        protected void paintComponent(Graphics gg) {
            super.paintComponent(gg);
            Graphics2D g = (Graphics2D) gg.create();
            g.setColor(new Color(18, 52, 46));
            g.fillRect(0, 0, getWidth(), getHeight());
            computeScale();
            g.translate(ox, oy);
            g.scale(sc, sc);
            g.setClip(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(Track.image(), 0, 0, W, H, null);
            for (double[] s : smoke) {
                float al = (float) Math.max(0, Math.min(1, s[2] * 0.45));
                double r = 5 + (1 - s[2]) * 9;
                g.setColor(new Color(235, 235, 235, (int) (al * 255)));
                g.fill(new Ellipse2D.Double(s[0] - r, s[1] - r, r * 2, r * 2));
            }
            List<Car> cs = new ArrayList<>(game.cars);
            for (Car c : cs) if (c.id != game.myId) drawCar(g, c, false);
            for (Car c : cs) if (c.id == game.myId) drawCar(g, c, true);
            drawHud(g, cs);
            drawOverlay(g, cs);
            g.dispose();
        }

        void drawCar(Graphics2D g, Car c, boolean me) {
            Graphics2D q = (Graphics2D) g.create();
            q.translate(c.x, c.y); q.rotate(c.angle);
            Color color = CAR_COLORS[Math.floorMod(c.id, CAR_COLORS.length)];
            q.setColor(new Color(0, 0, 0, 70)); q.fillRoundRect(-15, -8, 36, 22, 9, 9);
            q.setColor(color); q.fillRoundRect(-18, -11, 36, 22, 8, 8);
            q.setColor(new Color(255, 255, 255, 160)); q.fillRoundRect(-8, -9, 15, 18, 5, 5);
            q.setColor(new Color(34, 47, 57)); q.fillRoundRect(-7, -8, 12, 16, 4, 4);
            q.setColor(new Color(255, 248, 207)); q.fillRect(13, -7, 4, 5); q.fillRect(13, 2, 4, 5);
            q.setColor(c.drift ? new Color(255, 60, 60) : new Color(170, 40, 40)); q.fillRect(-18, -8, 2, 5); q.fillRect(-18, 3, 2, 5);
            q.setColor(new Color(30, 37, 42)); q.fillRect(-13, -13, 8, 3); q.fillRect(5, -13, 8, 3); q.fillRect(-13, 10, 8, 3); q.fillRect(5, 10, 8, 3);
            q.rotate(-c.angle);
            q.setFont(font(Font.BOLD, me ? 11 : 10));
            int tw = q.getFontMetrics().stringWidth(c.name);
            q.setColor(new Color(0, 0, 0, 120)); q.drawString(c.name, -tw / 2f + 1, -21);
            q.setColor(me ? GOLD : Color.WHITE); q.drawString(c.name, -tw / 2f, -22);
            q.dispose();
        }

        List<Car> standings(List<Car> cs) {
            List<Car> sorted = new ArrayList<>(cs);
            final int win = game.phase == 3 ? game.winner : -1;
            sorted.sort((a, b) -> {
                if (a.id == win) return -1;
                if (b.id == win) return 1;
                return Double.compare(b.dist, a.dist);
            });
            return sorted;
        }

        static int lap(Car c) { return Math.max(1, Math.min(LAPS, (int) Math.floor(c.dist / Track.N) + 1)); }

        void panel(Graphics2D g, int x, int y, int w, int h, int alpha) {
            g.setColor(new Color(6, 20, 32, alpha)); g.fillRoundRect(x, y, w, h, 22, 22);
            g.setColor(new Color(255, 255, 255, 30)); g.drawRoundRect(x, y, w, h, 22, 22);
        }

        void text(Graphics2D g, String s, int cx, int y, Font f, Color col) {
            g.setFont(f);
            int w = g.getFontMetrics().stringWidth(s);
            g.setColor(new Color(0, 0, 0, 90)); g.drawString(s, cx - w / 2 + 2, y + 2);
            g.setColor(col); g.drawString(s, cx - w / 2, y);
        }

        void button(Graphics2D g, Rectangle r, String label, Color bg, Color fg, int size) {
            g.setColor(bg); g.fillRoundRect(r.x, r.y, r.width, r.height, 16, 16);
            g.setFont(font(Font.BOLD, size));
            int w = g.getFontMetrics().stringWidth(label);
            g.setColor(fg); g.drawString(label, r.x + (r.width - w) / 2, r.y + r.height / 2 + size / 3 + 1);
        }

        void drawHud(Graphics2D g, List<Car> cs) {
            List<Car> order = standings(cs);
            Car me = null; int pos = 0;
            for (int i = 0; i < order.size(); i++) if (order.get(i).id == game.myId) { me = order.get(i); pos = i + 1; }

            panel(g, 18, 16, 330, 88, 215);
            g.setColor(GOLD); g.setFont(font(Font.BOLD, 22)); g.drawString("SUNSET RACERS", 34, 44);
            g.setColor(Color.WHITE); g.setFont(font(Font.PLAIN, 13));
            String status = game.phase == 0 ? "LOBBY  \u2022  " + game.humans + "/" + MAX_PLAYERS + " PLAYERS"
                    : game.phase == 1 ? "GET READY"
                    : game.phase == 2 ? "RACING  \u2022  FIRST TO " + LAPS + " LAPS WINS" : "RACE OVER";
            g.drawString(status, 34, 65);
            if (me != null) {
                g.setFont(font(Font.BOLD, 17));
                g.drawString("LAP " + lap(me) + "/" + LAPS + "    P" + pos + "/" + order.size() + "    "
                        + String.format(Locale.US, "%03d", (int) (Math.abs(me.speed) * 0.55)) + " km/h", 34, 91);
            }

            int h = 40 + order.size() * 24;
            panel(g, W - 280, 16, 260, h, 210);
            g.setColor(GOLD); g.setFont(font(Font.BOLD, 15)); g.drawString("DRIVERS", W - 262, 40);
            int y = 64;
            for (Car c : order) {
                g.setColor(CAR_COLORS[Math.floorMod(c.id, CAR_COLORS.length)]);
                g.fillRoundRect(W - 262, y - 11, 11, 11, 4, 4);
                g.setColor(Color.WHITE); g.setFont(font(c.id == game.myId ? Font.BOLD : Font.PLAIN, 13));
                g.drawString((c.id == game.myId ? "YOU \u2022 " : "") + c.name, W - 244, y);
                g.drawString("L" + lap(c), W - 52, y);
                y += 24;
            }

            panel(g, 18, H - 56, 350, 38, 190);
            g.setColor(new Color(239, 245, 244)); g.setFont(font(Font.PLAIN, 12));
            g.drawString("WASD / ARROWS  drive      SPACE  handbrake      ESC  menu", 30, H - 32);
        }

        void drawOverlay(Graphics2D g, List<Car> cs) {
            int phase = game.phase;
            if (phase == 0) {
                if (game.setupOpen) drawSetup(g);
                else drawLobby(g, cs);
            } else if (phase == 1) {
                double cd = game.countdown;
                int n = (int) Math.ceil(cd);
                double frac = cd - Math.floor(cd);
                if (n < 1) n = 1;
                int size = (int) (130 + 60 * frac);
                g.setColor(new Color(0, 0, 0, 90)); g.fillRoundRect(W / 2 - 150, H / 2 - 150, 300, 300, 50, 50);
                text(g, "GET READY", W / 2, H / 2 - 100, font(Font.BOLD, 26), Color.WHITE);
                Color col = n <= 2 ? new Color(255, 120, 90) : GOLD;
                text(g, String.valueOf(n), W / 2, H / 2 + size / 3 + 10, font(Font.BOLD, size), new Color(col.getRed(), col.getGreen(), col.getBlue(), (int) (255 - 120 * (1 - frac))));
            } else if (phase == 2 && game.raceTime < 1.2) {
                int al = (int) (255 * Math.max(0, 1 - game.raceTime / 1.2));
                text(g, "GO!", W / 2, H / 2 + 30, font(Font.BOLD, 150), new Color(110, 240, 150, al));
            } else if (phase == 3) {
                drawResults(g, cs);
            }
        }

        void drawLobby(Graphics2D g, List<Car> cs) {
            int x = W / 2 - 270, y = H / 2 - 165;
            panel(g, x, y, 540, 330, 220);
            text(g, "READY TO RACE?", W / 2, y + 52, font(Font.BOLD, 32), GOLD);
            g.setFont(font(Font.PLAIN, 15)); g.setColor(new Color(220, 235, 240));
            text(g, "Players connected: " + game.humans + "/" + MAX_PLAYERS, W / 2, y + 84, font(Font.PLAIN, 16), Color.WHITE);
            int ry = y + 112;
            List<Car> humansList = new ArrayList<>();
            for (Car c : cs) if (!c.bot) humansList.add(c);
            humansList.sort((a, b) -> Integer.compare(a.id, b.id));
            for (Car c : humansList) {
                g.setColor(CAR_COLORS[Math.floorMod(c.id, CAR_COLORS.length)]);
                g.fillRoundRect(W / 2 - 80, ry - 11, 12, 12, 4, 4);
                g.setColor(Color.WHITE); g.setFont(font(c.id == game.myId ? Font.BOLD : Font.PLAIN, 15));
                g.drawString(c.name + (c.id == game.myId ? "  (you)" : ""), W / 2 - 58, ry);
                ry += 24;
            }
            if (game.hosted != null) {
                button(g, LOBBY_BTN, "START RACE  (ENTER)", ORANGE, INK, 17);
                g.setColor(new Color(200, 220, 228)); g.setFont(font(Font.PLAIN, 12));
                text(g, "You choose how many racers - empty spots become computer drivers", W / 2, y + 310, font(Font.PLAIN, 12), new Color(200, 220, 228));
            } else {
                text(g, "Waiting for the host to start the race...", W / 2, y + 270, font(Font.PLAIN, 16), new Color(220, 235, 240));
            }
        }

        void drawSetup(Graphics2D g) {
            int x = W / 2 - 260, y = H / 2 - 185;
            panel(g, x, y, 520, 370, 235);
            text(g, "RACE SETUP", W / 2, y + 50, font(Font.BOLD, 32), GOLD);
            text(g, "How many racers on the grid?", W / 2, y + 84, font(Font.PLAIN, 16), Color.WHITE);
            boolean canMinus = game.setupTotal > game.minTotal(), canPlus = game.setupTotal < MAX_PLAYERS;
            button(g, MINUS, "-", canMinus ? ORANGE : new Color(90, 100, 108), INK, 40);
            button(g, PLUS, "+", canPlus ? ORANGE : new Color(90, 100, 108), INK, 40);
            text(g, String.valueOf(game.setupTotal), W / 2, H / 2 - 12 + 6, font(Font.BOLD, 72), Color.WHITE);
            int bots = game.setupTotal - game.humans;
            String desc = game.humans + (game.humans == 1 ? " human" : " humans") + "  +  " + bots + (bots == 1 ? " computer racer" : " computer racers");
            text(g, desc, W / 2, H / 2 + 56, font(Font.BOLD, 16), new Color(180, 235, 200));
            text(g, "LEFT / RIGHT or click  - +   (or press 2, 3, 4)", W / 2, H / 2 + 82, font(Font.PLAIN, 12), new Color(200, 220, 228));
            button(g, BACK, "BACK", new Color(70, 84, 94), Color.WHITE, 16);
            button(g, GO, "START RACE  (ENTER)", ORANGE, INK, 17);
        }

        void drawResults(Graphics2D g, List<Car> cs) {
            boolean win = game.winner == game.myId;
            List<Car> order = standings(cs);
            int myPos = 0; String winnerName = "";
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id == game.myId) myPos = i + 1;
                if (order.get(i).id == game.winner) winnerName = order.get(i).name;
            }
            g.setColor(new Color(5, 16, 25, 170)); g.fillRect(0, 0, W, H);
            for (double[] c : confetti) {
                Graphics2D q = (Graphics2D) g.create();
                q.translate(c[0], c[1]); q.rotate(c[4]);
                q.setColor(CAR_COLORS[(int) c[5]]); q.fillRect(-4, -2, 8, 4);
                q.dispose();
            }
            int x = W / 2 - 310, y = H / 2 - 240;
            panel(g, x, y, 620, 480, 225);
            if (win) {
                text(g, "YOU WIN!", W / 2, y + 86, font(Font.BOLD, 68), GOLD);
                text(g, "You took the checkered flag. Brilliant driving, " + game.myName + "!", W / 2, y + 122, font(Font.PLAIN, 17), Color.WHITE);
            } else {
                text(g, "YOU LOSE", W / 2, y + 84, font(Font.BOLD, 64), new Color(255, 112, 100));
                text(g, winnerName + " won the race - you finished " + ordinal(myPos) + ".", W / 2, y + 122, font(Font.PLAIN, 17), Color.WHITE);
            }
            text(g, "FINAL STANDINGS", W / 2, y + 162, font(Font.BOLD, 13), new Color(190, 210, 220));
            int ry = y + 176;
            for (int i = 0; i < order.size(); i++) {
                Car c = order.get(i);
                boolean mine = c.id == game.myId;
                g.setColor(mine ? new Color(255, 206, 116, 55) : new Color(255, 255, 255, 18));
                g.fillRoundRect(x + 60, ry, 500, 32, 12, 12);
                g.setColor(Color.WHITE); g.setFont(font(Font.BOLD, 16));
                g.drawString(String.valueOf(i + 1), x + 80, ry + 22);
                g.setColor(CAR_COLORS[Math.floorMod(c.id, CAR_COLORS.length)]);
                g.fillRoundRect(x + 112, ry + 10, 12, 12, 4, 4);
                g.setColor(mine ? GOLD : Color.WHITE); g.setFont(font(mine ? Font.BOLD : Font.PLAIN, 16));
                g.drawString(c.name + (mine ? "  (you)" : ""), x + 134, ry + 22);
                String st = c.id == game.winner ? "WINNER" : "Lap " + lap(c) + "/" + LAPS;
                g.setColor(c.id == game.winner ? GOLD : new Color(200, 215, 222));
                int sw = g.getFontMetrics().stringWidth(st);
                g.drawString(st, x + 540 - sw, ry + 22);
                ry += 38;
            }
            if (game.hosted != null) {
                button(g, REMATCH, "REMATCH  (ENTER)", ORANGE, INK, 18);
            } else {
                text(g, "Waiting for the host to start a rematch...", W / 2, y + 412, font(Font.PLAIN, 16), new Color(220, 235, 240));
            }
            text(g, "ESC  -  back to main menu", W / 2, y + 462, font(Font.PLAIN, 12), new Color(170, 195, 205));
        }

        static String ordinal(int n) {
            if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
            switch (n % 10) { case 1: return n + "st"; case 2: return n + "nd"; case 3: return n + "rd"; default: return n + "th"; }
        }
    }
}