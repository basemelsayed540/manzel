package com.manzel.app;

import android.os.Bundle;
import android.webkit.WebSettings;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ManzelPlugin.class);
        super.onCreate(savedInstanceState);
        // الواجهة تُحمَّل من GitHub Pages: بدون كاش حتى يصل أي تعديل فوراً.
        // لو مفيش نت يفتح offline.html المحزَّن داخل التطبيق (server.errorPath).
        getBridge().getWebView().getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
    }
}
