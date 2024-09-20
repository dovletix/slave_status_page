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

    // Новый метод для обновления статусов
    @PostMapping("/refreshStatuses")
    @ResponseBody
    public String refreshStatuses() {
        new Thread(() -> generatorService.refreshStatuses()).start();
        return "OK";
    }
    // Методы для добавления, редактирования и удаления генераторов

    @GetMapping("/generators")
    public String listGenerators(Model model) {
        List<Generator> generators = generatorRepository.findAll();
        model.addAttribute("generators", generators);
        return "generators";
    }

    @GetMapping("/generators/add")
    public String addGeneratorForm(Model model) {
        model.addAttribute("generator", new Generator());
        return "add-generator";
    }

    @PostMapping("/generators/add")
    public String addGenerator(@ModelAttribute Generator generator) {
        generatorRepository.save(generator);
        return "redirect:/generators";
    }

    @GetMapping("/generators/edit/{id}")
    public String editGeneratorForm(@PathVariable Long id, Model model) {
        Generator generator = generatorRepository.findById(id).orElse(null);
        if (generator != null) {
            model.addAttribute("generator", generator);
            return "add-generator"; // Используем тот же шаблон
        }
        return "redirect:/generators";
    }

    @PostMapping("/generators/edit")
    public String editGenerator(@ModelAttribute Generator generator) {
        generatorRepository.save(generator);
        return "redirect:/generators";
    }

    @GetMapping("/generators/delete/{id}")
    public String deleteGenerator(@PathVariable Long id) {
        generatorRepository.deleteById(id);
        return "redirect:/generators";
    }

    // Методы для занятия и освобождения генератора

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

}
