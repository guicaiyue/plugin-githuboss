package com.xirizhi.plugingithuboss.handler;

import com.xirizhi.plugingithuboss.config.Constant;
import com.xirizhi.plugingithuboss.exception.GitHubExceptionHandler;
import com.xirizhi.plugingithuboss.extension.GithubOssPolicySettings;
import com.xirizhi.plugingithuboss.service.GitHubService;

import lombok.extern.slf4j.Slf4j;
import org.pf4j.Extension;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ResponseStatusException;
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
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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

    // 新增：进程内文件路径占位集合（不自动淘汰，上传结束后释放）
    private static final java.util.Set<String> RESERVED_PATHS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

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

        // 新增：在上传最前阶段生成并占位唯一文件名/路径（秒级加一，不等待真实时间流逝）
        var pathBuild = buildPathAndName(settings, filePart);
        
        // 文件大小检测优先于 GitHub 连通性
        return readFileBytes(filePart)
                .flatMap(bytes -> validateMinSize(bytes, settings.getMinSizeMB()))
                .flatMap(bytes -> Mono.fromSupplier(gitHubService::checkConnectivity)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(isConnected -> isConnected
                                ? Mono.just(bytes)
                                : Mono.error(new IllegalStateException("GitHub 无法访问，请检查网络连接或配置代理"))))
                .flatMap(bytes -> Mono.fromCallable(() -> {
                    String filePath = pathBuild.filePath();
                    String sha = gitHubService.uploadContent(settings, filePath, bytes,
                            "Upload via Halo AttachmentHandler");
                    String cdnUrl = gitHubService.buildCdnUrl(settings, filePath);
                    log.info("文件上传成功,owner: {}, repoName: {}, 完整仓库名: {}, 完整路径: {}, sha: {}, cdnUrl: {}", owner, repoName, owner + "/" + repoName, filePath, sha, cdnUrl);
                    Attachment attachment = buildAttachment(filePath, sha, (long) bytes.length, policy);
                    return attachment;
                }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .doFinally(signalType -> {
                    synchronized (RESERVED_PATHS) {
                        RESERVED_PATHS.remove(pathBuild.filePath());
                    }
                })
                .onErrorMap(GitHubExceptionHandler::map);
    }

    // 新增：将路径与文件名构造逻辑提取为方法，保持原有逻辑不变
    private record PathBuildResult(String filePath, String filename, String ext) {}

    // 新增：将路径与文件名构造逻辑提取为方法，保持原有逻辑不变
    private PathBuildResult buildPathAndName(GithubOssPolicySettings settings, FilePart filePart) {
        final String namePrefix = settings.getNamePrefix();
        // 基准时间只取一次；若冲突则在此基础上“秒级 +1”
        var baseNow = Instant.now().atZone(ZoneId.systemDefault());

        final String originalFilename = filePart.filename();
        final String ext = extractExt(originalFilename);
        final String trimmedOriginal = Optional.ofNullable(originalFilename)
                .map(String::trim)
                .orElse("");
        // 移除 15 字符限制，改为保留完整原始文件名（不含扩展名）
        final String baseOriginal = trimmedOriginal.isBlank()
                ? ""
                : (ext != null && trimmedOriginal.lastIndexOf('.') >= 0
                    ? trimmedOriginal.substring(0, trimmedOriginal.lastIndexOf('.'))
                    : trimmedOriginal);

        int bump = 0;
        final int maxAttempts = 120; // 合理上限，避免极端情况下无限循环
        while (true) {
            var now = baseNow.plusSeconds(bump);

            final String folderPattern = (namePrefix != null && !namePrefix.isBlank())
                    ? switch (namePrefix) {
                        case "yyyy" -> "yyyy";
                        case "yyyyMM" -> "yyyyMM";
                        case "yyyyMMdd" -> "yyyyMMdd";
                        default -> null;
                    }
                    : null;
            final String folderDir = folderPattern == null
                    ? ""
                    : DateTimeFormatter.ofPattern(folderPattern).format(now);

            String timePattern;
            if (folderPattern == null) {
                timePattern = "yyyyMMddHHmmss";
            } else if ("yyyy".equals(folderPattern)) {
                timePattern = "MMddHHmmss";
            } else if ("yyyyMM".equals(folderPattern)) {
                timePattern = "ddHHmmss";
            } else { // "yyyyMMdd"
                timePattern = "ddHHmmss";
            }
            String timePrefix = DateTimeFormatter.ofPattern(timePattern).format(now);

            // 将日期后缀追加到原始文件名后面（扩展名前），保留扩展名
            final String filename = !baseOriginal.isBlank()
                    ? (baseOriginal + "-" + timePrefix + (ext != null ? ("." + ext) : ""))
                    : (timePrefix + (ext != null ? ("." + ext) : ""));

            final String filePath = new StringBuilder(settings.getPath())
                    .append(folderDir.isBlank() ? "" : ("/" + folderDir))
                    .append("/")
                    .append(filename)
                    .toString();

            synchronized (RESERVED_PATHS) {
                if (!RESERVED_PATHS.contains(filePath)) {
                    RESERVED_PATHS.add(filePath);
                    return new PathBuildResult(filePath, filename, ext);
                }
            }

            bump++;
            if (bump > maxAttempts) {
                throw new IllegalStateException("无法生成唯一文件名: 冲突过多，请稍后重试");
            }
        }
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
                    
                    // 检查是否仅解除关联
                    boolean unLinked = Boolean.parseBoolean(attachment.getMetadata().getAnnotations().getOrDefault(Constant.ANNOTATION_UNLINKED, Boolean.FALSE.toString()));
                    if (unLinked) {
                        log.info("附件已解除关联，仅逻辑删除 attachment: {}", JsonUtils.objectToJson(attachment));
                    }else{
                        gitHubService.deleteContent(settings, path, sha, "Delete via Halo AttachmentHandler");
                        log.info("远程文件删除成功 attachment: {}", JsonUtils.objectToJson(attachment));
                    }
                    
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

    public <T> Mono<T> authenticationConsumer(Function<Authentication, Mono<T>> func) {
        return ReactiveSecurityContextHolder.getContext()
            .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Authentication required.")))
            .map(SecurityContext::getAuthentication)
            .flatMap(func);
    }

    public Attachment buildAttachment(String path, String sha, long size, Policy policy) {
        Metadata metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        HashMap<String, String> annotationMap = new HashMap<>();
        annotationMap.put("path", path);
        annotationMap.put("sha", sha);
        metadata.setAnnotations(annotationMap);

        Attachment.AttachmentSpec as = new Attachment.AttachmentSpec();
        as.setSize(size);
        as.setDisplayName(extractFileName(path));
        as.setMediaType(MediaTypeFactory.getMediaType(as.getDisplayName())
                .orElse(MediaType.APPLICATION_OCTET_STREAM).toString());
        as.setPolicyName(policy.getMetadata().getName());
        // as.setGroupName("githuboss");
        // as.setOwnerName(authenticationConsumer(auth -> Mono.just(auth.getName())).block());
        
        Attachment attachment = new Attachment();
        attachment.setMetadata(metadata);
        attachment.setSpec(as);

        return attachment;
    }

    // 获取文件名
    private String extractFileName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
    // 获取文件扩展名
    private String extractExt(String filename) {
        if (filename == null) return null;
        int i = filename.lastIndexOf('.');
        if (i < 0) return null;
        return filename.substring(i + 1);
    }

    /**
     * 通用：校验文件最小大小（单位 MB），不满足时返回 Mono.error。
     * minSizeMB 为 null 或 <=0 时直接回传 bytes。
     */
    private Mono<byte[]> validateMinSize(byte[] bytes, Integer minSizeMB) {
        if (minSizeMB == null || minSizeMB <= 0) {
            return Mono.just(bytes);
        }
        if (bytes == null) {
            return Mono.error(new IllegalArgumentException("文件内容为空"));
        }
        long minBytes = minSizeMB * 1024L * 1024L;
        if ((long) bytes.length < minBytes) {
            return Mono.error(new IllegalArgumentException("文件大小低于最小限制: " + minSizeMB + "MB"));
        }
        return Mono.just(bytes);
    }

    private Mono<byte[]> readFileBytes(FilePart filePart) {
        return filePart.content()
                .reduce(new java.io.ByteArrayOutputStream(), (baos, dataBuffer) -> {
                    try {
                        var nio = dataBuffer.asByteBuffer();
                        byte[] bytes = new byte[nio.remaining()];
                        nio.get(bytes);
                        baos.write(bytes);
                        return baos;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(java.io.ByteArrayOutputStream::toByteArray);
    }
}