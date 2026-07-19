package com.aihub.user.entity;

import com.aihub.common.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 用户表映射（`user`）。
 */
@TableName("user")
public class UserEntity extends BaseEntity {

    private String username;

    /** BCrypt 哈希，禁止明文 / MD5 / SHA1 */
    private String password;

    private String nickname;

    private String email;

    /** 见 {@link com.aihub.common.auth.Role}，存字符串 */
    private String role;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
