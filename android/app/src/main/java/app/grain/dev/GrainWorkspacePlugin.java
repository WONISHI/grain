package app.grain.dev;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;
import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** App-owned workspace and a streamed shell process; no external terminal app. */
@CapacitorPlugin(name = "GrainWorkspace")
public class GrainWorkspacePlugin extends Plugin {
    private static final String TAG = "GrainWorkspace";
    private static final String NPM_VERSION = "10.9.9";
    private static final String GIT_VERSION = "2.51.0";
    private static final String PYTHON_VERSION = "3.13.7";
    private static final String WATCHER_VERSION = "2.6.0-nm";
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object terminalGate = new Object();
    private final Map<String, Integer> terminalHandles = new HashMap<>();
    private final Set<String> terminalPumps = new HashSet<>();
    private Process process;
    private Process watchProcess;
    private String activeId;

    @Override
    public void load() {
        executor.execute(() -> {
            try { ensureNpm(); }
            catch (Exception e) { Log.e(TAG, "npm extract failed", e); }
            try { ensureGitTemplates(); }
            catch (Exception e) { Log.e(TAG, "git template extract failed", e); }
            try { ensureWatcher(); }
            catch (Exception e) { Log.e(TAG, "watcher extract failed", e); }
        });
    }

    private WorkspaceFiles files() throws IOException {
        return new WorkspaceFiles(new File(getContext().getFilesDir(), "workspace"));
    }

    private File workspace() throws IOException { return files().root(); }

    @PluginMethod
    public void environment(PluginCall call) {
        try {
            File node = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_node.so");
            File npm = new File(npmRoot(), "bin/npm-cli.js");
            try { ensureNpm(); } catch (Exception e) { Log.e(TAG, "npm extract failed", e); }
            JSObject result = new JSObject();
            result.put("platform", "android");
            result.put("abi", Build.SUPPORTED_ABIS[0]);
            result.put("workspace", workspace().getAbsolutePath());
            // Presence is not compatibility verification. No version is inferred.
            result.put("nodePresent", node.isFile() && node.canExecute());
            result.put("npmPresent", npm.isFile());
            result.put("shellAvailable", new File("/system/bin/sh").canExecute());
            result.put("runtimeVerified", false);
            call.resolve(result);
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void listFiles(PluginCall call) {
        try {
            JSArray files = new JSArray();
            for (String path : files().list()) {
                JSObject item = new JSObject();
                item.put("path", path);
                files.put(item);
            }
            JSObject result = new JSObject();
            result.put("files", files);
            call.resolve(result);
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void listDirectory(PluginCall call) {
        try {
            String path = call.getString("path", "");
            if (path == null) path = "";
            JSArray entries = new JSArray();
            for (WorkspaceFiles.DirEntry entry : files().listDirectory(path)) {
                JSObject item = new JSObject();
                item.put("name", entry.name);
                item.put("path", entry.path);
                item.put("kind", entry.directory ? "dir" : "file");
                entries.put(item);
            }
            JSObject result = new JSObject();
            result.put("entries", entries);
            call.resolve(result);
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void watchWorkspace(PluginCall call) {
        executor.execute(() -> {
            try {
                startWatcher();
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "watch failed", e);
                call.reject(e.getMessage() == null ? "文件监听没有启动" : e.getMessage(), e);
            }
        });
    }

    private synchronized void startWatcher() throws Exception {
        if (watchProcess != null && watchProcess.isAlive()) return;
        File node = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_node.so");
        if (!node.isFile()) throw new IOException("缺少 Node，无法监听文件");
        File root = ensureWatcher();
        File script = new File(root, "watch.js");
        File library = new File(root, "lib/watcher.node");
        File runtime = new File(root, "lib");
        if (!script.isFile() || !library.isFile()) throw new IOException("文件监听模块不完整");
        CountDownLatch ready = new CountDownLatch(1);
        StringBuilder failure = new StringBuilder();
        ProcessBuilder builder = new ProcessBuilder(node.getAbsolutePath(), script.getAbsolutePath(), workspace().getAbsolutePath());
        builder.directory(workspace());
        builder.environment().put("GRAIN_WATCHER", library.getAbsolutePath());
        builder.environment().put("LD_LIBRARY_PATH", runtime.getAbsolutePath());
        builder.environment().put("HOME", getContext().getFilesDir().getAbsolutePath());
        builder.environment().put("TMPDIR", getContext().getCacheDir().getAbsolutePath());
        Process started = builder.start();
        watchProcess = started;
        executor.execute(() -> readWatcher(started, ready, failure));
        if (!ready.await(8, TimeUnit.SECONDS) || !started.isAlive() || failure.length() > 0) {
            started.destroy();
            watchProcess = null;
            throw new IOException(failure.length() > 0 ? failure.toString() : "文件监听没有启动");
        }
    }

    private void readWatcher(Process started, CountDownLatch ready, StringBuilder failure) {
        executor.execute(() -> {
            try (BufferedReader errors = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = errors.readLine()) != null) Log.e(TAG, "watch: " + line);
            } catch (IOException ignored) {
                // The process has closed its error stream.
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JSObject payload;
                try {
                    payload = new JSObject(line);
                } catch (JSONException e) {
                    Log.w(TAG, "watch: " + line);
                    continue;
                }
                if (payload.has("ready")) {
                    ready.countDown();
                    continue;
                }
                String error = payload.optString("error", "");
                if (!error.isEmpty() && !payload.has("events")) {
                    failure.append(error);
                    ready.countDown();
                    continue;
                }
                JSONArray events = payload.optJSONArray("events");
                if (events != null) {
                    Log.i(TAG, "watch events " + events.length());
                    notifyListeners("files", payload);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "watch ended", e);
        } finally {
            ready.countDown();
            synchronized (this) {
                if (watchProcess == started) watchProcess = null;
            }
        }
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        try {
            String content = files().read(call.getString("path"));
            JSObject result = new JSObject();
            result.put("exists", content != null);
            if (content != null) result.put("content", content);
            call.resolve(result);
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        try {
            files().write(call.getString("path"), call.getString("content"));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void clipboardRead(PluginCall call) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            JSObject result = new JSObject();
            String text = "";
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence value = clip.getItemAt(0).coerceToText(getContext());
                    if (value != null) text = value.toString();
                }
            }
            result.put("text", text);
            call.resolve(result);
        } catch (Exception e) { call.reject(e.getMessage() == null ? "读取剪贴板失败" : e.getMessage(), e); }
    }

    @PluginMethod
    public void clipboardWrite(PluginCall call) {
        try {
            String text = call.getString("text", "");
            if (text == null) text = "";
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IOException("剪贴板不可用");
            clipboard.setPrimaryClip(ClipData.newPlainText("grain", text));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage() == null ? "写入剪贴板失败" : e.getMessage(), e); }
    }

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, intent, "filesPicked");
    }

    @PluginMethod
    public void pickDirectory(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(call, intent, "directoryPicked");
    }

    @ActivityCallback
    private void filesPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSArray files = new JSArray();
        Intent data = result.getData();
        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int index = 0; index < count && files.length() < 200; index += 1) {
                    Uri uri = data.getClipData().getItemAt(index).getUri();
                    addImportFile(files, displayName(uri), uri);
                }
            } else if (data.getData() != null) {
                addImportFile(files, displayName(data.getData()), data.getData());
            }
        }
        JSObject payload = new JSObject();
        payload.put("files", files);
        call.resolve(payload);
    }

    @ActivityCallback
    private void directoryPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        JSArray files = new JSArray();
        Intent data = result.getData();
        boolean opened = result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null;
        String name = "";
        if (opened) {
            DocumentFile root = DocumentFile.fromTreeUri(getContext(), data.getData());
            if (root != null) {
                String rootName = root.getName();
                if (rootName != null) name = rootName;
                DocumentFile[] children = root.listFiles();
                if (children != null) {
                    int[] count = new int[] { 0 };
                    for (DocumentFile child : children) collectImportTree(child, "", files, count);
                }
            }
        }
        JSObject payload = new JSObject();
        payload.put("opened", opened);
        payload.put("name", name);
        payload.put("files", files);
        call.resolve(payload);
    }

    @PluginMethod
    public void clearWorkspace(PluginCall call) {
        try {
            files().clear();
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void promoteDirectory(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path == null || path.isEmpty()) {
                call.reject("缺少文件夹路径");
                return;
            }
            files().promote(path);
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void deletePath(PluginCall call) {
        try {
            files().delete(call.getString("path"));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void renamePath(PluginCall call) {
        try {
            files().rename(call.getString("from"), call.getString("to"));
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    private void collectImportTree(DocumentFile file, String relative, JSArray files, int[] count) {
        if (file == null || count[0] >= 200) return;
        String name = file.getName();
        if (name == null || name.equals(".") || name.equals("..") || name.equals("node_modules") || name.equals(".git")) return;
        String path = relative == null || relative.isEmpty() ? name : relative + "/" + name;
        if (file.isDirectory()) {
            DocumentFile[] children = file.listFiles();
            if (children == null) return;
            for (DocumentFile child : children) collectImportTree(child, path, files, count);
            return;
        }
        if (!file.isFile()) return;
        addImportFile(files, path, file.getUri());
        if (files.length() > count[0]) count[0] = files.length();
    }

    private void addImportFile(JSArray files, String path, Uri uri) {
        if (path == null || path.isEmpty() || uri == null || files.length() >= 200) return;
        String content = readImportText(uri);
        if (content == null) return;
        JSObject item = new JSObject();
        item.put("path", path);
        item.put("content", content);
        files.put(item);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContext().getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {
            name = null;
        }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment();
        return name == null ? "" : name;
    }

    private String readImportText(Uri uri) {
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(chunk)) >= 0) {
                for (int index = 0; index < read; index += 1) {
                    if (chunk[index] == 0) return null;
                }
                total += read;
                if (total > 512 * 1024) return null;
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "import read failed", e);
            return null;
        }
    }

    private void event(String id, String type, String text, Integer code) {
        JSObject payload = new JSObject();
        payload.put("id", id);
        payload.put("type", type);
        if (text != null) payload.put("text", text);
        if (code != null) payload.put("code", code);
        notifyListeners("output", payload);
    }

    public void emitWebConsole(JSObject payload) {
        if (getBridge() == null || getBridge().getActivity() == null) {
            notifyListeners("webConsole", payload);
            return;
        }
        getBridge().getActivity().runOnUiThread(() -> notifyListeners("webConsole", payload));
    }

    public void emitWebNetwork(JSObject payload) {
        if (getBridge() == null || getBridge().getActivity() == null) {
            notifyListeners("webNetwork", payload);
            return;
        }
        getBridge().getActivity().runOnUiThread(() -> notifyListeners("webNetwork", payload));
    }

    @PluginMethod
    public synchronized void execute(PluginCall call) {
        String command = call.getString("command");
        String id = call.getString("id");
        if (command == null || command.trim().isEmpty() || id == null) {
            call.reject("缺少命令或任务编号");
            return;
        }
        if (process != null) { call.reject("已有命令正在执行，请先停止"); return; }
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", commandScript(command));
            builder.directory(workspace());
            builder.redirectErrorStream(true);
            File prefix = ensurePrefix();
            ensureRuntimeCommands();
            builder.environment().put("HOME", getContext().getFilesDir().getAbsolutePath());
            builder.environment().put("TMPDIR", getContext().getCacheDir().getAbsolutePath());
            builder.environment().put("npm_config_cache", new File(getContext().getCacheDir(), "npm").getAbsolutePath());
            builder.environment().put("npm_config_prefix", prefix.getAbsolutePath());
            builder.environment().put("npm_config_update_notifier", "false");
            prependPath(builder, new File(getContext().getFilesDir(), "runtime/bin"), new File(prefix, "bin"));
            File git = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_git.so");
            if (git.isFile()) {
                try {
                    builder.environment().put("GIT_TEMPLATE_DIR", ensureGitTemplates().getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "git template extract failed", e);
                }
                builder.environment().put("GIT_EXEC_PATH", new File(getContext().getFilesDir(), "runtime/bin").getAbsolutePath());
                try {
                    putTlsCertEnv(builder.environment(), ensureCaCerts());
                } catch (Exception e) {
                    Log.e(TAG, "CA cert extract failed", e);
                }
                builder.environment().put("LD_LIBRARY_PATH", getContext().getApplicationInfo().nativeLibraryDir);
            }
            File python = pythonBinary();
            if (python.isFile()) {
                builder.environment().put("GRAIN_PYTHON", python.getAbsolutePath());
                builder.environment().put("LD_LIBRARY_PATH", getContext().getApplicationInfo().nativeLibraryDir);
                builder.environment().put("PYTHONUNBUFFERED", "1");
                try {
                    builder.environment().put("PYTHONHOME", ensurePython().getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "python stdlib extract failed", e);
                }
            }
            process = builder.start();
            activeId = id;
            Process running = process;
            executor.execute(() -> {
                try (InputStreamReader reader = new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int size;
                    while ((size = reader.read(buffer)) != -1) {
                        event(id, "data", new String(buffer, 0, size), null);
                    }
                    int code = running.waitFor();
                    synchronized (this) {
                        if (process == running) { process = null; activeId = null; }
                    }
                    event(id, "exit", null, code);
                } catch (Exception e) {
                    running.destroy();
                    synchronized (this) {
                        if (process == running) { process = null; activeId = null; }
                    }
                    event(id, "error", e.getMessage(), null);
                }
            });
            call.resolve();
        } catch (Exception e) { process = null; activeId = null; call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public synchronized void input(PluginCall call) {
        try {
            if (process == null || !activeId.equals(call.getString("id"))) throw new IOException("命令已结束");
            String data = call.getString("data");
            if (data == null) throw new IOException("缺少输入内容");
            OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(data);
            writer.flush();
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public synchronized void stop(PluginCall call) {
        if (process != null && activeId.equals(call.getString("id"))) {
            process.destroy();
            // Android O+ can kill the direct child if it does not handle SIGTERM.
            if (Build.VERSION.SDK_INT >= 26) process.destroyForcibly();
        }
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (terminalGate) { terminalPumps.clear(); }
        try { GrainPty.closeAll(); } catch (Throwable ignored) { /* Library may be absent during teardown. */ }
        synchronized (terminalGate) { terminalHandles.clear(); }
        if (process != null) process.destroy();
        if (watchProcess != null) watchProcess.destroy();
        executor.shutdownNow();
        super.handleOnDestroy();
    }

    @PluginMethod
    public void terminalOpen(PluginCall call) {
        try {
            File node = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_node.so");
            File git = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_git.so");
            ensurePrefix();
            ensureRuntimeCommands();
            File npm = null;
            try { npm = ensureNpm(); } catch (Exception e) { Log.e(TAG, "npm extract failed", e); }
            if (git.isFile()) {
                try { ensureGitTemplates(); } catch (Exception e) { Log.e(TAG, "git template extract failed", e); }
                try { ensureCaCerts(); } catch (Exception e) { Log.e(TAG, "CA cert extract failed", e); }
            }
            if (pythonBinary().isFile()) {
                try { ensurePython(); } catch (Exception e) { Log.e(TAG, "python stdlib extract failed", e); }
            }
            File rc = writeShellRc(node, npm, git);
            int cols = call.getInt("cols", 80);
            int rows = call.getInt("rows", 24);
            String cwdRel = call.getString("cwd", "");
            File cwdDir = files().directory(cwdRel == null ? "" : cwdRel);
            int handle = GrainPty.open(cwdDir.getAbsolutePath(), shellEnvironment(rc, node), cols, rows);
            if (handle < 0) throw new IOException(GrainPty.lastError());
            String requested = call.getString("id");
            String id = requested == null || requested.isEmpty() ? UUID.randomUUID().toString() : requested;
            synchronized (terminalGate) {
                Integer previous = terminalHandles.put(id, handle);
                terminalPumps.add(id);
                if (previous != null) GrainPty.close(previous);
            }
            executor.execute(() -> pump(id, handle));
            JSObject result = new JSObject();
            result.put("id", id);
            call.resolve(result);
        } catch (UnsatisfiedLinkError e) {
            call.reject("终端原生库没有载入：" + e.getMessage());
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void terminalWrite(PluginCall call) {
        try {
            Integer handle = terminalHandle(call.getString("id"));
            if (handle == null) throw new IOException("终端已关闭");
            String data = call.getString("data");
            if (data == null) throw new IOException("缺少输入");
            if (GrainPty.write(handle, data.getBytes(StandardCharsets.UTF_8)) != 0) throw new IOException("无法写入终端");
            call.resolve();
        } catch (Exception e) { call.reject(e.getMessage(), e); }
    }

    @PluginMethod
    public void terminalResize(PluginCall call) {
        Integer handle = terminalHandle(call.getString("id"));
        if (handle != null) GrainPty.resize(handle, call.getInt("cols", 80), call.getInt("rows", 24));
        call.resolve();
    }

    @PluginMethod
    public void terminalInterrupt(PluginCall call) {
        Integer handle = terminalHandle(call.getString("id"));
        if (handle != null) GrainPty.interrupt(handle);
        call.resolve();
    }

    @PluginMethod
    public void terminalClose(PluginCall call) {
        String id = call.getString("id");
        Integer handle;
        synchronized (terminalGate) {
            terminalPumps.remove(id);
            handle = terminalHandles.remove(id);
        }
        if (handle != null) {
            try { GrainPty.close(handle); } catch (Throwable ignored) { /* Already closed. */ }
        }
        call.resolve();
    }

    @PluginMethod
    public void searchContent(PluginCall call) {
        final String query = call.getString("query", "");
        final String cwd = call.getString("cwd", "");
        final boolean caseSensitive = Boolean.TRUE.equals(call.getBoolean("caseSensitive", false));
        final boolean wholeWord = Boolean.TRUE.equals(call.getBoolean("wholeWord", false));
        final boolean isRegex = Boolean.TRUE.equals(call.getBoolean("isRegex", false));
        final int maxResults = call.getInt("maxResults", 2000);
        executor.execute(() -> {
            try {
                WorkspaceFiles.SearchResult result = files().searchContent(
                    query == null ? "" : query,
                    cwd == null ? "" : cwd,
                    caseSensitive,
                    wholeWord,
                    isRegex,
                    maxResults
                );
                JSArray files = new JSArray();
                for (Map.Entry<String, List<WorkspaceFiles.SearchHit>> entry : result.files.entrySet()) {
                    JSObject file = new JSObject();
                    file.put("path", entry.getKey());
                    JSArray matches = new JSArray();
                    for (WorkspaceFiles.SearchHit hit : entry.getValue()) {
                        JSObject row = new JSObject();
                        row.put("path", hit.path);
                        row.put("line", hit.line);
                        row.put("column", hit.column);
                        row.put("text", hit.text);
                        row.put("matchStart", hit.matchStart);
                        row.put("matchEnd", hit.matchEnd);
                        matches.put(row);
                    }
                    file.put("matches", matches);
                    files.put(file);
                }
                JSObject payload = new JSObject();
                payload.put("files", files);
                payload.put("truncated", result.truncated);
                payload.put("engine", "android");
                call.resolve(payload);
            } catch (Exception e) {
                call.reject(e.getMessage(), e);
            }
        });
    }

    /** Fetch a local preview URL from native code (WebView fetch to 127.0.0.1 often fails). */
    @PluginMethod
    public void fetchUrl(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("缺少地址");
            return;
        }
        executor.execute(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                java.net.URL target = new java.net.URL(url);
                String host = target.getHost();
                if (host == null || (!host.equals("127.0.0.1") && !host.equalsIgnoreCase("localhost"))) {
                    call.reject("只允许本机地址");
                    return;
                }
                connection = (java.net.HttpURLConnection) target.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) stream = new java.io.ByteArrayInputStream(new byte[0]);
                String contentType = connection.getContentType();
                String charsetName = "UTF-8";
                if (contentType != null) {
                    int cs = contentType.toLowerCase().indexOf("charset=");
                    if (cs >= 0) {
                        charsetName = contentType.substring(cs + 8).trim().replace("\"", "");
                        int semi = charsetName.indexOf(';');
                        if (semi >= 0) charsetName = charsetName.substring(0, semi).trim();
                    }
                }
                byte[] bytes = readStream(stream);
                java.nio.charset.Charset charset;
                try {
                    charset = java.nio.charset.Charset.forName(charsetName);
                } catch (Exception ignored) {
                    charset = StandardCharsets.UTF_8;
                }
                String text = new String(bytes, charset);
                JSObject result = new JSObject();
                result.put("status", status);
                result.put("text", text);
                if (contentType != null) result.put("contentType", contentType);
                call.resolve(result);
            } catch (Exception e) {
                call.reject(e.getMessage() == null ? "请求失败" : e.getMessage(), e);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static byte[] readStream(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = stream.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private Integer terminalHandle(String id) {
        synchronized (terminalGate) { return terminalHandles.get(id); }
    }

    private boolean pumping(String id) {
        synchronized (terminalGate) { return terminalPumps.contains(id); }
    }

    private void pump(String id, int handle) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer input = ByteBuffer.allocate(16384);
        CharBuffer output = CharBuffer.allocate(16384);
        byte[] buffer = new byte[4096];
        boolean logged = false;
        while (pumping(id)) {
            int size = GrainPty.read(handle, buffer, 200);
            if (size == 0) continue;
            if (size < 0) break;
            if (input.remaining() < size) {
                input.flip();
                decoder.decode(input, output, false);
                input.compact();
                emitDecoded(id, output, logged);
                logged = true;
            }
            input.put(buffer, 0, size);
            input.flip();
            decoder.decode(input, output, false);
            input.compact();
            if (emitDecoded(id, output, logged)) logged = true;
        }
        input.flip();
        decoder.decode(input, output, true);
        decoder.flush(output);
        emitDecoded(id, output, true);
        boolean owned;
        synchronized (terminalGate) {
            terminalPumps.remove(id);
            owned = Integer.valueOf(handle).equals(terminalHandles.get(id));
            if (owned) terminalHandles.remove(id);
        }
        if (owned) {
            try { GrainPty.close(handle); } catch (Throwable ignored) { /* Reader and close can overlap. */ }
            event(id, "exit", null, 0);
        }
    }

    private boolean emitDecoded(String id, CharBuffer output, boolean logged) {
        output.flip();
        String text = output.toString();
        output.clear();
        if (text.isEmpty()) return logged;
        if (!logged) Log.i(TAG, "pty: " + text.replace('\r', ' ').replace('\n', ' '));
        event(id, "data", text, null);
        return true;
    }

    private File writeShellRc(File node, File npm, File git) throws IOException {
        File rc = new File(getContext().getFilesDir(), "runtime/grain.shrc");
        File parent = rc.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建终端配置目录");
        }
        StringBuilder script = new StringBuilder();
        script.append("cd ").append(shellQuote(workspace().getAbsolutePath())).append(" || exit\n");
        if (node.isFile()) {
            script.append("node() { \"$GRAIN_NODE\" \"$@\"; }\n");
            if (npm != null) {
                String npmCli = shellQuote(new File(npm, "bin/npm-cli.js").getAbsolutePath());
                String npxCli = shellQuote(new File(npm, "bin/npx-cli.js").getAbsolutePath());
                script.append("npm() { \"$GRAIN_NODE\" ").append(npmCli).append(" \"$@\"; grain_status=$?; grain_bins; return $grain_status; }\n");
                script.append("npx() { \"$GRAIN_NODE\" ").append(npxCli).append(" \"$@\"; }\n");
            }
            script.append("grain_wrap_dir() {\n");
            script.append("  local dir=$1 f name target line\n");
            script.append("  [ -d \"$dir\" ] || return 0\n");
            script.append("  for f in \"$dir\"/*; do\n");
            script.append("    [ -e \"$f\" ] || [ -L \"$f\" ] || continue\n");
            script.append("    name=${f##*/}\n");
            script.append("    case $name in node|npm|npx|git|git-remote-http|git-remote-https|python|python3|*.cmd|*.ps1|*.grain-js|*.grain-cmd|*.grain-new) continue ;; esac\n");
            script.append("    case $name in *[!A-Za-z0-9_-]*|[!A-Za-z_]*) continue ;; esac\n");
            script.append("    if [ -L \"$f\" ]; then\n");
            script.append("      target=$(readlink \"$f\") || continue\n");
            script.append("      case $target in /*) ;; *) target=$(dirname \"$f\")/$target ;; esac\n");
            script.append("      target=$(cd \"$(dirname \"$target\")\" && pwd)/$(basename \"$target\")\n");
            script.append("      [ -f \"$target\" ] || continue\n");
            script.append("    else\n");
            script.append("      read -r line < \"$f\" || continue\n");
            script.append("      if [ -f \"$f.grain-js\" ]; then\n");
            script.append("        read -r target < \"$f.grain-js\" || continue\n");
            script.append("        [ -f \"$target\" ] || continue\n");
            script.append("      else\n");
            script.append("        case $line in '#!/system/bin/sh') continue ;; '#!'*node*) ;; *) continue ;; esac\n");
            script.append("        target=$f.grain-cmd\n");
            script.append("        mv \"$f\" \"$target\" || continue\n");
            script.append("      fi\n");
            script.append("    fi\n");
            script.append("    read -r line < \"$target\" || continue\n");
            script.append("    case $line in '#!'*node*) ;; *) continue ;; esac\n");
            script.append("    printf '%s\\n' '#!/system/bin/sh' \"exec \\\"\\$GRAIN_NODE\\\" '$target' \\\"\\$@\\\"\" > \"$f.grain-new\" || continue\n");
            script.append("    chmod 755 \"$f.grain-new\" || continue\n");
            script.append("    mv -f \"$f.grain-new\" \"$f\" || continue\n");
            script.append("    printf '%s\\n' \"$target\" > \"$f.grain-js\"\n");
            script.append("  done\n");
            script.append("}\n");
            script.append("grain_wrap() {\n");
            script.append("  grain_wrap_dir \"$GRAIN_PREFIX/bin\"\n");
            script.append("  grain_wrap_dir \"$GRAIN_ROOT/node_modules/.bin\"\n");
            script.append("}\n");
            script.append("grain_bins_dir() {\n");
            script.append("  local dir=$1 f name line js line2\n");
            script.append("  [ -d \"$dir\" ] || return 0\n");
            script.append("  for f in \"$dir\"/*; do\n");
            script.append("    [ -f \"$f\" ] || [ -L \"$f\" ] || continue\n");
            script.append("    name=${f##*/}\n");
            script.append("    case $name in node|npm|npx|git|git-remote-http|git-remote-https|python|python3|*.cmd|*.ps1|*.grain-js|*.grain-cmd|*.grain-new) continue ;; esac\n");
            script.append("    case $name in *[!A-Za-z0-9_-]*|[!A-Za-z_]*) continue ;; esac\n");
            script.append("    js=\n");
            script.append("    if [ -f \"$f.grain-js\" ]; then read -r js < \"$f.grain-js\"\n");
            script.append("    else\n");
            script.append("      read -r line < \"$f\" || continue\n");
            script.append("      case $line in\n");
            script.append("        '#!/system/bin/sh')\n");
            script.append("          if [ -f \"$f.grain-cmd\" ]; then js=$f.grain-cmd\n");
            script.append("          else line2=$(tail -n +2 \"$f\" | head -n 1); js=${line2#*\\'}; js=${js%%\\'*}; fi ;;\n");
            script.append("        '#!'*node*) js=$f ;;\n");
            script.append("        *) continue ;;\n");
            script.append("      esac\n");
            script.append("    fi\n");
            script.append("    [ -n \"$js\" ] && [ -f \"$js\" ] || continue\n");
            script.append("    eval \"$name() { \\\"\\$GRAIN_NODE\\\" \\\"$js\\\" \\\"\\$@\\\"; }\"\n");
            script.append("  done\n");
            script.append("}\n");
            script.append("grain_bins() {\n");
            script.append("  grain_wrap\n");
            script.append("  grain_bins_dir \"$GRAIN_PREFIX/bin\"\n");
            script.append("  grain_bins_dir \"$GRAIN_ROOT/node_modules/.bin\"\n");
            script.append("  hash -r\n");
            script.append("}\n");
            script.append("grain_bins\n");
        }
        if (git.isFile()) script.append("git() { \"$GRAIN_GIT\" \"$@\"; }\n");
        File python = pythonBinary();
        if (python.isFile()) {
            script.append("python() { \"$GRAIN_PYTHON\" \"$@\"; }\n");
            script.append("python3() { \"$GRAIN_PYTHON\" \"$@\"; }\n");
        }
        script.append("PS1='$ '\n");
        try (FileOutputStream output = new FileOutputStream(rc)) {
            output.write(script.toString().getBytes(StandardCharsets.UTF_8));
        }
        return rc;
    }

    private String[] shellEnvironment(File rc, File node) throws IOException {
        File prefix = prefixRoot();
        ArrayList<String> env = new ArrayList<>();
        env.add("HOME=" + getContext().getFilesDir().getAbsolutePath());
        env.add("TMPDIR=" + getContext().getCacheDir().getAbsolutePath());
        env.add("TERM=xterm-256color");
        env.add("ENV=" + rc.getAbsolutePath());
        env.add("GRAIN_NODE=" + node.getAbsolutePath());
        env.add("GRAIN_PREFIX=" + prefix.getAbsolutePath());
        env.add("GRAIN_ROOT=" + workspace().getAbsolutePath());
        env.add("PATH=" + new File(getContext().getFilesDir(), "runtime/bin").getAbsolutePath()
            + ":" + new File(prefix, "bin").getAbsolutePath()
            + ":/system/bin:/system/xbin");
        env.add("npm_config_cache=" + new File(getContext().getCacheDir(), "npm").getAbsolutePath());
        env.add("npm_config_prefix=" + prefix.getAbsolutePath());
        env.add("npm_config_update_notifier=false");
        env.add("npm_config_fund=false");
        File npmCli = new File(npmRoot(), "bin/npm-cli.js");
        File npxCli = new File(npmRoot(), "bin/npx-cli.js");
        if (npmCli.isFile() && npxCli.isFile()) {
            env.add("GRAIN_NPM_CLI=" + npmCli.getAbsolutePath());
            env.add("GRAIN_NPX_CLI=" + npxCli.getAbsolutePath());
            env.add("NODE_OPTIONS=--require=" + writeSpawnHook().getAbsolutePath());
        }
        File git = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_git.so");
        if (git.isFile()) {
            env.add("GRAIN_GIT=" + git.getAbsolutePath());
            try {
                env.add("GIT_TEMPLATE_DIR=" + ensureGitTemplates().getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "git template extract failed", e);
            }
            env.add("GIT_EXEC_PATH=" + new File(getContext().getFilesDir(), "runtime/bin").getAbsolutePath());
            try {
                File ca = ensureCaCerts();
                env.add("SSL_CERT_FILE=" + ca.getAbsolutePath());
                env.add("CURL_CA_BUNDLE=" + ca.getAbsolutePath());
                env.add("GIT_SSL_CAINFO=" + ca.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "CA cert extract failed", e);
            }
            env.add("LD_LIBRARY_PATH=" + getContext().getApplicationInfo().nativeLibraryDir);
        }
        File python = pythonBinary();
        if (python.isFile()) {
            env.add("GRAIN_PYTHON=" + python.getAbsolutePath());
            try {
                env.add("PYTHONHOME=" + ensurePython().getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "python stdlib extract failed", e);
            }
            env.add("PYTHONUNBUFFERED=1");
            env.add("LD_LIBRARY_PATH=" + getContext().getApplicationInfo().nativeLibraryDir);
        }
        return env.toArray(new String[0]);
    }

    private File writeSpawnHook() throws IOException {
        File hook = new File(getContext().getFilesDir(), "runtime/grain-spawn.js");
        File parent = hook.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建终端配置目录");
        }
        String source = """
            const cp = require('child_process')
            const fs = require('fs')
            const path = require('path')
            const node = process.env.GRAIN_NODE
            const bins = {
              npm: process.env.GRAIN_NPM_CLI,
              npx: process.env.GRAIN_NPX_CLI,
            }
            function readHead(file) {
              const fd = fs.openSync(file, 'r')
              try {
                const buf = Buffer.alloc(120)
                const n = fs.readSync(fd, buf, 0, 120, 0)
                return buf.slice(0, n).toString('utf8')
              } finally {
                fs.closeSync(fd)
              }
            }
            function resolveJs(command) {
              if (!command) return null
              try {
                const sidecar = command + '.grain-js'
                if (fs.existsSync(sidecar)) {
                  const js = fs.readFileSync(sidecar, 'utf8').trim().split(/\\r?\\n/)[0]
                  if (js && fs.existsSync(js)) return js
                }
                let file = command
                let st = fs.lstatSync(file)
                if (st.isSymbolicLink()) {
                  const target = fs.readlinkSync(file)
                  file = path.isAbsolute(target) ? target : path.resolve(path.dirname(command), target)
                  st = fs.lstatSync(file)
                }
                if (!st.isFile()) return null
                const line = (readHead(file).split(/\\r?\\n/)[0] || '')
                if (line.indexOf('node') !== -1 && /^#!/.test(line)) return file
                if (line.indexOf('/system/bin/sh') !== -1) {
                  const mapped = command + '.grain-cmd'
                  if (fs.existsSync(mapped)) return resolveJs(mapped) || mapped
                }
                return null
              } catch (e) {
                return null
              }
            }
            function which(cmd, env) {
              if (!cmd) return null
              if (cmd.indexOf('/') !== -1) return cmd
              const pathEnv = (env && env.PATH) || process.env.PATH || ''
              for (const dir of pathEnv.split(':')) {
                if (!dir) continue
                const candidate = path.join(dir, cmd)
                try {
                  fs.accessSync(candidate)
                  return candidate
                } catch (e) {}
              }
              return null
            }
            function rewriteShellLine(line, env) {
              if (!node || line == null) return null
              const trimmed = String(line).trim()
              const match = trimmed.match(/^([A-Za-z0-9_@./+-]+)(\\s+[\\s\\S]*)?$/)
              if (!match) return null
              let cmd = match[1]
              const rest = match[2] || ''
              if (bins[cmd]) return JSON.stringify(node) + ' ' + JSON.stringify(bins[cmd]) + rest
              const resolved = which(cmd, env)
              if (!resolved) return null
              const js = resolveJs(resolved)
              if (!js) return null
              return JSON.stringify(node) + ' ' + JSON.stringify(js) + rest
            }
            function rewrite(command, args, options) {
              if (!node) return null
              const env = (options && options.env) || process.env
              if (options && options.shell) {
                const line = args && args.length ? [command].concat(args).join(' ') : String(command)
                const next = rewriteShellLine(line, env)
                if (!next) return null
                return { command: next, args: [], options: Object.assign({}, options, { shell: true }) }
              }
              if ((command === 'sh' || command === '/system/bin/sh' || /\\/sh$/.test(String(command)))
                  && args && args[0] === '-c' && args[1]) {
                const next = rewriteShellLine(args[1], env)
                if (!next) return null
                const copy = args.slice()
                copy[1] = next
                return { command: command, args: copy, options: options }
              }
              if (bins[command]) return { command: node, args: [bins[command]].concat(args || []), options: options }
              const found = command.indexOf('/') === -1 ? which(command, env) : command
              const script = resolveJs(found)
              if (script) return { command: node, args: [script].concat(args || []), options: options }
              return null
            }
            function wrap(name) {
              const orig = cp[name]
              if (typeof orig !== 'function' || orig.grainSpawn) return
              function wrapped(command, args, options, callback) {
                if (args && typeof args === 'object' && !Array.isArray(args)) {
                  callback = options
                  options = args
                  args = []
                }
                const next = rewrite(command, args || [], options || {})
                if (next) {
                  command = next.command
                  args = next.args
                  options = next.options
                }
                if (typeof callback === 'function') return orig.call(cp, command, args, options, callback)
                return orig.call(cp, command, args, options)
              }
              wrapped.grainSpawn = true
              cp[name] = wrapped
            }
            function wrapExec(name) {
              const orig = cp[name]
              if (typeof orig !== 'function' || orig.grainSpawn) return
              function wrapped(command, options, callback) {
                if (typeof options === 'function') {
                  callback = options
                  options = {}
                }
                const next = rewriteShellLine(command, (options && options.env) || process.env)
                if (next) command = next
                if (typeof callback === 'function') return orig.call(cp, command, options, callback)
                return orig.call(cp, command, options)
              }
              wrapped.grainSpawn = true
              cp[name] = wrapped
            }
            for (const name of ['spawn', 'spawnSync', 'execFile', 'execFileSync']) wrap(name)
            for (const name of ['exec', 'execSync']) wrapExec(name)
            """;
        try (FileOutputStream output = new FileOutputStream(hook)) {
            output.write(source.getBytes(StandardCharsets.UTF_8));
        }
        return hook;
    }

    private File npmRoot() {
        return new File(getContext().getFilesDir(), "runtime/npm");
    }

    private File prefixRoot() {
        return new File(getContext().getFilesDir(), "runtime/prefix");
    }

    private File ensurePrefix() throws IOException {
        File bin = new File(prefixRoot(), "bin");
        if (!bin.mkdirs() && !bin.isDirectory()) throw new IOException("无法创建全局命令目录");
        return prefixRoot();
    }

    private void ensureRuntimeCommands() throws IOException, InterruptedException {
        File bin = new File(getContext().getFilesDir(), "runtime/bin");
        if (!bin.mkdirs() && !bin.isDirectory()) throw new IOException("无法创建命令目录");
        linkRuntimeCommand(bin, "libgrain_node.so", "node");
        linkRuntimeCommand(bin, "libgrain_git.so", "git");
        linkRuntimeCommand(bin, "libgrain_git_remote_http.so", "git-remote-http");
        linkRuntimeCommand(bin, "libgrain_git_remote_http.so", "git-remote-https");
        linkRuntimeCommand(bin, "libgrain_python.so", "python3");
        linkRuntimeCommand(bin, "libgrain_python.so", "python");
    }

    private static void putTlsCertEnv(Map<String, String> env, File ca) {
        String path = ca.getAbsolutePath();
        env.put("SSL_CERT_FILE", path);
        env.put("CURL_CA_BUNDLE", path);
        env.put("GIT_SSL_CAINFO", path);
    }

    private File caCertFile() {
        return new File(getContext().getFilesDir(), "runtime/certs/cacert.pem");
    }

    private synchronized File ensureCaCerts() throws IOException {
        File dest = caCertFile();
        if (dest.isFile() && dest.length() > 1024) return dest;
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建证书目录");
        }
        try (InputStream input = getContext().getAssets().open("cacert.pem");
             FileOutputStream output = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
        }
        return dest;
    }

    private void linkRuntimeCommand(File bin, String library, String name) throws IOException, InterruptedException {
        File source = new File(getContext().getApplicationInfo().nativeLibraryDir, library);
        if (!source.isFile()) return;
        Process link = new ProcessBuilder("/system/bin/ln", "-sfn", source.getAbsolutePath(), new File(bin, name).getAbsolutePath()).start();
        if (link.waitFor() != 0) throw new IOException("无法创建 " + name + " 命令");
    }

    private static void prependPath(ProcessBuilder builder, File... directories) {
        StringBuilder path = new StringBuilder();
        for (File directory : directories) {
            if (path.length() > 0) path.append(':');
            path.append(directory.getAbsolutePath());
        }
        String current = builder.environment().get("PATH");
        if (current != null && !current.isEmpty()) path.append(':').append(current);
        builder.environment().put("PATH", path.toString());
    }

    private void appendGlobalCommands(StringBuilder script, String nodePath) {
        File[] bins = new File(prefixRoot(), "bin").listFiles();
        if (bins == null) return;
        for (File bin : bins) {
            String name = bin.getName();
            if (!bin.isFile() || name.endsWith(".cmd") || name.endsWith(".ps1")) continue;
            if (!name.matches("[A-Za-z_][A-Za-z0-9_-]*")) continue;
            if (name.equals("node") || name.equals("npm") || name.equals("npx")) continue;
            script.append(name).append("() { ").append(nodePath).append(" ")
                .append(shellQuote(bin.getAbsolutePath())).append(" \"$@\"; }; ");
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String commandScript(String command) {
        File node = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_node.so");
        File git = new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_git.so");
        if (!node.isFile() && !git.isFile()) return command;
        StringBuilder script = new StringBuilder();
        if (node.isFile()) {
            String nodePath = shellQuote(node.getAbsolutePath());
            script.append("node() { ").append(nodePath).append(" \"$@\"; }; ");
            try {
                File npm = ensureNpm();
                String npmCli = shellQuote(new File(npm, "bin/npm-cli.js").getAbsolutePath());
                String npxCli = shellQuote(new File(npm, "bin/npx-cli.js").getAbsolutePath());
                script.append("npm() { ").append(nodePath).append(" ").append(npmCli).append(" \"$@\"; }; ");
                script.append("npx() { ").append(nodePath).append(" ").append(npxCli).append(" \"$@\"; }; ");
                appendGlobalCommands(script, nodePath);
            } catch (Exception e) {
                Log.e(TAG, "npm extract failed", e);
            }
        }
        if (git.isFile()) {
            script.append("git() { ").append(shellQuote(git.getAbsolutePath())).append(" \"$@\"; }; ");
        }
        File python = pythonBinary();
        if (python.isFile()) {
            String pythonPath = shellQuote(python.getAbsolutePath());
            script.append("python() { ").append(pythonPath).append(" \"$@\"; }; ");
            script.append("python3() { ").append(pythonPath).append(" \"$@\"; }; ");
        }
        script.append(command);
        return script.toString();
    }

    private File pythonBinary() {
        return new File(getContext().getApplicationInfo().nativeLibraryDir, "libgrain_python.so");
    }

    private File pythonHome() {
        return new File(getContext().getFilesDir(), "runtime/python");
    }

    private synchronized File ensurePython() throws IOException {
        File root = pythonHome();
        File marker = new File(root, ".grain-python-version");
        File stdlib = new File(root, "lib/python3.13/os.py");
        if (stdlib.isFile() && PYTHON_VERSION.equals(readSmall(marker))) return root;
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建 Python 目录");
        extractZip(getContext().getAssets().open("python-stdlib.zip"), root);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(PYTHON_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private File gitTemplateRoot() {
        return new File(getContext().getFilesDir(), "runtime/git/templates");
    }

    private synchronized File ensureGitTemplates() throws IOException {
        File root = gitTemplateRoot();
        File marker = new File(root, ".grain-git-version");
        if (new File(root, "description").isFile() && GIT_VERSION.equals(readSmall(marker))) return root;
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建 Git 模板目录");
        extractZip(getContext().getAssets().open("git-templates.zip"), root);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(GIT_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static void extractZip(InputStream raw, File root) throws IOException {
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..")) throw new IOException("非法压缩包路径");
                if (entry.isDirectory()) continue;
                File out = new File(root, name);
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建目录");
                try (FileOutputStream output = new FileOutputStream(out)) {
                    int size;
                    while ((size = zip.read(buffer)) != -1) output.write(buffer, 0, size);
                }
                zip.closeEntry();
            }
        }
    }

    private synchronized File ensureWatcher() throws IOException {
        File root = new File(getContext().getFilesDir(), "runtime/watcher");
        File marker = new File(root, ".grain-watcher-version");
        File script = new File(root, "watch.js");
        File binding = new File(root, "lib/watcher.node");
        File cxx = new File(root, "lib/libc++_shared.so");
        if (script.isFile() && binding.isFile() && cxx.isFile() && WATCHER_VERSION.equals(readSmall(marker))) return root;
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建文件监听目录");
        extractZip(getContext().getAssets().open("watcher.zip"), root);
        binding.setReadable(true, false);
        binding.setExecutable(true, false);
        cxx.setReadable(true, false);
        cxx.setExecutable(true, false);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(WATCHER_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private synchronized File ensureNpm() throws IOException {
        File root = npmRoot();
        File marker = new File(root, ".grain-npm-version");
        File cli = new File(root, "bin/npm-cli.js");
        if (cli.isFile() && NPM_VERSION.equals(readSmall(marker))) return root;
        deleteRecursively(root);
        if (!root.mkdirs() && !root.isDirectory()) throw new IOException("无法创建 npm 目录");
        byte[] buffer = new byte[8192];
        try (InputStream raw = getContext().getAssets().open("npm.zip");
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("..")) throw new IOException("非法 npm 路径");
                if (entry.isDirectory()) continue;
                File out = new File(root, name);
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建 npm 目录");
                try (FileOutputStream output = new FileOutputStream(out)) {
                    int size;
                    while ((size = zip.read(buffer)) != -1) output.write(buffer, 0, size);
                }
                zip.closeEntry();
            }
        }
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(NPM_VERSION.getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    private static String readSmall(File file) {
        if (!file.isFile() || file.length() > 64) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int size = input.read(data, offset, data.length - offset);
                if (size < 0) break;
                offset += size;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }
}
