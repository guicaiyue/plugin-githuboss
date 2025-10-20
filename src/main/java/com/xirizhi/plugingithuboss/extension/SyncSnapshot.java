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
@GVK(group = "plugin-githuboss.halo.run", version = "v1alpha1", kind = "SyncSnapshot", plural = "syncsnapshots", singular = "syncsnapshot")
public class SyncSnapshot extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        // 关联的仓库配置名称（RepositoryConfig.metadata.name）
        private String repoRef;
        // 最近一次同步的提交 sha（或快照标识）
        private String lastCommitSha;
        // 最近一次同步时间（ISO 字符串）
        private String lastSyncTime;
        // 仓库下已记录的文件数量
        private Integer fileCount;
    }
}