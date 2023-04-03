package run.halo.oss;


import org.pf4j.PluginWrapper;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;

/**
 * @author xirizhi
 */
@Component
public class GithubOSSPlugin extends BasePlugin {

    public GithubOSSPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }
}
