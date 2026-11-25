package com.mimax.ai.desktop;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.prefs.Preferences;
import java.util.regex.*;

/**
 * Windows AI panel. Uses the same encrypted built-in key fallback as Android Vault;
 * a user-supplied key overrides it, and clearing the field returns to the built-in key.
 */
public final class AiPanel {
 private final Preferences prefs=Preferences.userNodeForPackage(AiPanel.class);
 private final JPanel root=new JPanel(new BorderLayout(10,10));
 private final JTextArea history=new JTextArea();
 private final JTextField prompt=new JTextField();
 private final JPasswordField key=new JPasswordField(24);
 private final JComboBox<String> model=new JComboBox<>(new String[]{"openrouter/auto"});
 private final JSlider temperature=new JSlider(0,15,7);
 private final JLabel status=new JLabel("جاهز");
 private final JLabel keyStatus=new JLabel();
 private final java.util.List<String> messages=new ArrayList<>();
 private boolean busy=false;

 public AiPanel(JFrame owner){
  root.setBorder(new EmptyBorder(16,16,16,16));
  JPanel north=new JPanel();north.setLayout(new BoxLayout(north,BoxLayout.Y_AXIS));
  JLabel title=new JLabel("Mi_Max Ai  •  الذكاء الاصطناعي");title.setFont(new Font("Segoe UI",Font.BOLD,22));north.add(title);
  north.add(new JLabel("يعمل بالمفتاح المدمج المشفّر افتراضياً، ويمكنك استبداله بمفتاحك الخاص."));
  JPanel controls=new JPanel(new FlowLayout(FlowLayout.RIGHT));
  key.setText(prefs.get("openrouter_key",""));key.setToolTipText("اتركه فارغاً لاستخدام المفتاح المدمج");
  JButton save=new JButton("حفظ المفتاح الخاص");save.addActionListener(e->{String k=new String(key.getPassword()).trim();if(k.isEmpty()){prefs.remove("openrouter_key");keyStatus.setText("تمت إزالة المفتاح الخاص — عاد المفتاح المدمج");}else{prefs.put("openrouter_key",k);keyStatus.setText("تم حفظ مفتاحك الخاص محلياً");}});
  JButton clear=new JButton("العودة للمفتاح المدمج");clear.addActionListener(e->{key.setText("");prefs.remove("openrouter_key");keyStatus.setText("المفتاح المدمج هو المستخدم الآن");});
  JButton test=new JButton("فحص المفتاح");test.addActionListener(e->testKey());
  controls.add(new JLabel("مفتاح خاص (اختياري)"));controls.add(key);controls.add(save);controls.add(clear);controls.add(test);
  model.setEditable(true);String savedModel=prefs.get("model","openrouter/auto");model.setSelectedItem(savedModel);
  JButton refresh=new JButton("تحديث النماذج");refresh.addActionListener(e->loadModels());
  controls.add(new JLabel("النموذج"));controls.add(model);controls.add(refresh);
  temperature.setValue((int)Math.round(prefs.getDouble("temp",0.7)*10));temperature.setPreferredSize(new Dimension(110,24));
  temperature.addChangeListener(e->prefs.putDouble("temp",temperature.getValue()/10.0));
  controls.add(new JLabel("الإبداع"));controls.add(temperature);
  north.add(controls);keyStatus.setText(activeKeyLabel());north.add(keyStatus);root.add(north,BorderLayout.NORTH);

  history.setEditable(false);history.setLineWrap(true);history.setWrapStyleWord(true);history.setFont(new Font("Segoe UI",Font.PLAIN,14));
  history.setText("مرحباً بك في Mi_Max Ai.\n");root.add(new JScrollPane(history),BorderLayout.CENTER);
  JPanel south=new JPanel(new BorderLayout(8,8));JButton go=new JButton("إرسال");go.addActionListener(e->send());prompt.addActionListener(e->send());
  south.add(prompt,BorderLayout.CENTER);south.add(go,BorderLayout.WEST);south.add(status,BorderLayout.SOUTH);root.add(south,BorderLayout.SOUTH);
 }
 public JPanel view(){return root;}
 private String customKey(){return prefs.get("openrouter_key","").trim();}
 private String activeKey(){String k=customKey();return k.isEmpty()?Vault.key():k;}
 private String activeKeyLabel(){return customKey().isEmpty()?(Vault.key().isEmpty()?"تنبيه: المفتاح المدمج غير متاح في هذا الإصدار":"الحالة: يعمل بالمفتاح المدمج المشفّر"):"الحالة: تستخدم مفتاحك الخاص";}
 private void testKey(){
  status.setText("جارٍ فحص المفتاح...");
  new SwingWorker<String,Void>(){protected String doInBackground()throws Exception{
   HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/auth/key").openConnection();
   c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Authorization","Bearer "+activeKey());
   int code=c.getResponseCode();String data=read(code>=400?c.getErrorStream():c.getInputStream());c.disconnect();
   if(code>=400)throw new Exception("HTTP "+code+": "+data);
   Matcher m=Pattern.compile("\\"usage\\"\\s*:\\s*([0-9.]+)").matcher(data);
   return m.find()?"المفتاح يعمل. الاستهلاك: "+m.group(1)+" دولار":"المفتاح يعمل.";
  }protected void done(){try{keyStatus.setText(get());status.setText("تم الفحص");}catch(Exception e){keyStatus.setText("فشل الفحص: "+e.getMessage());status.setText("تعذر التحقق");}}}.execute();
 }
 private void loadModels(){
  status.setText("جارٍ تحميل النماذج...");
  new SwingWorker<java.util.List<String>,Void>(){
   protected java.util.List<String> doInBackground()throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/models").openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);
    String data=read(c.getInputStream());c.disconnect();java.util.List<String> ids=new ArrayList<>();
    Matcher m=Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(data);while(m.find()&&ids.size()<500)ids.add(m.group(1));return ids;
   }
   protected void done(){try{String current=String.valueOf(model.getSelectedItem());java.util.List<String> ids=get();model.removeAllItems();for(String id:ids)model.addItem(id);model.setSelectedItem(ids.contains(current)?current:"openrouter/auto");status.setText("تم تحميل "+ids.size()+" نموذجاً");}catch(Exception e){status.setText("تعذر تحميل النماذج: "+e.getMessage());}}
  }.execute();
 }
 private void send(){
  if(busy)return;final String q=prompt.getText().trim();if(q.isEmpty())return;
  final String k=activeKey();if(k.isEmpty()){status.setText("المفتاح المدمج غير متاح؛ تحقق من إعداد الخادم أو أضف مفتاحك الخاص.");return;}
  final String m=String.valueOf(model.getSelectedItem());prefs.put("model",m);
  final double temp=temperature.getValue()/10.0;prompt.setText("");messages.add("{\"role\":\"user\",\"content\":\""+esc(q)+"\"}");
  history.append("\nأنت: "+q+"\nMi_Max Ai: ");status.setText("جارٍ الاتصال...");busy=true;
  new SwingWorker<String,Void>(){
   protected String doInBackground()throws Exception{
    StringBuilder ms=new StringBuilder("[{\"role\":\"system\",\"content\":\"أنت Mi_Max Ai، مساعد عملي. أجب بلغة المستخدم بوضوح وإيجاز.\"}");
    for(String msg:messages)ms.append(",").append(msg);ms.append("]");
    String body="{\"model\":\""+esc(m)+"\",\"messages\":"+ms+",\"temperature\":"+temp+"}";
    HttpURLConnection c=(HttpURLConnection)new URL("https://openrouter.ai/api/v1/chat/completions").openConnection();c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(120000);c.setDoOutput(true);
    c.setRequestProperty("Authorization","Bearer "+k);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-Title","Mi_Max Ai");
    try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}
    int code=c.getResponseCode();String data=read(code>=400?c.getErrorStream():c.getInputStream());c.disconnect();if(code>=400)throw new Exception("HTTP "+code+": "+data);
    Matcher x=Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(data);if(!x.find())throw new Exception("لم يتم العثور على نص الرد: "+data);return unesc(x.group(1));
   }
   protected void done(){try{String answer=get();history.append(answer+"\n");messages.add("{\"role\":\"assistant\",\"content\":\""+esc(answer)+"\"}");status.setText("اكتمل الرد");}
    catch(Exception e){messages.remove(messages.size()-1);history.append("تعذر الاتصال: "+e.getMessage()+"\n");status.setText("فشل الطلب");}
    busy=false;history.setCaretPosition(history.getDocument().getLength());}
  }.execute();
 }
 private static String read(InputStream in)throws Exception{if(in==null)return "";StringBuilder b=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String x;while((x=r.readLine())!=null)b.append(x);}return b.toString();}
 private static String esc(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");}
 private static String unesc(String s){return s.replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").replace("\\\"","\"").replace("\\\\","\\");}
}
