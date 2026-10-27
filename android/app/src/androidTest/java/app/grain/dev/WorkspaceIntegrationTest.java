package app.grain.dev;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Runs against the registered plugin inside the real Android application. */
@RunWith(AndroidJUnit4.class)
public class WorkspaceIntegrationTest {
    private ActivityScenario<MainActivity> activity;
    private GrainWorkspacePlugin plugin;
    private File temporary;

    static class Call extends PluginCall {
        JSObject value;
        String error;
        Call(String method, JSObject data) {
            super(null, "GrainWorkspace", UUID.randomUUID().toString(), method, data);
        }
        @Override public void resolve(JSObject data) { value = data; }
        @Override public void resolve() { value = new JSObject(); }
        @Override public void reject(String message, String code, Exception exception, JSObject data) { error = message; }
    }

    @Before public void open() {
        activity = ActivityScenario.launch(MainActivity.class);
        activity.onActivity(app -> {
            plugin = (GrainWorkspacePlugin) app.getBridge().getPlugin("GrainWorkspace").getInstance();
        });
        assertNotNull("Native plugin must be registered", plugin);
    }

    @After public void close() {
        if (temporary != null) temporary.delete();
        if (activity != null) activity.close();
    }

    @Test public void utf8FilePersistsThroughNativeBridge() throws Exception {
        Call environment = new Call("environment", new JSObject());
        plugin.environment(environment);
        assertNull(environment.error);
        assertTrue(environment.value.getBoolean("shellAvailable"));
        String path = ".grain-test-" + UUID.randomUUID() + ".js";
        temporary = new File(environment.value.getString("workspace"), path);
        String content = "console.log('你好 🌾')\n";
        Call write = new Call("writeFile", new JSObject().put("path", path).put("content", content));
        plugin.writeFile(write);
        assertNull(write.error);
        assertTrue(temporary.isFile());
        Call read = new Call("readFile", new JSObject().put("path", path));
        plugin.readFile(read);
        assertNull(read.error);
        assertEquals(content, read.value.getString("content"));
    }

    @Test public void traversalIsRejectedOnDevice() {
        Call write = new Call("writeFile", new JSObject().put("path", "../escape.js").put("content", "bad"));
        plugin.writeFile(write);
        assertNotNull(write.error);
        assertNull(write.value);
    }

    @Test public void shellStreamsStdoutStderrAndExitCode() throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        String id = UUID.randomUUID().toString();
        StringBuilder output = new StringBuilder();
        AtomicReference<JSObject> termination = new AtomicReference<>();
        Call listener = new Call("addListener", new JSObject().put("eventName", "output")) {
            @Override public void resolve(JSObject data) {
                if (!id.equals(data.optString("id"))) return;
                if ("data".equals(data.optString("type"))) output.append(data.optString("text"));
                else { termination.set(data); exited.countDown(); }
            }
        };
        plugin.addListener(listener);
        Call execute = new Call("execute", new JSObject().put("id", id)
            .put("command", "printf 'grain-stdout'; printf 'grain-stderr' >&2; exit 7"));
        plugin.execute(execute);
        assertNull(execute.error);
        assertTrue("Shell must finish in 10 seconds", exited.await(10, TimeUnit.SECONDS));
        assertTrue(output.toString().contains("grain-stdout"));
        assertTrue(output.toString().contains("grain-stderr"));
        assertEquals("exit", termination.get().getString("type"));
        assertEquals(7, termination.get().getInt("code"));
    }

    @Test public void renameAndDeleteStayInsideWorkspace() throws Exception {
        String path = ".grain-test-" + UUID.randomUUID() + "/note.js";
        String renamed = path.replace("/note.js", "/renamed.js");
        Call write = new Call("writeFile", new JSObject().put("path", path).put("content", "keep"));
        plugin.writeFile(write);
        assertNull(write.error);
        Call rename = new Call("renamePath", new JSObject().put("from", path).put("to", renamed));
        plugin.renamePath(rename);
        assertNull(rename.error);
        Call read = new Call("readFile", new JSObject().put("path", renamed));
        plugin.readFile(read);
        assertEquals("keep", read.value.getString("content"));
        Call remove = new Call("deletePath", new JSObject().put("path", path.substring(0, path.indexOf('/'))));
        plugin.deletePath(remove);
        assertNull(remove.error);
        Call missing = new Call("readFile", new JSObject().put("path", renamed));
        plugin.readFile(missing);
        assertFalse(missing.value.getBoolean("exists"));
        Call escape = new Call("deletePath", new JSObject().put("path", "../escape.js"));
        plugin.deletePath(escape);
        assertNotNull(escape.error);
    }
}
