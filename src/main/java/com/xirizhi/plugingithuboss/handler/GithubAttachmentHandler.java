package com.xirizhi.plugingithuboss.handler;

import com.xirizhi.plugingithuboss.exception.GitHubExceptionHandler;
import com.xirizhi.plugingithuboss.extension.AttachmentRecord;
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
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
    
    // 已移除旧的手工 JSON 解析方法，改用 JsonUtils + GithubOssPolicySettings 类型化解析。

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
        final ConfigMap cfg = context.configMap();
        
        // 从 ConfigMap 中读取配置字段
        if (cfg == null || cfg.getData() == null) {
            return Mono.error(new IllegalArgumentException("配置为空"));
        }
        
        
        
        // 解析嵌套的配置数据
        String configJson = cfg.getData().get("default");
        if (configJson == null || configJson.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置数据为空"));
        }
        
        // 使用 JsonUtils 反序列化配置对象，替代手工解析
        final GithubOssPolicySettings settings;
        try {
            settings = JsonUtils.jsonToObject(configJson, GithubOssPolicySettings.class);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("配置解析失败: " + e.getMessage(), e));
        }
        
        final String owner = settings.getOwner();
        final String repoName = settings.getRepoName();
        final String branch = settings.getBranch();
        final String path = settings.getPath();
        final String token = settings.getToken();
        final Boolean namePrefix = settings.getNamePrefix();
        
        // 校验必需字段
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 owner"));
        }
        if (repoName == null || repoName.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 repoName"));
        }
        if (token == null || token.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 token"));
        }
        
        // GitHub 连通性检测
        return checkGitHubConnectivity()
            .flatMap(isConnected -> {
                if (!isConnected) {
                    return Mono.error(new IllegalStateException("GitHub 无法访问，请检查网络连接或配置代理"));
                }
                
                // 构建 RepositoryConfig.Spec 对象
                RepositoryConfig.Spec spec = new RepositoryConfig.Spec();
                spec.setOwner(owner);
                spec.setRepo(repoName);  // 使用 repoName 作为 repo 值
                spec.setBranch(branch != null ? branch : "master");
                spec.setPat(token);
                spec.setRootPath(path != null && !path.isBlank() ? path : "attachments");
        
        // 构造存储路径：rootPath/YYYYMMDD/timestamp.ext
        final String dateDir = DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.now().atZone(ZoneId.systemDefault()));
        final String ts = String.valueOf(System.currentTimeMillis());
        
        // 提取文件扩展名
        final String originalFilename = filePart.filename();
        final String ext = extractExt(originalFilename);
        
        // 根据 namePrefix 配置决定文件名格式
        final String filename;
        if (Boolean.TRUE.equals(namePrefix) && originalFilename != null && !originalFilename.isBlank()) {
            // 保留原文件名作为后缀，最长15个字符
            String originalName = originalFilename;
            if (originalName.length() > 15) {
                originalName = originalName.substring(0, 15);
            }
            filename = ts + "-" + originalName;
        } else {
            filename = ts + (ext != null ? ("." + ext) : "");
        }
        
        final String root = spec.getRootPath() == null ? "attachments" : spec.getRootPath();
        final String filePath = root + "/" + dateDir + "/" + filename;
        // 组装上传
        return filePart.content()
                .reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
                    try {
                        java.nio.ByteBuffer nio = dataBuffer.asByteBuffer();
                        byte[] bytes = new byte[nio.remaining()];
                        nio.get(bytes);
                        baos.write(bytes);
                        return baos;
                    } catch (Exception e) { throw new RuntimeException(e); }
                })
                .flatMap(baos -> {
                    byte[] bytes = baos.toByteArray();
                    // 大小校验
                    if (spec.getMaxSizeMB() != null && spec.getMaxSizeMB() > 0) {
                        long maxBytes = spec.getMaxSizeMB() * 1024L * 1024L;
                        if (bytes.length > maxBytes) {
                            return Mono.error(new IllegalArgumentException("文件超过大小限制: " + spec.getMaxSizeMB() + "MB"));
                        }
                    }
                    try {
                        String sha = gitHubService.uploadContent(spec, filePath, bytes, "Upload via Halo AttachmentHandler");
                        String cdnUrl = gitHubService.buildCdnUrl(spec, filePath);
                        // 记录附件到扩展模型，便于后续删除与审计（与现有策略一致）
                        AttachmentRecord record = new AttachmentRecord();
                        // 初始化 metadata（AbstractExtension 需要手动初始化）
                        record.setMetadata(new run.halo.app.extension.Metadata());
                        record.getMetadata().setName(owner + "-" + repoName + "-" + ts);
                        
                        // 初始化 spec（必需字段）
                        AttachmentRecord.Spec rs = new AttachmentRecord.Spec();
                        rs.setRepoRef(owner + "/" + repoName);
                        rs.setPath(filePath);
                        rs.setSha(sha);
                        rs.setSize(bytes.length);
                        rs.setCdnUrl(cdnUrl);
                        rs.setDeleted(false);
                        rs.setCreatedAt(Instant.now().toString());
                        record.setSpec(rs);
                        
                        // 使用响应式方式创建记录，避免阻塞调用
                        return client.create(record)
                            .flatMap(createdRecord -> {
                                // 构造 Halo Attachment 返回
                                Attachment attachment = new Attachment();
                                // 初始化 metadata（AbstractExtension 需要手动初始化）
                                attachment.setMetadata(new run.halo.app.extension.Metadata());
                                attachment.getMetadata().setName(createdRecord.getMetadata().getName());
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
                            });
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
        final Policy policy = context.policy();
        final ConfigMap cfg = context.configMap();
        
        // 从 ConfigMap 中读取配置字段
        if (cfg == null || cfg.getData() == null) {
            return Mono.error(new IllegalArgumentException("配置为空"));
        }
        
        // 解析嵌套的配置数据（与 upload 方法保持一致）
        String configJson = cfg.getData().get("default");
        if (configJson == null || configJson.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置数据为空"));
        }
        
        // 使用 JsonUtils 反序列化配置对象，替代手工解析
        final GithubOssPolicySettings settings;
        try {
            settings = JsonUtils.jsonToObject(configJson, GithubOssPolicySettings.class);
        } catch (Exception e) {
            return Mono.error(new IllegalArgumentException("配置解析失败: " + e.getMessage(), e));
        }
        
        final String owner = settings.getOwner();
        final String repoName = settings.getRepoName();
        final String token = settings.getToken();
        final String branch = settings.getBranch();
        
        // 校验必需字段
        if (owner == null || owner.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 owner"));
        }
        if (repoName == null || repoName.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 repoName"));
        }
        if (token == null || token.isBlank()) {
            return Mono.error(new IllegalArgumentException("配置缺少 token"));
        }
        
        // 构建 RepositoryConfig.Spec 对象
        RepositoryConfig.Spec spec = new RepositoryConfig.Spec();
        spec.setOwner(owner);
        spec.setRepo(repoName);  // 使用 repoName 作为 repo 值
        spec.setPat(token);
        spec.setBranch(branch != null ? branch : "master");
        
        // 直接执行远程删除：尝试从记录表查找 sha，否则走 API 查询获取 sha
        String fullPath = derivePathFromAttachment(attachment);
        // 规范化为仓库相对路径：处理 jsdelivr 风格的 gh/{owner}/{repo}@{branch}/{path}
        String repoPath = fullPath;
        int atIdx = repoPath.indexOf('@');
        if (repoPath.startsWith("gh/") && atIdx != -1) {
            int slashAfterAt = repoPath.indexOf('/', atIdx);
            if (slashAfterAt != -1 && slashAfterAt + 1 < repoPath.length()) {
                repoPath = repoPath.substring(slashAfterAt + 1);
            }
        }
        log.info("开始删除远程文件，完整路径: {}", fullPath);
        log.info("仓库相对路径: {}", repoPath);
        final String finalRepoPath = repoPath; // 供 lambda 引用的实际最终变量
        log.info("查找参数 - owner: {}, repoName: {}, 完整仓库名: {}", owner, repoName, owner + "/" + repoName);
        return findShaByRecord(owner + "/" + repoName, finalRepoPath)
                .doOnNext(sha -> log.info("findShaByRecord 返回结果: '{}'", sha))
                .flatMap(sha -> {
                    log.info("进入第一个 flatMap，从记录中查找到的 SHA: '{}'", sha);
                    // 如果从记录中找不到 sha，则通过 API 查询
                    if (sha == null || sha.isBlank()) {
                        log.info("记录中未找到 SHA，通过 API 查询");
                        return Mono.fromCallable(() -> gitHubService.fetchContentSha(spec, finalRepoPath));
                    } else {
                        log.info("使用记录中的 SHA: {}", sha);
                        return Mono.just(sha);
                    }
                })
                .doOnNext(finalSha -> log.info("最终获得的 SHA: '{}'", finalSha))
                .flatMap(sha -> {
                    log.info("进入第二个 flatMap，准备删除远程文件，SHA: '{}'", sha);
                    return Mono.fromCallable(() -> {
                        gitHubService.deleteContent(spec, finalRepoPath, sha, "Delete via Halo AttachmentHandler");
                        log.info("远程文件删除成功");
                        return attachment;
                    });
                })
                .doOnError(error -> log.error("删除过程中发生错误", error))
                .onErrorMap(GitHubExceptionHandler::map);
    }

    @Override
    public Mono<URI> getSharedURL(Attachment attachment, Policy policy, ConfigMap configMap, Duration ttl) {
        // 对于 GitHub 内容，CDN 地址即可视为共享 URL，这里直接返回 permalink 或基于路径构建 CDN URL
        try {
            String path = derivePathFromAttachment(attachment);
            
            // 从 ConfigMap 中读取配置字段
            if (configMap == null || configMap.getData() == null) {
                return Mono.error(new IllegalArgumentException("配置为空"));
            }
            
            // 解析嵌套的配置数据（与 upload 方法保持一致）
            String configJson = configMap.getData().get("default");
            if (configJson == null || configJson.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置数据为空"));
            }
            
            // 使用 JsonUtils 反序列化配置对象
            final GithubOssPolicySettings settings = JsonUtils.jsonToObject(configJson, GithubOssPolicySettings.class);
            final String owner = settings.getOwner();
            final String repoName = settings.getRepoName();
            final String token = settings.getToken();
            
            // 校验必需字段
            if (owner == null || owner.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 owner"));
            }
            if (repoName == null || repoName.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 repoName"));
            }
            if (token == null || token.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 token"));
            }
            
            // 构建 RepositoryConfig.Spec 对象
            RepositoryConfig.Spec spec = new RepositoryConfig.Spec();
            spec.setOwner(owner);
            spec.setRepo(repoName);  // 使用 repoName 作为 repo 值
            spec.setPat(token);
            
            String cdn = gitHubService.buildCdnUrl(spec, path);
            return Mono.just(URI.create(cdn));
        } catch (Exception e) {
            return Mono.error(GitHubExceptionHandler.map(e));
        }
    }

    @Override
    public Mono<URI> getPermalink(Attachment attachment, Policy policy, ConfigMap configMap) {
        // GitHub 场景：permalink 使用 CDN 地址
        try {
            String path = derivePathFromAttachment(attachment);
            
            // 从 ConfigMap 中读取配置字段
            if (configMap == null || configMap.getData() == null) {
                return Mono.error(new IllegalArgumentException("配置为空"));
            }
            
            // 解析嵌套的配置数据（与 upload 方法保持一致）
            String configJson = configMap.getData().get("default");
            if (configJson == null || configJson.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置数据为空"));
            }
            
            // 使用 JsonUtils 反序列化配置对象
            final GithubOssPolicySettings settings = JsonUtils.jsonToObject(configJson, GithubOssPolicySettings.class);
            final String owner = settings.getOwner();
            final String repoName = settings.getRepoName();
            final String token = settings.getToken();
            
            // 校验必需字段
            if (owner == null || owner.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 owner"));
            }
            if (repoName == null || repoName.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 repoName"));
            }
            if (token == null || token.isBlank()) {
                return Mono.error(new IllegalArgumentException("配置缺少 token"));
            }
            
            // 构建 RepositoryConfig.Spec 对象
            RepositoryConfig.Spec spec = new RepositoryConfig.Spec();
            spec.setOwner(owner);
            spec.setRepo(repoName);  // 使用 repoName 作为 repo 值
            spec.setPat(token);
            
            String cdn = gitHubService.buildCdnUrl(spec, path);
            return Mono.just(URI.create(cdn));
        } catch (Exception e) {
            return Mono.error(GitHubExceptionHandler.map(e));
        }
    }

    // 从附件对象推断仓库内路径：
    // 简化策略：优先从 status.permalink 去掉域名与前缀，回退到扩展记录表；如仍失败则抛异常。
    private String derivePathFromAttachment(Attachment attachment) {
        // 这里假设 permalink 为 CDN URL，例如 https://cdn.example.com/attachments/20241012/1697100000000.png
        String permalink = attachment.getStatus() != null ? attachment.getStatus().getPermalink() : null;
        if (permalink == null || permalink.isBlank()) {
            throw new IllegalArgumentException("附件缺少 permalink，无法推断路径");
        }
        // 简化解析：找到 "attachments/" 之后的部分作为路径（rootPath 默认 attachments）
        int idx = permalink.indexOf("/attachments/");
        if (idx > 0) {
            return permalink.substring(idx + 1); // 去掉前导斜杠，得到 attachments/...
        }
        // 如果自定义 rootPath，则更复杂，这里回退为直接取 URL 路径部分
        try {
            java.net.URI uri = java.net.URI.create(permalink);
            String p = uri.getPath();
            if (p.startsWith("/")) p = p.substring(1);
            return p;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析附件 permalink: " + permalink);
        }
    }

    private Mono<String> findShaByRecord(String repoName, String path) {
        log.info("开始从记录中查找 SHA，仓库: {}, 路径: {}", repoName, path);
        // 改为异步实现，避免阻塞调用
        return client.list(AttachmentRecord.class, e -> true, null, 0, 1000)
                .doOnNext(result -> log.info("查询到 {} 条附件记录", result.getItems().size()))
                .flatMapMany(result -> reactor.core.publisher.Flux.fromIterable(result.getItems()))
                .doOnNext(rec -> {
                    if (rec.getSpec() != null) {
                        log.info("检查记录: repoRef={}, path={}", rec.getSpec().getRepoRef(), rec.getSpec().getPath());
                    }
                })
                .filter(rec -> rec.getSpec() != null && repoName.equals(rec.getSpec().getRepoRef())
                        && path.equals(rec.getSpec().getPath()))
                .doOnNext(rec -> log.info("找到匹配记录，SHA: {}", rec.getSpec().getSha()))
                .map(rec -> rec.getSpec().getSha())
                .next()
                .doOnSuccess(sha -> {
                    if (sha != null && !sha.isEmpty()) {
                        log.info("从记录中成功找到 SHA: {}", sha);
                    } else {
                        log.info("记录中未找到匹配的 SHA");
                    }
                })
                .onErrorReturn(""); // 出错时返回空字符串，而不是 null
    }

    /**
     * 检测 GitHub 连通性
     * @return Mono<Boolean> 连通性检测结果
     */
    private Mono<Boolean> checkGitHubConnectivity() {
        return Mono.fromCallable(() -> {
            try {
                // 使用 Java 11+ 的 HttpClient 进行连通性检测
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
                
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://github.com"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
                
                java.net.http.HttpResponse<String> response = client.send(request, 
                    java.net.http.HttpResponse.BodyHandlers.ofString());
                
                // 检查响应状态码，200-399 都认为是成功
                return response.statusCode() >= 200 && response.statusCode() < 400;
                
            } catch (Exception e) {
                // 任何异常都认为连接失败
                System.err.println("GitHub 连通性检测失败: " + e.getMessage());
                return false;
            }
        })
        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()) // 在弹性线程池中执行阻塞操作
        .onErrorReturn(false); // 发生错误时返回 false
    }

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