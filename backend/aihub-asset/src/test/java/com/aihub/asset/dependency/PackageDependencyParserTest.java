package com.aihub.asset.dependency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PackageDependencyParser}：上传包内 aihub.yaml 的 requires 解析。
 *
 * <p>重点是"不阻断上传"的语义：任何异常都只产出 problems，绝不抛异常。
 */
class PackageDependencyParserTest {

    private final PackageDependencyParser parser = new PackageDependencyParser();

    /** 打包一个 zip：entries 为 (文件名, 内容) 交替 */
    private byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (int i = 0; i < entries.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entries[i]));
                zos.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("简单列表写法：requires: [a, b] → 两条依赖")
    void shouldParseSimpleList() throws Exception {
        byte[] content = zip("SKILL.md", "# demo", "aihub.yaml", """
                requires:
                  - save-last
                  - md2docx
                """);

        PackageDependencyParser.ParseResult result = parser.parse(content);

        assertThat(result.problems()).isEmpty();
        assertThat(result.requirements()).extracting(PackageDependencyParser.Requirement::name)
                .containsExactly("save-last", "md2docx");
        assertThat(result.requirements().get(0).versionConstraint()).isNull();
    }

    @Test
    @DisplayName("映射写法：带版本约束被保留（第一版只落库不求解）")
    void shouldParseMapFormWithVersion() throws Exception {
        byte[] content = zip("aihub.yaml", """
                requires:
                  - name: save-last
                    version: ">=1.0.0"
                """);

        PackageDependencyParser.ParseResult result = parser.parse(content);

        assertThat(result.requirements()).hasSize(1);
        assertThat(result.requirements().get(0).name()).isEqualTo("save-last");
        assertThat(result.requirements().get(0).versionConstraint()).isEqualTo(">=1.0.0");
    }

    @Test
    @DisplayName("包内没有 aihub.yaml / 不是 zip / 没有 requires → 都视为无依赖，且无问题")
    void shouldTreatMissingManifestAsNoDependency() throws Exception {
        assertThat(parser.parse(zip("SKILL.md", "# demo")).requirements()).isEmpty();
        assertThat(parser.parse(zip("SKILL.md", "# demo")).problems()).isEmpty();
        assertThat(parser.parse("not a zip".getBytes(StandardCharsets.UTF_8)).requirements()).isEmpty();
        assertThat(parser.parse(null).problems()).isEmpty();
        assertThat(parser.parse(zip("aihub.yaml", "name: demo\nsummary: 无依赖声明")).requirements()).isEmpty();
    }

    @Test
    @DisplayName("YAML 非法 → 只报问题，不抛异常（不阻断上传）")
    void shouldReportMalformedYamlWithoutThrowing() throws Exception {
        byte[] content = zip("aihub.yaml", "requires: [unclosed\n  - broken: :::");

        PackageDependencyParser.ParseResult result = parser.parse(content);

        assertThat(result.requirements()).isEmpty();
        assertThat(result.problems()).hasSize(1);
        assertThat(result.problems().get(0)).contains("aihub.yaml");
    }

    @Test
    @DisplayName("结构错误与脏数据：requires 非列表、条目缺 name、重复声明都被逐条说明")
    void shouldReportStructuralProblems() throws Exception {
        assertThat(parser.parse(zip("aihub.yaml", "requires: save-last")).problems())
                .containsExactly("requires 必须是列表");

        PackageDependencyParser.ParseResult mixed = parser.parse(zip("aihub.yaml", """
                requires:
                  - save-last
                  - save-last
                  - version: "1.0.0"
                  - 123
                """));
        assertThat(mixed.requirements()).extracting(PackageDependencyParser.Requirement::name)
                .containsExactly("save-last", "123");
        assertThat(mixed.problems()).anySatisfy(p -> assertThat(p).contains("重复声明"));
        assertThat(mixed.problems()).anySatisfy(p -> assertThat(p).contains("缺少 name"));
    }

    @Test
    @DisplayName("条目数有界：超过 50 条截断并说明")
    void shouldCapRequiresCount() throws Exception {
        StringBuilder yaml = new StringBuilder("requires:\n");
        for (int i = 0; i < 60; i++) {
            yaml.append("  - dep-").append(i).append('\n');
        }

        PackageDependencyParser.ParseResult result = parser.parse(zip("aihub.yaml", yaml.toString()));

        assertThat(result.requirements()).hasSize(50);
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("超过 50 条"));
    }

    @Test
    @DisplayName("嵌套目录里的 aihub.yaml 不被采纳（只认包根，避免误抓）")
    void shouldOnlyAcceptRootManifest() throws Exception {
        byte[] content = zip("sub/aihub.yaml", "requires:\n  - save-last\n");

        assertThat(parser.parse(content).requirements()).isEmpty();
    }

    @Test
    @DisplayName("超长清单文件被拒绝，不吞内存")
    void shouldRejectOversizedManifest() throws Exception {
        StringBuilder big = new StringBuilder("requires:\n");
        for (int i = 0; i < 20000; i++) {
            big.append("  - some-long-dependency-name-").append(i).append('\n');
        }
        byte[] content = zip("aihub.yaml", big.toString());

        PackageDependencyParser.ParseResult result = parser.parse(content);

        assertThat(result.requirements()).isEmpty();
        assertThat(result.problems()).anySatisfy(p -> assertThat(p).contains("字节上限"));
    }
}
