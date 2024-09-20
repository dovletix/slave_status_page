// src/main/java/com/example/generatorapp/controller/GeneratorController.java

package com.example.generatorapp.controller;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.repository.GeneratorRepository;
import com.example.generatorapp.service.GeneratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Controller
public class GeneratorController {

    @Autowired
    private GeneratorRepository generatorRepository;

    @Autowired
    private GeneratorService generatorService;

    @GetMapping("/")
    public String index(Model model) {
        List<Generator> generators = generatorRepository.findAll();
        model.addAttribute("generators", generators);
        return "index";
    }

    // Новый метод для получения статусов генераторов
    @GetMapping("/getStatuses")
    @ResponseBody
    public Map<Long, String> getStatuses() {
        List<Generator> generators = generatorRepository.findAll();
        Map<Long, String> statusMap = new HashMap<>();
        for (Generator generator : generators) {
            statusMap.put(generator.getId(), generator.getStatus());
        }
        return statusMap;
    }

    // Новый метод для обновления статусов
    @PostMapping("/refreshStatuses")
    @ResponseBody
    public String refreshStatuses() {
        new Thread(() -> generatorService.refreshStatuses()).start();
        return "OK";
    }

    // Методы для занятия и освобождения генераторов
    @PostMapping("/occupy/{id}")
    @ResponseBody
    public String occupyGenerator(@PathVariable Long id) {
        Generator generator = generatorRepository.findById(id).orElse(null);
        if (generator != null) {
            new Thread(() -> generatorService.occupyGenerator(generator)).start();
            return "OK";
        } else {
            return "Generator not found";
        }
    }

    @PostMapping("/release/{id}")
    @ResponseBody
    public String releaseGenerator(@PathVariable Long id) {
        Generator generator = generatorRepository.findById(id).orElse(null);
        if (generator != null) {
            new Thread(() -> generatorService.releaseGenerator(generator)).start();
            return "OK";
        } else {
            return "Generator not found";
        }
    }

}
