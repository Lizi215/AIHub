package com.aihub.file;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文件服务：MinIO 的唯一出口。
 *
 * <p>无状态、不持有数据表，也不解析业务语义，对象路径由调用方决定。
 */
@SpringBootApplication
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
