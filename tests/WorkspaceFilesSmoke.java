package app.grain.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class WorkspaceFilesSmoke {
    interface Action { void run() throws Exception; }
    static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected an IOException");
    }
    static void check(boolean condition) {
        if (!condition) throw new AssertionError("Workspace persistence check failed");
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("grain-workspace-test-");
        try {
            WorkspaceFiles files = new WorkspaceFiles(temporary.resolve("workspace").toFile());
            String source = "console.log('你好 🌾')\n";
            files.write("src/main.js", source);
            check(source.equals(new WorkspaceFiles(files.root()).read("src/main.js")));
            files.write("src/main.js", "updated");
            check("updated".equals(files.read("src/main.js")));
            check(files.read("missing.js") == null);
            rejects(() -> files.write("../outside.js", "bad"));
            rejects(() -> files.read(temporary.resolve("outside.js").toString()));
            Files.createDirectory(temporary.resolve("outside"));
            Files.createSymbolicLink(files.root().toPath().resolve("link"), temporary.resolve("outside"));
            rejects(() -> files.write("link/escape.js", "bad"));
            rejects(() -> files.write("src/main.js", "x".repeat(2 * 1024 * 1024 + 1)));
            check("updated".equals(files.read("src/main.js")));
            System.out.println("PASS: UTF-8 persistence, reopen, replacement, missing file, traversal, symlink, size limit");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
