package com.codemem.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class ObsidianScanner {

    private static final Logger log = LoggerFactory.getLogger(ObsidianScanner.class);
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern WIKI_LINK = Pattern.compile("\\[\\[([^\\]|#]+)(?:#[^\\]|]+)?(?:\\|[^\\]]+)?]]");
    // 普通 markdown 目录里的链接：[标题](路径.md#锚点)
    private static final Pattern MD_LINK = Pattern.compile("\\[[^\\]]*]\\(([^)\\s]+)");

    private final KnowledgeProperties properties;

    public ObsidianScanner(KnowledgeProperties properties) {
        this.properties = properties;
    }

    public List<ParsedNote> scan() {
        return scan(properties.root(), KnowledgeSourceType.OBSIDIAN);
    }

    /**
     * Scans one note library. Obsidian vaults additionally understand [[wikilinks]];
     * plain markdown folders accept .txt too and read standard markdown links.
     */
    public List<ParsedNote> scan(String rootPath, KnowledgeSourceType type) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("knowledge root is not a directory: " + root);
        }
        Set<String> extensions = extensionsOf(type);

        try (Stream<Path> stream = Files.walk(root, 12)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> matchesExtension(path, extensions))
                    .filter(path -> !isHidden(root.relativize(path)))
                    .sorted()
                    .toList();

            List<ParsedNote> notes = new ArrayList<>(files.size());
            for (Path file : files) {
                try {
                    notes.add(parse(root, file, type));
                } catch (Exception ex) {
                    log.warn("Unable to parse note {}: {}", file, ex.getMessage());
                }
            }
            return notes;
        } catch (IOException ex) {
            throw new IllegalStateException("unable to scan knowledge root: " + root, ex);
        }
    }

    private Set<String> extensionsOf(KnowledgeSourceType type) {
        if (type == KnowledgeSourceType.MARKDOWN) {
            return Set.of(".md", ".markdown", ".txt");
        }
        return Set.of(".md", ".markdown");
    }

    private boolean matchesExtension(Path path, Set<String> extensions) {
        String fileName = path.getFileName().toString().toLowerCase();
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private ParsedNote parse(Path root, Path file, KnowledgeSourceType type) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
            raw = raw.substring(1);
        }
        raw = raw.replace("\r\n", "\n");

        Frontmatter frontmatter = parseFrontmatter(raw);
        String title = firstHeading(frontmatter.body())
                .orElseGet(() -> stripExtension(file.getFileName().toString()));
        List<NoteChunk> chunks = chunk(frontmatter.body(), title, type);

        Set<String> links = new LinkedHashSet<>();
        for (NoteChunk chunk : chunks) {
            links.addAll(chunk.links());
        }

        return new ParsedNote(
                root.relativize(file).toString().replace('\\', '/'),
                title,
                frontmatter.values().get("subject"),
                frontmatter.values().get("status"),
                parseList(frontmatter.values().get("tags")),
                List.copyOf(links),
                HashUtils.sha256(raw),
                Files.getLastModifiedTime(file).toInstant(),
                chunks
        );
    }

    private Frontmatter parseFrontmatter(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new Frontmatter(Map.of(), content);
        }

        int end = -1;
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].trim().equals("---")) {
                end = index;
                break;
            }
        }
        if (end < 0) {
            return new Frontmatter(Map.of(), content);
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < end; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            if (!key.isEmpty() && !value.isEmpty()) {
                values.put(key, value);
            }
        }

        String body = String.join("\n", Arrays.copyOfRange(lines, end + 1, lines.length)).trim();
        return new Frontmatter(values, body);
    }

    private List<NoteChunk> chunk(String body, String title, KnowledgeSourceType type) {
        List<NoteChunk> chunks = new ArrayList<>();
        Deque<String> headings = new ArrayDeque<>();
        StringBuilder current = new StringBuilder();
        boolean inCode = false;
        String fence = null;

        for (String line : body.split("\n", -1)) {
            String trimmed = line.trim();
            boolean fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (fenceLine) {
                if (!inCode) {
                    inCode = true;
                    fence = trimmed.substring(0, 3);
                } else if (trimmed.startsWith(fence)) {
                    inCode = false;
                    fence = null;
                }
                current.append(line).append('\n');
                continue;
            }

            if (!inCode) {
                Matcher matcher = HEADING.matcher(trimmed);
                if (matcher.matches()) {
                    flushChunks(chunks, headings, current, title, type);
                    int level = matcher.group(1).length();
                    while (headings.size() >= level) {
                        headings.removeLast();
                    }
                    headings.addLast(matcher.group(2).trim());
                    current.append(line).append('\n');
                    continue;
                }
            }
            current.append(line).append('\n');
        }

        flushChunks(chunks, headings, current, title, type);
        return chunks;
    }

    private void flushChunks(List<NoteChunk> chunks,
                             Deque<String> headings,
                             StringBuilder current,
                             String title,
                             KnowledgeSourceType type) {
        String content = current.toString().trim();
        current.setLength(0);
        if (content.isBlank()) {
            return;
        }

        String headingPath = headings.isEmpty() ? title : String.join(" > ", headings);
        for (String part : splitLongContent(content, properties.chunkMaxChars())) {
            if (part.isBlank()) {
                continue;
            }
            chunks.add(new NoteChunk(
                    headingPath,
                    part,
                    estimateTokens(part),
                    HashUtils.sha256(part),
                    extractLinks(part, type)
            ));
        }
    }

    private List<String> splitLongContent(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return List.of(content);
        }

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inCode = false;
        String fence = null;

        for (String line : content.split("\n", -1)) {
            String trimmed = line.trim();
            boolean fenceLine = trimmed.startsWith("```") || trimmed.startsWith("~~~");
            if (fenceLine) {
                if (!inCode) {
                    inCode = true;
                    fence = trimmed.substring(0, 3);
                } else if (trimmed.startsWith(fence)) {
                    inCode = false;
                    fence = null;
                }
            }

            boolean paragraphBreak = !inCode
                    && trimmed.isEmpty()
                    && current.length() >= Math.max(200, maxChars / 2);
            if (paragraphBreak) {
                parts.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private List<String> extractLinks(String content, KnowledgeSourceType type) {
        Set<String> links = new LinkedHashSet<>();
        if (type == KnowledgeSourceType.MARKDOWN) {
            Matcher matcher = MD_LINK.matcher(content);
            while (matcher.find()) {
                String target = normalizeLinkTarget(matcher.group(1));
                if (!target.isEmpty() && !target.startsWith("http")) {
                    links.add(target);
                }
            }
            return List.copyOf(links);
        }

        Matcher matcher = WIKI_LINK.matcher(content);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (!target.isEmpty()) {
                links.add(target);
            }
        }
        return List.copyOf(links);
    }

    private String normalizeLinkTarget(String rawTarget) {
        String target = rawTarget.trim();
        int anchor = target.indexOf('#');
        if (anchor >= 0) {
            target = target.substring(0, anchor);
        }
        String lower = target.toLowerCase();
        for (String extension : List.of(".markdown", ".md", ".txt")) {
            if (lower.endsWith(extension)) {
                return target.substring(0, target.length() - extension.length());
            }
        }
        return target;
    }

    private java.util.Optional<String> firstHeading(String body) {
        for (String line : body.split("\n", -1)) {
            Matcher matcher = HEADING.matcher(line.trim());
            if (matcher.matches() && matcher.group(1).length() == 1) {
                return java.util.Optional.of(matcher.group(2).trim());
            }
        }
        return java.util.Optional.empty();
    }

    private boolean isHidden(Path relativePath) {
        for (Path segment : relativePath) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return Arrays.stream(normalized.split(","))
                .map(String::trim)
                .map(this::unquote)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private int estimateTokens(String content) {
        return Math.max(1, (int) Math.ceil(content.length() / 1.8));
    }

    private record Frontmatter(Map<String, String> values, String body) {
    }
}