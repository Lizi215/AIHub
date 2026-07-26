package com.aihub.asset.vo;

/**
 * 下载结果（内部传递，控制器直接写响应体，不套统一响应体）。
 *
 * @param fileName 文件名
 * @param content  文件字节
 */
public record AssetDownload(String fileName, byte[] content) {
}
