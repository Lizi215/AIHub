package com.aihub.common.result;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.aihub.common.jackson.NumericLongSerializer;

import java.util.List;

/**
 * 统一分页响应契约，见 docs/API.md §4。
 *
 * <p>这里显式覆盖序列化器：全局把 Long 当雪花 ID 转字符串，但计数没有精度问题，
 * 转字符串反而会让 {@code el-pagination} 这类组件拿到错误类型。
 *
 * <p>规则保持「默认把 Long 当 ID」，只对确定不是 ID 的字段开例外；反过来的做法一旦漏加注解，
 * 就会静默丢精度，可能用错误的 ID 去读写他人资源。
 *
 * @param records 当前页数据
 * @param total   总条数（计数，非 ID）
 * @param page    当前页码，从 1 开始（计数，非 ID）
 * @param size    每页条数（计数，非 ID）
 */
public record PageResult<T>(
        List<T> records,
        @JsonSerialize(using = NumericLongSerializer.class) long total,
        @JsonSerialize(using = NumericLongSerializer.class) long page,
        @JsonSerialize(using = NumericLongSerializer.class) long size) {

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        return new PageResult<>(records, total, page, size);
    }
}
