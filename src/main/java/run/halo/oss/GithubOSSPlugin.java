package run.halo.oss;


import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;

/**
 * @author xirizhi
 * 插件加载入口
 */
@Slf4j
@Component
public class GithubOSSPlugin extends BasePlugin {

    public GithubOSSPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
        log.info("GithubOSSPlugin 插件已启动");
    }

    @Override
    public void stop() {
        log.info("GithubOSSPlugin 插件已停止");
    }
}
