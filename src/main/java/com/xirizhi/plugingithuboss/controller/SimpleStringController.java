package com.xirizhi.plugingithuboss.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xirizhi.plugingithuboss.service.GitHubService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
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
     * 根据传入策略参数，找到对应gihtub附件存储策略信息，然后通过github api 查询这个策略指定的路径下的所有附件信息返回给前端
     * github api接口用的是：https://docs.github.com/en/rest/repos/contents?apiVersion=2022-11-28#list-repository-contents
     * @return
     */
    @GetMapping("/github/oss/contents")
    public Mono<ConfigMap> getGitHubOssContents(
        @RequestParam("repoName") String repoName
    ) {
        log.info("name: {}", repoName);
        if (repoName == null || repoName.isBlank()) {
            return Mono.error(new IllegalArgumentException("name 不能为空"));
        }
        return client.fetch(Policy.class, "attachment-policy-lrn2y0ty")
                .flatMap(policy -> {
                    String configMapName = policy.getSpec() != null ? policy.getSpec().getConfigMapName() : null;
                    if (configMapName == null || configMapName.isBlank()) {
                        return Mono.error(new RuntimeException("该 Policy 未配置 configMapName"));
                    }
                    return client.fetch(ConfigMap.class, configMapName);
                })
                .doOnNext(config -> log.info("config: {}", config))
                .onErrorResume(e -> Mono.error(new RuntimeException("查询失败: " + e.getMessage(), e)));
    }


    /**
     * 简单的GET接口，返回固定字符串
     * 完整访问路径：/apis/myplugin.example.com/v1alpha1/hello
     */
    @GetMapping("/hello")
    public Mono<String> getHello() {
        // 使用Mono包装字符串，适配Halo的响应式架构
        return Mono.just("Hello from My Halo Plugin!");
    }

    /**
     * 带参数的GET接口，返回拼接后的字符串
     * 完整访问路径：0000000000000
     */
    @GetMapping("/greet")
    public Mono<String> getGreet(String name) {
        // 处理参数（若name为null则默认"Guest"）
        String actualName = name == null ? "Guest" : name;
        return Mono.just("Hello, " + actualName + "! This is my plugin API.");
    }

    /**
     * 查询 GitHub 目录内容并返回给前端。
     * 默认使用策略 ConfigMap.data["default"] 中的 owner/repoName/token/branch/path；
     * 可通过 query 参数 path 覆盖默认路径。
     */
    @GetMapping("/github/oss/list")
    public Mono<String> listGitHubDirectoryContents(@RequestParam("policyName") String policyName) {
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
                    return Mono.fromCallable(() -> gitHubService.listDirectoryContents(settings, settings.getPath()));
                })
                .doOnError(error -> log.error("查询目录内容失败", error))
                .onErrorMap(e -> new RuntimeException("查询失败: " + e.getMessage(), e));
    }
}