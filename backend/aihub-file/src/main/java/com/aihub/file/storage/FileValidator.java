package com.aihub.file.storage;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 上传文件校验（docs/SECURITY.md §3）。
 *
 * <p>{@code Content-Type} 可被客户端随意伪造，只信它不够，因此做三道校验：白名单扩展名 → 文件魔数 → 大小上限。
 *
 * <p>失败关闭：某个扩展名若没有配置对应的魔数规则，直接拒绝，而不是因为没有规则就跳过校验，
 * 跳过一次就等于白名单形同虚设。
 *
 * <p>无状态静态工具，便于脱离 MinIO 直接单测。
 */
public final class FileValidator {

    /** 扩展名 → 允许的文件头（任一匹配即通过） */
    private static final Map<String, List<byte[]>> MAGIC_NUMBERS = Map.of(
            "zip", List.of(
                    new byte[] {0x50, 0x4B, 0x03, 0x04}, // 普通 zip
                    new byte[] {0x50, 0x4B, 0x05, 0x06}, // 空归档
                    new byte[] {0x50, 0x4B, 0x07, 0x08}  // 分卷 zip
            )
    );

    private FileValidator() {
    }

    /**
     * 校验上传内容，不通过则抛出业务异常。
     *
     * @param objectKey         对象路径，用于取扩展名
     * @param content           文件字节
     * @param allowedExtensions 允许的扩展名集合
     * @param maxSizeBytes      大小上限
     */
    public static void validate(String objectKey, byte[] content,
                                Collection<String> allowedExtensions, long maxSizeBytes) {
        // objectKey 由 Asset Service 生成，正常不会出现路径穿越或绝对路径；
        // 服务间接口也做纵深防御，一旦出现说明调用方被污染
        if (objectKey == null || objectKey.isBlank()
                || objectKey.contains("..") || objectKey.startsWith("/") || objectKey.startsWith("\\")) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "illegal objectKey: " + objectKey);
        }
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.FILE_EMPTY);
        }
        if (content.length > maxSizeBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "file size " + content.length + " exceeds limit " + maxSizeBytes);
        }

        String extension = extensionOf(objectKey);
        if (extension.isEmpty() || allowedExtensions == null
                || allowedExtensions.stream().noneMatch(allowed -> allowed.equalsIgnoreCase(extension))) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "extension not allowed: " + extension);
        }

        List<byte[]> expectedHeaders = MAGIC_NUMBERS.get(extension);
        if (expectedHeaders == null) {
            // 白名单里出现了没有魔数规则的扩展名：拒绝，而不是放行
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "no magic-number rule configured for extension: " + extension);
        }
        if (!startsWithAny(content, expectedHeaders)) {
            throw new BizException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                    "file content does not match extension: " + extension);
        }
    }

    /**
     * 取小写扩展名（不含点）。无扩展名时返回空串。
     */
    public static String extensionOf(String objectKey) {
        if (objectKey == null) {
            return "";
        }
        int slash = Math.max(objectKey.lastIndexOf('/'), objectKey.lastIndexOf('\\'));
        String fileName = slash >= 0 ? objectKey.substring(slash + 1) : objectKey;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWithAny(byte[] content, List<byte[]> headers) {
        for (byte[] header : headers) {
            if (startsWith(content, header)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] content, byte[] header) {
        if (content.length < header.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (content[i] != header[i]) {
                return false;
            }
        }
        return true;
    }
}
