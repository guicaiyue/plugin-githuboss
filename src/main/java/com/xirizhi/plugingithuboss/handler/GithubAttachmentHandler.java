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
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * 解析策略配置，带 fallback。
     * Halo 有时会将插件级 ConfigMap（githuboss-configMap，只有 basic/network）
     * 误传给 AttachmentHandler，而不是策略级 ConfigMap（有 default 键含 token）。
     * 此方法检测到 default 键缺失时，自动通过 policy.configMapName 获取正确的 ConfigMap。
     */
    private Mono<GithubOssPolicySettings> resolveSettings(ConfigMap configMap, Policy policy) {
        var settingJson = configMap.getData().get("default");
        if (settingJson != null && !settingJson.isBlank() && !"{}".equals(settingJson.trim())) {
            return Mono.just(JsonUtils.jsonToObject(settingJson, GithubOssPolicySettings.class));
        }
        // Fallback: 通过 policy 的 configMapName 获取策略专属 ConfigMap
        var cmName = policy.getSpec().getConfigMapName();
        if (cmName != null && !cmName.isBlank()) {
            log.warn("ConfigMap '{}' 缺少 'default' 配置，fallback 到策略 ConfigMap '{}'",
                    configMap.getMetadata().getName(), cmName);
            return client.fetch(ConfigMap.class, cmName)
                    .map(cm -> {
                        var json = cm.getData().getOrDefault("default", "{}");
                        return JsonUtils.jsonToObject(json, GithubOssPolicySettings.class);
                    })
                    .switchIfEmpty(Mono.fromCallable(() -> {
                        log.error("策略 ConfigMap '{}' 不存在，使用空配置", cmName);
                        return JsonUtils.jsonToObject("{}", GithubOssPolicySettings.class);
                    }));
        }
        log.error("ConfigMap '{}' 缺少 'default' 配置且 policy 无 configMapName，使用空配置",
                configMap.getMetadata().getName());
        return Mono.just(JsonUtils.jsonToObject("{}", GithubOssPolicySettings.class));
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
        
        return resolveSettings(context.configMap(), policy)
                .flatMap(settings -> {
                    final String owner = settings.getOwner();
                    final String repoName = settings.getRepoName();
                    var pathBuild = buildPathAndName(settings, filePart);

                    return readFileBytes(filePart)
                            .flatMap(bytes -> validateMinSize(bytes, settings.getMinSizeMB()))
                            .flatMap(bytes -> gitHubService.checkConnectivity()
                                    .flatMap(isConnected -> isConnected
                                            ? Mono.just(bytes)
                                            : Mono.error(new IllegalStateException("GitHub 无法访问，请检查网络连接或配置代理"))))
                            .flatMap(bytes -> gitHubService.uploadContent(settings, pathBuild.filePath(), bytes,
                                        "Upload via Halo AttachmentHandler")
                                    .map(sha -> {
                                        log.info("文件上传成功,owner: {}, repoName: {}, 完整仓库名: {}, 完整路径: {}, sha: {}", owner, repoName, owner + "/" + repoName, pathBuild.filePath(), sha);
                                        Attachment attachment = buildAttachment(pathBuild.filePath(), sha, (long) bytes.length, policy);
                                        return attachment;
                                    }))
                            .doFinally(signalType -> {
                                synchronized (RESERVED_PATHS) {
                                    RESERVED_PATHS.remove(pathBuild.filePath());
                                }
                            });
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

            String tempFilePath = Paths.get(Optional.ofNullable(settings.getPath()).orElse("").trim(),folderDir.trim(),filename)
                    .normalize()
                    .toString();
            final String filePath = tempFilePath.startsWith("/") ? tempFilePath.substring(1) : tempFilePath;
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
        final Policy policy = context.policy();
        
        return resolveSettings(context.configMap(), policy)
                .flatMap(settings -> {
                    final String sha = attachment.getMetadata().getAnnotations().get("sha");
                    final String path = attachment.getMetadata().getAnnotations().remove("path");

                    return Mono.defer(() -> {
                                log.info("开始删除远程文件,owner: {}, repoName: {}, 完整仓库名: {}, 完整路径: {}", settings.getOwner(), settings.getRepoName(), settings.getOwner() + "/" + settings.getRepoName(), path);

                                boolean unLinked = Boolean.parseBoolean(attachment.getMetadata().getAnnotations().getOrDefault(Constant.ANNOTATION_UNLINKED, Boolean.FALSE.toString()));
                                if (unLinked) {
                                    log.info("附件已解除关联，仅逻辑删除 attachment: {}", JsonUtils.objectToJson(attachment));
                                    return Mono.just(attachment);
                                } else {
                                    return gitHubService.deleteContent(settings, path, sha, "Delete via Halo AttachmentHandler")
                                            .then(Mono.fromCallable(() -> {
                                                log.info("远程文件删除成功 attachment: {}", JsonUtils.objectToJson(attachment));
                                                return attachment;
                                            }));
                                }
                            })
                            .doOnError(error -> log.error("删除过程中发生错误", error));
                })
                .onErrorMap(GitHubExceptionHandler::map);
    }

    @Override
    public Mono<URI> getSharedURL(Attachment attachment, Policy policy, ConfigMap configMap, Duration ttl) {
        String path = attachment.getMetadata().getAnnotations().get("path");
        if (path == null) {
            return Mono.error(new IllegalArgumentException("附件缺少 path 注解"));
        }
        return resolveSettings(configMap, policy)
                .flatMap(settings -> gitHubService.buildCdnUrl(settings, path).map(URI::create))
                .onErrorMap(GitHubExceptionHandler::map);
    }

    @Override
    public Mono<URI> getPermalink(Attachment attachment, Policy policy, ConfigMap configMap) {
        String path = attachment.getMetadata().getAnnotations().get("path");
        if (path == null) {
            return Mono.error(new IllegalArgumentException("附件缺少 path 注解"));
        }
        return resolveSettings(configMap, policy)
                .flatMap(settings -> gitHubService.buildCdnUrl(settings, path).map(URI::create))
                .onErrorMap(GitHubExceptionHandler::map);
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
        
        // 初始化 status 避免 Halo 2.25+ 调用 setPermalink 时 NPE
        Attachment.AttachmentStatus status = new Attachment.AttachmentStatus();
        attachment.setStatus(status);

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
    private Mono<byte[]> validateMinSize(byte[] bytes, Double minSizeMB) {
        log.info("校验文件最小大小，文件大小: {}，最小限制: {}MB", bytes.length, minSizeMB);
        if (minSizeMB == null || minSizeMB <= 0) {
            return Mono.just(bytes);
        }
        if (bytes == null) {
            return Mono.error(new IllegalArgumentException("文件内容为空"));
        }
        long minBytes = (long) (minSizeMB * 1024L * 1024L);
        if ((long) bytes.length > minBytes) {
            return Mono.error(new IllegalArgumentException("文件大小高于最大限制: " + minSizeMB + "MB"));
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