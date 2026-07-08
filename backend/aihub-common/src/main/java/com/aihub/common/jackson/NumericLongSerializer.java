package com.aihub.common.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 把 long / Long 按普通数字输出，用于显式豁免全局的 Long→String 规则。
 *
 * <p>全局把 Long 当雪花 ID 序列化为字符串，避免 19 位超出 JS 安全整数范围；但计数、字节数、时长
 * 没有精度问题，转成字符串反而让前端拿到错误类型（如 {@code el-pagination} 的 total 必须是 Number）。
 *
 * <p>Jackson 自带的 {@code NumberSerializer} 没有无参构造，不满足 {@code @JsonSerialize(using = ...)} 要求，故自定义。
 *
 * <p>用法：{@code @JsonSerialize(using = NumericLongSerializer.class)}。这是豁免全局规则的唯一入口，不要改全局配置。
 */
public class NumericLongSerializer extends JsonSerializer<Long> {

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeNumber(value);
    }
}
