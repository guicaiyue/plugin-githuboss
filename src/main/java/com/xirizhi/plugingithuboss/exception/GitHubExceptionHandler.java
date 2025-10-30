package com.xirizhi.plugingithuboss.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

/**
 * GitHub 异常处理器
 * 负责将技术性异常转换为用户友好的业务异常
 */
@Slf4j
public class GitHubExceptionHandler {

    /**
     * 将技术异常映射为用户友好的异常
     * 
     * @param throwable 原始异常
     * @return 转换后的异常
     */
    public static Throwable map(Throwable throwable) {
        // 1. GitHub 连通性检测失败（网络不可达）
        if (throwable instanceof UnknownHostException) {
            log.error("GitHub 域名解析失败", throwable);
            return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "异常：无法解析 GitHub 域名，请检查网络连接或 DNS 配置。如果在国内环境，可能需要配置代理。"
            );
        }

        // 2. 连接超时
        if (throwable instanceof ConnectException || 
            throwable instanceof HttpTimeoutException ||
            throwable instanceof SocketTimeoutException) {
            log.error("GitHub 连接超时", throwable);
            return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "异常：连接 GitHub 超时，请检查网络连接。如果在国内环境，建议配置代理或稍后重试。"
            );
        }

        // 3. IO 异常（通常是网络问题）
        if (throwable instanceof IOException) {
            log.error("GitHub 网络 IO 异常", throwable);
            return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "异常：GitHub 网络连接异常，请检查网络连接或稍后重试。"
            );
        }

        // 4. IllegalStateException（我们自定义的连通性检测失败异常）
        if (throwable instanceof IllegalStateException) {
            log.error("GitHub 连通性检测失败", throwable);
            return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "异常：" + throwable.getMessage()
            );
        }

        // 5. 其他异常，返回通用错误信息
        log.error("GitHub 操作发生未知异常", throwable);
        return new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "异常：GitHub 操作失败，请检查配置或稍后重试。详细错误：" + throwable.getMessage()
        );
    }
}