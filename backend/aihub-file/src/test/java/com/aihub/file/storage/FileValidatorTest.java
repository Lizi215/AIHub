package com.aihub.file.storage;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上传校验：白名单扩展名 + 文件魔数 + 大小上限，且失败关闭。
 */
class FileValidatorTest {

    private static final List<String> ALLOWED = List.of("zip");
    private static final long MAX_SIZE = 1024L;

    private static byte[] zip(String payload) {
        byte[] header = {0x50, 0x4B, 0x03, 0x04};
        byte[] body = payload.getBytes();
        byte[] result = new byte[header.length + body.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(body, 0, result, header.length, body.length);
        return result;
    }

    @Test
    void shouldAcceptZipWithCorrectMagicNumber() {
        assertThatCode(() -> FileValidator.validate("assets/1/1.0.0/package.zip", zip("data"), ALLOWED, MAX_SIZE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptEmptyZipArchiveHeader() {
        byte[] emptyZip = {0x50, 0x4B, 0x05, 0x06, 0x00, 0x00};

        assertThatCode(() -> FileValidator.validate("p.zip", emptyZip, ALLOWED, MAX_SIZE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectEmptyContent() {
        assertThatThrownBy(() -> FileValidator.validate("p.zip", new byte[0], ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_EMPTY);
    }

    @Test
    void shouldRejectNullContent() {
        assertThatThrownBy(() -> FileValidator.validate("p.zip", null, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_EMPTY);
    }

    @Test
    void shouldRejectOversizedFile() {
        byte[] big = zip("x".repeat(2048));

        assertThatThrownBy(() -> FileValidator.validate("p.zip", big, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void shouldRejectExtensionOutsideWhitelist() {
        assertThatThrownBy(() -> FileValidator.validate("evil.exe", zip("data"), ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void shouldRejectMissingExtension() {
        assertThatThrownBy(() -> FileValidator.validate("noextension", zip("data"), ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void shouldRejectDisguisedFileEvenWhenExtensionAllowed() {
        // 扩展名是 .zip，内容却是 ELF：只信扩展名 / Content-Type 会漏掉这种伪装
        byte[] elf = {0x7F, 0x45, 0x4C, 0x46, 0x02, 0x01, 0x01, 0x00};

        assertThatThrownBy(() -> FileValidator.validate("fake.zip", elf, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void shouldFailClosedWhenAllowedExtensionHasNoMagicRule() {
        // 白名单放开了没有魔数规则的扩展名时，必须拒绝而不是跳过校验
        assertThatThrownBy(() -> FileValidator.validate("p.txt", "hello".getBytes(), List.of("txt"), MAX_SIZE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED);
    }

    @Test
    void shouldExtractLowercaseExtension() {
        assertThat(FileValidator.extensionOf("assets/1/1.0.0/PACKAGE.ZIP")).isEqualTo("zip");
        assertThat(FileValidator.extensionOf("a/b/c.tar.gz")).isEqualTo("gz");
        assertThat(FileValidator.extensionOf("noext")).isEmpty();
        assertThat(FileValidator.extensionOf("trailing.")).isEmpty();
        assertThat(FileValidator.extensionOf(null)).isEmpty();
    }

    // ------------------------------------------------------------ 边界与安全检查

    @Test
    @DisplayName("大小边界：上限-1 与上限通过，上限+1 拒绝（错误码 FILE_TOO_LARGE）")
    void sizeBoundary() {
        byte[] justUnder = zipOfSize(MAX_SIZE - 1);
        byte[] exact = zipOfSize(MAX_SIZE);
        byte[] over = zipOfSize(MAX_SIZE + 1);

        FileValidator.validate("a/1.0.0.zip", justUnder, ALLOWED, MAX_SIZE);
        FileValidator.validate("a/1.0.0.zip", exact, ALLOWED, MAX_SIZE);
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0.zip", over, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
    }

    @Test
    @DisplayName("zip 三种合法魔数都放行（普通/空归档/分卷）")
    void allZipMagicNumbersAccepted() {
        for (int[] header : List.of(new int[]{0x50, 0x4B, 0x03, 0x04}, new int[]{0x50, 0x4B, 0x05, 0x06}, new int[]{0x50, 0x4B, 0x07, 0x08})) {
            byte[] content = new byte[]{(byte) header[0], (byte) header[1], (byte) header[2], (byte) header[3], 1};
            FileValidator.validate("a/1.0.0.zip", content, ALLOWED, MAX_SIZE);
        }
    }

    @Test
    @DisplayName("扩展名大小写不敏感、无扩展名拒绝、白名单外拒绝")
    void extensionRules() {
        byte[] content = zip("x");
        FileValidator.validate("a/1.0.0.ZIP", content, ALLOWED, MAX_SIZE);
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0", content, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_TYPE_NOT_ALLOWED));
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0.tar", content, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0.", content, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("objectKey 路径穿越 / 绝对路径 / 空白 → 拒绝（服务间接口也做纵深防御）")
    void illegalObjectKeyRejected() {
        byte[] content = zip("x");
        for (String key : new String[]{"../../etc/passwd.zip", "/abs/path.zip", "a/../../b.zip", "", "   ", null}) {
            assertThatThrownBy(() -> FileValidator.validate(key, content, ALLOWED, MAX_SIZE))
                    .as("objectKey=%s 应被拒绝", key)
                    .isInstanceOf(BizException.class);
        }
    }

    @Test
    @DisplayName("0 字节 / null 内容 → FILE_EMPTY；非法扩展名不做魔数校验（先拒绝类型）")
    void emptyContentRejected() {
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0.zip", new byte[0], ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.FILE_EMPTY));
        assertThatThrownBy(() -> FileValidator.validate("a/1.0.0.zip", null, ALLOWED, MAX_SIZE))
                .isInstanceOf(BizException.class);
    }

    /** 生成恰好 length 字节的合法 zip（魔数正确）。 */
    private byte[] zipOfSize(long length) {
        if (length < 4) {
            throw new IllegalArgumentException("zip 至少 4 字节");
        }
        byte[] result = new byte[(int) length];
        result[0] = 0x50;
        result[1] = 0x4B;
        result[2] = 0x03;
        result[3] = 0x04;
        return result;
    }
}
