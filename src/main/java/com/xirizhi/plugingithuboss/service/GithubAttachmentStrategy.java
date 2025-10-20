package com.xirizhi.plugingithuboss.service;

import com.xirizhi.plugingithuboss.extension.AttachmentRecord;
import com.xirizhi.plugingithuboss.extension.AuditLog;
import com.xirizhi.plugingithuboss.extension.RepositoryConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import run.halo.app.extension.ExtensionClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * GitHub 附件策略实现：封装现有控制器逻辑，便于后续挂接到 Halo 附件扩展点。
 * 注意：保持与控制器一致的校验与审计逻辑，避免重复代码（已抽取为方法）。
 */
@Component
public class GithubAttachmentStrategy implements AttachmentStrategy {

    private final ExtensionClient client;
    private final GitHubService gitHubService;

    public GithubAttachmentStrategy(ExtensionClient client, GitHubService gitHubService) {
        this.client = client;
        this.gitHubService = gitHubService;
    }

    @Override
    public UploadResult upload(String repoName, String originalFilename, byte[] data, long size) throws Exception {
        String path = null;
        String sha = null;
        try {
            RepositoryConfig.Spec spec = loadAndValidateRepoSpec(repoName, originalFilename, size);
            String ext = extractExt(originalFilename);
            // 路径策略：rootPath/YYYYMMDD/timestamp.ext
            String dateDir = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.now().atZone(ZoneId.systemDefault()));
            String ts = String.valueOf(System.currentTimeMillis());
            String filename = ts + (ext != null ? ("." + ext) : "");
            String root = spec.getRootPath() == null ? "attachments" : spec.getRootPath();
            path = root + "/" + dateDir + "/" + filename;

            // 调用 GitHub 上传
            sha = gitHubService.uploadContent(spec, path, data, "Upload via Halo plugin-githuboss");
            String cdnUrl = gitHubService.buildCdnUrl(spec, path);

            // 记录附件元数据
            AttachmentRecord record = new AttachmentRecord();
            record.getMetadata().setName(repoName + "-" + ts);
            AttachmentRecord.Spec rs = new AttachmentRecord.Spec();
            rs.setRepoRef(repoName);
            rs.setPath(path);
            rs.setSha(sha);
            rs.setSize(size);
            rs.setCdnUrl(cdnUrl);
            rs.setDeleted(false);
            rs.setCreatedAt(Instant.now().toString());
            record.setSpec(rs);
            client.create(record);

            // 审计日志：上传成功
            logAudit("upload", repoName, path, sha, true, "上传成功");
            return new UploadResult(record.getMetadata().getName(), cdnUrl, sha, path);
        } catch (Exception e) {
            logAudit("upload", repoName, path, sha, false, "上传失败: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public DeleteResult delete(String attachmentRecordName) throws Exception {
        try {
            AttachmentRecord record = client.fetch(AttachmentRecord.class, attachmentRecordName)
                    .orElseThrow(() -> new IllegalArgumentException("附件记录不存在: " + attachmentRecordName));
            RepositoryConfig repo = client.fetch(RepositoryConfig.class, record.getSpec().getRepoRef())
                    .orElseThrow(() -> new IllegalArgumentException("仓库配置不存在: " + record.getSpec().getRepoRef()));
            RepositoryConfig.Spec spec = repo.getSpec();
            // 直接执行远程删除
            gitHubService.deleteContent(spec, record.getSpec().getPath(), record.getSpec().getSha(), "Delete via Halo plugin-githuboss");
            // 标记逻辑删除
            record.getSpec().setDeleted(true);
            client.update(record);
            logAudit("delete", record.getSpec().getRepoRef(), record.getSpec().getPath(), record.getSpec().getSha(), true, "删除成功（含远程删除）");
            return new DeleteResult(attachmentRecordName, true, true);
        } catch (Exception e) {
            logAudit("delete", null, null, null, false, "删除失败: " + e.getMessage());
            throw e;
        }
    }

    // 统一仓库加载与校验逻辑，避免重复代码
    private RepositoryConfig.Spec loadAndValidateRepoSpec(String repoName, String originalFilename, long size) {
        RepositoryConfig repo = client.fetch(RepositoryConfig.class, repoName).orElseThrow(() -> new IllegalArgumentException("仓库配置不存在: " + repoName));
        RepositoryConfig.Spec spec = repo.getSpec();
        if (spec == null) {
            throw new IllegalArgumentException("仓库配置为空");
        }
        if (spec.getMaxSizeMB() != null && spec.getMaxSizeMB() > 0) {
            long maxBytes = spec.getMaxSizeMB() * 1024L * 1024L;
            if (size > maxBytes) {
                throw new IllegalArgumentException("文件超过大小限制: " + spec.getMaxSizeMB() + "MB");
            }
        }
        return spec;
    }

    private static String extractExt(String filename) {
        if (filename == null) return null;
        int i = filename.lastIndexOf('.');
        if (i < 0 || i == filename.length() - 1) return null;
        return filename.substring(i + 1);
    }

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
            // 审计日志失败不影响主流程
        }
    }
}