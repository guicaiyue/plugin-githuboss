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
@GVK(group = "plugin-githuboss.halo.run", version = "v1alpha1", kind = "AuditLog", plural = "auditlogs", singular = "auditlog")
public class AuditLog extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Spec spec;

    @Data
    public static class Spec {
        // 操作动作：upload、delete、sync
        private String action;
        // 关联的仓库配置名称
        private String repoRef;
        // 目标路径
        private String path;
        // 相关 sha
        private String sha;
        // 时间戳（ISO 字符串）
        private String timestamp;
        // 操作结果（true/false）
        private Boolean success;
        // 描述消息
        private String message;
    }
}