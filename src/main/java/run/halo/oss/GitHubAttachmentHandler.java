package run.halo.oss;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.internal.StringUtil;
import org.pf4j.Extension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.util.UriUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Constant;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.AttachmentHandler;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * @author xirizhi
 */
@Slf4j
@Extension
public class GitHubAttachmentHandler implements AttachmentHandler {
    public final static String handlerName = "githuboss";
    public static final String OBJECT_KEY = "github.plugin.halo.run/object-key";
    private static final String API_CONTENTS = "https://api.github.com/repos/{owner}/{repo}/contents/{path}";
    private static final String API_TREE = "https://api.github.com/repos/{owner}/{repo}/git/trees/{branch}";
    private static WebClient webClient;
    private static final String defaultMessage = "githuboss plugin commit";


    // 用作于文件去重
    private final Map<String, Object> uploadingFile = new ConcurrentHashMap<>();

    private final ReactiveExtensionClient extensionClient;

    public GitHubAttachmentHandler(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
        GitHubPolicyHandler.initWatch(this.extensionClient, this);

        BasicConfig basicConfig = getConfigMap(BasicConfig.NAME, BasicConfig.GROUP).block();
        int updateMax = 3;
        if (basicConfig != null && basicConfig.getUpdateMax() != null) {
            updateMax = basicConfig.getUpdateMax();
        }
        debug("初始化请求最大并发数" + updateMax, null);
        ConnectionProvider connectionProvider = ConnectionProvider.builder("githubOssConnectionProvider")
                .maxConnections(updateMax) // 最大同时请求数
                .pendingAcquireMaxCount(100) // 等待队列大小
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider).responseTimeout(Duration.ofMillis(60000));

        webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Override
    public Mono<Attachment> upload(UploadContext uploadContext) {
        debug("开始执行上传文件", uploadContext.file().filename());
        return Mono.just(uploadContext).filter(context -> this.shouldHandle(context.policy()))
                .flatMap(context -> {
                    final var properties = getProperties(context.configMap());
                    debug("存储策略配置参数", properties);
                    return upload(context, properties).map(
                            objectDetail -> this.buildAttachment(properties, objectDetail));
                });
    }

    @Override
    public Mono<Attachment> delete(DeleteContext deleteContext) {
        debug("开始删除文件", deleteContext.attachment());
        return Mono.just(deleteContext).filter(context -> this.shouldHandle(context.policy()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(context -> {
                    var annotations = context.attachment().getMetadata().getAnnotations();
                    if (annotations == null || !annotations.containsKey(OBJECT_KEY)) {
                        return;
                    }
                    var objectKey = annotations.get(OBJECT_KEY);
                    var properties = getProperties(deleteContext.configMap());
                    log.info("{} is being deleted from GithubOSS", properties);
                    if (properties.getDeleteSync()) {
                        ossExecute(() -> delete(objectKey, properties), null).flatMap(exit -> {
                            log.info("was deleted successfully from GithubOSS,final result：{}", exit);
                            return Mono.just(exit);
                        }).block();
                    }
                }).map(DeleteContext::attachment);
    }

    boolean shouldHandle(Policy policy) {
        if (policy == null || policy.getSpec() == null ||
                policy.getSpec().getTemplateName() == null) {
            return false;
        }
        debug("Github Oss 模板元数据配置: ", policy.getMetadata());
        return handlerName.equals(policy.getSpec().getTemplateName());
    }

    GithubOssProperties getProperties(ConfigMap configMap) {
        var settingJson = configMap.getData().getOrDefault("default", "{}");
        return JsonUtils.jsonToObject(settingJson, GithubOssProperties.class);
    }

    Attachment buildAttachment(GithubOssProperties properties, ObjectDetail objectDetail) {
        var externalLink = jsdelivrConvert(properties, objectDetail.objectKey);

        var metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        metadata.setAnnotations(
                Map.of(OBJECT_KEY, objectDetail.objectKey(), Constant.EXTERNAL_LINK_ANNO_KEY,
                        UriUtils.encodePath(externalLink, StandardCharsets.UTF_8)));

        var githubVo = objectDetail.githubVo;
        var spec = new Attachment.AttachmentSpec();
        spec.setSize(githubVo.getSize());
        spec.setDisplayName(objectDetail.fileName());
        spec.setMediaType(objectDetail.fileType());

        var attachment = new Attachment();
        attachment.setMetadata(metadata);
        attachment.setSpec(spec);
        return attachment;
    }

    Mono<ObjectDetail> upload(UploadContext uploadContext, GithubOssProperties properties) {
        return Mono.just(new GitHubAttachmentHandler.FileNameHolder(uploadContext.file().filename(), properties))
                .flatMap(fileNameHolder -> checkFileExistsAndRename(fileNameHolder)
                        .flatMap(holder -> {
                            log.info("Uploading {} into GitHub {}/{}/{}/{}", uploadContext.file().filename(),
                                    properties.getOwner(), properties.getRepo(), properties.getPath(), holder.objectKey);
                            return ossExecute(() -> upload(uploadContext.file(), holder), null);
                        })
                        .doFinally(signalType -> {
                            if (fileNameHolder.needRemoveMapKey) {
                                uploadingFile.remove(fileNameHolder.getUploadingMapKey());
                            }
                        }));
    }

    // 发起请求上传文件
    public Mono<ObjectDetail> upload(FilePart filePart, FileNameHolder fileNameHolder) {
        GithubOssProperties properties = fileNameHolder.properties;
        return Mono.zip(filePart.content().reduce(DataBuffer::write),
                getConfigMap(BasicConfig.NAME, BasicConfig.GROUP)).flatMap(tuple -> {
            var dataBuffer = tuple.getT1();
            var baseConfig = tuple.getT2();
            debug("配置信息", baseConfig);
            String base64Content = Base64.getEncoder().encodeToString(dataBuffer.toByteBuffer().array());
            JSONObject jsonObject = new JSONObject();
            jsonObject.putOpt("committer", new JSONObject()
                    .putOpt("email", baseConfig.email)
                    .putOpt("name", baseConfig.name));
            jsonObject.putOpt("content", base64Content);
            jsonObject.putOpt("message", defaultMessage);
            jsonObject.putOpt("branch", properties.getBranch());
            return webClient.method(HttpMethod.PUT)
                    .uri(buildContentsPath(properties, fileNameHolder.objectKey))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(jsonObject.toString())
                    .exchangeToMono(clientResponse -> {
                        Mono<String> dataMap = clientResponse.bodyToMono(String.class).map(m -> {
                            debug(String.format("上传文件 %s 调用状态码：%s", fileNameHolder.objectKey, clientResponse.statusCode().value()), m);
                            return m;
                        });
                        if (clientResponse.statusCode().is2xxSuccessful()) {
                            return dataMap.map(body -> {
                                JSONObject entries = JSONUtil.parseObj(body).getJSONObject("content");
                                var githubVo = new GithubVo();
                                githubVo.setSize(entries.getLong("size"));
                                debug("返回文件类型: " + entries.getStr("type"), "");
                                return new GitHubAttachmentHandler.ObjectDetail(fileNameHolder.objectKey, githubVo, fileNameHolder.fileName, fileNameHolder.fileType);
                            });
                        } else {
                            return dataMap.flatMap(m->Mono.error(new RuntimeException("Failed to upload file")));
                        }
                    })
                    .onErrorContinue((e, i) -> {
                        e.printStackTrace();
                        log.info(JSONUtil.toJsonStr(i));
                        // Log the error here.
                    });
        });
    }

    public Mono<Boolean> delete(String filePath, GithubOssProperties properties) {
        debug("开始删除文件:filePath:" + filePath, properties);
        return ossExecute(() -> getFileSha(properties, filePath), null).flatMap(sha -> {
            if (StringUtil.isBlank(sha)) {
                debug("文件不存在", null);
                return Mono.just(true);
            }
            return getConfigMap(BasicConfig.NAME, BasicConfig.GROUP).flatMap(baseConfig -> {
                JSONObject jsonObject = new JSONObject();
                jsonObject.putOpt("branch", properties.getBranch());
                jsonObject.putOpt("committer", new JSONObject()
                        .putOpt("email", baseConfig.getEmail())
                        .putOpt("name", baseConfig.getName()));
                jsonObject.putOpt("message", defaultMessage);
                jsonObject.putOpt("sha", sha);
                return webClient.method(HttpMethod.DELETE)
                        .uri(buildContentsPath(properties, filePath))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                        .bodyValue(jsonObject.toString())
                        .exchangeToMono(clientResponse -> {
                            Mono<String> dataMap = clientResponse.bodyToMono(String.class).map(m -> {
                                debug("删除文件调用结果", m);
                                return m;
                            });
                            if (clientResponse.statusCode().is2xxSuccessful()) {
                                return dataMap.flatMap(m -> Mono.just(true));
                            } else if (clientResponse.statusCode().is4xxClientError()) {
                                return dataMap.flatMap(m -> Mono.just(false));
                            } else {
                                return dataMap.flatMap(m -> Mono.error(new RuntimeException("Failed to delete file")));
                            }
                        });
            });
        });
    }


    private Mono<GitHubAttachmentHandler.FileNameHolder> checkFileExistsAndRename(FileNameHolder fileNameHolder) {
        return Mono.defer(() -> {
                    // deduplication of uploading files
                    if (uploadingFile.put(fileNameHolder.objectKey,
                            fileNameHolder.fileName) != null) {
                        return Mono.error(new FileAlreadyExistsException("文件 " + fileNameHolder.objectKey
                                + " 已存在，建议更名后重试。[local]"));
                    }
                    fileNameHolder.needRemoveMapKey = true;
                    // check whether file exists
                    return ossExecute(() -> checkFileExists(fileNameHolder.properties, fileNameHolder.objectKey), null)
                            .flatMap(exist -> {
                                if (exist) {
                                    return Mono.error(new FileAlreadyExistsException("文件 " + fileNameHolder.objectKey
                                            + " 已存在，建议更名后重试。[remote]"));
                                } else {
                                    return Mono.just(fileNameHolder);
                                }
                            });
                })
                .retryWhen(Retry.max(3)
                        .filter(FileAlreadyExistsException.class::isInstance)
                        .doAfterRetry(retrySignal -> {
                            if (fileNameHolder.needRemoveMapKey) {
                                uploadingFile.remove(fileNameHolder.getUploadingMapKey());
                                fileNameHolder.needRemoveMapKey = false;
                            }
                            fileNameHolder.randomFileName();
                        })
                )
                .onErrorMap(Exceptions::isRetryExhausted,
                        throwable -> new ServerWebInputException(throwable.getCause().getMessage()));
    }

    public Mono<String> getFileInfo(GithubOssProperties properties, String objectKey) {
        // Perform further operations on the result 开始获取文件信息，太大的文件拿不了
        return webClient.method(HttpMethod.GET)
                .uri(buildContentsPath(properties, objectKey))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                .header("Accept", "application/vnd.github+json")
                .exchangeToMono(clientResponse -> {
                    debug("校验文件返回结果编状态码 " + clientResponse.statusCode().value(), null);
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return clientResponse.bodyToMono(String.class);
                    } else if (clientResponse.statusCode().is4xxClientError()) {
                        return Mono.just("");
                    } else {
                        return Mono.error(new RuntimeException("Failed to check file existence"));
                    }
                });
    }

    // 判断文件是否存在
    public Mono<Boolean> checkFileExists(GithubOssProperties properties, String objectKey) {
        debug("校验远程仓库文件是否存在: " + buildContentsPath(properties, objectKey), "");
        // 不为空代表存在这个文件
        return getFileSha(properties, objectKey).flatMap(data -> Mono.just(StrUtil.isNotBlank(data)));
    }

    // 获取 github 目录下所有文件列表
    public Mono<String> getFileShaList(GithubOssProperties properties, String filePath) {
        // Perform further operations on the result
        return webClient.method(HttpMethod.GET)
                .uri(buildTreePath(properties, filePath))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                .header("Accept", "application/vnd.github+json")
                .exchangeToMono(clientResponse -> {
                    Mono<String> dataMap = clientResponse.bodyToMono(String.class).map(m -> {
                        debug("查询仓库目录下文件列表", m);
                        return m;
                    });
                    if (clientResponse.statusCode().is2xxSuccessful()) {
                        return dataMap;
                    } else if (clientResponse.statusCode().is4xxClientError()) {
                        return dataMap.flatMap(f -> Mono.just("{}"));
                    } else {
                        return dataMap.flatMap(f -> Mono.error(new RuntimeException("Failed to check file existence")));
                    }
                });
    }

    // 获取文件在仓库中的sha值
    public Mono<String> getFileSha(GithubOssProperties properties, String objectKey) {
        int index = objectKey.lastIndexOf("/");
        final String filePath = index == -1 ? "" : objectKey.substring(0, index);
        final String fileName = index == -1 ? objectKey : objectKey.substring(index + 1);
        debug("获取sha值，路径：" + filePath + " 文件名: " + fileName, null);
        // Perform further operations on the result
        return getFileShaList(properties, filePath).flatMap(data -> {
            List<String> collect = Optional.ofNullable(JSONUtil.parseObj(data).getJSONArray("tree")).orElse(new JSONArray()).stream().filter(f -> {
                JSONObject obj = JSONUtil.parseObj(f);
                return fileName.equals(obj.getStr("path"));
            }).map(m -> JSONUtil.parseObj(m).getStr("sha")).toList();
            if (collect.isEmpty()) {
                return Mono.just("");
            } else {
                return Mono.just(collect.get(0));
            }
        });
    }

    private <T> Mono<T> ossExecute(Supplier<Mono<T>> runnable, Runnable finalizer) {
        return runnable.get()
                .onErrorMap(throwable -> {
                    log.error("""
                            Caught an ClientException, which means the client encountered a serious internal
                            problem while trying to communicate with OSS, such as not being able to access
                            the network.
                            """);
                    return Exceptions.propagate(throwable);
                })
                .doFinally(signalType -> {
                    if (finalizer != null) {
                        finalizer.run();
                    }
                });
    }

    // 获取插件主体配置
    public Mono<BasicConfig> getConfigMap(String name, String key) {
        return extensionClient.fetch(ConfigMap.class, name)
                .map(ConfigMap::getData)
                .map(m -> m.get(key))
                .mapNotNull(json -> JSONUtil.toBean(json, BasicConfig.class))
                .onErrorMap(throwable -> Exceptions.propagate(new RuntimeException("请检查插件主体配置是否配置" + throwable.getMessage())));
    }

    public String buildContentsPath(GithubOssProperties properties, String filePath) {
        return API_CONTENTS.replace("{owner}", properties.getOwner())
                .replace("{repo}", properties.getRepo()).replace("{path}", filePath);
    }

    // path 不用当前的存储配置的路径，可能和图片路径不符合
    public String buildTreePath(GithubOssProperties properties, String path) {
        String url = API_TREE.replace("{owner}", properties.getOwner())
                .replace("{repo}", properties.getRepo()).replace("{branch}", properties.getBranch());
        if (StrUtil.isBlank(path)) {
            return url;
        }
        return url + ":" + path;
    }

    // 返回 jsdeliver cdn路径
    public static String jsdelivrConvert(GithubOssProperties properties, String path) {
        return String.format("https://%s/gh/%s/%s@%s/%s", properties.getJsdelivr(), properties.getOwner(), properties.getRepo(), properties.getBranch(), path);
    }

    void debug(String msg, Object object) {
        if (log.isDebugEnabled()) {
            if (object == null) {
                log.debug(msg);
                return;
            }
            log.debug("{}:{}", msg, JSONUtil.toJsonStr(object));
        }
    }

    record ObjectDetail(String objectKey, GithubVo githubVo, String fileName, String fileType) {
    }

    @Data
    static class FileNameHolder {
        final GithubOssProperties properties;
        // 源文件名
        final String originalFileName;
        // 上传后的文件名
        String fileName;
        // 文件类型
        String fileType;
        // 包含路径的文件名，上传到OSS上的完整地址，也是唯一标识
        String objectKey;
        boolean needRemoveMapKey = false;

        FileNameHolder(String fileName, GithubOssProperties properties) {
            this.fileName = FileNameUtils.formatDateInFileName(fileName);
            this.objectKey = properties.getObjectName(this.fileName);
            this.fileType = FileNameUtils.fileType(this.fileName);
            this.originalFileName = fileName;
            this.properties = properties;
        }

        public String getUploadingMapKey() {
            return objectKey;
        }

        public void randomFileName() {
            this.fileName = FileNameUtils.randomFileName(originalFileName, 4);
            this.objectKey = properties.getObjectName(fileName);
        }
    }

    @Data
    static class GithubVo {
        private Long size;
        private String type;
    }

    @Data
    public static class BasicConfig {
        public static final String NAME = "githuboss-settings";
        public static final String GROUP = "basic";
        String email;
        String name;
        Integer updateMax;
    }
}
