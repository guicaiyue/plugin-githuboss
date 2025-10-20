package com.xirizhi.plugingithuboss.endpoint;

import com.xirizhi.plugingithuboss.extension.RepositoryConfig;
import com.xirizhi.plugingithuboss.service.GitHubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import org.pf4j.Extension;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

/**
 * GitHub 内容查询接口：提供查询 GitHub 仓库目录内容的 API。
 * 遵循 Halo 插件开发规范，使用 CustomEndpoint 接口实现自定义 API。
 * 
 * API 路径规则：/apis/console.api.githuboss.halo.run/v1alpha1/github-contents
 */
@Slf4j
@Extension
@Component
@RequiredArgsConstructor
public class GitHubContentsEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final GitHubService gitHubService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
                .GET("/github-contents", this::listContents,
                        builder -> builder.operationId("listGitHubContents")
                                .summary("查询 GitHub 仓库目录内容")
                                .description("根据仓库名称和路径查询 GitHub 仓库中的文件和目录列表")
                                .parameter(parameterBuilder()
                                        .name("repoName")
                                        .in(ParameterIn.QUERY)
                                        .description("仓库配置名称")
                                        .required(true))
                                .parameter(parameterBuilder()
                                        .name("path")
                                        .in(ParameterIn.QUERY)
                                        .description("目录路径，默认为根目录")
                                        .required(false))
                                .response(responseBuilder()
                                        .responseCode("200")
                                        .description("查询成功，返回目录内容 JSON"))
                                .response(responseBuilder()
                                        .responseCode("400")
                                        .description("请求参数错误"))
                                .response(responseBuilder()
                                        .responseCode("404")
                                        .description("仓库配置不存在"))
                                .response(responseBuilder()
                                        .responseCode("500")
                                        .description("查询失败")))
                .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.githuboss.halo.run", "v1alpha1");
    }

    /**
     * 查询 GitHub 仓库目录内容的处理方法
     */
    private Mono<ServerResponse> listContents(ServerRequest request) {
        // 获取查询参数
        String repoName = request.queryParam("repoName").orElse("");
        String path = request.queryParam("path").orElse("");

        if (repoName.isEmpty()) {
            return ServerResponse.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"error\":\"repoName 参数不能为空\"}");
        }

        return client.fetch(RepositoryConfig.class, repoName)
                .cast(RepositoryConfig.class)
                .flatMap(config -> {
                    try {
                        // 调用 GitHubService 查询目录内容
                        String contents = gitHubService.listDirectoryContents(config.getSpec(), path);
                        return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(contents);
                    } catch (Exception e) {
                        log.error("查询 GitHub 目录内容失败: repoName={}, path={}", repoName, path, e);
                        return ServerResponse.status(500)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue("{\"error\":\"查询失败: " + e.getMessage() + "\"}");
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("获取仓库配置失败: repoName={}", repoName, throwable);
                    return ServerResponse.status(404)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{\"error\":\"仓库配置不存在: " + repoName + "\"}");
                });
    }
}