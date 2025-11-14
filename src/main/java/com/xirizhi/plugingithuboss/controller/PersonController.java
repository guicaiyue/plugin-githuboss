package com.xirizhi.plugingithuboss.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.xirizhi.plugingithuboss.service.GitHubService;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ApiVersion;

@ApiVersion("my-plugin.halo.run/v1alpha1")
@RequestMapping("/persons")
@RestController
@RequiredArgsConstructor
public class PersonController {
  private final GitHubService gitHubService;

    @GetMapping("/{name}")
    public Mono<Boolean> checkConnectivity() {
      return gitHubService.checkConnectivity();
    }
}