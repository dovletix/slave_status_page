// src/main/java/com/example/generatorapp/controller/GeneratorController.java

package com.example.generatorapp.controller;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.model.WhitelistEntry;
import com.example.generatorapp.repository.GeneratorRepository;
import com.example.generatorapp.repository.WhitelistEntryRepository;
import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class GeneratorController {

    @Autowired
    private GeneratorRepository generatorRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    private Map<String, String> statusMap = new ConcurrentHashMap<>();

    public GeneratorController() {
        // Инициализация статусов
        // Статусы будут обновлены при первом обращении к странице
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Generator> generators = generatorRepository.findAll();
        updateAllStatuses(generators);

        model.addAttribute("generators", generators);
        model.addAttribute("statusMap", statusMap);
        return "index";
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

    // Остальные методы (occupy, release, update statuses)

    @PostMapping("/occupy/{id}")
    public String occupyGenerator(@PathVariable Long id) {
        Generator generator = generatorRepository.findById(id).orElse(null);
        if (generator != null) {
            new Thread(() -> occupyGeneratorAction(generator)).start();
        }
        return "redirect:/";
    }

    @PostMapping("/release/{id}")
    public String releaseGenerator(@PathVariable Long id) {
        Generator generator = generatorRepository.findById(id).orElse(null);
        if (generator != null) {
            new Thread(() -> releaseGeneratorAction(generator)).start();
        }
        return "redirect:/";
    }

    private void updateAllStatuses(List<Generator> generators) {
        for (Generator generator : generators) {
            new Thread(() -> updateGeneratorStatus(generator)).start();
        }
    }

    private void updateGeneratorStatus(Generator generator) {
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            InputStream inputStream = null;
            try {
                // Пытаемся открыть файл lockfile.txt
                inputStream = sftpChannel.get("lockfile.txt");
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '1'
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("0");
                    writer.close();
                    statusMap.put(generator.getName(), "Занят");
                    sftpChannel.disconnect();
                    session.disconnect();
                    return;
                } else {
                    throw e;
                }
            }

            // Если файл найден, читаем его содержимое
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String content = reader.readLine().trim();
            if ("1".equals(content)) {
                statusMap.put(generator.getName(), "Занят");
            } else {
                statusMap.put(generator.getName(), "Свободен");
            }
            reader.close();

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }


    private void occupyGeneratorAction(Generator generator) {
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            // Попытка открыть lockfile.txt для проверки
            try {
                sftpChannel.lstat("lockfile.txt");
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("1");
                    writer.close();
                    statusMap.put(generator.getName(), "Занят");
                    sftpChannel.disconnect();
                    session.disconnect();
                    return;
                } else {
                    throw e;
                }
            }

            // Если файл найден, устанавливаем '1'
            OutputStream outputStream = sftpChannel.put("lockfile.txt");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write("1");
            writer.close();

            sftpChannel.disconnect();
            session.disconnect();

            statusMap.put(generator.getName(), "Занят");
        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }


    private void releaseGeneratorAction(Generator generator) {
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            // Устанавливаем lockfile.txt в '0'
            OutputStream outputStream = sftpChannel.put("lockfile.txt");
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write("0");
            writer.close();

            // Получаем белый список
            List<WhitelistEntry> whitelist = whitelistEntryRepository.findAll();
            Set<String> whitelistPaths = new HashSet<>();
            for (WhitelistEntry entry : whitelist) {
                whitelistPaths.add(entry.getPath());
            }

            // Получаем список файлов и папок в корневом каталоге
            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(".");

            for (ChannelSftp.LsEntry entry : files) {
                String filename = entry.getFilename();

                // Пропускаем скрытые файлы и папки
                if (filename.startsWith(".")) {
                    continue;
                }

                // Пропускаем файлы и папки из белого списка
                if (whitelistPaths.contains(filename)) {
                    continue;
                }

                // Удаляем файл или папку
                if (entry.getAttrs().isDir()) {
                    deleteDirectory(sftpChannel, filename);
                } else {
                    sftpChannel.rm(filename);
                }
            }

            sftpChannel.disconnect();
            session.disconnect();

            statusMap.put(generator.getName(), "Свободен");
        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }

    private void deleteDirectory(ChannelSftp sftpChannel, String path) throws SftpException {
        Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(path);

        for (ChannelSftp.LsEntry entry : files) {
            String filename = entry.getFilename();
            if (".".equals(filename) || "..".equals(filename)) {
                continue;
            }

            String fullPath = path + "/" + filename;

            if (entry.getAttrs().isDir()) {
                deleteDirectory(sftpChannel, fullPath);
            } else {
                sftpChannel.rm(fullPath);
            }
        }

        sftpChannel.rmdir(path);
    }

    private Session createSession(Generator generator) throws JSchException {
        JSch jsch = new JSch();
        Session session = jsch.getSession(generator.getUsername(), generator.getAddress(), 22);
        session.setPassword(generator.getPassword());

        // Настройка, чтобы избежать проверки ключа хоста
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        return session;
    }
}
