package com.household.manager.system;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** System-Aktionen des Dashboards (aktuell nur der Reboot-Button). */
@RestController
@RequestMapping("/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemRebootService systemRebootService;

    @PostMapping("/reboot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reboot() {
        systemRebootService.reboot();
    }
}
