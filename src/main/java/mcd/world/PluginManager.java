package mcd.world;

import mcd.server.PaperBuild;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

/** 로컬 JAR 검증·설치. 호출자는 관리 락과 백업을 먼저 확보한다. */
public final class PluginManager {
    public static final String MANIFEST = "mcd-plugins.properties";
    private PluginManager() {}

    public record Plugin(String name, String version, String api, List<String> required, List<String> provides, Path file) {
        @Override public String toString() { return name + " " + version + " (배포 파일)"; }
    }

    public static Plugin inspect(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) >= SafeFiles.MAX_FILE) throw new IOException("설치 가능한 크기의 일반 JAR 파일이 아닙니다");
        try (JarFile jar = new JarFile(file.toFile())) {
            var entry = jar.getJarEntry("paper-plugin.yml");
            boolean paper = entry != null;
            if (entry == null) entry = jar.getJarEntry("plugin.yml");
            if (entry == null) throw new IOException("plugin.yml 또는 paper-plugin.yml이 없습니다. Fabric/Forge 모드는 지원하지 않습니다.");
            Map<?, ?> data;
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(256 * 1024 + 1);
                if (bytes.length > 256 * 1024) throw new IOException("플러그인 descriptor가 너무 큽니다");
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(16);
                options.setNestingDepthLimit(16);
                options.setCodePointLimit(256 * 1024);
                Object value = new Yaml(new SafeConstructor(options)).load(new String(bytes, StandardCharsets.UTF_8));
                if (!(value instanceof Map<?, ?> map)) throw new IOException("잘못된 플러그인 descriptor");
                data = map;
            }
            String name = text(data, "name");
            if (!name.matches("[A-Za-z0-9_.-]+")) throw new IOException("지원하지 않는 플러그인 이름: " + name);
            String main = text(data, "main");
            if (!main.matches("[A-Za-z_$][A-Za-z0-9_$.]*") || jar.getJarEntry(main.replace('.', '/') + ".class") == null) throw new IOException("플러그인의 main class가 없습니다");
            List<String> required = new ArrayList<>(strings(data.get("depend")));
            if (paper && data.get("dependencies") instanceof Map<?, ?> dependencies) {
                for (String phase : List.of("bootstrap", "server")) {
                    if (dependencies.get(phase) instanceof Map<?, ?> plugins) {
                        for (var plugin : plugins.entrySet()) {
                            if (!(plugin.getValue() instanceof Map<?, ?> config)) throw new IOException("잘못된 Paper 의존성 설정");
                            if (!Boolean.FALSE.equals(config.get("required"))) required.add(plugin.getKey().toString());
                        }
                    }
                }
            }
            return new Plugin(name, text(data, "version"), Objects.toString(data.get("api-version"), ""),
                    List.copyOf(required), strings(data.get("provides")), file);
        } catch (org.yaml.snakeyaml.error.YAMLException | IllegalArgumentException e) {
            throw new IOException("플러그인 descriptor를 읽지 못했습니다: " + e.getMessage(), e);
        }
    }

    public static List<Plugin> list(Path directory) throws IOException {
        Path plugins = directory.resolve("plugins");
        if (!Files.exists(plugins)) return List.of();
        if (Files.isSymbolicLink(plugins)) throw new IOException("plugins 폴더가 링크입니다");
        List<Plugin> result = new ArrayList<>();
        try (var files = Files.list(plugins)) {
            for (Path jar : files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()) result.add(inspect(jar));
        }
        return List.copyOf(result);
    }

    public static void install(Path directory, Path input) throws Exception {
        Plugin next = inspect(input);
        List<Plugin> existing = list(directory);
        List<Plugin> desired = new ArrayList<>(existing.stream().filter(p -> !p.name().equalsIgnoreCase(next.name())).toList());
        desired.add(next);
        validate(desired, WorldTemplate.paperBuild(directory));
        migrateIgnore(directory);
        Path destination = SafeFiles.resolve(directory.resolve("plugins"), next.name().toLowerCase(Locale.ROOT) + ".jar");
        Files.createDirectories(destination.getParent());
        // 입력을 먼저 별도 사본으로 고정해 같은 경로를 교체하는 경우도 처리한다.
        Path staged = Files.createTempFile("mcd-plugin-", ".jar");
        try {
            Files.copy(input, staged, StandardCopyOption.REPLACE_EXISTING);
            for (Plugin previous : existing) if (previous.name().equalsIgnoreCase(next.name())) Files.delete(previous.file());
            Files.copy(staged, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
        writeManifest(directory);
    }

    public static void remove(Path directory, String name) throws Exception {
        List<Plugin> existing = list(directory);
        Plugin removing = existing.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IOException("플러그인이 없습니다: " + name));
        validate(existing.stream().filter(p -> p != removing).toList(), WorldTemplate.paperBuild(directory));
        migrateIgnore(directory);
        Files.delete(removing.file());
        writeManifest(directory);
    }

    public static void verifyRuntime(Path directory) throws IOException {
        Properties expected = new Properties();
        Path manifest = directory.resolve(MANIFEST);
        if (Files.exists(manifest)) try (Reader reader = Files.newBufferedReader(manifest)) { expected.load(reader); }
        List<Plugin> installed = list(directory);
        if (installed.size() != expected.size()) throw new IOException("관리 목록과 plugins JAR가 다릅니다. 로컬 전용 JAR를 빼고 플러그인 관리에서 추가하세요.");
        for (Plugin plugin : installed) {
            String hash = expected.getProperty(plugin.file().getFileName().toString());
            if (!sha256(plugin.file()).equals(hash)) throw new IOException("플러그인 파일이 관리 목록과 다릅니다: " + plugin.name());
        }
        validate(installed, WorldTemplate.paperBuild(directory));
    }

    private static void validate(List<Plugin> plugins, PaperBuild build) throws IOException {
        Set<String> names = new HashSet<>();
        Set<String> provided = new HashSet<>();
        for (Plugin plugin : plugins) {
            if (!names.add(plugin.name().toLowerCase(Locale.ROOT))) throw new IOException("중복 플러그인: " + plugin.name());
            provided.add(plugin.name().toLowerCase(Locale.ROOT));
            plugin.provides().forEach(p -> provided.add(p.toLowerCase(Locale.ROOT)));
            if (!plugin.api().isEmpty() && (!plugin.api().matches("\\d+(\\.\\d+){0,2}") || build.compareVersion(plugin.api()) < 0)) throw new IOException("현재 Paper보다 새 API가 필요합니다: " + plugin.name() + " " + plugin.api());
        }
        for (Plugin plugin : plugins) for (String dependency : plugin.required()) {
            if (!provided.contains(dependency.toLowerCase(Locale.ROOT))) throw new IOException(plugin.name() + "에 필요한 플러그인이 없습니다: " + dependency);
        }
    }

    private static void migrateIgnore(Path directory) throws IOException {
        Path ignore = directory.resolve(".gitignore");
        String previous = Files.exists(ignore) ? Files.readString(ignore) : "";
        String rules = "\n# mcd managed plugins\n!/plugins/\n!/plugins/*.jar\n/plugins/.paper-remapped/\n";
        if (!previous.contains("!/plugins/\n!/plugins/*.jar\n/plugins/.paper-remapped/")) Files.writeString(ignore, previous + rules);
    }

    private static void writeManifest(Path directory) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Plugin plugin : list(directory)) text.append(plugin.file().getFileName()).append('=').append(sha256(plugin.file())).append('\n');
        Files.writeString(directory.resolve(MANIFEST), text);
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(Map<?, ?> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String || value instanceof Number) || value.toString().isBlank()) throw new IOException("플러그인 " + key + " 항목이 없습니다");
        return value.toString();
    }

    private static List<String> strings(Object value) throws IOException {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) throw new IOException("플러그인 의존성/제공 항목은 문자열 목록이어야 합니다");
        return list.stream().map(Object::toString).toList();
    }
}
