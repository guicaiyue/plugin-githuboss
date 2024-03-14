package run.halo.oss.util;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import run.halo.oss.config.GithubOssProperties;
import run.halo.oss.enums.GithubUrlEnum;

/**
 * github api 调用工具类
 */
@Slf4j
public class GitHubHttpUtil {
    private static WebClient webClient;

    static {
        initOrUpdate(3); // 默认最大连接数是 3
    }

    public static void setMaxConnections(int updateMax) {

    }

    // 删除文件
    public static Mono<String> contentFileGet(GithubOssProperties properties, String path,
        JSONObject jsonObject) {
        return contentFile(HttpMethod.GET, properties, path, jsonObject);
    }

    // 删除文件
    public static Mono<String> contentFileDelete(GithubOssProperties properties, String path,
        JSONObject jsonObject) {
        return contentFile(HttpMethod.DELETE, properties, path, jsonObject);
    }

    // 上传文件
    public static Mono<String> contentFilePut(GithubOssProperties properties, String path,
        JSONObject jsonObject) {
        return contentFile(HttpMethod.PUT, properties, path, jsonObject);
    }

    /**
     * github 文件操作
     *
     * @param method 操作方式：PUT 上传
     * @param properties URL参数
     * @param path 文件路径
     * @param jsonObject 参数体
     * @return 返回结果
     */
    public static Mono<String> contentFile(HttpMethod method, GithubOssProperties properties,
        String path, JSONObject jsonObject) {
        return webClient.method(method)
            .uri(convertUrl(GithubUrlEnum.API_CONTENTS, properties, path))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
            .header("Accept", "application/vnd.github+json")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .bodyValue(jsonObject.toString())
            .exchangeToMono(clientResponse -> {
                Mono<String> dataMap = clientResponse.bodyToMono(String.class).map(m -> {
                    log.debug(String.format("%s 文件内容 %s 调用状态码：%s", method.name(), path,
                        clientResponse.statusCode().value()), m);
                    return m;
                });
                if (clientResponse.statusCode().is2xxSuccessful()) {
                    return dataMap;
                } else {
                    return dataMap.flatMap(
                        m -> Mono.error(new RuntimeException("Failed to upload file")));
                }
            })
            .onErrorContinue((e, i) -> {
                e.printStackTrace();
                log.info(JSONUtil.toJsonStr(i));
                // Log the error here.
            });
    }

    // 获取 github 目录下所有文件列表
    public static Mono<String> getFileShaList(GithubOssProperties properties, String filePath) {
        // Need perform further operations on the result
        return webClient.method(HttpMethod.GET)
            .uri(convertUrl(GithubUrlEnum.API_TREE, properties, filePath))
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
            .header("Accept", "application/vnd.github+json")
            .exchangeToMono(clientResponse -> {
                Mono<String> dataMap = clientResponse.bodyToMono(String.class).map(m -> {
                    log.debug("查询仓库目录下文件列表:{}", m);
                    return m;
                });
                if (clientResponse.statusCode().is2xxSuccessful()) {
                    return dataMap;
                } else if (clientResponse.statusCode().is4xxClientError()) {
                    return dataMap.flatMap(f -> Mono.just("{}"));
                } else {
                    return dataMap.flatMap(
                        f -> Mono.error(new RuntimeException("Failed to check file existence")));
                }
            });
    }

    /**
     * 获取github url 路径
     *
     * @param githubUrlEnum github api 模板
     * @param properties 路径参数
     * @param path 路径最后地址
     * @return 完整url路径
     */
    public static String convertUrl(GithubUrlEnum githubUrlEnum, GithubOssProperties properties,
        String path) {
        path = Optional.ofNullable(path).orElse("");
        path = path.startsWith("/") ? path.substring(1) : path;
        String url = "";
        switch (githubUrlEnum) {
            case API_TREE -> {
                url = GithubUrlEnum.API_TREE.getUrl().replace("{owner}", properties.getOwner())
                    .replace("{repo}", properties.getRepo())
                    .replace("{branch}", properties.getBranch());
                url = url + ":" + path;
            }
            case API_CONTENTS -> {
                url = GithubUrlEnum.API_CONTENTS.getUrl().replace("{owner}", properties.getOwner())
                    .replace("{repo}", properties.getRepo()).replace("{path}", path);
            }
        }
        log.debug("当前访问的ulr: {}", url);
        return url;
    }

    /**
     * 初始化或更新 webClient
     *
     * @param maxConnections 最大连接数
     */
    public static void initOrUpdate(int maxConnections) {
        // 最大连接数是
        // 等待队列大小
        ConnectionProvider connectionProvider =
            ConnectionProvider.builder("githubOssConnectionProvider")
                .maxConnections(maxConnections) // 最大连接数是
                .pendingAcquireMaxCount(100) // 等待队列大小
                .build();
        HttpClient httpClient =
            HttpClient.create(connectionProvider).responseTimeout(Duration.ofMillis(60000));

        webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }
}
