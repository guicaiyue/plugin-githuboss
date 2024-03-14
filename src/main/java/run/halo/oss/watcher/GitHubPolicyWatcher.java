package run.halo.oss.watcher;

import cn.hutool.json.JSONUtil;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Constant;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.app.extension.Watcher;
import run.halo.oss.config.GithubOssProperties;
import run.halo.oss.handler.GitHubAttachmentHandler;
import run.halo.oss.util.FileNameUtils;
import run.halo.oss.util.GitHubHttpUtil;

/**
 * 策略处理器
 * Watcher 是一个策略观察接口
 * GitHubPolicyWatcher 是对附件github策略新增等事件的观察
 */
@Slf4j
@Component
public class GitHubPolicyWatcher implements Watcher {
    private final ReactiveExtensionClient extensionClient;
    private final GitHubAttachmentHandler gitHubAttachmentHandler;

    public GitHubPolicyWatcher(ReactiveExtensionClient extensionClient,//反应式客户端
        GitHubAttachmentHandler gitHubAttachmentHandler) {
        this.extensionClient = extensionClient;
        this.gitHubAttachmentHandler = gitHubAttachmentHandler;
        // 为客户端 halo 增加github附件策略观察处理器
        extensionClient.watch(this);
    }

    /**
     *
     */

    private Runnable disposeHook;
    private boolean disposed = false;

    /**
     * 收到了用户，在页面添加新的GitHub策略附件库事件
     *
     * @param extension
     */
    @Override
    public void onAdd(Extension extension) {
        if (!checkExtension(extension)) {
            return;
        }
        Policy policy = convertTo(extension);
        if (shouldHandle(policy)) {
            var configMapName = policy.getSpec().getConfigMapName(); // 获取填写的配置信息
            ReactiveSecurityContextHolder.getContext()
                .map(ctx -> {
                    var name = ctx.getAuthentication().getName();
                    debug("获取文件名", name);
                    return name;
                }).subscribe();
            extensionClient.get(ConfigMap.class, configMapName)
                .flatMap(configMap -> Mono.just(
                    JSONUtil.toBean(configMap.getData().get("default"), GithubOssProperties.class)))
                .doOnNext(data -> {
                    debug("创建附件", data);
                    // 将GithubOssProperties对象传递给方法
                    handleAttachments(data, policy);
                })
                .subscribe();
        }
    }

    // TODO 从上游传入线程
    @Override
    public void registerDisposeHook(Runnable dispose) {
        this.disposeHook = dispose;
    }

    //TODO 疑似开启禁用的逻辑
    @Override
    public void dispose() {
        if (isDisposed()) {
            return;
        }
        this.disposed = true;
        if (this.disposeHook != null) {
            this.disposeHook.run();
        }
    }

    @Override
    public boolean isDisposed() {
        return this.disposed;
    }


    /**
     * 是不是属于我的策略
     *
     * @param policy 策略
     * @return 结果，我的为true,否则为false
     */
    boolean shouldHandle(Policy policy) {
        if (policy == null || policy.getSpec() == null ||
            policy.getSpec().getTemplateName() == null) {
            return false;
        }
        return GitHubAttachmentHandler.handlerName.equals(policy.getSpec().getTemplateName());
    }

    /**
     * 控制输出 debug 日志
     *
     * @param msg
     * @param object
     */
    void debug(String msg, Object object) {
        if (log.isInfoEnabled()) {
            if (object == null) {
                log.debug(msg);
                return;
            }
            log.info("{}:{}", msg, JSONUtil.toJsonStr(object));
        }
    }

    // TODO 要反序列操作，但不知道为什么这么做
    private Policy convertTo(Extension extension) {
        if (extension instanceof Policy) {
            return (Policy) extension;
        }
        return Unstructured.OBJECT_MAPPER.convertValue(extension, Policy.class);
    }

    /**
     * 1、确认 gitHubPolicyWatcher 观察者是否启用
     * 2、确认 extension 的元数据中 deletionTimestamp 字段没有值代表未删除
     * 3、
     *
     * @param extension
     * @return
     */
    private boolean checkExtension(Extension extension) {
        return !this.isDisposed()
            && extension.getMetadata().getDeletionTimestamp() == null
            && isPolicy(extension);
    }

    // TODO 感觉这个对比，是对象对比，可能存在问题，还是 record 申明的对象对比会用字段比较
    private boolean isPolicy(Extension extension) {
        return GroupVersionKind.fromExtension(Policy.class).equals(extension.groupVersionKind());
    }


    // 查询新增的github附件策略指定目录下，所有文件信息，并同步到 halo 这边的附件管理
    private void handleAttachments(GithubOssProperties githubOssProperties, Policy policy) {
        GitHubHttpUtil.getFileShaList(githubOssProperties, githubOssProperties.getPath())
            .map(
                configMap -> JSONUtil.parseObj(configMap).getJSONArray("tree").toList(String.class))
            .flatMapIterable(dataTree -> dataTree)
            .map(JSONUtil::parseObj)
            .filter(f -> "blob".equals(f.getStr("type")))
            .flatMap(f -> {
                String fileName = f.getStr("path");
                String fileType = FileNameUtils.fileType(fileName);
                if ("file".equals(fileType)) {
                    return Mono.empty();
                }
                Long size = f.getLong("size");
                Attachment attachment =
                    buildAttachment(githubOssProperties, size, fileName, fileType, policy);
                return extensionClient.create(attachment);//增加重试次数
            })
            .subscribe();
    }

    /**
     * 构建附件信息
     *
     * @param properties github 配置
     * @param size 文件大小
     * @param fileName 文件名
     * @param fileType 文件类型
     * @param policy 哪个附件策略
     * @return 附件信息
     */
    Attachment buildAttachment(GithubOssProperties properties, Long size, String fileName,
        String fileType, Policy policy) {
        String filePath = properties.getObjectName(fileName);
        var externalLink = GitHubAttachmentHandler.jsdelivrConvert(properties, filePath);

        var metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        metadata.setAnnotations(
            Map.of(GitHubAttachmentHandler.OBJECT_KEY, filePath, Constant.EXTERNAL_LINK_ANNO_KEY,
                UriUtils.encodePath(externalLink, StandardCharsets.UTF_8)));

        var spec = new Attachment.AttachmentSpec();
        spec.setSize(size);
        spec.setDisplayName(fileName);
        spec.setMediaType(fileType);

        var attachment = new Attachment();
        attachment.setMetadata(metadata);
        attachment.setSpec(spec);

        spec.setOwnerName(properties.getCreatName());
        spec.setPolicyName(policy.getMetadata().getName());

        return attachment;
    }
}
