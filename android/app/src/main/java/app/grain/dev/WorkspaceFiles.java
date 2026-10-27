package app.grain.dev;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** UTF-8 workspace persistence, using APIs available on Android API 24. */
final class WorkspaceFiles {
    private static final int LIMIT = 2 * 1024 * 1024;
    private static final int LIST_LIMIT = 4000;
    private static final Set<String> SKIP_DIRS = new HashSet<>();
    private static final Set<String> SEARCH_SKIP_DIRS = new HashSet<>();
    static {
        SKIP_DIRS.add(".git");
        SKIP_DIRS.add(".grain");
        SKIP_DIRS.add("dist");
        SKIP_DIRS.add("coverage");
        SKIP_DIRS.add(".cache");
        SKIP_DIRS.add(".gradle");
        SKIP_DIRS.add(".idea");
        SKIP_DIRS.add(".nuxt");
        SKIP_DIRS.add(".output");
        SKIP_DIRS.add(".next");
        SEARCH_SKIP_DIRS.addAll(SKIP_DIRS);
        SEARCH_SKIP_DIRS.add("node_modules");
        SEARCH_SKIP_DIRS.add("build");
    }
    private final File root;

    WorkspaceFiles(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) throw new IOException("无法创建工作区");
        root = directory.getCanonicalFile();
    }

    File root() { return root; }

    /** Resolve a workspace-relative directory; empty path returns the root. */
    File directory(String relative) throws IOException {
        if (relative == null || relative.isEmpty()) return root;
        File file = resolve(relative);
        if (!file.isDirectory()) throw new IOException("不是文件夹");
        return file;
    }

    private File resolve(String path) throws IOException {
        if (path == null || path.isEmpty() || new File(path).isAbsolute()) throw new IOException("需要相对工作区的文件路径");
        File file = new File(root, path).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator)) throw new IOException("路径超出工作区");
        return file;
    }

    List<String> list() throws IOException {
        ArrayList<String> paths = new ArrayList<>();
        collect(root, "", paths, new HashSet<String>());
        return paths;
    }

    /** Immediate children of a workspace directory. Empty path lists the root. */
    List<DirEntry> listDirectory(String relative) throws IOException {
        File dir = (relative == null || relative.isEmpty()) ? root : resolve(relative);
        if (!dir.isDirectory()) throw new IOException("不是文件夹");
        String canonical = dir.getCanonicalPath();
        if (!canonical.equals(root.getPath()) && !canonical.startsWith(root.getPath() + File.separator)) {
            throw new IOException("路径超出工作区");
        }
        ArrayList<DirEntry> entries = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return entries;
        String base = relative == null ? "" : relative;
        for (File child : children) {
            String name = child.getName();
            if (name.equals(".") || name.equals("..") || SKIP_DIRS.contains(name)) continue;
            String path = base.isEmpty() ? name : base + "/" + name;
            if (child.isDirectory()) entries.add(new DirEntry(name, path, true));
            else if (child.isFile()) entries.add(new DirEntry(name, path, false));
        }
        return entries;
    }

    static final class DirEntry {
        final String name;
        final String path;
        final boolean directory;

        DirEntry(String name, String path, boolean directory) {
            this.name = name;
            this.path = path;
            this.directory = directory;
        }
    }

    private void collect(File dir, String relative, List<String> paths, Set<String> seen) throws IOException {
        if (paths.size() >= LIST_LIMIT) return;
        String canonical = dir.getCanonicalPath();
        if (!canonical.equals(root.getPath()) && !canonical.startsWith(root.getPath() + File.separator)) return;
        if (!seen.add(canonical)) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (paths.size() >= LIST_LIMIT) return;
            String name = child.getName();
            if (name.equals(".") || name.equals("..") || SKIP_DIRS.contains(name)) continue;
            String path = relative.isEmpty() ? name : relative + "/" + name;
            if (child.isDirectory()) collect(child, path, paths, seen);
            else if (child.isFile()) paths.add(path);
        }
    }

    String read(String path) throws IOException {
        File file = resolve(path);
        if (!file.isFile()) return null;
        if (file.length() > LIMIT) throw new IOException("文件超过 2 MB 编辑限制");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (bytes.size() + size > LIMIT) throw new IOException("文件超过 2 MB 编辑限制");
                bytes.write(buffer, 0, size);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    void write(String path, String content) throws IOException {
        if (content == null) throw new IOException("缺少文件内容");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("文件超过 2 MB 编辑限制");
        File file = resolve(path);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) throw new IOException("无法创建目录");
        File temporary = File.createTempFile(".grain-", ".tmp", parent);
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            // Same-directory rename is atomic on the Android/Linux file system.
            if (!temporary.renameTo(file)) throw new IOException("无法替换工作区文件");
        } finally { temporary.delete(); }
    }

    void delete(String path) throws IOException {
        File file = resolve(path);
        if (file.equals(root)) throw new IOException("不能删除工作区");
        if (!file.exists()) return;
        deleteTree(file);
    }

    void clear() throws IOException {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) deleteTree(child);
    }

    /** Make a subdirectory the new workspace root contents. */
    void promote(String path) throws IOException {
        File source = resolve(path);
        if (!source.isDirectory()) throw new IOException("只能打开文件夹");
        if (source.equals(root)) return;
        File staging = new File(root, ".grain-open-" + System.nanoTime());
        if (staging.exists()) throw new IOException("无法准备打开目录");
        if (!source.renameTo(staging)) throw new IOException("无法打开该文件夹");
        try {
            File[] siblings = root.listFiles();
            if (siblings != null) {
                for (File child : siblings) {
                    if (child.equals(staging)) continue;
                    deleteTree(child);
                }
            }
            File[] promoted = staging.listFiles();
            if (promoted != null) {
                for (File child : promoted) {
                    File target = new File(root, child.getName());
                    if (target.exists()) deleteTree(target);
                    if (!child.renameTo(target)) throw new IOException("无法移入 " + child.getName());
                }
            }
        } finally {
            if (staging.exists()) deleteTree(staging);
        }
    }

    void rename(String from, String to) throws IOException {
        File source = resolve(from);
        File target = resolve(to);
        if (source.equals(root)) throw new IOException("不能重命名工作区");
        if (!source.exists()) throw new IOException("路径不存在");
        String sourcePath = source.getPath();
        String targetPath = target.getPath();
        if (targetPath.equals(sourcePath) || targetPath.startsWith(sourcePath + File.separator)) {
            throw new IOException("不能把目录移进自身");
        }
        if (target.exists()) throw new IOException("目标已存在");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录");
        }
        if (!source.renameTo(target)) throw new IOException("无法重命名");
    }

    private void deleteTree(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        if (!file.delete() && file.exists()) throw new IOException("无法删除 " + file.getName());
    }

    static final class SearchHit {
        final String path;
        final int line;
        final int column;
        final String text;
        final int matchStart;
        final int matchEnd;

        SearchHit(String path, int line, int column, String text, int matchStart, int matchEnd) {
            this.path = path;
            this.line = line;
            this.column = column;
            this.text = text;
            this.matchStart = matchStart;
            this.matchEnd = matchEnd;
        }
    }

    static final class SearchResult {
        final Map<String, List<SearchHit>> files;
        final boolean truncated;

        SearchResult(Map<String, List<SearchHit>> files, boolean truncated) {
            this.files = files;
            this.truncated = truncated;
        }
    }

    SearchResult searchContent(
        String query,
        String cwdRel,
        boolean caseSensitive,
        boolean wholeWord,
        boolean isRegex,
        int maxResults
    ) throws IOException {
        if (query == null || query.isEmpty()) {
            return new SearchResult(new LinkedHashMap<String, List<SearchHit>>(), false);
        }
        int limit = Math.min(Math.max(maxResults, 1), 5000);
        final Pattern pattern;
        final String needle;
        if (isRegex) {
            try {
                String source = wholeWord ? ("\\b(?:" + query + ")\\b") : query;
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                pattern = Pattern.compile(source, flags);
                needle = null;
            } catch (PatternSyntaxException e) {
                throw new IOException("正则表达式无效");
            }
        } else {
            pattern = null;
            needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        }

        File start = (cwdRel == null || cwdRel.isEmpty()) ? root : directory(cwdRel);
        Map<String, List<SearchHit>> grouped = new LinkedHashMap<>();
        int[] total = { 0 };
        boolean[] truncated = { false };
        searchWalk(start, relativeUnderRoot(start), pattern, needle, caseSensitive, wholeWord, grouped, total, truncated, limit);
        return new SearchResult(grouped, truncated[0]);
    }

    private String relativeUnderRoot(File file) throws IOException {
        String rootPath = root.getPath();
        String path = file.getCanonicalPath();
        if (path.equals(rootPath)) return "";
        if (!path.startsWith(rootPath + File.separator)) throw new IOException("路径超出工作区");
        return path.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private void searchWalk(
        File dir,
        String relative,
        Pattern pattern,
        String needle,
        boolean caseSensitive,
        boolean wholeWord,
        Map<String, List<SearchHit>> grouped,
        int[] total,
        boolean[] truncated,
        int limit
    ) throws IOException {
        if (truncated[0] || total[0] >= limit) {
            truncated[0] = true;
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (total[0] >= limit) {
                truncated[0] = true;
                return;
            }
            String name = child.getName();
            if (name.equals(".") || name.equals("..") || SEARCH_SKIP_DIRS.contains(name)) continue;
            String path = relative.isEmpty() ? name : relative + "/" + name;
            if (child.isDirectory()) {
                searchWalk(child, path, pattern, needle, caseSensitive, wholeWord, grouped, total, truncated, limit);
            } else if (child.isFile()) {
                searchFile(child, path, pattern, needle, caseSensitive, wholeWord, grouped, total, truncated, limit);
            }
        }
    }

    private void searchFile(
        File file,
        String path,
        Pattern pattern,
        String needle,
        boolean caseSensitive,
        boolean wholeWord,
        Map<String, List<SearchHit>> grouped,
        int[] total,
        boolean[] truncated,
        int limit
    ) throws IOException {
        if (file.length() > LIMIT || file.length() == 0) return;
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
            || lowerName.endsWith(".gif") || lowerName.endsWith(".webp") || lowerName.endsWith(".ico")
            || lowerName.endsWith(".pdf") || lowerName.endsWith(".zip") || lowerName.endsWith(".gz")
            || lowerName.endsWith(".woff") || lowerName.endsWith(".woff2") || lowerName.endsWith(".ttf")
            || lowerName.endsWith(".mp3") || lowerName.endsWith(".mp4") || lowerName.endsWith(".wasm")
            || lowerName.endsWith(".so") || lowerName.endsWith(".apk") || lowerName.endsWith(".jar")) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo += 1;
                if (line.indexOf('\0') >= 0) return;
                int start = -1;
                int end = -1;
                if (pattern != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (!matcher.find()) continue;
                    start = matcher.start();
                    end = matcher.end();
                } else {
                    String hay = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                    int from = 0;
                    while (from <= hay.length()) {
                        int idx = hay.indexOf(needle, from);
                        if (idx < 0) break;
                        int stop = idx + needle.length();
                        if (wholeWord) {
                            boolean okBefore = idx == 0 || !isWordChar(hay.charAt(idx - 1));
                            boolean okAfter = stop >= hay.length() || !isWordChar(hay.charAt(stop));
                            if (!(okBefore && okAfter)) {
                                from = idx + 1;
                                continue;
                            }
                        }
                        start = idx;
                        end = stop;
                        break;
                    }
                    if (start < 0) continue;
                }
                String text = line.length() > 240 ? line.substring(0, 240) + "…" : line;
                List<SearchHit> list = grouped.get(path);
                if (list == null) {
                    list = new ArrayList<>();
                    grouped.put(path, list);
                }
                list.add(new SearchHit(path, lineNo, start + 1, text, start, end));
                total[0] += 1;
                if (total[0] >= limit) {
                    truncated[0] = true;
                    return;
                }
            }
        } catch (IOException e) {
            /* skip unreadable files */
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || c == '_';
    }
}
