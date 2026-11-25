package com.mimax.ai.desktop;

import com.mimax.ai.engine.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Collections;

/** Mi_Max Ai Desktop - Windows x86/x64. Shared Java ROM engines, independent of Android. */
public final class Main extends JFrame {
    private static final Color BG = new Color(17, 20, 29), PANEL = new Color(27, 32, 44), ACCENT = new Color(94, 145, 255), FG = new Color(235, 239, 248), MUTED = new Color(153, 164, 185);
    private final JTextArea log = new JTextArea();
    private File selected;
    private JLabel selectedLabel;

    public static void main(String[] args) { SwingUtilities.invokeLater(() -> new Main().setVisible(true)); }

    private Main() {
        super("Mi_Max Ai Desktop  •  3.0");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1080, 720);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(12, 12));
        ((JComponent)getContentPane()).setBorder(new EmptyBorder(14, 14, 14, 14));
        JLabel title = new JLabel("Mi_Max Ai", SwingConstants.RIGHT);
        title.setForeground(FG); title.setFont(new Font("Segoe UI", Font.BOLD, 26));
        JLabel sub = new JLabel("أدوات الرومات والملفات • نسخة Windows 32/64-bit", SwingConstants.RIGHT);
        sub.setForeground(MUTED);
        JPanel head = new JPanel(new BorderLayout()); head.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(2,1)); titles.setOpaque(false); titles.add(title); titles.add(sub);
        head.add(titles, BorderLayout.EAST);
        JButton choose = button("اختيار ملف");
        choose.addActionListener(e -> chooseFile());
        head.add(choose, BorderLayout.WEST);
        add(head, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("الرئيسية", home());
        tabs.addTab("أدوات ROM", rom());
        tabs.addTab("الأرشيف", archives());
        tabs.addTab("logo.bin", logo());
        tabs.addTab("السجل", logPanel());
        tabs.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
        style(tabs);
        add(tabs, BorderLayout.CENTER);
        selectedLabel = new JLabel("لم يتم اختيار ملف بعد");
        selectedLabel.setForeground(MUTED);
        add(selectedLabel, BorderLayout.SOUTH);
        note("تم تشغيل إصدار الكمبيوتر. المحركات المشتركة مكتوبة بـ Java وتعمل على Windows x86 وx64.");
    }

    private JPanel home() {
        JPanel p = panel();
        JLabel h = new JLabel("مرحباً بك في Mi_Max Ai Desktop");
        h.setForeground(FG); h.setFont(new Font("Segoe UI", Font.BOLD, 23));
        p.add(h);
        p.add(label("نسخة الكمبيوتر المستقلة: لا تحتاج إلى Android أو Termux."));
        p.add(label("المعمارية: يدعم حزمة 32-bit وحزمة 64-bit منفصلتين."));
        p.add(Box.createVerticalStrut(16));
        JPanel cards = new JPanel(new GridLayout(2,2,12,12)); cards.setOpaque(false);
        cards.add(card("ROM Tools", "كشف صيغ الصور، Sparse ⇄ Raw، معلومات super.img وboot.img"));
        cards.add(card("logo.bin", "فك صور MediaTek مع الاحتفاظ بالبيانات الخام وإعادة التجميع"));
        cards.add(card("Archive", "عرض وفحص واستخراج ZIP وإنشاء ZIP وMT1 وMT_1"));
        cards.add(card("File workspace", "اختيار ملفات ومجلدات العمل وحفظ النتائج بجوار المصدر"));
        p.add(cards);
        p.add(Box.createVerticalStrut(12));
        p.add(label("ملاحظة: هذه بداية نسخة سطح المكتب. الأدوات التي تعتمد على Android أو خدمات الهاتف لن تظهر كميزات عاملة هنا."));
        return p;
    }

    private JPanel rom() {
        JPanel p = panel();
        p.add(label("ملف العمل الحالي"));
        p.add(fileLine());
        p.add(action("فحص نوع الملف والترويسة", () -> withFile(f -> {
            Detect.Info i = Detect.detect(f);
            StringBuilder b = new StringBuilder("النوع: ").append(i.label).append("\nالحالة: ").append(i.statusAr()).append("\n").append(i.detail);
            for (String e : i.evidence) b.append("\n• ").append(e);
            show("نتيجة الفحص", b.toString());
        })));
        p.add(action("تحويل Sparse إلى Raw", () -> withFile(f -> {
            File out = output(f, "_raw.img");
            run("تحويل Sparse → Raw", () -> Sparse.toRaw(f, out, null, null), () -> note("تم إنشاء: " + out));
        })));
        p.add(action("تحويل Raw إلى Sparse", () -> withFile(f -> {
            File out = output(f, "_sparse.img");
            run("تحويل Raw → Sparse", () -> Sparse.fromRaw(f, out, null, null), () -> note("تم إنشاء: " + out));
        })));
        p.add(action("عرض أقسام super.img", () -> withFile(f -> {
            try (Vsrc v = new Vsrc.FileV(f)) {
                Lp.Meta m = Lp.read(v);
                StringBuilder b = new StringBuilder("Logical block: ").append(m.logicalBlockSize).append("\n\n");
                for (Lp.Part part : m.parts) b.append(part.name).append("  •  ").append(Io.human(part.size)).append("\n");
                show("أقسام super.img", b.toString());
            }
        })));
        p.add(action("استخراج boot.img / vendor_boot", () -> withFile(f -> {
            BootImg.Info info = BootImg.parse(f);
            File dir = new File(f.getParentFile(), f.getName() + "_unpacked");
            run("استخراج " + info.kind, () -> BootImg.extract(f, info, dir, true, null), () -> note("تم الاستخراج في: " + dir));
        })));
        return p;
    }

    private JPanel archives() {
        JPanel p = panel();
        p.add(label("الأرشيفات المدعومة في هذه النسخة: ZIP وما يتوافق معه مثل APK وJAR وAPKS وXAPK وMT1 وMT_1."));
        p.add(fileLine());
        p.add(action("عرض محتويات الأرشيف", () -> withFile(f -> {
            List<Archives.Item> items = Archives.list(f);
            StringBuilder b = new StringBuilder("العناصر: ").append(items.size()).append("\n\n");
            for (Archives.Item i : items) b.append(i.dir ? "[مجلد] " : "").append(i.name).append("   ").append(Io.human(Math.max(0, i.size))).append("\n");
            show("محتويات الأرشيف", b.toString());
        })));
        p.add(action("استخراج الأرشيف كاملاً", () -> withFile(f -> {
            File dest = new File(f.getParentFile(), f.getName() + "_extracted");
            run("استخراج الأرشيف", () -> Archives.extract(f, dest, null, null, null), () -> note("تم الاستخراج إلى: " + dest));
        })));
        p.add(action("إنشاء ZIP من مجلد", () -> chooseFolderAndPack(".zip", null, null)));
        p.add(action("إنشاء حزمة إضافة .mt1", () -> chooseFolderAndPack(".mt1", "META-INF/mimax/addon.json", "{\n  \"format\": \"MiMaxAddon\",\n  \"formatVersion\": 1\n}\n")));
        p.add(action("إنشاء حزمة ثيم .mt_1", () -> chooseFolderAndPack(".mt_1", "META-INF/mimax/theme.json", "{\n  \"format\": \"MiMaxTheme\",\n  \"formatVersion\": 1\n}\n")));
        return p;
    }

    private JPanel logo() {
        JPanel p = panel();
        p.add(label("محرك MediaTek logo.bin مستقل عن Android، يستخدم ImageIO على الكمبيوتر."));
        p.add(fileLine());
        p.add(action("فك logo.bin", () -> withFile(f -> {
            File dir = new File(f.getParentFile(), f.getName() + "_logo");
            run("فك logo.bin", () -> LogoBin.extract(f, dir, "bgrale", null, null), () -> note("اكتمل الفك. الصور المدعومة تظهر PNG، وتُحفظ ملفات raw.z الأصلية.\n" + dir));
        })));
        p.add(action("إعادة تجميع مجلد logo.bin", () -> {
            JFileChooser c = new JFileChooser(); c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File dir = c.getSelectedFile(), out = new File(dir.getParentFile(), dir.getName() + "_rebuilt.logo.bin");
                run("إعادة تجميع logo.bin", () -> LogoBin.repack(dir, out), () -> note("تم إنشاء: " + out));
            }
        }));
        return p;
    }

    private JPanel logPanel() {
        JPanel p = panel();
        log.setEditable(false); log.setBackground(PANEL); log.setForeground(FG); log.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane s = new JScrollPane(log); s.setPreferredSize(new Dimension(800,420)); p.add(s);
        return p;
    }

    private JPanel fileLine() {
        JPanel row = new JPanel(new BorderLayout(8,8)); row.setOpaque(false);
        JLabel l = new JLabel(selected == null ? "اختر ملفاً من الزر أعلى النافذة" : selected.getAbsolutePath());
        l.setForeground(MUTED); row.add(l, BorderLayout.CENTER);
        JButton b = button("تغيير الملف"); b.addActionListener(e -> { chooseFile(); l.setText(selected == null ? "لم يتم الاختيار" : selected.getAbsolutePath()); });
        row.add(b, BorderLayout.WEST); return row;
    }

    private void chooseFile() {
        JFileChooser c = new JFileChooser();
        c.setDialogTitle("اختيار ملف");
        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selected = c.getSelectedFile();
            selectedLabel.setText(selected.getAbsolutePath());
            note("تم اختيار: " + selected);
        }
    }

    private interface FileTask { void run(File f) throws Exception; }
    private void withFile(FileTask task) {
        if (selected == null || !selected.isFile()) { warn("اختر ملفاً أولاً."); return; }
        try { task.run(selected); } catch (Exception e) { error(e); }
    }

    private void chooseFolderAndPack(String ext, String meta, String json) {
        JFileChooser c = new JFileChooser(); c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        c.setDialogTitle("اختر مجلد المحتوى");
        if (c.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File src = c.getSelectedFile(), out = new File(src.getParentFile(), src.getName() + ext);
        run("ضغط الملفات", () -> ArchivePack.create(src, out, meta, json, null, null), () -> note("تم إنشاء الحزمة: " + out));
    }

    private File output(File f, String suffix) { return new File(f.getParentFile(), f.getName() + suffix); }
    private void run(String name, Work work, Runnable done) {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception { work.run(); return null; }
            protected void done() {
                setCursor(Cursor.getDefaultCursor());
                try { get(); done.run(); } catch (Exception e) { error(e.getCause() == null ? e : e.getCause()); }
            }
        }.execute();
    }
    private interface Work { Object run() throws Exception; }
    private void show(String title, String text) {
        JTextArea area = new JTextArea(text, 22, 64); area.setEditable(false); area.setBackground(PANEL); area.setForeground(FG); area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), title, JOptionPane.INFORMATION_MESSAGE);
    }
    private void note(String s) { log.append("• " + s + "\n"); }
    private void warn(String s) { JOptionPane.showMessageDialog(this,s,"تنبيه",JOptionPane.WARNING_MESSAGE); }
    private void error(Throwable e) { note("خطأ: " + e); JOptionPane.showMessageDialog(this,e.getMessage(),"تعذر إكمال العملية",JOptionPane.ERROR_MESSAGE); }
    private JButton button(String s) { JButton b = new JButton(s); b.setFocusPainted(false); b.setBackground(ACCENT); b.setForeground(Color.WHITE); b.setFont(new Font("Segoe UI",Font.BOLD,13)); return b; }
    private JPanel action(String s, Runnable r) { JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT)); p.setOpaque(false); JButton b=button(s); b.addActionListener(e -> r.run()); p.add(b); return p; }
    private JPanel panel() { JPanel p=new JPanel(); p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS)); p.setBackground(BG); p.setBorder(new EmptyBorder(18,18,18,18)); return p; }
    private JLabel label(String s) { JLabel l=new JLabel(s); l.setForeground(FG); l.setFont(new Font("Segoe UI",Font.PLAIN,14)); l.setAlignmentX(Component.RIGHT_ALIGNMENT); return l; }
    private JPanel card(String title,String desc) { JPanel p=new JPanel(new BorderLayout(6,8)); p.setBackground(PANEL); p.setBorder(new EmptyBorder(18,18,18,18)); JLabel h=new JLabel(title); h.setForeground(ACCENT); h.setFont(new Font("Segoe UI",Font.BOLD,17)); JLabel d=new JLabel("<html><div style='width:280px;color:#d5dced'>"+desc+"</div></html>"); p.add(h,BorderLayout.NORTH); p.add(d,BorderLayout.CENTER); return p; }
    private void style(JTabbedPane t) { t.setBackground(PANEL); t.setForeground(FG); t.setFont(new Font("Segoe UI",Font.BOLD,13)); }
}
