package com.xirizhi.plugingithuboss;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.SchemeManager;

@Slf4j
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
        log.info("githubOss 插件启动成功！");
    }

    @Override
    public void stop() {
        log.info("githubOss 插件停止！");
    }
}
