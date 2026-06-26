package monitoring;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MonitorUI extends JFrame {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    // Wenn der Consumer DIESES Objekt aus der Queue zieht,
    // weiß er "Producer ist fertig, ich kann aufhören" - eleganter als einen Boolean abzufragen,
    // weil das Signal genau wie ein normales Reading durch dieselbe Queue läuft.
    private static final Messwert POISON_PILL = new Messwert(Instant.EPOCH, 0, "__STOP__");

    private final DefaultTableModel modMesswerte = new DefaultTableModel(
            new String[]{"Zeitstempel", "Wert", "Typ"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };

    private final DefaultListModel<Alarm> modAlertList = new DefaultListModel<>();
    private final JLabel lblTemperatur = new JLabel("–");
    private final JLabel lblFeuchtigkeit = new JLabel("–");
    private final JTextField txtTempSchwelle = new JTextField("22.4", 5);
    private final JTextField txtFeuchteSchwelle = new JTextField("60.0", 5);
    private final JLabel lblStatus = new JLabel(" ");
    private final JButton btnLoad = new JButton("CSV wählen …");
    private final JButton btnStop = new JButton("Stop");

    private final Map<String, LaufzeitStatistik> statistiken = new HashMap<>();

    private BlockingQueue<Messwert> queue;
    private Thread producerThread;
    private Thread consumerThread;

    public MonitorUI() {
        super("Temperatur-Monitor (Live)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(900, 620);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(6, 6));
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);
        btnStop.setEnabled(false);
    }

    private JPanel buildToolbar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        btnLoad.addActionListener(e -> auswahlUndStart());
        p.add(btnLoad);
        p.add(new JLabel("Temp-Schwelle:"));
        p.add(txtTempSchwelle);
        p.add(new JLabel("Feuchte-Schwelle:"));
        p.add(txtFeuchteSchwelle);
        btnStop.addActionListener(e -> stopStreaming());
        p.add(btnStop);

        JButton exportJson = new JButton("JSON");
        exportJson.addActionListener(e -> export("json"));
        p.add(exportJson);

        JButton exportXml = new JButton("XML");
        exportXml.addActionListener(e -> export("xml"));
        p.add(exportXml);
        return p;
    }

    private JTabbedPane buildTabs() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Daten", buildDataTab());
        tabs.addTab("Statistik", buildStatsTab());
        tabs.addTab("Alarme", buildAlertsTab());
        return tabs;
    }

    private JScrollPane buildDataTab() {
        JTable table = new JTable(modMesswerte);
        table.setAutoCreateRowSorter(true);
        return new JScrollPane(table);
    }

    private JPanel buildStatsTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(12, 5, 12, 5));
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 4, 2, 4); // oben, links, unten, rechts

        JLabel lblTemp = new JLabel("Temperatur:");
        lblTemp.setFont(lblTemp.getFont().deriveFont(20f));
        lblTemperatur.setFont(lblTemp.getFont().deriveFont(20f));

        JLabel lblFeuchte = new JLabel("Luftfeuchte:");
        lblFeuchte.setFont(lblFeuchte.getFont().deriveFont(20f));
        lblFeuchtigkeit.setFont(lblFeuchte.getFont().deriveFont(20f));

        c.gridx = 0;
        c.gridy = 0;
        p.add(lblTemp, c);
        c.gridx = 1;
        p.add(lblTemperatur, c);
        c.gridx = 0;
        c.gridy = 1;
        p.add(lblFeuchte, c);
        c.gridx = 1;
        p.add(lblFeuchtigkeit, c);

        return p;
    }

    private JScrollPane buildAlertsTab() {
        JList<Alarm> lstAlarme = new JList<>(modAlertList);
        lstAlarme.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return new JScrollPane(lstAlarme);
    }

    private JPanel buildStatus() {
        JPanel p = new JPanel(new BorderLayout());
        p.add(lblStatus, BorderLayout.CENTER);
        return p;
    }

    private void auswahlUndStart() {
        JFileChooser fc = new JFileChooser(System.getProperty("user.dir"));
        fc.setMultiSelectionEnabled(true);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        startStreaming(fc.getSelectedFiles());
    }

    private void startStreaming(File[] files) {
        modMesswerte.setRowCount(0);
        modAlertList.clear();
        statistiken.clear();
        //Liste hat 2 Probleme: nicht threadsafe, kann nicht warten (müsste in
        //Endlosschleife fragen: Sind Daten da?)
        //Lösung Queue: Producer legt Daten rein, Consumer nimmt Element raus und
        //blockiert, solange Queue leer (keine Endlosschleife, keine CPU Belastung)
        //ArrayBlockingQueue: feste Größe
        queue = new ArrayBlockingQueue<>(100);
        btnLoad.setEnabled(false);
        btnStop.setEnabled(true);
        lblStatus.setText("Läuft …");

        producerThread = new Thread(() -> {
            try {
                for (File file: files) {
                    CSVReader.einlesenInQueue(file, queue, 800);
                }
            } catch (InterruptedException ie) {
                //Stopp wurde gedrückt
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> lblStatus.setText(ex.getMessage()));
            } finally {
                try {
                    queue.put(POISON_PILL);
                } catch (InterruptedException e) {
                    // ignoriert
                }
            }
        });
        producerThread.start();

        //3 Consumer
        consumerThread = new Thread(() -> {
            while (true) {
                Messwert messwert;
                try {
                    messwert = queue.take(); //consume (blockiert)
                } catch (InterruptedException ie) {
                    break;
                }
                if(messwert == POISON_PILL) {
                    break;
                }
                double schwellenwert = messwert.typ().equals("temperature")?
                        parseDouble(txtTempSchwelle.getText(), 22.4):
                        parseDouble(txtFeuchteSchwelle.getText(), 60.0);

                if(!statistiken.containsKey(messwert.typ())) {
                    statistiken.put(messwert.typ(), new LaufzeitStatistik(messwert.typ()));
                }
                LaufzeitStatistik statistik = statistiken.get(messwert.typ());
                statistik.add(messwert, schwellenwert);
                SwingUtilities.invokeLater(() -> onNeuMesswert(messwert));
            }
        });
        consumerThread.start();
    }

    private void onNeuMesswert(Messwert messwert) {
        modMesswerte.addRow(new Object[] {FMT.format(messwert.zeitstempel()), round(messwert.wert()), messwert.typ()});

        lblTemperatur.setText(format(statistiken.get("temperature")));
        lblFeuchtigkeit.setText(format(statistiken.get("feuchtigkeit")));
    }

    private String format(LaufzeitStatistik s) {
        if(s == null) {
            return "Keine Daten";
        }
        return String.format("n=%d, min=%.2f, max=%.2f, avg=%.2f, alarme=%d",
                s.count(), s.min(), s.max(), s.avg(), s.alarme().size());
    }

    private void stopStreaming() {
        if (producerThread != null) {
            producerThread.interrupt();
        }
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
        btnLoad.setEnabled(true);
        btnStop.setEnabled(false);
        lblStatus.setText("Gestoppt.");
    }

    private void export(String format) {

    }

    private double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MonitorUI().setVisible(true));
    }
}
