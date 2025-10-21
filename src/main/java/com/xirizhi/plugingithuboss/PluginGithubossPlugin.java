package com.xirizhi.plugingithuboss;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.SchemeManager;
import com.xirizhi.plugingithuboss.extension.RepositoryConfig;
import com.xirizhi.plugingithuboss.extension.AttachmentRecord;

@Component
public class PluginGithubossPlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public PluginGithubossPlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        // 注册自定义模型，生成 CRUD APIs
        schemeManager.register(RepositoryConfig.class);
        schemeManager.register(AttachmentRecord.class);
        System.out.println("插件启动成功！已注册自定义模型：RepositoryConfig, AttachmentRecord, SyncSnapshot, AuditLog");
    }

    @Override
    public void stop() {
        System.out.println("插件停止！");
    }
}
