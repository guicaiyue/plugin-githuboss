package com.xirizhi.plugingithuboss.extension.theme;

import lombok.Data;

@Data
public class NetworkConfig {
    // 代理地址(127.0.0.1:7890)
    private String proxyPath;
    // 是否启用代理
    private Boolean enabled;
    // 接口超时毫秒，默认10000
    private Integer timeoutMs;
}

