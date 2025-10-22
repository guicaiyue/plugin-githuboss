package com.xirizhi.plugingithuboss.handler;

import com.xirizhi.plugingithuboss.exception.GitHubExceptionHandler;
import com.xirizhi.plugingithuboss.extension.GithubOssPolicySettings;
import com.xirizhi.plugingithuboss.extension.RepositoryConfig;
import com.xirizhi.plugingithuboss.service.GitHubService;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.AttachmentHandler;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.UUID;

/**
 * GitHub OSS 附件处理器：将上传/删除能力挂接到 Halo 的附件扩展点。
 * 设计原则：
 * - 复用已有 GitHubService 进行远程交互，避免重复代码（遵循 DRY）。
 * - 保持逻辑清晰，提前返回，避免多层嵌套。
 * - 使用扩展点 @Extension 让 Halo 发现此处理器。
 */
@Slf4j
@Extension
@Component
public class GithubAttachmentHandler implements AttachmentHandler {

    private final ReactiveExtensionClient client;
    private final GitHubService gitHubService;

    public GithubAttachmentHandler(ReactiveExtensionClient client, GitHubService gitHubService) {
        this.client = client;
        this.gitHubService = gitHubService;
    }

    /**
     * 上传文件：
     * - 从 Policy 与 ConfigMap 中读取仓库别名（repoName）等配置
     * - 根据策略生成路径并上传到 GitHub
     * - 生成 Halo Attachment 对象并返回，status.permalink 为 CDN URL
     */
    @Override
    public Mono<Attachment> upload(UploadContext context) {
        final FilePart filePart = context.file();
        final Policy policy = context.policy();
        
        // 解析嵌套的配置数据
        var settingJson = context.configMap().getData().getOrDefault("default", "{}");
        GithubOssPolicySettings settings = JsonUtils.jsonToObject(settingJson, GithubOssPolicySettings.class);
        
        // 简单的 JSON 解析（手动解析关键字段）
        final String owner = settings.getOwner();
        final String repoName = settings.getRepoName();
        final String branch = settings.getBranch();
        final String path = settings.getPath();
        final String token = settings.getToken();
        final Boolean namePrefix = settings.getNamePrefix();
        
        // GitHub 连通性检测
        return Mono.fromCallable(() -> gitHubService.checkConnectivity())
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(isConnected -> {
                    if (!isConnected) {
                        return Mono.error(new IllegalStateException("GitHub 无法访问，请检查网络连接或配置代理"));
                    }

                    // 构建 RepositoryConfig.Spec 对象
                    RepositoryConfig.Spec spec = new RepositoryConfig.Spec();
                    spec.setOwner(owner);
                    spec.setRepo(repoName); // 使用 repoName 作为 repo 值
                    spec.setBranch(branch != null ? branch : "master");
                    spec.setPat(token);
                    spec.setRootPath(path != null && !path.isBlank() ? path : "attachments");

                    // 构造存储路径：rootPath/YYYYMMDD/timestamp.ext
                    final String dateDir = DateTimeFormatter.ofPattern("yyyyMMdd")
                            .format(Instant.now().atZone(ZoneId.systemDefault()));
                    final String ts = String.valueOf(System.currentTimeMillis());

                    // 提取文件扩展名
                    final String originalFilename = filePart.filename();
                    final String ext = extractExt(originalFilename);

                    // 根据 namePrefix 配置决定文件名格式
                    final String filename;
                    if (namePrefix && originalFilename != null && !originalFilename.isBlank()) {
                        // 保留原文件名作为后缀，最长15个字符
                        String originalName = originalFilename;
                        if (originalName.length() > 15) {
                            originalName = originalName.substring(0, 15);
                        }
                        filename = ts + "-" + originalName;
                    } else {
                        filename = ts + (ext != null ? ("." + ext) : "");
                    }
                    
                    final String filePath = spec.getRootPath() + "/" + dateDir + "/" + filename;
                    // 组装上传
                    return filePart.content()
                            .reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
                                try {
                                    java.nio.ByteBuffer nio = dataBuffer.asByteBuffer();
                                    byte[] bytes = new byte[nio.remaining()];
                                    nio.get(bytes);
                                    baos.write(bytes);
                                    return baos;
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .flatMap(baos -> {
                                byte[] bytes = baos.toByteArray();
                                // 大小校验
                                if (spec.getMaxSizeMB() != null && spec.getMaxSizeMB() > 0) {
                                    long maxBytes = spec.getMaxSizeMB() * 1024L * 1024L;
                                    if (bytes.length > maxBytes) {
                                        return Mono.error(new IllegalArgumentException(
                                                "文件超过大小限制: " + spec.getMaxSizeMB() + "MB"));
                                    }
                                }
                                try {
                                    String sha = gitHubService.uploadContent(settings, filePath, bytes,
                                            "Upload via Halo AttachmentHandler");
                                    String cdnUrl = gitHubService.buildCdnUrl(settings, filePath);
                                    log.info("文件上传成功,owner: {}, repoName: {}, 完整仓库名: {}, 完整路径: {}, sha: {}, cdnUrl: {}", owner, repoName, owner + "/" + repoName, filePath, sha, cdnUrl);
                                    // 记录附件到扩展模型，便于后续删除与审计（与现有策略一致）
                                    // 构造 Halo Attachment 返回
                                    Attachment attachment = new Attachment();
                                    // 初始化 metadata（AbstractExtension 需要手动初始化）
                                    var metadata = new Metadata();
                                    metadata.setName(UUID.randomUUID().toString());
                                    attachment.setMetadata(metadata);
                                    HashMap<String, String> annotationMap = new HashMap<>();
                                    annotationMap.put("path", filePath);
                                    annotationMap.put("sha", sha);
                                    attachment.getMetadata().setAnnotations(annotationMap);
                                    
                                    Attachment.AttachmentSpec as = new Attachment.AttachmentSpec();
                                    as.setDisplayName(originalFilename);
                                    as.setGroupName("githuboss");
                                    as.setPolicyName(policy.getMetadata().getName());
                                    as.setOwnerName("system"); // 可根据登录用户设置
                                    as.setMediaType(getMediaTypeByExt(ext));
                                    as.setSize((long) bytes.length);
                                    attachment.setSpec(as);
                                    Attachment.AttachmentStatus status = new Attachment.AttachmentStatus();
                                    status.setPermalink(cdnUrl);
                                    attachment.setStatus(status);
                                    return Mono.just(attachment);
                                } catch (Exception e) {
                                    return Mono.error(e);
                                }
                            });
                })
                .onErrorMap(GitHubExceptionHandler::map);
    }

    /**
     * 删除文件：
     * - 根据 AttachmentStatus.permalink 或扩展记录 AttachmentRecord 定位到仓库与路径
     * - 按仓库配置的删除模式执行远程删除或仅逻辑删除
     */
    @Override
    public Mono<Attachment> delete(DeleteContext context) {
        final Attachment attachment = context.attachment();
        
        // 解析嵌套的配置数据
        var settingJson = context.configMap().getData().getOrDefault("default", "{}");
        GithubOssPolicySettings settings = JsonUtils.jsonToObject(settingJson, GithubOssPolicySettings.class);
        
        final String sha = attachment.getMetadata().getAnnotations().get("sha");
        final String path = attachment.getMetadata().getAnnotations().remove("path");
        
        return  Mono.fromCallable(() -> {
                    log.info("开始删除远程文件,owner: {}, repoName: {}, 完整仓库名: {}, 完整路径: {}", settings.getOwner(), settings.getRepoName(), settings.getOwner() + "/" + settings.getRepoName(), path);
                    gitHubService.deleteContent(settings, path, sha, "Delete via Halo AttachmentHandler");
                    log.info("远程文件删除成功");
                    return attachment;
                })
                .doOnError(error -> log.error("删除过程中发生错误", error))
                .onErrorMap(GitHubExceptionHandler::map);
    }

    @Override
    public Mono<URI> getSharedURL(Attachment attachment, Policy policy, ConfigMap configMap, Duration ttl) {
        // 对于 GitHub 内容，CDN 地址即可视为共享 URL，这里直接返回 permalink 或基于路径构建 CDN URL
        try {
            String path = attachment.getMetadata().getAnnotations().get("path");

            var settingJson = configMap.getData().getOrDefault("default", "{}");
            GithubOssPolicySettings settings = JsonUtils.jsonToObject(settingJson, GithubOssPolicySettings.class);
           
            String cdn = gitHubService.buildCdnUrl(settings, path);
            return Mono.just(URI.create(cdn));
        } catch (Exception e) {
            return Mono.error(GitHubExceptionHandler.map(e));
        }
    }

    @Override
    public Mono<URI> getPermalink(Attachment attachment, Policy policy, ConfigMap configMap) {
        // GitHub 场景：permalink 使用 CDN 地址
        try {
            String path = attachment.getMetadata().getAnnotations().get("path");
            
            var settingJson = configMap.getData().getOrDefault("default", "{}");
            GithubOssPolicySettings settings = JsonUtils.jsonToObject(settingJson, GithubOssPolicySettings.class);
                
            String cdn = gitHubService.buildCdnUrl(settings, path);
            return Mono.just(URI.create(cdn));
        } catch (Exception e) {
            return Mono.error(GitHubExceptionHandler.map(e));
        }
    }

    // GitHub 连通性检测已迁移至 GitHubService.checkConnectivity()
    private String extractExt(String filename) {
        if (filename == null) return null;
        int i = filename.lastIndexOf('.');
        if (i < 0) return null;
        return filename.substring(i + 1);
    }

    /**
     * 根据文件扩展名获取 MIME 类型
     * @param ext 文件扩展名（不含点号）
     * @return MIME 类型字符串
     */
    private String getMediaTypeByExt(String ext) {
        if (ext == null) return "application/octet-stream";
        
        String lowerExt = ext.toLowerCase();
        switch (lowerExt) {
            // 图片类型
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "bmp":
                return "image/bmp";
            case "ico":
                return "image/x-icon";
            
            // 文档类型
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            
            // 文本类型
            case "txt":
                return "text/plain";
            case "html":
            case "htm":
                return "text/html";
            case "css":
                return "text/css";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "csv":
                return "text/csv";
            
            // 压缩文件
            case "zip":
                return "application/zip";
            case "rar":
                return "application/vnd.rar";
            case "7z":
                return "application/x-7z-compressed";
            case "tar":
                return "application/x-tar";
            case "gz":
                return "application/gzip";
            
            // 音视频
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "mp4":
                return "video/mp4";
            case "avi":
                return "video/x-msvideo";
            case "mov":
                return "video/quicktime";
             
            default:
                return "application/octet-stream";
        }
    }
}