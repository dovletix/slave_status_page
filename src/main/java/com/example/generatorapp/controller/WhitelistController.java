// src/main/java/com/example/generatorapp/controller/WhitelistController.java

package com.example.generatorapp.controller;

import com.example.generatorapp.model.WhitelistEntry;
import com.example.generatorapp.repository.WhitelistEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Controller
public class WhitelistController {

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @GetMapping("/whitelist")
    public String listWhitelist(Model model) {
        List<WhitelistEntry> whitelist = whitelistEntryRepository.findAll();
        model.addAttribute("whitelist", whitelist);
        return "whitelist";
    }

    @GetMapping("/whitelist/add")
    public String addWhitelistEntryForm(Model model) {
        model.addAttribute("whitelistEntry", new WhitelistEntry());
        return "add-whitelist-entry";
    }

    @PostMapping("/whitelist/add")
    public String addWhitelistEntry(@ModelAttribute WhitelistEntry whitelistEntry) {
        whitelistEntryRepository.save(whitelistEntry);
        return "redirect:/whitelist";
    }

    @GetMapping("/whitelist/delete/{id}")
    public String deleteWhitelistEntry(@PathVariable Long id) {
        whitelistEntryRepository.deleteById(id);
        return "redirect:/whitelist";
    }
}
