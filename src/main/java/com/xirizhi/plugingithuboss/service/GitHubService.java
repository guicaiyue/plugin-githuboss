package com.xirizhi.plugingithuboss.service;

import com.xirizhi.plugingithuboss.config.Constant;
import com.xirizhi.plugingithuboss.extension.GitHubThemeSettings;
import com.xirizhi.plugingithuboss.extension.GithubOssPolicySettings;
import com.xirizhi.plugingithuboss.extension.theme.GitHubBasic;
import com.xirizhi.plugingithuboss.extension.theme.NetworkConfig;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * GitHub API 封装服务：负责与 GitHub Contents API 交互。
 * 注意：PAT 需至少具备 repo 的 Contents 权限。
 */
@Slf4j
@Service
public class GitHubService {


    private final ReactiveExtensionClient client;

    public GitHubService(ReactiveExtensionClient client) {
        this.client = client;
    }

    // 新增：按仓库+分支维度的公平锁，串行化提交避免 409 冲突
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> REPO_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.locks.ReentrantLock lockFor(GithubOssPolicySettings settings) {
        String branch = settings.getBranch() == null ? "main" : settings.getBranch();
        String key = settings.getOwner() + "/" + settings.getRepoName() + "@" + branch;
        return REPO_LOCKS.computeIfAbsent(key, k -> new java.util.concurrent.locks.ReentrantLock(true));
    }

    /**
     * 上传文件到 GitHub 仓库，返回内容 sha。
     * @param spec 仓库配置 Spec
     * @param path 目标路径（相对仓库根），例如 attachments/20250101/1700000000000.png
     * @param data 文件二进制数据
     * @param message 提交信息（commit message）
     * @return 上传后返回的内容 SHA
     */
    public Mono<String> uploadContent(GithubOssPolicySettings settings, String path, byte[] data, String message) {
        return getProxyConfig().flatMap(cfg -> Mono.fromCallable(() -> {
            var lock = lockFor(settings);
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;

                String url = String.format("https://api.github.com/repos/%s/%s/contents/%s", settings.getOwner(), settings.getRepoName(), path);
                String body = "{" +
                        "\"message\":\"" + escapeJson(message) + "\"," +
                        (settings.getBranch() != null ? "\"branch\":\"" + escapeJson(settings.getBranch()) + "\"," : "") +
                        "\"content\":\"" + Base64.getEncoder().encodeToString(data) + "\"" +
                        "}";
                HttpClient client = buildBaseGitHubHttpClient(cfg);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + settings.getToken())
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofMillis(cfg.getTimeoutMs()))
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    String sha = extractByKey(resp.body(), "\"sha\":\"", "\"");
                    return sha;
                }
                throw new RuntimeException("GitHub 上传失败，状态码=" + resp.statusCode() + ", 响应=" + resp.body());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 删除 GitHub 仓库中的文件，需要提供 sha 以保证幂等与正确性。
     * @param spec 仓库配置 Spec
     * @param path 目标路径
     * @param sha 现有文件的内容 SHA（来自上传或查询）
     * @param message 提交信息
     */
    public Mono<Void> deleteContent(GithubOssPolicySettings settings, String path, String sha, String message) {
        return getProxyConfig().flatMap(cfg -> reactor.core.publisher.Mono.fromCallable(() -> {
            var lock = lockFor(settings);
            boolean locked = false;
            try {
                lock.lockInterruptibly();
                locked = true;

                String url = String.format("https://api.github.com/repos/%s/%s/contents/%s", settings.getOwner(), settings.getRepoName(), path);
                String body = "{" +
                        "\"message\":\"" + escapeJson(message) + "\"," +
                        (settings.getBranch() != null ? "\"branch\":\"" + escapeJson(settings.getBranch()) + "\"," : "") +
                        "\"sha\":\"" + escapeJson(sha) + "\"" +
                        "}";
                HttpClient client = buildBaseGitHubHttpClient(cfg);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + settings.getToken())
                        .header("Accept", "application/vnd.github+json")
                        .header("Content-Type", "application/json")
                        .timeout(java.time.Duration.ofMillis(cfg.getTimeoutMs()))
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return null;
                }
                if (resp.statusCode() == 404) {
                    return null;
                }
                throw new RuntimeException("GitHub 删除失败，状态码=" + resp.statusCode() + ", 响应=" + resp.body());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("GitHub 删除过程被中断", ie);
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).then());
    }

    /**
     * 生成 CDN URL，并支持降级域名列表（取第一个作为主域名）。
     * jsDelivr GitHub 路由：/gh/{owner}/{repo}@{branch}/{path}
     * @param spec 仓库配置
     * @param path 相对路径
     * @return 可直接访问的 CDN URL
     */
    public Mono<String> buildCdnUrl(GithubOssPolicySettings settings, String path) {
        return client.fetch(ConfigMap.class, Constant.PLUGIN_GITHUBOSS_CONFIGMAP)
                    .map(ConfigMap::getData)
                    .map(data -> {
                        String basicJson = data.get(GitHubThemeSettings.GitHub_BASIC);
                        return JsonUtils.jsonToObject(basicJson, GitHubBasic.class).getJsdelivr();
                    })
                    .defaultIfEmpty("gcore.jsdelivr.net")
                    .map(jsdelivr -> {
                        String branch = settings.getBranch() == null ? "main" : settings.getBranch();
                        return String.format("https://%s/gh/%s/%s@%s/%s", jsdelivr, settings.getOwner(), settings.getRepoName(), branch, path); 
                    });
    }

    /**
     * 读取代理超时配置
     */
    public Mono<NetworkConfig> getProxyConfig() {
        return client.fetch(ConfigMap.class, Constant.PLUGIN_GITHUBOSS_CONFIGMAP)
                .map(cm -> {
                    String json = cm.getData().get(GitHubThemeSettings.GitHub_NETWORK);
                    if (json != null && !json.isBlank()) {
                        return JsonUtils.jsonToObject(json, NetworkConfig.class);
                    }
                    NetworkConfig cfg = new NetworkConfig();
                    cfg.setEnabled(Boolean.FALSE);
                    cfg.setTimeoutMs(10000);
                    cfg.setProxyPath("");
                    return cfg;
                })
                .onErrorResume(e -> {
                    NetworkConfig cfg = new NetworkConfig();
                    cfg.setEnabled(Boolean.FALSE);
                    cfg.setTimeoutMs(10000);
                    cfg.setProxyPath("");
                    return Mono.just(cfg);
                });
    }

    /**
     * JSON 简易转义，避免字符串中包含特殊字符导致请求体无效。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 通过简单的字符串查找提取 JSON 中 key 对应的值（仅用于轻量响应解析）。
     */
    private static String extractByKey(String src, String keyStart, String endToken) {
        int i = src.indexOf(keyStart);
        if (i < 0) return null;
        int j = src.indexOf(endToken, i + keyStart.length());
        if (j < 0) return null;
        return src.substring(i + keyStart.length(), j);
    }

    /**
     * 根据仓库路径获取文件的 SHA，用于删除操作。
     * 注意：GitHub Contents API 会返回 JSON，其中包含 sha 字段。
     */
    public Mono<String> fetchContentSha(GithubOssPolicySettings settings, String path) {
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                settings.getOwner(), settings.getRepoName(), path, settings.getBranch() == null ? "main" : settings.getBranch());
        return getProxyConfig()
            .flatMap(cfg -> Mono.fromCallable(() -> {
                HttpClient client = buildBaseGitHubHttpClient(cfg);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + settings.getToken())
                        .header("Accept", "application/vnd.github+json")
                        .timeout(java.time.Duration.ofMillis(cfg.getTimeoutMs()))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    String body = response.body();
                    int idx = body.indexOf("\"sha\":\"");
                    if (idx >= 0) {
                        int start = idx + "\"sha\":\"".length();
                        int end = body.indexOf('"', start);
                        if (end > start) {
                            return body.substring(start, end);
                        }
                    }
                    throw new IllegalStateException("未从 GitHub API 响应中解析到 sha 字段");
                }
                throw new IllegalStateException("获取内容 SHA 失败，状态码：" + response.statusCode() + ", 响应：" + response.body());
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
    }

    /**
     * 根据仓库与路径查询目录内容，返回 GitHub API 的原始 JSON 字符串。
     * 若 path 为空则查询仓库根目录。
     */
    public Mono<String> listDirectoryContents(GithubOssPolicySettings settings, String path) {
        String p = (path == null || path.isBlank()) ? "" : path;
        String branch = settings.getBranch() == null ? "main" : settings.getBranch();
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                settings.getOwner(), settings.getRepoName(), p, branch);
        return getProxyConfig()
            .flatMap(cfg -> Mono.fromCallable(() -> {
                HttpClient client = buildBaseGitHubHttpClient(cfg);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + settings.getToken())
                        .header("Accept", "application/vnd.github+json")
                        .timeout(java.time.Duration.ofMillis(cfg.getTimeoutMs()))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return response.body();
                }
                if (response.statusCode() == 404) {
                    throw new IllegalStateException("指定仓库"+p+"目录不存在，github响应：" + response.body());
                }
                throw new IllegalStateException("目录内容查询失败，状态码：" + response.statusCode() + ", 响应：" + response.body());
            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
    }

    public Mono<Boolean> checkConnectivity() {
        return networkTest("api.github.com")
            .map(com.xirizhi.plugingithuboss.service.GitHubService.NetworkTestItem::isSuccess)
            .onErrorReturn(false);
    }
    /**
     * 构建用于访问 GitHub API 的基础 HttpClient（应用代理与超时配置）
     *
     * 说明：
     * - 从插件配置中读取网络设置（代理开关、代理地址、超时时间）
     * - 代理地址支持 "http://host:port" 或 "socks://host:port" 两种格式
     * - 若配置缺失或异常，则使用默认超时 10000ms 且不启用代理
     */
    public HttpClient buildBaseGitHubHttpClient(NetworkConfig cfg) {
        HttpClient.Builder builder = HttpClient.newBuilder();
        if (Boolean.TRUE.equals(cfg.getEnabled())) {
            ProxySelector selector = buildProxySelector(cfg);
            if (selector != null) builder.proxy(selector);
        }
        return builder.build();
    }

    // 解析代理配置，仅支持 HTTP 代理，返回可应用于 HttpClient 的 ProxySelector
    private static ProxySelector buildProxySelector(NetworkConfig cfg) {
        String[] path = cfg.getProxyPath().split(":");
        log.info("构建 HTTP 代理 {}:{}", path[0].trim(), path[1].trim());
        return ProxySelector.of(new InetSocketAddress(path[0], Integer.parseInt(path[1])));
    }

    @Data
    public static class NetworkTestItem {
        private String host;
        private List<String> ips;
        private String dnsError;
        private int httpStatus;
        private long httpLatencyMs;
        private boolean success;
        private String error;
    }

    /**
     * 测试 GitHub 服务的连通性（DNS 查询与 HTTP HEAD 请求）
     *
     * 说明：
     * - 对 github.com 与 api.github.com 进行并行查询
     * - 记录 DNS 查询结果（IP 列表或错误信息）与 HTTP 响应状态码（2xx 为成功）
     * - 超时时间默认 10000ms，可通过插件配置自定义
     *
     * @return 包含每个主机的测试结果（IP 列表、DNS 错误、HTTP 状态码、延迟、是否成功、错误信息）
     */
    public Mono<NetworkTestItem> networkTest(String host) {
        return getProxyConfig().flatMap(cfg -> Mono.fromCallable(() -> {
            NetworkTestItem item = new NetworkTestItem();
            item.setHost(host);
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                List<String> ips = Arrays.stream(addrs).map(a -> a.getHostAddress()).distinct().toList();
                item.setIps(ips);
            } catch (Exception e) {
                item.setIps(List.of());
                item.setDnsError(e.getMessage());
            }
            long start = System.nanoTime();
            int status = -1;
            String error = null;
            try {
                HttpClient http = buildBaseGitHubHttpClient(cfg);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://" + host + "/"))
                        .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                long costMs = (System.nanoTime() - start) / 1_000_000;
                log.info("测试 {} 连通性，HTTP 状态码 {}，耗时 {}ms resp {}", host, resp.statusCode(), costMs, resp.body());
                status = resp.statusCode();
            } catch (Exception ex) {
                error = ex.getMessage();
                log.error("测试 {} 连通性失败，耗时 {}ms，错误：{}", host, (System.nanoTime() - start) / 1_000_000, error,ex);
            }
            long costMs = (System.nanoTime() - start) / 1_000_000;
            item.setHttpStatus(status);
            item.setHttpLatencyMs(costMs);
            item.setSuccess(status >= 200 && status < 400);
            if (error != null) item.setError(error);
            return item;
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}