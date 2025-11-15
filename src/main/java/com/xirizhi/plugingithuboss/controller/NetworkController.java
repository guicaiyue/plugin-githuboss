package com.xirizhi.plugingithuboss.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xirizhi.plugingithuboss.config.Constant;
import com.xirizhi.plugingithuboss.extension.GitHubThemeSettings;
import com.xirizhi.plugingithuboss.extension.theme.NetworkConfig;
import com.xirizhi.plugingithuboss.service.GitHubService;
import com.xirizhi.plugingithuboss.service.GitHubService.NetworkTestItem;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.plugin.ApiVersion;

@ApiVersion("githubOs.halo.run/v1alpha1")
@RequestMapping("/network")
@RestController
@RequiredArgsConstructor
public class NetworkController {
    private final GitHubService gitHubService;
    private final ReactiveExtensionClient client;

        // 读取代理配置
    @GetMapping("/proxy")
    public Mono<NetworkConfig> getProxy() {
        return gitHubService.getProxyConfig();
    }

    // 保存代理配置
    @PostMapping("/proxy")
    public Mono<NetworkConfig> saveProxy(@RequestBody NetworkConfig req) {
        if (req == null) {
            return Mono.error(new IllegalArgumentException("请求体不能为空"));
        }
        if (req.getTimeoutMs() == null || req.getTimeoutMs() <= 0) {
            req.setTimeoutMs(10000);
        }
        return client.fetch(ConfigMap.class, Constant.PLUGIN_GITHUBOSS_CONFIGMAP)
                .flatMap(cm -> {
                    Map<String, String> data = cm.getData();
                    if (data == null) data = new HashMap<>();
                    data.put(GitHubThemeSettings.GitHub_NETWORK, JsonUtils.objectToJson(req));
                    cm.setData(data);
                    return client.update(cm).then(Mono.just(req));
                });
    }

    // 连通性测试：对 github.com 与 api.github.com 进行 DNS 与 HTTP 探测
    @GetMapping("/test")
    public Mono<java.util.List<NetworkTestItem>> networkTest() {
        Mono<NetworkTestItem> m1 = gitHubService.networkTest("github.com");
        Mono<NetworkTestItem> m2 = gitHubService.networkTest("api.github.com");
        Mono<NetworkTestItem> m3 = gitHubService.networkTest("raw.githubusercontent.com");
        return Mono.zip(m1, m2, m3)
                .map(t -> java.util.List.of(t.getT1(), t.getT2(), t.getT3()));
    }
}