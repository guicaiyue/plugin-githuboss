package com.xirizhi.plugingithuboss.controller;

import com.xirizhi.plugingithuboss.extension.AttachmentRecord;
import com.xirizhi.plugingithuboss.extension.RepositoryConfig;
import com.xirizhi.plugingithuboss.extension.AuditLog;
import com.xirizhi.plugingithuboss.service.AttachmentStrategy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import run.halo.app.plugin.ApiVersion;
import run.halo.app.extension.ExtensionClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 后端控制器：提供上传与删除接口。
 * 路由规则：/api/{version}/plugins/{plugin-name}/githuboss/...
 */
@ApiVersion("v1alpha1")
@RestController
@RequestMapping("githuboss")
public class GithubossController {

    private final ExtensionClient client;
    private final AttachmentStrategy attachmentStrategy;

    public GithubossController(ExtensionClient client, AttachmentStrategy attachmentStrategy) {
        this.client = client;
        this.attachmentStrategy = attachmentStrategy;
    }

    /**
     * 上传附件到指定仓库。
     * 参数：repoName（RepositoryConfig 的 metadata.name）、file（Multipart 文件）
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("repo") String repoName,
                                    @RequestParam("file") MultipartFile file) throws Exception {
        AttachmentStrategy.UploadResult result = attachmentStrategy.upload(repoName, file.getOriginalFilename(), file.getBytes(), file.getSize());
        return ResponseEntity.ok(java.util.Map.of(
                "name", result.getName(),
                "cdnUrl", result.getCdnUrl(),
                "sha", result.getSha(),
                "path", result.getPath()
        ));
    }

    /**
     * 删除附件：支持逻辑删除与远程删除。
     */
    @DeleteMapping("/attachments/{name}")
    public ResponseEntity<?> delete(@PathVariable("name") String name) throws Exception {
        AttachmentStrategy.DeleteResult result = attachmentStrategy.delete(name);
        return ResponseEntity.ok(java.util.Map.of(
                "name", result.getName(),
                "deleted", result.isDeleted(),
                "remote", result.isRemote()
        ));
    }

    // 提取扩展名的小工具方法
    private static String extractExt(String filename) {
        if (filename == null) return null;
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1) return null;
        return filename.substring(i + 1);
    }

    // 审计日志写入：统一封装，避免重复代码
    private void logAudit(String action, String repoRef, String path, String sha, boolean success, String message) {
        try {
            AuditLog log = new AuditLog();
            String ts = String.valueOf(System.currentTimeMillis());
            String repoPart = repoRef == null ? "unknown" : repoRef;
            log.getMetadata().setName("audit-" + action + "-" + repoPart + "-" + ts);
            AuditLog.Spec spec = new AuditLog.Spec();
            spec.setAction(action);
            spec.setRepoRef(repoRef);
            spec.setPath(path);
            spec.setSha(sha);
            spec.setTimestamp(Instant.now().toString());
            spec.setSuccess(success);
            spec.setMessage(message);
            log.setSpec(spec);
            client.create(log);
        } catch (Exception ignored) {
            // 审计日志失败不影响主流程，避免副作用
        }
    }
}