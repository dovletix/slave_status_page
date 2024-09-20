// src/main/java/com/example/generatorapp/controller/GeneratorStatusController.java

package com.example.generatorapp.controller;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.repository.GeneratorRepository;
import com.example.generatorapp.service.GeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class GeneratorStatusController {

    @Autowired
    private GeneratorRepository generatorRepository;

    @Autowired
    private GeneratorService generatorService;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        List<Generator> generators = generatorRepository.findAll();
        generatorService.updateAllStatuses(generators);

        Map<String, Object> response = Map.of(
                "statusMap", generatorService.getStatusMap()
        );
        return response;
    }
}
