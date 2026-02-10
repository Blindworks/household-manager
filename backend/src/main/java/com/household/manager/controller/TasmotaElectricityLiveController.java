package com.household.manager.controller;

import com.household.manager.service.TasmotaElectricityLiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE endpoint for live Tasmota electricity data.
 * Base URL: /api/v1/tasmota-electricity
 */
@RestController
@RequestMapping("/v1/tasmota-electricity")
@RequiredArgsConstructor
public class TasmotaElectricityLiveController {

    private final TasmotaElectricityLiveService liveService;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLive() {
        return liveService.subscribe();
    }
}
