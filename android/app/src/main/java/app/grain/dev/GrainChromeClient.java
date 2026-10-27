package app.grain.dev;

import android.webkit.ConsoleMessage;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginHandle;

public class GrainChromeClient extends BridgeWebChromeClient {
    private final Bridge bridge;
    private boolean emitting;

    public GrainChromeClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        if (!emitting && consoleMessage != null && consoleMessage.message() != null) {
            String source = consoleMessage.sourceId() == null ? "" : consoleMessage.sourceId();
            String text = consoleMessage.message();
            boolean special = text.startsWith("grain-elements:")
                || text.startsWith("grain-network:")
                || text.startsWith("grain-pick:")
                || text.startsWith("grain-pick-cancel:");
            if ((special || isPreviewSource(source)) && !looksLikeBridgeNoise(text)) {
                JSObject payload = new JSObject();
                payload.put("level", consoleMessage.messageLevel() == null ? "LOG" : consoleMessage.messageLevel().name());
                payload.put("text", text);
                payload.put("source", source);
                payload.put("line", consoleMessage.lineNumber());
                PluginHandle handle = bridge.getPlugin("GrainWorkspace");
                if (handle != null && handle.getInstance() instanceof GrainWorkspacePlugin) {
                    emitting = true;
                    try {
                        ((GrainWorkspacePlugin) handle.getInstance()).emitWebConsole(payload);
                    } finally {
                        emitting = false;
                    }
                }
            }
        }
        return super.onConsoleMessage(consoleMessage);
    }

    private static boolean isPreviewSource(String source) {
        String value = source == null ? "" : source.toLowerCase();
        return value.contains("http://127.0.0.1:")
            || value.contains("http://localhost:")
            || value.contains("about:srcdoc")
            || value.equals("about:blank");
    }

    private static boolean looksLikeBridgeNoise(String text) {
        return text.startsWith("{\"level\":") || text.contains("console.groupEnd");
    }
}
