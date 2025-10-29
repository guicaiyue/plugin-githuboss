package com.xirizhi.plugingithuboss.extension.theme;

import lombok.Data;

// 基础配置
@Data
public class GitHubBasic {
    // github jsdelivr 访问前缀
    private String jsdelivr;

    // 前端访问优化开关（开启后注入前端测速脚本）
    private Boolean enableOptimization;
}
