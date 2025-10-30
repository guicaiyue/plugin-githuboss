package com.xirizhi.plugingithuboss.extension;

import lombok.Data;

/**
 * 映射 githuboss-policy-settings.yaml 的配置数据结构。
 * 存储于 ConfigMap.data["default"] 的 JSON 将反序列化为该对象。
 */
@Data
public class GithubOssPolicySettings {
    private String owner;
    private String repoName;
    private String path;
    private String branch;
    private String namePrefix; // 是否在重命名时追加原文件名后缀
    private String token;       // GitHub PAT
    private Integer minSizeMB;  // 上传文件最小大小(MB)
}