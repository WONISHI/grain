package app.grain.dev;

import com.getcapacitor.BridgeActivity;
import android.os.Bundle;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GrainWorkspacePlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getBridge() == null || getBridge().getWebView() == null) return;
        getBridge().setWebViewClient(new GrainWebViewClient(getBridge()));
        getBridge().getWebView().setWebChromeClient(new GrainChromeClient(getBridge()));
    }
}
