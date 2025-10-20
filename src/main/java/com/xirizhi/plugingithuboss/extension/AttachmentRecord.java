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
@GVK(group = "plugin-githuboss.halo.run", version = "v1alpha1", kind = "AttachmentRecord", plural = "attachmentrecords", singular = "attachmentrecord")
public class AttachmentRecord extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        // 关联的仓库配置名称（RepositoryConfig.metadata.name）
        private String repoRef;
        // 仓库中文件的相对路径（包含 YYYYMMDD 子目录与文件名）
        private String path;
        // GitHub 返回的内容 sha
        private String sha;
        // 文件大小（字节）
        private long size;
        // 生成的 CDN URL
        private String cdnUrl;
        // 是否逻辑删除
        private boolean deleted;
        // 记录创建时间（ISO 字符串）
        private String createdAt;
    }
}