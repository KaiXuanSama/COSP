package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderModelDiscoveryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 供应商模型发现 API。 */
@RestController
public class ProviderModelDiscoveryController {

    private final ProviderModelDiscoveryService providerModelDiscoveryService;

    public ProviderModelDiscoveryController(ProviderModelDiscoveryService providerModelDiscoveryService) {
        this.providerModelDiscoveryService = providerModelDiscoveryService;
    }

    @PostMapping("/config/api/providers/{providerKey}/pull-models")
    public Mono<ResponseEntity<Object>> pullProviderModels(@PathVariable String providerKey,
                                                           @RequestBody Map<String, String> body) {
        return providerModelDiscoveryService.pullModels(providerKey, new ProviderModelDiscoveryService.ModelPullCommand(
                body.get("baseUrl"), body.get("apiKey"), body.get("keyUuid"), body.get("modelPullPath"),
                ProviderModelDiscoveryService.ModelPullCommand.parseProtocol(body.get("protocol"))));
    }
}