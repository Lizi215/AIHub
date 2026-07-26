package com.aihub.asset.dependency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 上传包内的依赖声明解析（约定：包根目录的 {@code aihub.yaml}）。
 *
 * <pre>
 * requires:
 *   - save-last            # 简单写法：只声明资源名
 *   - name: md2docx        # 带版本约束的写法
 *     version: ">=1.0.0"   # 第一版只落库不做约束求解（与 asset_dependency 注释一致）
 * </pre>
 *
 * <p>解析失败不阻断上传：依赖是增强信息，所有问题收集进 {@code problems} 由调用方回给上传者。
 * 包不是 zip、没有声明文件都算"没有依赖"，不是错误。上传包来自不可信输入，因此读取有界：
 * 声明文件 ≤ {@value #MAX_MANIFEST_BYTES} 字节、条目 ≤ {@value #MAX_REQUIRES} 条，超过则截断并记录问题。
 */
@Component
public class PackageDependencyParser {

    private static final Logger log = LoggerFactory.getLogger(PackageDependencyParser.class);

    /** 约定文件名（只认包根目录这一处，不做模糊匹配，避免误抓同名文件） */
    static final String MANIFEST_NAME = "aihub.yaml";

    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    static final int MAX_REQUIRES = 50;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_VERSION_LENGTH = 50;

    public record Requirement(String name, String versionConstraint) {
    }

    /**
     * @param requirements   解析出的依赖声明（可能为空）
     * @param problems       面向上传者的问题说明；空列表表示没有任何异常
     * @param manifestPresent 包内是否存在 {@value #MANIFEST_NAME}；用于区分"没声明依赖"与
     *                        "没有声明文件"，两者都按空声明全量替换，但提示语不同
     */
    public record ParseResult(List<Requirement> requirements, List<String> problems, boolean manifestPresent) {
    }

    public ParseResult parse(byte[] content) {
        if (content == null || content.length < 4
                || content[0] != 'P' || content[1] != 'K') {
            // 非 zip：本项目资源包约定为 zip，这里不算错误（依赖声明只是可选增强）
            return new ParseResult(List.of(), List.of(), false);
        }
        String yamlText;
        try {
            yamlText = readManifest(content);
        } catch (Exception e) {
            log.warn("read {} from package failed: {}", MANIFEST_NAME, e.toString());
            return new ParseResult(List.of(), List.of("读取 " + MANIFEST_NAME + " 失败：" + e.getMessage()), false);
        }
        if (yamlText == null) {
            return new ParseResult(List.of(), List.of(), false);
        }
        return parseYaml(yamlText);
    }

    /** @return 声明文件内容；包内没有该文件时返回 null */
    private String readManifest(byte[] content) throws Exception {
        try (InputStream in = new ByteArrayInputStream(content);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replaceFirst("^\\./", "");
                if (name.equals(MANIFEST_NAME) && !entry.isDirectory()) {
                    return readBounded(zip);
                }
            }
        }
        return null;
    }

    private String readBounded(InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int read;
        while ((read = in.read(buf)) > 0) {
            total += read;
            if (total > MAX_MANIFEST_BYTES) {
                throw new IllegalStateException(MANIFEST_NAME + " 超过 " + MAX_MANIFEST_BYTES + " 字节上限");
            }
            out.write(buf, 0, read);
        }
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private ParseResult parseYaml(String yamlText) {
        Object loaded;
        try {
            // 使用 safe load 语义（Yaml 构造函数只解析标准类型，不实例化任意类）
            loaded = new Yaml().load(yamlText);
        } catch (Exception e) {
            return new ParseResult(List.of(), List.of(MANIFEST_NAME + " 不是合法 YAML：" + e.getMessage()), true);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            if (loaded == null) {
                return new ParseResult(List.of(), List.of(), true);
            }
            return new ParseResult(List.of(), List.of(MANIFEST_NAME + " 顶层必须是键值映射（如 requires: [...]）"), true);
        }
        Object requires = root.get("requires");
        if (requires == null) {
            // 有声明文件但没写 requires：等价于"声明为空"，上传新版本时会清掉旧依赖
            return new ParseResult(List.of(), List.of(), true);
        }
        if (!(requires instanceof List<?> list)) {
            return new ParseResult(List.of(), List.of("requires 必须是列表"), true);
        }

        List<Requirement> result = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        int processed = 0;
        for (Object item : list) {
            if (processed++ >= MAX_REQUIRES) {
                problems.add("requires 条目超过 " + MAX_REQUIRES + " 条，多余部分已忽略");
                break;
            }
            Requirement requirement = toRequirement(item, problems);
            if (requirement == null) {
                continue;
            }
            if (seen.putIfAbsent(requirement.name(), Boolean.TRUE) != null) {
                problems.add("重复声明依赖「" + requirement.name() + "」，已只保留一条");
                continue;
            }
            result.add(requirement);
        }
        return new ParseResult(result, problems, true);
    }

    private Requirement toRequirement(Object item, List<String> problems) {
        // YAML 会把裸标量解析成 String/Number/Boolean（如 123、true），统一按名字处理
        if (item instanceof String || item instanceof Number || item instanceof Boolean) {
            return validated(String.valueOf(item), null, problems);
        }
        if (item instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name == null) {
                problems.add("requires 中有条目缺少 name，已忽略");
                return null;
            }
            Object version = map.get("version");
            return validated(String.valueOf(name), version == null ? null : String.valueOf(version), problems);
        }
        problems.add("requires 条目必须是资源名或 {name, version} 映射，已忽略一项");
        return null;
    }

    private Requirement validated(String rawName, String version, List<String> problems) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            problems.add("requires 中有空名称，已忽略");
            return null;
        }
        if (name.length() > MAX_NAME_LENGTH) {
            problems.add("依赖名过长（>" + MAX_NAME_LENGTH + "），已忽略：" + name.substring(0, 20) + "…");
            return null;
        }
        if (version != null && version.length() > MAX_VERSION_LENGTH) {
            problems.add("依赖「" + name + "」的版本约束过长，已忽略该约束");
            version = null;
        }
        return new Requirement(name, version);
    }
}
