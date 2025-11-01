package com.xirizhi.plugingithuboss.service;

import com.xirizhi.plugingithuboss.config.Constant;
import com.xirizhi.plugingithuboss.extension.GitHubThemeSettings;
import com.xirizhi.plugingithuboss.extension.GithubOssPolicySettings;
import com.xirizhi.plugingithuboss.extension.theme.GitHubBasic;

import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * GitHub API 封装服务：负责与 GitHub Contents API 交互。
 * 注意：PAT 需至少具备 repo 的 Contents 权限。
 */
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
    public String uploadContent(GithubOssPolicySettings settings, String path, byte[] data, String message) throws Exception {
        var lock = lockFor(settings);
        boolean locked = false;
        try {
            lock.lockInterruptibly();
            locked = true;

            // 构造 GitHub Contents API URL
            String url = String.format("https://api.github.com/repos/%s/%s/contents/%s", settings.getOwner(), settings.getRepoName(), path);
            // 组装请求体（JSON），包含提交信息、分支（可选）与 Base64 编码内容
            String body = "{" +
                    "\"message\":\"" + escapeJson(message) + "\"," +
                    (settings.getBranch() != null ? "\"branch\":\"" + escapeJson(settings.getBranch()) + "\"," : "") +
                    "\"content\":\"" + Base64.getEncoder().encodeToString(data) + "\"" +
                    "}";
            // 构造 HTTP 请求（PUT 表示创建/更新内容）
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + settings.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            // 执行请求并获取响应
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                // 简单提取返回 JSON 中的 sha 字段（轻量实现，避免引入额外 JSON 依赖）
                String sha = extractByKey(resp.body(), "\"sha\":\"", "\"");
                return sha;
            }
            // 非 2xx 状态码视为失败，抛出异常以便上层捕获与审计记录
            throw new RuntimeException("GitHub 上传失败，状态码=" + resp.statusCode() + ", 响应=" + resp.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("GitHub 上传过程被中断", ie);
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    /**
     * 删除 GitHub 仓库中的文件，需要提供 sha 以保证幂等与正确性。
     * @param spec 仓库配置 Spec
     * @param path 目标路径
     * @param sha 现有文件的内容 SHA（来自上传或查询）
     * @param message 提交信息
     */
    public void deleteContent(GithubOssPolicySettings settings, String path, String sha, String message) throws Exception {
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + settings.getToken())
                    .header("Accept", "application/vnd.github+json")
                    .header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return;
            }
            if (resp.statusCode() == 404) {
                return;
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
    public String fetchContentSha(GithubOssPolicySettings settings, String path) throws Exception {
        // 构造请求 URL，例如：https://api.github.com/repos/{owner}/{repo}/contents/{path}?ref={branch}
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                settings.getOwner(), settings.getRepoName(), path, settings.getBranch() == null ? "main" : settings.getBranch());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + settings.getToken())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            // 解析 JSON，提取 sha 字段（轻量级方式，避免引入额外依赖）
            String body = response.body();
            // 简单解析：查找 "sha":"..." 模式（注意：此处为简单实现，生产中建议使用 JSON 库）
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
    }

    /**
     * 根据仓库与路径查询目录内容，返回 GitHub API 的原始 JSON 字符串。
     * 若 path 为空则查询仓库根目录。
     */
    public String listDirectoryContents(GithubOssPolicySettings settings, String path) throws Exception {
        String p = (path == null || path.isBlank()) ? "" : path;
        String branch = settings.getBranch() == null ? "main" : settings.getBranch();
        String url = String.format("https://api.github.com/repos/%s/%s/contents/%s?ref=%s",
                settings.getOwner(), settings.getRepoName(), p, branch);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + settings.getToken())
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }
        throw new IllegalStateException("目录内容查询失败，状态码：" + response.statusCode() + ", 响应：" + response.body());
    }

    public boolean checkConnectivity() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://github.com"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }
}