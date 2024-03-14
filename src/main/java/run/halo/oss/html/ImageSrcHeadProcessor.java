package run.halo.oss.html;

import cn.hutool.json.JSONUtil;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

/**
 * 模板处理器，打开主题时处理，增加引入js的html片段
 */
@Component
public class ImageSrcHeadProcessor implements TemplateHeadProcessor {

    private final ReactiveExtensionClient extensionClient;

    public ImageSrcHeadProcessor(ReactiveExtensionClient extensionClient) {
        this.extensionClient = extensionClient;
    }

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler structureHandler) {
        return extensionClient.fetch(ConfigMap.class, BasicConfig.NAME)
                .map(ConfigMap::getData)
                .map(m-> m.get(BasicConfig.GROUP))
                .mapNotNull(json-> JSONUtil.toBean(json, BasicConfig.class))
                .onErrorMap(throwable-> Exceptions.propagate(new RuntimeException("请检查插件主体文本内容配置是否配置"+throwable.getMessage())))
                .filter(BasicConfig::getEnable) // 为 true 开启使用
                .map(config -> {
                    final IModelFactory modelFactory = context.getModelFactory();
                    model.add(modelFactory.createText(ImageSrcScript(config)));
                    return Mono.empty();
                }).then();
    }

    /**
     * 懒加载 js
     *
     * @param config 内容配置
     * @return
     */
    private String ImageSrcScript(BasicConfig config) {
        return """
                <!-- github 使用 jsdelivr 路径优化 start -->
                <script src="/plugins/PluginGitHubOSS/assets/static/plugin-githuboss-min.js"></script>       
                <!-- PluginLazyLoad end -->
               """;
    }

    @Data
    public static class BasicConfig {
        public static final String NAME = "githuboss-settings";
        public static final String GROUP = "article";
        Boolean enable;
    }
}
