package com.xirizhi.plugingithuboss.controller;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.xirizhi.plugingithuboss.service.GitHubService;
import com.xirizhi.plugingithuboss.handler.GithubAttachmentHandler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.QueryFactory;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.plugin.ApiVersion; // 确保已导入 ApiVersion 注解

import com.xirizhi.plugingithuboss.extension.GithubOssPolicySettings;
import run.halo.app.infra.utils.JsonUtils;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 后端控制器：提供附件查询、关联、关联解除接口。
 */
@Slf4j
@ApiVersion("githubOs.halo.run/v1alpha1") // 声明API版本（格式：{插件域名}/{版本}）
@RestController
@RequiredArgsConstructor
public class SimpleStringController {

    private final ReactiveExtensionClient client;
    private final GitHubService gitHubService;
    private final GithubAttachmentHandler githubAttachmentHandler;

    // 查询 github 存储策略的根目录
    @GetMapping("/Attachments/rootPath")
    public Mono<String> getGitHubRootPath(@RequestParam("policyName") String policyName) {
        return client.fetch(Policy.class, policyName)
                .map(policy -> {
                    String configMapName = policy.getSpec() != null ? policy.getSpec().getConfigMapName() : null;
                    if (configMapName == null) {
                        throw new IllegalArgumentException("策略 " + policyName + " 未配置 ConfigMap");
                    }
                    return configMapName;
                })
                .flatMap(configMapName -> client.fetch(ConfigMap.class, configMapName))
                .map(configMap -> {
                    GithubOssPolicySettings settings = JsonUtils.jsonToObject(configMap.getData().get("default"), GithubOssPolicySettings.class);
                    String path = settings.getPath();
                    if (path == null || path.isEmpty() || path.charAt(0) != '/') {
                        path = '/' + (path == null ? "" : path);
                    }
                    return path;
                })
                .doOnError(error -> log.error("查询策略根目录失败 policyName={}", policyName, error))
                .onErrorMap(e -> new RuntimeException("查询失败: " + e.getMessage(), e));
    }


    /**
     * 查询这个附件存储策略，在halo上上传的文件列表
     * github api接口用的是：https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#list-repository-contents
     * @return
     */
    @GetMapping("/attachments/haloList")
    public Mono<java.util.Map<String, Boolean>> listGitHubHaloAttachments(@RequestParam("policyName") String policyName) {
        ListOptions listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(QueryFactory.equal("spec.policyName", policyName)));
        return client.listAll(Attachment.class, listOptions, Sort.unsorted())
                .filter(attachment -> {
                    log.info("attachment={}", JsonUtils.objectToJson(attachment));
                    var annotations = attachment.getMetadata().getAnnotations();
                    return annotations != null
                            && annotations.get("sha") != null
                            && annotations.get("path") != null;
                })
                .collectMap(
                        att -> att.getMetadata().getAnnotations().get("sha")+att.getMetadata().getAnnotations().get("path"),
                        att -> true
                )
                .doOnError(error -> log.error("查询策略附件列表失败 policyName={}", policyName, error))
                .onErrorMap(e -> new RuntimeException("查询失败: " + e.getMessage(), e));
    }

    /**
     * 查询 GitHub 目录内容并返回给前端。
     * 默认使用策略 ConfigMap.data["default"] 中的 owner/repoName/token/branch/path；
     * 可通过 query 参数 path 覆盖默认路径。
     */
    @GetMapping("/Attachments/list")
    public Mono<String> listGitHubAttachments(@RequestParam("policyName") String policyName,@RequestParam("path") String path) {
        return client.fetch(Policy.class, policyName)
                .flatMap(policy -> {
                    String configMapName = policy.getSpec() != null ? policy.getSpec().getConfigMapName() : null;
                    if (configMapName == null || configMapName.isBlank()) {
                        return Mono.error(new RuntimeException("该 Policy 未配置 configMapName"));
                    }
                    return client.fetch(ConfigMap.class, configMapName);
                })
                .flatMap(config -> {
                    String configJson = config.getData() != null ? config.getData().get("default") : null;
                    if (configJson == null || configJson.isBlank()) {
                        return Mono.error(new IllegalArgumentException("配置数据为空"));
                    }
                    GithubOssPolicySettings settings = JsonUtils.jsonToObject(configJson, GithubOssPolicySettings.class);
                    if (path != null && !path.isBlank()) {
                        settings.setPath(path);
                    }
                    return Mono.fromCallable(() -> gitHubService.listDirectoryContents(settings, settings.getPath()));
                })
                .doOnError(error -> log.error("查询目录内容失败", error))
                .onErrorMap(e -> new RuntimeException("查询失败: " + e.getMessage(), e));
    }

    // github 文件关联 halo 上的附件
    private record linkReqObject(String policyName, String path, String sha, Long size) {}
    private record linkRespObject(Integer saveCount, Integer failCount, String firstErrorMsg) {}
    @PostMapping("/Attachments/link")
    public Mono<linkRespObject> linkGitHubAttachment(@RequestBody List<linkReqObject> reqList) {
        if (reqList == null || reqList.isEmpty()) {
            return Mono.error(new IllegalArgumentException("请求体不能为空"));
        }

        java.util.concurrent.atomic.AtomicInteger saveCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> firstErrorMsg = new java.util.concurrent.atomic.AtomicReference<>();

        return reactor.core.publisher.Flux.fromIterable(reqList)
                .concatMap(req ->
                        client.fetch(Policy.class, req.policyName())
                                .flatMap(policy -> {
                                    String configMapName = policy.getSpec() != null ? policy.getSpec().getConfigMapName() : null;
                                    if (configMapName == null || configMapName.isBlank()) {
                                        return Mono.error(new RuntimeException("该 Policy 未配置 configMapName"));
                                    }
                                    return client.fetch(ConfigMap.class, configMapName)
                                            .flatMap(config -> {
                                                Attachment attachment = githubAttachmentHandler.buildAttachment(req.path(), req.sha(), req.size(), policy);
                                                return client.create(attachment);
                                            });
                                })
                                .doOnError(err -> {
                                    failCount.incrementAndGet();
                                    firstErrorMsg.compareAndSet(null, err.getMessage());
                                    log.error("关联附件失败 policyName={}, path={}, sha={}", req.policyName(), req.path(), req.sha(), err);
                                })
                                .onErrorResume(err -> Mono.empty())
                                .doOnSuccess(att -> saveCount.incrementAndGet())
                )
                .then(Mono.fromSupplier(() -> new linkRespObject(saveCount.get(), failCount.get(), firstErrorMsg.get())));
    }
}
