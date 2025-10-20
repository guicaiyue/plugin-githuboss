package com.xirizhi.plugingithuboss.extension;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@GVK(group = "plugin-githuboss.halo.run", version = "v1alpha1", kind = "RepositoryConfig", plural = "repositoryconfigs", singular = "repositoryconfig")
public class RepositoryConfig extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        // GitHub 所属组织或用户名
        private String owner;
        // 仓库名称
        private String repo;
        // 分支名
        private String branch;
        // 根目录路径（相对仓库根），用于在其下创建 YYYYMMDD 子目录
        private String rootPath;
        // CDN 域名优先级（降级列表）
        private java.util.List<String> cdnDomains;
        // 最大文件大小（MB），默认为 50MB
        private Integer maxSizeMB;
        // 访问令牌（PAT），建议仅具备 Contents 权限
        private String pat;
    }
}