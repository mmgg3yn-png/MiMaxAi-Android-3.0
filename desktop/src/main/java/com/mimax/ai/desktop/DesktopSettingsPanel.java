package com.mimax.ai.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.util.Properties;
import java.util.prefs.Preferences;

/** Desktop counterpart of Android Settings: local profile, AI preferences, appearance and backup. */
public final class DesktopSettingsPanel {
    private final Preferences prefs;
    private final Runnable changed;
    private final JPanel root=new JPanel();
    private final JTextField profile=new JTextField();
    private final JTextField audioModel=new JTextField();
    private final JTextArea prompt=new JTextArea(5,42);
    private final JLabel tempValue=new JLabel();
    private final JLabel fontValue=new JLabel();
    private final JSlider temp=new JSlider(0,15,7);
    private final JSlider font=new JSlider(70,200,100);
    private final JComboBox<String> theme=new JComboBox<>();
    private final JComboBox<String> style=new JComboBox<>(new String[]{"عادي","زجاج سائل","Material 3"});
    private final JCheckBox stream=check("عرض الرد تدريجياً أثناء الكتابة","stream",true);
    private final JCheckBox autoMem=check("حفظ المعلومات تلقائياً في الذاكرة","autoMem",true);
    private final JCheckBox recall=check("تذكّر المحادثات السابقة","recall",true);
    private final JCheckBox motion=check("حركة الخلفية السائلة","motion",true);
    private final JCheckBox dev=check("تفعيل خيارات المطور والألوان المخصصة","devOpts",false);

    public DesktopSettingsPanel(Preferences prefs,Runnable changed){
        this.prefs=prefs;this.changed=changed;
        root.setLayout(new BoxLayout(root,BoxLayout.Y_AXIS));root.setBorder(new EmptyBorder(18,18,18,18));
        addSection("الملف الشخصي");
        profile.setText(prefs.get("profileName",""));addRow("الاسم المحلي",profile);
        JButton saveProfile=button("حفظ الملف الشخصي");saveProfile.addActionListener(e->{prefs.put("profileName",profile.getText().trim());status("تم حفظ الاسم محلياً");});root.add(saveProfile);
        addSection("الذكاء الاصطناعي");
        audioModel.setText(prefs.get("audioModel",""));addRow("نموذج الصوت (فارغ = نموذج المحادثة)",audioModel);
        JButton saveAudio=button("حفظ نموذج الصوت");saveAudio.addActionListener(e->{prefs.put("audioModel",audioModel.getText().trim());status("تم حفظ نموذج الصوت");});root.add(saveAudio);
        temp.setValue((int)Math.round(prefs.getDouble("temp",0.7)*10));tempValue.setText("الإبداع (الحرارة): "+(temp.getValue()/10.0));
        temp.addChangeListener(e->{tempValue.setText("الإبداع (الحرارة): "+(temp.getValue()/10.0));if(!temp.getValueIsAdjusting())prefs.putDouble("temp",temp.getValue()/10.0);});
        root.add(tempValue);root.add(temp);
        addCheck(stream);addCheck(autoMem);addCheck(recall);
        addSection("البرمبت");
        prompt.setText(prefs.get("prompt",""));prompt.setLineWrap(true);prompt.setWrapStyleWord(true);
        prompt.setFont(new Font("Segoe UI",Font.PLAIN,13));root.add(new JScrollPane(prompt));
        JButton savePrompt=button("حفظ تعليمات الذكاء الاصطناعي");savePrompt.addActionListener(e->{prefs.put("prompt",prompt.getText());status("تم حفظ البرمبت");});root.add(savePrompt);
        addSection("المظهر");
        for(DesktopTheme t:DesktopTheme.ALL)theme.addItem(t.nameAr+" — "+t.descAr);
        theme.setSelectedIndex(indexOf(prefs.get("theme","basic")));
        theme.addActionListener(e->{prefs.put("theme",DesktopTheme.ALL[theme.getSelectedIndex()].id);changed.run();});
        addRow("الثيم",theme);
        style.setSelectedIndex(Math.max(0,Math.min(2,prefs.getInt("style",0))));
        style.addActionListener(e->{prefs.putInt("style",style.getSelectedIndex());changed.run();});
        addRow("نمط التصميم",style);addCheck(motion);
        font.setValue(Math.max(70,Math.min(200,prefs.getInt("fontScale",100))));
        fontValue.setText("حجم الخط: "+font.getValue()+"%");
        font.addChangeListener(e->{fontValue.setText("حجم الخط: "+font.getValue()+"%");if(!font.getValueIsAdjusting())prefs.putInt("fontScale",font.getValue());});
        root.add(fontValue);root.add(font);
        addSection("خيارات المطور");
        addCheck(dev);
        JButton custom=button("تعديل ألوان الواجهة");custom.addActionListener(e->editColors());root.add(custom);
        addSection("النسخ الاحتياطي");
        root.add(new JLabel("تصدير أو استعادة إعدادات Mi_Max Ai محلياً. مفتاح API لا يدخل في النسخة الاحتياطية."));
        JButton export=button("تصدير الإعدادات");export.addActionListener(e->backup());root.add(export);
        JButton restore=button("استعادة الإعدادات");restore.addActionListener(e->restore());root.add(restore);
        JButton clear=button("إعادة ضبط إعدادات التطبيق");clear.addActionListener(e->{if(JOptionPane.showConfirmDialog(root,"إعادة ضبط إعدادات التطبيق المحلية؟ لن يُحذف مفتاح API.","تأكيد",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){try{for(String k:prefs.keys())if(!k.equals("openrouter_key"))prefs.remove(k);}catch(Exception ignored){}changed.run();status("تمت إعادة ضبط الإعدادات");}});root.add(clear);
    }
    public JPanel view(){return root;}
    private int indexOf(String id){for(int i=0;i<DesktopTheme.ALL.length;i++)if(DesktopTheme.ALL[i].id.equals(id))return i;return 0;}
    private JCheckBox check(String title,String key,boolean def){JCheckBox c=new JCheckBox(title,prefs.getBoolean(key,def));c.setOpaque(false);c.addActionListener(e->prefs.putBoolean(key,c.isSelected()));return c;}
    private void addCheck(JCheckBox c){root.add(c);}
    private void addSection(String title){JLabel l=new JLabel(title);l.setFont(new Font("Segoe UI",Font.BOLD,18));l.setBorder(new EmptyBorder(14,0,7,0));root.add(l);}
    private void addRow(String title,JComponent input){JPanel p=new JPanel(new BorderLayout(10,4));p.setOpaque(false);p.add(new JLabel(title),BorderLayout.NORTH);p.add(input,BorderLayout.CENTER);p.setBorder(new EmptyBorder(4,0,6,0));root.add(p);}
    private JButton button(String s){JButton b=new JButton(s);b.setAlignmentX(Component.RIGHT_ALIGNMENT);return b;}
    private void status(String s){JOptionPane.showMessageDialog(root,s,"Mi_Max Ai",JOptionPane.INFORMATION_MESSAGE);}
    private void backup(){
        JFileChooser c=new JFileChooser();c.setSelectedFile(new File("MiMaxAi-settings.properties"));
        if(c.showSaveDialog(root)!=JFileChooser.APPROVE_OPTION)return;
        try{Properties p=new Properties();for(String k:prefs.keys())if(!k.toLowerCase().contains("key")&&!k.toLowerCase().contains("token"))p.setProperty(k,prefs.get(k,""));try(OutputStream o=new FileOutputStream(c.getSelectedFile())){p.store(o,"Mi_Max Ai settings backup — secrets excluded");}status("تم تصدير الإعدادات.");}
        catch(Exception e){status("فشل التصدير: "+e.getMessage());}
    }
    private void restore(){
        JFileChooser c=new JFileChooser();if(c.showOpenDialog(root)!=JFileChooser.APPROVE_OPTION)return;
        try{Properties p=new Properties();try(InputStream in=new FileInputStream(c.getSelectedFile())){p.load(in);}for(String k:p.stringPropertyNames())if(!k.toLowerCase().contains("key")&&!k.toLowerCase().contains("token"))prefs.put(k,p.getProperty(k));changed.run();status("تمت استعادة الإعدادات. راجع الثيم والخيارات.");}
        catch(Exception e){status("فشلت الاستعادة: "+e.getMessage());}
    }
    private void editColors(){
        String[] keys={"base","surface","surface2","line","tx","mut","ac","er"};
        String[] names={"الخلفية","البطاقات","السطح القوي","الحدود","النص","النص الثانوي","التمييز","الخطأ"};
        DesktopTheme t=DesktopTheme.byId(prefs.get("theme","basic"));
        Color[] defaults={t.base,t.surface,t.surface2,t.line,t.text,t.muted,t.accent,t.error};
        for(int i=0;i<keys.length;i++){
            String old=prefs.get("cc_"+keys[i],String.format("%08X",defaults[i].getRGB()));
            Color initial;try{initial=new Color((int)Long.parseLong(old,16),true);}catch(Exception ex){initial=defaults[i];}
            Color picked=JColorChooser.showDialog(root,names[i],initial);
            if(picked!=null)prefs.put("cc_"+keys[i],String.format("%08X",picked.getRGB()));
        }
        prefs.putBoolean("ccOn",true);changed.run();
    }
}
