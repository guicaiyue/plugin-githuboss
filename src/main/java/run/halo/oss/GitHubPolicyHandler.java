package run.halo.oss;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Constant;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class GitHubPolicyHandler {
    private final GitHubPolicyWatcher gitHubPolicyWatcher = new GitHubPolicyWatcher();
    private final ReactiveExtensionClient extensionClient;
    private final GitHubAttachmentHandler gitHubAttachmentHandler;

    public GitHubPolicyHandler(ReactiveExtensionClient extensionClient, GitHubAttachmentHandler gitHubAttachmentHandler) {
        this.extensionClient = extensionClient;
        this.gitHubAttachmentHandler = gitHubAttachmentHandler;
        extensionClient.watch(gitHubPolicyWatcher);
    }

    public static void initWatch(ReactiveExtensionClient extensionClient, GitHubAttachmentHandler gitHubAttachmentHandler) {
        new GitHubPolicyHandler(extensionClient, gitHubAttachmentHandler);
    }

    public class GitHubPolicyWatcher implements Watcher {
        private Runnable disposeHook;
        private boolean disposed = false;

        @Override
        public void onAdd(Extension extension) {
            if (!checkExtension(extension)) {
                return;
            }
            Policy policy = convertTo(extension);
            if (shouldHandle(policy)) {
                var configMapName = policy.getSpec().getConfigMapName();
                ReactiveSecurityContextHolder.getContext()
                        .map(ctx -> {
                            var name = ctx.getAuthentication().getName();
                            debug("获取文件名", name);
                            return name;
                        }).subscribe();
                extensionClient.get(ConfigMap.class, configMapName)
                        .flatMap(configMap -> Mono.just(JSONUtil.toBean(configMap.getData().get("default"), GithubOssProperties.class)))
                        .doOnNext(data -> {
                            debug("创建附件", data);
                            // 将GithubOssProperties对象传递给方法
                            handleAttachments(data, policy);
                        })
                        .subscribe();
            }
        }

        private void handleAttachments(GithubOssProperties githubOssProperties, Policy policy) {
            gitHubAttachmentHandler.getFileShaList(githubOssProperties, githubOssProperties.getPath())
                    .map(configMap -> JSONUtil.parseObj(configMap).getJSONArray("tree").toList(String.class))
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
                        Attachment attachment = buildAttachment(githubOssProperties, size, fileName, fileType, policy);
                        return extensionClient.create(attachment);//增加重试次数
                    })
                    .subscribe();
        }

        @Override
        public void registerDisposeHook(Runnable dispose) {
            this.disposeHook = dispose;
        }

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
    }

    Attachment buildAttachment(GithubOssProperties properties, Long size, String fileName, String fileType, Policy policy) {
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

    boolean shouldHandle(Policy policy) {
        if (policy == null || policy.getSpec() == null ||
                policy.getSpec().getTemplateName() == null) {
            return false;
        }
        return GitHubAttachmentHandler.handlerName.equals(policy.getSpec().getTemplateName());
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

    private Policy convertTo(Extension extension) {
        if (extension instanceof Policy) {
            return (Policy) extension;
        }
        return Unstructured.OBJECT_MAPPER.convertValue(extension, Policy.class);
    }

    private boolean checkExtension(Extension extension) {
        return !gitHubPolicyWatcher.isDisposed()
                && extension.getMetadata().getDeletionTimestamp() == null
                && isPolicy(extension);
    }

    private boolean isPolicy(Extension extension) {
        return GroupVersionKind.fromExtension(Policy.class).equals(extension.groupVersionKind());
    }
}
