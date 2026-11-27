package com.calendar;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SpriteCalendar extends JFrame {

    private static final String SPRITES_PATH = "/sprites/";
    private static final String SAVE_FILE = "calendar_save.dat";
    private static final String HASH_SALT = "SpriteCal_v1_secret_salt_9f3a";
    private static final int DEFAULT_PORT = 9876;

    private static final String DIGIT_PREFIX = "digit_";
    private static final String DIGIT_SUFFIX = ".png";
    private static final String DAY_BG_1 = "day_bg_1.png";
    private static final String DAY_BG_2 = "day_bg_2.png";
    private static final String DAY_BG_3 = "day_bg_3.png";
    private static final String DAY_BG_TODAY = "day_bg_selected.png";
    private static final String ARROW_LEFT = "arrow_left.png";
    private static final String ARROW_RIGHT = "arrow_right.png";
    private static final String MONTH_LABEL = "month_label.png";

    private static final int WINDOW_W = 1280;
    private static final int WINDOW_H = 920;

    private YearMonth currentMonth;
    private final Set<String> deletedDayHashes = new LinkedHashSet<>();
    private final Map<LocalDate, List<EventItem>> events = new HashMap<>();
    private final Map<LocalDate, PollItem> polls = new HashMap<>();
    private final List<String> eventTypes = new ArrayList<>();

    private final ImageIcon[] digitIcons = new ImageIcon[10];
    private final ImageIcon[] dayBgVariants = new ImageIcon[3];
    private ImageIcon dayBgTodayIcon;
    private ImageIcon arrowLeftIcon;
    private ImageIcon arrowRightIcon;
    private ImageIcon monthLabelIcon;
    private final List<ImageIcon> deleteAnimFrames = new ArrayList<>();

    private JPanel daysPanel;
    private JPanel monthYearPanel;
    private JLabel networkStatusLabel;
    private final Random random = new Random();

    // ---- Сеть ----
    private volatile boolean networkRunning = false;
    private ServerSocket serverSocket;
    private final List<PeerConnection> peers = new CopyOnWriteArrayList<>();
    private static final String SHARED_DIR = "shared";
    private final DefaultListModel<String> sharedFilesModel = new DefaultListModel<>();
    private JList<String> sharedFilesList;
    private Thread acceptThread;

    public static class EventItem {
        public String text;
        public String colorHex;
        public String type;

        public EventItem(String text, String colorHex, String type) {
            this.text = text;
            this.colorHex = colorHex;
            this.type = type;
        }
    }

    public static class PollItem {
        public String question;
        public List<String> options = new ArrayList<>();
        public List<Integer> votes = new ArrayList<>();
        public boolean voted = false;

        public PollItem(String question, List<String> options, List<Integer> votes) {
            this.question = question;
            this.options = options;
            this.votes = votes;
            this.voted = false;
        }
    }

    private class PeerConnection {
        final Socket socket;
        final BufferedReader in;
        final PrintWriter out;
        final Thread readerThread;
        volatile boolean alive = true;

        PeerConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.readerThread = new Thread(this::readLoop, "peer-reader");
            this.readerThread.setDaemon(true);
            this.readerThread.start();
        }

        void readLoop() {
            try {
                String line;
                while (alive && (line = in.readLine()) != null) {
                    if ("SYNC".equals(line)) {
                        StringBuilder body = new StringBuilder();
                        while ((line = in.readLine()) != null && !"END".equals(line)) {
                            body.append(line).append("\n");
                        }
                        final String data = body.toString();
                        SwingUtilities.invokeLater(() -> applyRemoteState(data));
                    } else if ("PING".equals(line)) {
                        out.println("PONG");
                    } else if ("FILELIST".equals(line)) {
                        List<String> names = new ArrayList<>();
                        while ((line = in.readLine()) != null && !"END".equals(line)) {
                            if (!line.isBlank()) names.add(line.trim());
                        }
                        SwingUtilities.invokeLater(() -> {
                            for (String n : names) {
                                if (!containsFileName(n)) sharedFilesModel.addElement(n);
                            }
                        });
                    } else if ("GETFILE".equals(line)) {
                            String fileName = in.readLine();
                            if (fileName != null) sendFileToPeer(this, fileName.trim());
                        }
                        else if ("FILEDATA".equals(line)) {
                        String fileName = in.readLine();
                        String sizeStr = in.readLine();
                        String b64 = (sizeStr != null && Integer.parseInt(sizeStr.trim()) > 0) ? in.readLine() : "";
                        String end = in.readLine(); // ENDFILE
                        if (fileName != null && b64 != null && "ENDFILE".equals(end)) {
                            final String fn = fileName.trim();
                            final String data = b64.trim();
                            SwingUtilities.invokeLater(() -> receiveFile(fn, data));
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        void sendSync(String data) {
            try {
                out.println("SYNC");
                for (String line : data.split("\n", -1)) {
                    out.println(line);
                }
                out.println("END");
                out.flush();
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            alive = false;
            try { socket.close(); } catch (Exception ignored) {}
            peers.remove(this);
            SwingUtilities.invokeLater(() -> updateNetworkStatus());
        }
    }

    public SpriteCalendar() {
        currentMonth = YearMonth.now();
        eventTypes.add("Обычное");
        eventTypes.add("Важное");
        eventTypes.add("Работа");
        loadSprites();
        loadSave();
        initUI();
        updateCalendar();
        updateNetworkStatus();
    }

    private boolean containsFileName(String name) {
            for (int i = 0; i < sharedFilesModel.size(); i++) {
                if (sharedFilesModel.get(i).equals(name)) return true;
            }
            return false;
        }

    private Path sharedPath() {
        Path p = Paths.get(System.getProperty("user.dir"), SHARED_DIR);
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }

    private void refreshLocalSharedList() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sharedPath())) {
            for (Path f : ds) {
                if (!Files.isRegularFile(f)) continue;
                String name = f.getFileName().toString();
                if (!containsFileName(name)) sharedFilesModel.addElement(name);
            }
        } catch (Exception ignored) {}
    }


    private void broadcastFileList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sharedFilesModel.size(); i++) {
            sb.append(sharedFilesModel.get(i)).append("\n");
        }
        for (PeerConnection p : peers) {
            if (!p.alive) continue;
            p.out.println("FILELIST");
            p.out.print(sb);
            p.out.println("END");
            p.out.flush();
        }
    }

    private void requestFileDownload(String fileName) {
        for (PeerConnection p : peers) {
            if (!p.alive) continue;
            p.out.println("GETFILE");
            p.out.println(fileName);
            p.out.flush();
            return; // первый живой пир
        }
        JOptionPane.showMessageDialog(this, "Нет подключённых пиров.");
    }

    /** Вызывается из потока чтения PeerConnection */
    private void sendFileToPeer(PeerConnection target, String fileName) {
    Path shared = sharedPath();
    Path file = shared.resolve(fileName).normalize();
    if (!file.startsWith(shared) || !Files.isRegularFile(file)) {
        // сообщаем, что файла нет — пустой FILEDATA с size=0, чтобы клиент не завис
        target.out.println("FILEDATA");
        target.out.println(fileName);
        target.out.println("0");
        target.out.println("");
        target.out.println("ENDFILE");
        target.out.flush();
        return;
    }
    try {
        byte[] bytes = Files.readAllBytes(file);
        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
        target.out.println("FILEDATA");
        target.out.println(fileName);
        target.out.println(bytes.length);
        target.out.println(b64);
        target.out.println("ENDFILE");
        target.out.flush();
    } catch (Exception e) {
        System.err.println("Отправка файла: " + e.getMessage());
    }
}


    private void receiveFile(String fileName, String base64) {
        try {
            if (fileName == null || fileName.isEmpty()) return;
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) return;
            byte[] bytes = base64 == null || base64.isEmpty()
                    ? new byte[0]
                    : java.util.Base64.getDecoder().decode(base64);
            Path dest = sharedPath().resolve(fileName);
            Files.write(dest, bytes);
            if (!containsFileName(fileName)) sharedFilesModel.addElement(fileName);
            JOptionPane.showMessageDialog(SpriteCalendar.this,
                    "Скачан файл: " + fileName + "\nПапка: " + sharedPath());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(SpriteCalendar.this,
                    "Ошибка приёма файла: " + e.getMessage());
        }
    }


    private void uploadSharedFile() {
        JFileChooser ch = new JFileChooser();
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path src = ch.getSelectedFile().toPath();
        try {
            Path dest = sharedPath().resolve(src.getFileName().toString());
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            String name = dest.getFileName().toString();
            if (!containsFileName(name)) sharedFilesModel.addElement(name);
            broadcastFileList();
            // сразу разослать содержимое
            for (PeerConnection p : peers) if (p.alive) sendFileToPeer(p, name);
            JOptionPane.showMessageDialog(this, "Файл добавлен в shared и отправлен пирам.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка: " + e.getMessage());
        }
    }


    // ------------------------------------------------------------------
    // Спрайты
    // ------------------------------------------------------------------
    private void loadSprites() {
        for (int i = 0; i < 10; i++) {
            digitIcons[i] = loadIcon(DIGIT_PREFIX + i + DIGIT_SUFFIX);
        }
        dayBgVariants[0] = loadIcon(DAY_BG_1);
        dayBgVariants[1] = loadIcon(DAY_BG_2);
        dayBgVariants[2] = loadIcon(DAY_BG_3);
        dayBgTodayIcon = loadIcon(DAY_BG_TODAY);
        if (dayBgTodayIcon == null) dayBgTodayIcon = dayBgVariants[0];
        arrowLeftIcon = loadIcon(ARROW_LEFT);
        arrowRightIcon = loadIcon(ARROW_RIGHT);
        monthLabelIcon = loadIcon(MONTH_LABEL);

        for (int i = 0; i < 32; i++) {
            ImageIcon frame = loadIcon("delete_anim/" + i + ".png");
            if (frame == null) break;
            deleteAnimFrames.add(frame);
        }
    }

    private ImageIcon loadIcon(String name) {
        java.net.URL url = getClass().getResource(SPRITES_PATH + name);
        if (url == null) return null;
        return new ImageIcon(url);
    }

    // ------------------------------------------------------------------
    // Сохранение
    // ------------------------------------------------------------------
    private Path savePath() {
        return Paths.get(System.getProperty("user.dir"), SAVE_FILE);
    }

    private void loadSave() {
        Path p = savePath();
        if (!Files.exists(p)) return;
        try {
            applyStateText(Files.readString(p, StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("Ошибка загрузки: " + e.getMessage());
        }
    }

    private void applyStateText(String text) {
        deletedDayHashes.clear();
        events.clear();
        polls.clear();
        // types не очищаем полностью — мержим
        String section = "";
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            switch (section) {
                case "deleted":
                    deletedDayHashes.add(line);
                    break;
                case "types":
                    if (!eventTypes.contains(line)) eventTypes.add(line);
                    break;
                case "events": {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length >= 4) {
                        try {
                            LocalDate d = LocalDate.parse(parts[0]);
                            events.computeIfAbsent(d, k -> new ArrayList<>())
                                    .add(new EventItem(parts[1], parts[2], parts[3]));
                        } catch (Exception ignored) {}
                    }
                    break;
                }
                case "polls": {
                    String[] parts = line.split("\\|", -1);
                    if (parts.length >= 4) {
                        try {
                            LocalDate d = LocalDate.parse(parts[0]);
                            List<String> opts = new ArrayList<>(Arrays.asList(parts[2].split(";")));
                            List<Integer> votes = new ArrayList<>();
                            for (String v : parts[3].split(";")) {
                                try { votes.add(Integer.parseInt(v.trim())); }
                                catch (Exception ignored) { votes.add(0); }
                            }
                            while (votes.size() < opts.size()) votes.add(0);
                            PollItem pi = new PollItem(parts[1], opts, votes);
                            if (parts.length >= 5) pi.voted = "1".equals(parts[4].trim());
                            polls.put(d, pi);
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }
        }
    }

    private String buildStateText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SpriteCalendar save\n");
        sb.append("[deleted]\n");
        for (String h : deletedDayHashes) sb.append(h).append("\n");
        sb.append("[types]\n");
        for (String t : eventTypes) sb.append(t).append("\n");
        sb.append("[events]\n");
        for (Map.Entry<LocalDate, List<EventItem>> e : events.entrySet()) {
            for (EventItem ev : e.getValue()) {
                sb.append(e.getKey()).append("|")
                        .append(esc(ev.text)).append("|")
                        .append(ev.colorHex).append("|")
                        .append(esc(ev.type)).append("\n");
            }
        }
        sb.append("[polls]\n");
        for (Map.Entry<LocalDate, PollItem> e : polls.entrySet()) {
            PollItem p = e.getValue();
            sb.append(e.getKey()).append("|")
                    .append(esc(p.question)).append("|")
                    .append(String.join(";", p.options)).append("|");
            List<String> vs = new ArrayList<>();
            for (int v : p.votes) vs.add(String.valueOf(v));
            sb.append(String.join(";", vs)).append("|")
                    .append(p.voted ? "1" : "0").append("\n");
        }
        return sb.toString();
    }

    private void saveAll() {
        try {
            String data = buildStateText();
            Files.write(savePath(), data.getBytes(StandardCharsets.UTF_8));
            broadcastSync(data);
        } catch (Exception e) {
            System.err.println("Ошибка сохранения: " + e.getMessage());
        }
    }

    private void applyRemoteState(String data) {
        applyStateText(data);
        try {
            Files.write(savePath(), data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        updateCalendar();
    }

    private static String esc(String s) {
        return s.replace("|", "/").replace("\n", " ").replace(";", ",");
    }

    private String hashDay(LocalDate date) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((date.toString() + "|" + HASH_SALT).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : dig) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return date.toString() + "_fallback";
        }
    }

    private boolean isDeleted(LocalDate date) {
        return deletedDayHashes.contains(hashDay(date));
    }

    private int deletedCount() {
        return deletedDayHashes.size();
    }

    // ------------------------------------------------------------------
    // Сеть
    // ------------------------------------------------------------------
    private void broadcastSync(String data) {
        for (PeerConnection p : peers) {
            if (p.alive) p.sendSync(data);
        }
    }

    private void startHost(int port) {
        stopNetwork();
        try {
            serverSocket = new ServerSocket(port);
            networkRunning = true;
            acceptThread = new Thread(() -> {
                while (networkRunning) {
                    try {
                        Socket s = serverSocket.accept();
                        PeerConnection peer = new PeerConnection(s);
                        peers.add(peer);
                        // сразу отправляем своё состояние новому клиенту
                        peer.sendSync(buildStateText());
                         // список файлов новому клиенту
                        peer.out.println("FILELIST");
                        for (int i = 0; i < sharedFilesModel.size(); i++) {
                            peer.out.println(sharedFilesModel.get(i));
                        }
                        peer.out.println("END");
                        peer.out.flush();
                        SwingUtilities.invokeLater(this::updateNetworkStatus);
                    } catch (IOException e) {
                        if (networkRunning) e.printStackTrace();
                        break;
                    }
                }
            }, "accept-thread");
            acceptThread.setDaemon(true);
            acceptThread.start();
            updateNetworkStatus();
            JOptionPane.showMessageDialog(this,
                    "Хост запущен на порту " + port + "\nСообщи IP и порт друзьям.",
                    "Сеть", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Не удалось открыть порт " + port + ":\n" + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void joinHost(String host, int port) {
        stopNetwork();
        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, port), 5000);
                PeerConnection peer = new PeerConnection(s);
                peers.add(peer);
                networkRunning = true;
                // отправляем своё состояние хосту (он тоже может смержить — сейчас просто шлём)
                peer.sendSync(buildStateText());
                SwingUtilities.invokeLater(() -> {
                    refreshLocalSharedList();
                    broadcastFileList();
                });
                SwingUtilities.invokeLater(() -> {
                    updateNetworkStatus();
                    JOptionPane.showMessageDialog(SpriteCalendar.this,
                            "Подключено к " + host + ":" + port,
                            "Сеть", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(SpriteCalendar.this,
                                "Не удалось подключиться:\n" + e.getMessage(),
                                "Ошибка", JOptionPane.ERROR_MESSAGE));
            }
        }, "join-thread").start();
    }

    private void stopNetwork() {
        networkRunning = false;
        for (PeerConnection p : new ArrayList<>(peers)) p.close();
        peers.clear();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        updateNetworkStatus();
    }

    private void updateNetworkStatus() {
        if (networkStatusLabel == null) return;
        int n = 0;
        for (PeerConnection p : peers) if (p.alive) n++;
        if (serverSocket != null && !serverSocket.isClosed()) {
            networkStatusLabel.setText("Хост · пиров: " + n);
            networkStatusLabel.setForeground(new Color(30, 130, 60));
        } else if (n > 0) {
            networkStatusLabel.setText("Клиент · подключено");
            networkStatusLabel.setForeground(new Color(30, 100, 180));
        } else {
            networkStatusLabel.setText("Офлайн");
            networkStatusLabel.setForeground(Color.GRAY);
        }
    }

    private void openNetworkMenu() {
        refreshLocalSharedList();

        JDialog d = new JDialog(this, "Сеть — синхронизация и файлы", true);
        d.setSize(520, 420);
        d.setLocationRelativeTo(this);
        d.setLayout(new BorderLayout(10, 10));
        d.getContentPane().setBackground(Color.WHITE);

        // ---- Верх: хост / IP / порт ----
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(12, 16, 8, 16));
        top.setBackground(Color.WHITE);

        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        portRow.setOpaque(false);
        portRow.add(new JLabel("Порт:"));
        JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 6);
        portRow.add(portField);
        top.add(portRow);

        JPanel ipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ipRow.setOpaque(false);
        ipRow.add(new JLabel("IP хоста:"));
        JTextField ipField = new JTextField("127.0.0.1", 14);
        ipRow.add(ipField);
        top.add(ipRow);

        try {
            String localIp = InetAddress.getLocalHost().getHostAddress();
            JLabel myIp = new JLabel("Твой IP: " + localIp);
            myIp.setFont(new Font("SansSerif", Font.PLAIN, 12));
            top.add(myIp);
        } catch (Exception ignored) {}

        d.add(top, BorderLayout.NORTH);

        // ---- Центр: список файлов в рамке + кнопка «Скачать» справа ----
        JPanel center = new JPanel(new BorderLayout(8, 0));
        center.setBorder(new EmptyBorder(8, 16, 8, 16));
        center.setBackground(Color.WHITE);

        sharedFilesList = new JList<>(sharedFilesModel);
        sharedFilesList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(sharedFilesList);
        listScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(160, 160, 170)),
                "Загруженные файлы (папка shared)"));
        center.add(listScroll, BorderLayout.CENTER);

        JPanel rightBtns = new JPanel();
        rightBtns.setLayout(new BoxLayout(rightBtns, BoxLayout.Y_AXIS));
        rightBtns.setBackground(Color.WHITE);

        JButton downloadBtn = new JButton("Скачать");
        downloadBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        downloadBtn.addActionListener(e -> {
            String name = sharedFilesList.getSelectedValue();
            if (name == null) {
                JOptionPane.showMessageDialog(d, "Выбери файл в списке.");
                return;
            }
            // если файл уже есть локально — просто открыть папку / сообщение
            Path local = sharedPath().resolve(name);
            if (Files.isRegularFile(local)) {
                JOptionPane.showMessageDialog(d, "Уже есть локально:\n" + local);
            } else {
                requestFileDownload(name);
            }
        });

        JButton uploadBtn = new JButton("Загрузить…");
        uploadBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        uploadBtn.addActionListener(e -> uploadSharedFile());

        JButton refreshBtn = new JButton("Обновить");
        refreshBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        refreshBtn.addActionListener(e -> {
            refreshLocalSharedList();
            broadcastFileList();
        });

        rightBtns.add(downloadBtn);
        rightBtns.add(Box.createVerticalStrut(8));
        rightBtns.add(uploadBtn);
        rightBtns.add(Box.createVerticalStrut(8));
        rightBtns.add(refreshBtn);
        center.add(rightBtns, BorderLayout.EAST);

        d.add(center, BorderLayout.CENTER);

        // ---- Низ: сеть ----
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        buttons.setBackground(Color.WHITE);

        JButton hostBtn = new JButton("Стать хостом");
        JButton joinBtn = new JButton("Подключиться");
        JButton stopBtn = new JButton("Отключиться");
        JButton syncBtn = new JButton("Синхронизировать календарь");

        hostBtn.addActionListener(e -> {
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (Exception ex) { port = DEFAULT_PORT; }
            startHost(port);
            broadcastFileList();
        });
        joinBtn.addActionListener(e -> {
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (Exception ex) { port = DEFAULT_PORT; }
            String ip = ipField.getText().trim();
            if (ip.isEmpty()) ip = "127.0.0.1";
            joinHost(ip, port);
        });
        stopBtn.addActionListener(e -> stopNetwork());
        syncBtn.addActionListener(e -> {
            broadcastSync(buildStateText());
            broadcastFileList();
            JOptionPane.showMessageDialog(d, "Календарь и список файлов отправлены.");
        });

        buttons.add(hostBtn);
        buttons.add(joinBtn);
        buttons.add(syncBtn);
        buttons.add(stopBtn);
        d.add(buttons, BorderLayout.SOUTH);

        d.setVisible(true);
    }


    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------
    private void initUI() {
        setTitle("Sprite Calendar");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);
        setSize(WINDOW_W, WINDOW_H);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Color.WHITE);
        setLayout(new BorderLayout(0, 0));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(Color.WHITE);
        headerPanel.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 30));
        headerPanel.setPreferredSize(new Dimension(WINDOW_W, 90));

        // Слева: кнопка «Сеть» + статус (на одном уровне с месяцем)
        JPanel leftHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        leftHeader.setOpaque(false);

        JButton netBtn = new JButton("Сеть");
        netBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        netBtn.setFocusPainted(false);
        netBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        netBtn.addActionListener(e -> openNetworkMenu());
        leftHeader.add(netBtn);

        networkStatusLabel = new JLabel("Офлайн");
        networkStatusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        networkStatusLabel.setForeground(Color.GRAY);
        leftHeader.add(networkStatusLabel);

        if (monthLabelIcon != null) {
            leftHeader.add(Box.createHorizontalStrut(8));
            leftHeader.add(new JLabel(monthLabelIcon));
        }
        headerPanel.add(leftHeader, BorderLayout.WEST);

        monthYearPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 6));
        monthYearPanel.setOpaque(false);
        headerPanel.add(monthYearPanel, BorderLayout.CENTER);
        add(headerPanel, BorderLayout.NORTH);

        daysPanel = new JPanel(new GridLayout(0, 7, 6, 4));
        daysPanel.setBackground(Color.WHITE);
        daysPanel.setBorder(BorderFactory.createEmptyBorder(6, 40, 10, 40));
        add(daysPanel, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 80, 15));
        bottom.setBackground(Color.WHITE);
        bottom.setPreferredSize(new Dimension(WINDOW_W, 90));

        JButton prevBtn = createSpriteButton(arrowLeftIcon, "◀");
        prevBtn.addActionListener(e -> {
            currentMonth = currentMonth.minusMonths(1);
            updateCalendar();
        });
        JButton nextBtn = createSpriteButton(arrowRightIcon, "▶");
        nextBtn.addActionListener(e -> {
            currentMonth = currentMonth.plusMonths(1);
            updateCalendar();
        });
        bottom.add(prevBtn);
        bottom.add(nextBtn);
        add(bottom, BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopNetwork();
            }
        });
    }

    private JButton createSpriteButton(ImageIcon icon, String fallbackText) {
        JButton btn = new JButton();
        if (icon != null) {
            btn.setIcon(icon);
            int w = Math.max(icon.getIconWidth() + 16, 70);
            int h = Math.max(icon.getIconHeight() + 16, 70);
            btn.setPreferredSize(new Dimension(w, h));
        } else {
            btn.setText(fallbackText);
            btn.setFont(new Font("SansSerif", Font.BOLD, 32));
            btn.setPreferredSize(new Dimension(80, 70));
        }
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBackground(Color.WHITE);
        return btn;
    }

    private void updateCalendar() {
        updateHeader();
        daysPanel.removeAll();

        String[] weekDays = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};
        for (String d : weekDays) {
            JLabel lbl = new JLabel(d, SwingConstants.CENTER);
            lbl.setForeground(new Color(80, 80, 80));
            lbl.setFont(new Font("SansSerif", Font.BOLD, 18));
            daysPanel.add(lbl);
        }

        LocalDate first = currentMonth.atDay(1);
        int dayOfWeek = first.getDayOfWeek().getValue();
        for (int i = 1; i < dayOfWeek; i++) {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            daysPanel.add(empty);
        }

        LocalDate today = LocalDate.now();
        int daysInMonth = currentMonth.lengthOfMonth();

        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = currentMonth.atDay(day);
            if (isDeleted(date)) {
                JPanel empty = new JPanel();
                empty.setOpaque(false);
                daysPanel.add(empty);
                continue;
            }

            boolean isToday = date.equals(today);
            boolean hasEvents = events.containsKey(date) && !events.get(date).isEmpty();
            boolean hasPoll = polls.containsKey(date);

            JPanel cell = createDayCell(day, isToday, hasEvents, hasPoll);
            final LocalDate clickedDate = date;
            cell.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openDayMenu(clickedDate);
                }
            });
            daysPanel.add(cell);
        }

        daysPanel.revalidate();
        daysPanel.repaint();
    }

    private void updateHeader() {
        monthYearPanel.removeAll();
        int month = currentMonth.getMonthValue();
        int year = currentMonth.getYear();

        if (month >= 10) monthYearPanel.add(makeDigitLabel(month / 10));
        monthYearPanel.add(makeDigitLabel(month % 10));
        monthYearPanel.add(Box.createHorizontalStrut(16));
        monthYearPanel.add(makeDigitLabel(year / 1000));
        monthYearPanel.add(makeDigitLabel((year / 100) % 10));
        monthYearPanel.add(makeDigitLabel((year / 10) % 10));
        monthYearPanel.add(makeDigitLabel(year % 10));

        String monthName = currentMonth.getMonth()
                .getDisplayName(TextStyle.FULL_STANDALONE, new Locale("ru"));
        JLabel nameLbl = new JLabel("  " + monthName);
        nameLbl.setForeground(new Color(40, 40, 40));
        nameLbl.setFont(new Font("SansSerif", Font.PLAIN, 22));
        monthYearPanel.add(nameLbl);

        monthYearPanel.revalidate();
        monthYearPanel.repaint();
    }

    private JLabel makeDigitLabel(int digit) {
        if (digitIcons[digit] != null) return new JLabel(digitIcons[digit]);
        JLabel lbl = new JLabel(String.valueOf(digit));
        lbl.setFont(new Font("SansSerif", Font.BOLD, 28));
        lbl.setForeground(Color.BLACK);
        return lbl;
    }

    private JPanel createDayCell(int day, boolean isToday, boolean hasEvents, boolean hasPoll) {
        JPanel cell = new JPanel(new GridBagLayout());
        cell.setOpaque(false);
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        ImageIcon bg;
        if (isToday && dayBgTodayIcon != null) {
            bg = dayBgTodayIcon;
        } else {
            int idx = Math.floorMod(day * 31 + currentMonth.getMonthValue() * 7, 3);
            bg = dayBgVariants[idx];
            if (bg == null) {
                for (ImageIcon v : dayBgVariants) if (v != null) { bg = v; break; }
            }
        }

        if (bg != null) {
            JLabel bgLabel = new JLabel(bg);
            bgLabel.setLayout(new GridBagLayout());
            bgLabel.add(buildDayContent(day, hasEvents, hasPoll));
            cell.add(bgLabel);
        } else {
            cell.setBackground(Color.WHITE);
            cell.setOpaque(true);
            cell.setBorder(BorderFactory.createLineBorder(
                    isToday ? new Color(255, 140, 0) : new Color(200, 200, 200),
                    isToday ? 2 : 1));
            cell.add(buildDayContent(day, hasEvents, hasPoll));
        }
        return cell;
    }

    private JPanel buildDayContent(int day, boolean hasEvents, boolean hasPoll) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        JPanel dayDigits = createDigitPanel(day);
        dayDigits.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(dayDigits);

        if (hasEvents || hasPoll) {
            content.add(Box.createVerticalStrut(4));
            JPanel dots = new JPanel(new FlowLayout(FlowLayout.CENTER, 3, 0));
            dots.setOpaque(false);
            if (hasEvents) {
                JPanel d = new JPanel() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(255, 140, 0));
                        g2.fillOval(0, 0, 8, 8);
                    }
                };
                d.setPreferredSize(new Dimension(8, 8));
                d.setOpaque(false);
                dots.add(d);
            }
            if (hasPoll) {
                JPanel d = new JPanel() {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g;
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(80, 140, 255));
                        g2.fillOval(0, 0, 8, 8);
                    }
                };
                d.setPreferredSize(new Dimension(8, 8));
                d.setOpaque(false);
                dots.add(d);
            }
            dots.setAlignmentX(Component.CENTER_ALIGNMENT);
            content.add(dots);
        }
        return content;
    }

    private JPanel createDigitPanel(int number) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 1, 0));
        p.setOpaque(false);
        if (number >= 10) p.add(makeDigitLabel(number / 10));
        p.add(makeDigitLabel(number % 10));
        return p;
    }

    // ------------------------------------------------------------------
    // Меню дня
    // ------------------------------------------------------------------
    private void openDayMenu(LocalDate date) {
        JDialog dialog = new JDialog(this, "День — " + date, true);
        dialog.setSize(520, 560);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.getContentPane().setBackground(Color.WHITE);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBackground(Color.WHITE);
        center.setBorder(new EmptyBorder(12, 16, 8, 16));

        PollItem poll = polls.get(date);
        JPanel pollPanel = new JPanel();
        pollPanel.setLayout(new BoxLayout(pollPanel, BoxLayout.Y_AXIS));
        pollPanel.setBackground(new Color(240, 245, 255));
        pollPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(160, 180, 220)),
                new EmptyBorder(10, 12, 10, 12)));
        pollPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        refreshPollPanel(pollPanel, poll);
        center.add(pollPanel);
        center.add(Box.createVerticalStrut(12));

        JLabel evTitle = new JLabel("События");
        evTitle.setFont(new Font("SansSerif", Font.BOLD, 15));
        evTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(evTitle);
        center.add(Box.createVerticalStrut(6));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        List<EventItem> evList = events.computeIfAbsent(date, k -> new ArrayList<>());
        for (EventItem ev : evList) {
            listModel.addElement("[" + ev.type + "] " + ev.text);
        }
        JList<String> eventList = new JList<>(listModel);
        eventList.setFont(new Font("SansSerif", Font.PLAIN, 14));
        eventList.setSelectionBackground(new Color(200, 220, 255));
        eventList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (index >= 0 && index < evList.size()) {
                    try {
                        Color col = Color.decode(evList.get(index).colorHex);
                        setForeground(isSelected ? Color.WHITE : col.darker());
                    } catch (Exception ignored) {}
                }
                return c;
            }
        });
        JScrollPane scroll = new JScrollPane(eventList);
        scroll.setPreferredSize(new Dimension(460, 200));
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(scroll);

        dialog.add(new JScrollPane(center), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        buttons.setBackground(Color.WHITE);

        JButton addEventBtn = new JButton("Добавить событие");
        JButton addPollBtn = new JButton("Добавить опрос");
        JButton deleteDayBtn = new JButton("Удалить день");
        deleteDayBtn.setForeground(new Color(180, 40, 40));

        addEventBtn.addActionListener(e -> showAddEventDialog(date, listModel, evList));
        addPollBtn.addActionListener(e -> showAddPollDialog(date, pollPanel, dialog));
        deleteDayBtn.addActionListener(e -> {
            int conf = JOptionPane.showConfirmDialog(dialog,
                    "Удалить этот день навсегда?\nОткатить будет почти невозможно.",
                    "Подтверждение", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (conf == JOptionPane.YES_OPTION) {
                dialog.dispose();
                playDeleteAnimationAndRemove(date);
            }
        });

        buttons.add(addEventBtn);
        buttons.add(addPollBtn);
        buttons.add(deleteDayBtn);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void refreshPollPanel(JPanel pollPanel, PollItem poll) {
        pollPanel.removeAll();
        if (poll == null) {
            JLabel empty = new JLabel("Опроса нет");
            empty.setForeground(Color.GRAY);
            empty.setFont(new Font("SansSerif", Font.ITALIC, 13));
            pollPanel.add(empty);
        } else {
            JLabel q = new JLabel("<html><b>Опрос (анонимный):</b> " + poll.question + "</html>");
            q.setFont(new Font("SansSerif", Font.PLAIN, 13));
            pollPanel.add(q);
            pollPanel.add(Box.createVerticalStrut(8));

            if (!poll.voted) {
                JLabel hint = new JLabel("Выберите вариант:");
                hint.setFont(new Font("SansSerif", Font.ITALIC, 12));
                hint.setForeground(Color.GRAY);
                pollPanel.add(hint);
                pollPanel.add(Box.createVerticalStrut(6));

                for (int i = 0; i < poll.options.size(); i++) {
                    final int index = i;
                    JButton optBtn = new JButton(poll.options.get(i));
                    optBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
                    optBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
                    optBtn.addActionListener(e -> {
                        while (poll.votes.size() <= index) poll.votes.add(0);
                        poll.votes.set(index, poll.votes.get(index) + 1);
                        poll.voted = true;
                        saveAll();
                        refreshPollPanel(pollPanel, poll);
                        updateCalendar();
                    });
                    pollPanel.add(optBtn);
                    pollPanel.add(Box.createVerticalStrut(4));
                }
            } else {
                int total = 0;
                for (int v : poll.votes) total += v;
                for (int i = 0; i < poll.options.size(); i++) {
                    int v = i < poll.votes.size() ? poll.votes.get(i) : 0;
                    int pct = total > 0 ? (v * 100 / total) : 0;
                    JLabel row = new JLabel(String.format("  %s  —  %d голосов (%d%%)",
                            poll.options.get(i), v, pct));
                    row.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    pollPanel.add(row);
                }
                JLabel note = new JLabel("Вы уже проголосовали");
                note.setFont(new Font("SansSerif", Font.ITALIC, 10));
                note.setForeground(Color.GRAY);
                pollPanel.add(Box.createVerticalStrut(4));
                pollPanel.add(note);
            }
        }
        pollPanel.revalidate();
        pollPanel.repaint();
    }

    private void showAddEventDialog(LocalDate date, DefaultListModel<String> listModel,
                                    List<EventItem> evList) {
        JDialog d = new JDialog(this, "Новое событие", true);
        d.setSize(400, 280);
        d.setLocationRelativeTo(this);
        d.setLayout(new BorderLayout(8, 8));
        d.getContentPane().setBackground(Color.WHITE);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);
        form.setBorder(new EmptyBorder(16, 16, 8, 16));
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 4, 4, 4);
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        form.add(new JLabel("Текст:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        JTextField textField = new JTextField(20);
        form.add(textField, gc);

        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        form.add(new JLabel("Тип:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        JComboBox<String> typeBox = new JComboBox<>(eventTypes.toArray(new String[0]));
        typeBox.setEditable(true);
        form.add(typeBox, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        form.add(new JLabel("Цвет:"), gc);
        gc.gridx = 1; gc.weightx = 1;
        String[] colorNames = {"Чёрный", "Красный", "Синий", "Зелёный", "Оранжевый", "Фиолетовый"};
        String[] colorVals = {"#222222", "#CC2222", "#2244AA", "#228822", "#DD6600", "#7722AA"};
        JComboBox<String> colorBox = new JComboBox<>(colorNames);
        form.add(colorBox, gc);

        d.add(form, BorderLayout.CENTER);

        JButton ok = new JButton("Добавить");
        ok.addActionListener(e -> {
            String text = textField.getText().trim();
            if (text.isEmpty()) return;
            String type = Objects.toString(typeBox.getSelectedItem(), "Обычное").trim();
            if (type.isEmpty()) type = "Обычное";
            if (!eventTypes.contains(type)) eventTypes.add(type);
            String colorHex = colorVals[colorBox.getSelectedIndex()];
            EventItem item = new EventItem(text, colorHex, type);
            evList.add(item);
            listModel.addElement("[" + type + "] " + text);
            saveAll();
            updateCalendar();
            d.dispose();
        });
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bp.setBackground(Color.WHITE);
        bp.add(ok);
        d.add(bp, BorderLayout.SOUTH);
        d.setVisible(true);
    }

    private void showAddPollDialog(LocalDate date, JPanel pollPanel, JDialog parentDialog) {
        if (polls.containsKey(date)) {
            JOptionPane.showMessageDialog(parentDialog, "На этот день уже есть опрос.");
            return;
        }
        JDialog d = new JDialog(this, "Новый опрос", true);
        d.setSize(420, 320);
        d.setLocationRelativeTo(this);
        d.setLayout(new BorderLayout(8, 8));
        d.getContentPane().setBackground(Color.WHITE);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(16, 16, 8, 16));
        form.setBackground(Color.WHITE);

        form.add(new JLabel("Вопрос:"));
        JTextField qField = new JTextField();
        form.add(qField);
        form.add(Box.createVerticalStrut(8));
        form.add(new JLabel("Варианты (каждый с новой строки, минимум 2):"));
        JTextArea optsArea = new JTextArea(5, 30);
        optsArea.setLineWrap(true);
        form.add(new JScrollPane(optsArea));

        d.add(form, BorderLayout.CENTER);

        JButton ok = new JButton("Создать опрос");
        ok.addActionListener(e -> {
            String q = qField.getText().trim();
            String[] lines = optsArea.getText().split("\\r?\\n");
            List<String> opts = new ArrayList<>();
            for (String l : lines) {
                l = l.trim();
                if (!l.isEmpty()) opts.add(l);
            }
            if (q.isEmpty() || opts.size() < 2) {
                JOptionPane.showMessageDialog(d, "Нужен вопрос и минимум 2 варианта.");
                return;
            }
            int base = 1 + deletedCount();
            List<Integer> votes = new ArrayList<>();
            int remaining = base + random.nextInt(Math.max(1, base));
            for (int i = 0; i < opts.size(); i++) {
                if (i == opts.size() - 1) {
                    votes.add(Math.max(0, remaining));
                } else {
                    int v = remaining <= 0 ? 0 : random.nextInt(remaining + 1);
                    votes.add(v);
                    remaining -= v;
                }
            }
            int sum = 0;
            for (int v : votes) sum += v;
            if (sum < base && !votes.isEmpty()) {
                votes.set(0, votes.get(0) + (base - sum));
            }

            PollItem poll = new PollItem(q, opts, votes);
            polls.put(date, poll);
            saveAll();
            refreshPollPanel(pollPanel, poll);
            updateCalendar();
            d.dispose();
        });
        JPanel bp = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bp.setBackground(Color.WHITE);
        bp.add(ok);
        d.add(bp, BorderLayout.SOUTH);
        d.setVisible(true);
    }

    private void playDeleteAnimationAndRemove(LocalDate date) {
        JDialog anim = new JDialog(this, "Удаление…", true);
        anim.setUndecorated(true);
        anim.setSize(220, 220);
        anim.setLocationRelativeTo(this);
        anim.getContentPane().setBackground(Color.BLACK);

        JLabel frameLabel = new JLabel("", SwingConstants.CENTER);
        frameLabel.setOpaque(true);
        frameLabel.setBackground(Color.BLACK);
        anim.add(frameLabel);

        if (deleteAnimFrames.isEmpty()) {
            frameLabel.setText("Удаление дня…");
            frameLabel.setForeground(Color.WHITE);
            frameLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
            javax.swing.Timer t = new javax.swing.Timer(1200, e -> {
                ((javax.swing.Timer) e.getSource()).stop();
                finishDelete(date, anim);
            });
            t.setRepeats(false);
            t.start();
            anim.setVisible(true);
            return;
        }

        final int[] idx = {0};
        javax.swing.Timer t = new javax.swing.Timer(80, null);
        t.addActionListener(e -> {
            if (idx[0] >= deleteAnimFrames.size()) {
                t.stop();
                finishDelete(date, anim);
                return;
            }
            frameLabel.setIcon(deleteAnimFrames.get(idx[0]));
            idx[0]++;
        });
        t.start();
        anim.setVisible(true);
    }

    private void finishDelete(LocalDate date, JDialog anim) {
        deletedDayHashes.add(hashDay(date));
        events.remove(date);
        polls.remove(date);
        saveAll();
        anim.dispose();
        updateCalendar();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new SpriteCalendar().setVisible(true);
        });
    }
}