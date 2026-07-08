package com.aihub.common.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置。禁止各服务逐字段覆盖（如 {@code @JsonFormat}），否则契约会漂移。
 *
 * <p>{@code Long} 序列化为 String（雪花 ID 19 位，超出 JS 安全整数范围），{@code LocalDateTime}
 * 统一 ISO-8601（{@code yyyy-MM-dd'T'HH:mm:ss}），空值策略 NON_NULL，见 docs/API.md §5。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer aihubJacksonCustomizer() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(formatter));
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(formatter));
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
        };
    }
}
