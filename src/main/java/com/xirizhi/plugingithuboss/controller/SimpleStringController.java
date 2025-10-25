package com.xirizhi.plugingithuboss.controller;


import java.io.File;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xirizhi.plugingithuboss.service.GitHubService;

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

    /**
     * 查询这个附件存储策略，在halo上上传的文件列表
     * github api接口用的是：https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#list-repository-contents
     * @return
     */
    @GetMapping("/attachments/haloList")
    public Mono<java.util.Map<String, String>> listGitHubHaloAttachments(@RequestParam("policyName") String policyName) {
        ListOptions listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(QueryFactory.equal("spec.policyName", policyName)));
        return client.listAll(Attachment.class, listOptions, Sort.unsorted())
                .filter(attachment -> {
                    var annotations = attachment.getMetadata().getAnnotations();
                    return annotations != null
                            && annotations.get("sha") != null
                            && annotations.get("path") != null;
                })
                .collectMap(
                        att -> att.getMetadata().getAnnotations().get("sha"),
                        att -> att.getMetadata().getAnnotations().get("path")
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
}