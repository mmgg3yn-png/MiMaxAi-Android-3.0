package com.mimax.ai.desktop;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.prefs.Preferences;
import java.util.regex.*;

public final class AiPanel {
 private final Preferences prefs=Preferences.userNodeForPackage(AiPanel.class);
 private final JPanel root=new JPanel();
 private final JTextArea history=new JTextArea();
 private final JTextField prompt=new JTextField();
 private final JPasswordField key=new JPasswordField(28);
 private final JComboBox<String> model=new JComboBox<>(new String[]{"openrouter/auto","deepseek/deepseek-chat-v3-0324:free","meta-llama/llama-3.3-70b-instruct:free"});
 private final JLabel status=new JLabel("جاهز");
 public AiPanel(JFrame owner){
  root.setLayout(new BoxLayout(root,BoxLayout.Y_AXIS)); root.setBackground(new Color(17,20,29)); root.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));
  JLabel title=new JLabel("Mi_Max Ai  •  الذكاء الاصطناعي"); title.setForeground(new Color(235,239,248)); title.setFont(new Font("Segoe UI",Font.BOLD,22)); root.add(title);
  JLabel info=new JLabel("اتصال مباشر بـ OpenRouter — مفتاح API يبقى محفوظاً محلياً على هذا الكمبيوتر."); info.setForeground(new Color(153,164,185)); root.add(info);
  key.setText(prefs.get("openrouter_key","")); key.setToolTipText("OpenRouter API Key");
  JPanel settings=new JPanel(new FlowLayout(FlowLayout.RIGHT)); settings.setOpaque(false); settings.add(new JLabel("مفتاح API")); settings.add(key);
  JButton save=new JButton("حفظ المفتاح"); save.addActionListener(e->{prefs.put("openrouter_key",new String(key.getPassword()).trim()); status.setText("تم حفظ المفتاح محلياً");}); settings.add(save);
  settings.add(new JLabel("النموذج")); model.setEditable(true); settings.add(model);
  JButton refresh=new JButton("تحديث النماذج"); refresh.addActionListener(e->loadModels()); settings.add(refresh); root.add(settings);
  history.setEditable(false); history.setLineWrap(true); history.setWrapStyleWord(true); history.setFont(new Font("Segoe UI",Font.PLAIN,14)); history.setBackground(new Color(27,32,44)); history.setForeground(new Color(235,239,248)); history.setText("مرحباً بك في Mi_Max Ai.\n");
  JScrollPane scroll=new JScrollPane(history); scroll.setPreferredSize(new Dimension(800,360)); root.add(scroll);
  JPanel send=new JPanel(new BorderLayout(8,8)); send.setOpaque(false);
  JButton go=new JButton("إرسال"); go.addActionListener(e->send()); prompt.addActionListener(e->send()); send.add(prompt,BorderLayout.CENTER); send.add(go,BorderLayout.WEST); root.add(send);
  status.setForeground(new Color(153,164,185)); root.add(status);
 }
 public JPanel view(){return root;}
 private void loadModels(){
  status.setText("جارٍ تحميل النماذج...");
  new SwingWorker<java.util.List<String>,Void>(){
   protected java.util.List<String> doInBackground() throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/models").openConnection(); c.setConnectTimeout(15000); c.setReadTimeout(30000);
    String body=read(c.getInputStream()); c.disconnect(); java.util.List<String> ids=new java.util.ArrayList<>();
    Matcher m=Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body); while(m.find()&&ids.size()<300)ids.add(m.group(1)); return ids;
   }
   protected void done(){try{java.util.List<String> a=get(); model.removeAllItems(); for(String x:a)model.addItem(x); status.setText("تم تحميل "+a.size()+" نموذج");}catch(Exception e){status.setText("تعذر تحميل النماذج: "+e.getMessage());}}
  }.execute();
 }
 private void send(){
  final String q=prompt.getText().trim(), k=new String(key.getPassword()).trim(), m=String.valueOf(model.getSelectedItem());
  if(q.isEmpty())return; if(k.isEmpty()){status.setText("أدخل مفتاح OpenRouter أولاً");return;} prompt.setText(""); history.append("\nأنت: "+q+"\nMi_Max Ai: "); status.setText("جارٍ الاتصال...");
  new SwingWorker<String,Void>(){
   protected String doInBackground() throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection(); c.setRequestMethod("POST"); c.setConnectTimeout(20000); c.setReadTimeout(120000); c.setDoOutput(true);
    c.setRequestProperty("Authorization","Bearer "+k); c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("X-Title","Mi_Max Ai Desktop");
    String body="{\"model\":\""+esc(m)+"\",\"messages\":[{\"role\":\"system\",\"content\":\"أنت Mi_Max Ai، مساعد عملي. أجب بلغة المستخدم بوضوح.\"},{\"role\":\"user\",\"content\":\""+esc(q)+"\"}]}";
    try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));} int code=c.getResponseCode(); InputStream in=code>=400?c.getErrorStream():c.getInputStream(); String data=read(in); c.disconnect(); if(code>=400)throw new Exception("HTTP "+code+": "+data);
    Matcher x=Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(data); if(!x.find())throw new Exception("لم يتم العثور على نص الرد: "+data); return unesc(x.group(1));
   }
   protected void done(){try{history.append(get()+"\n");status.setText("اكتمل الرد");}catch(Exception e){history.append("تعذر الاتصال: "+e.getMessage()+"\n");status.setText("فشل الطلب");} history.setCaretPosition(history.getDocument().getLength());}
  }.execute();
 }
 private static String read(InputStream in)throws Exception{StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String x;while((x=r.readLine())!=null)b.append(x);}return b.toString();}
 private static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
 private static String unesc(String s){return s.replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");}
}