package com.xirizhi.plugingithuboss;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.SchemeManager;

@ExtendWith(MockitoExtension.class)
class PluginGithubossPluginTest {

    @Mock
    PluginContext context;

    @Mock
    SchemeManager schemeManager;

    @InjectMocks
    PluginGithubossPlugin plugin;

    @Test
    void contextLoads() {
        // 验证插件生命周期方法可调用，不抛出异常
        plugin.start();
        plugin.stop();
    }
}
