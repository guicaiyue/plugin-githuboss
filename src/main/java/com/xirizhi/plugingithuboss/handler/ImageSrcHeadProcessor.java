package com.xirizhi.plugingithuboss.handler;

import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;

import com.xirizhi.plugingithuboss.config.Constant;
import com.xirizhi.plugingithuboss.extension.GitHubThemeSettings;
import com.xirizhi.plugingithuboss.extension.theme.GitHubBasic;

import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

@Component
public class ImageSrcHeadProcessor implements TemplateHeadProcessor {

    private final ReactiveExtensionClient client;

    public ImageSrcHeadProcessor(ReactiveExtensionClient extensionClient) {
        this.client = extensionClient;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler structureHandler) {
        return client.fetch(ConfigMap.class, Constant.PLUGIN_GITHUBOSS_CONFIGMAP)
                .map(ConfigMap::getData)
                .map(data -> JsonUtils.jsonToObject(data.get(GitHubThemeSettings.GitHub_BASIC), GitHubBasic.class))
                .onErrorMap(throwable -> Exceptions.propagate(new RuntimeException("请检查插件主体文本内容配置是否正确：" + throwable.getMessage())))
                .map(basic -> {
                    if (basic.getEnableOptimization()) {
                        final IModelFactory modelFactory = context.getModelFactory();
                        model.add(modelFactory.createText(ImageSrcScript()));
                    }
                    return Mono.empty();
                }).then();
    }

    /**
     * 懒加载 js
     *
     * @param config 内容配置
     * @return
     */
    private String ImageSrcScript() {
        return """
                <!-- github 使用 jsdelivr 路径优化 start -->
                <script src="/plugins/PluginGitHubOSS/assets/static/plugin-githuboss.js?version=1.0.0"></script>
                <!-- PluginLazyLoad end -->
               """;
    }
}