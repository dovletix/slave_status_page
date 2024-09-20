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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller
public class GeneratorController {


    private static final Logger logger = LoggerFactory.getLogger(GeneratorController.class);


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
        logger.debug("Обновление статуса генератора '{}'", generator.getName());
        try {
            Session session = createSession(generator);
            session.connect();
            logger.debug("SSH-сессия с генератором '{}' установлена", generator.getName());

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            logger.debug("SFTP-канал с генератором '{}' открыт", generator.getName());

            InputStream inputStream = null;
            try {
                // Пытаемся открыть файл lockfile.txt
                inputStream = sftpChannel.get("lockfile.txt");
                logger.debug("Файл lockfile.txt найден на генераторе '{}'", generator.getName());
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '0'
                    logger.warn("Файл lockfile.txt не найден на генераторе '{}'. Создаём файл и устанавливаем '1'.", generator.getName());
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
            logger.debug("Содержимое lockfile.txt на генераторе '{}': '{}'", generator.getName(), content);
            if ("1".equals(content)) {
                statusMap.put(generator.getName(), "Занят");
            } else {
                statusMap.put(generator.getName(), "Свободен");
            }
            reader.close();

            sftpChannel.disconnect();
            session.disconnect();
            logger.debug("Статус генератора '{}' обновлён: {}", generator.getName(), statusMap.get(generator.getName()));
        } catch (Exception e) {
            logger.error("Ошибка при обновлении статуса генератора '{}': {}", generator.getName(), e.getMessage());
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }



    private void occupyGeneratorAction(Generator generator) {
        logger.info("Попытка занять генератор '{}'", generator.getName());
        try {
            Session session = createSession(generator);
            session.connect();
            logger.debug("SSH-сессия с генератором '{}' установлена", generator.getName());

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            logger.debug("SFTP-канал с генератором '{}' открыт", generator.getName());

            // Попытка открыть lockfile.txt для проверки
            try {
                sftpChannel.lstat("lockfile.txt");
                logger.debug("Файл lockfile.txt найден на генераторе '{}'", generator.getName());
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его
                    logger.warn("Файл lockfile.txt не найден на генераторе '{}'. Создаём файл.", generator.getName());
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("1");
                    writer.close();
                    statusMap.put(generator.getName(), "Занят");
                    sftpChannel.disconnect();
                    session.disconnect();
                    logger.info("Генератор '{}' успешно занят.", generator.getName());
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
            logger.debug("Файл lockfile.txt на генераторе '{}' установлен в '1'", generator.getName());

            sftpChannel.disconnect();
            session.disconnect();

            statusMap.put(generator.getName(), "Занят");
            logger.info("Генератор '{}' успешно занят.", generator.getName());
        } catch (Exception e) {
            logger.error("Ошибка при занятии генератора '{}': {}", generator.getName(), e.getMessage());
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }



    private void releaseGeneratorAction(Generator generator) {
        logger.info("Попытка освободить генератор '{}'", generator.getName());
        try {
            Session session = createSession(generator);
            session.connect();
            logger.debug("SSH-сессия с генератором '{}' установлена", generator.getName());

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();
            logger.debug("SFTP-канал с генератором '{}' открыт", generator.getName());

            boolean fileExists = true;

            // Попытка открыть lockfile.txt для проверки
            try {
                sftpChannel.lstat("lockfile.txt");
                logger.debug("Файл lockfile.txt найден на генераторе '{}'", generator.getName());
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '0'
                    logger.warn("Файл lockfile.txt не найден на генераторе '{}'. Создаём файл и устанавливаем '0'.", generator.getName());
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("0");
                    writer.close();
                    fileExists = false;
                } else {
                    throw e;
                }
            }

            if (fileExists) {
                // Файл найден, записываем '0'
                OutputStream outputStream = sftpChannel.put("lockfile.txt");
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                writer.write("0");
                writer.close();
                logger.debug("Файл lockfile.txt на генераторе '{}' установлен в '0'", generator.getName());
            }

            // Получаем белый список
            List<WhitelistEntry> whitelist = whitelistEntryRepository.findAll();
            Set<String> whitelistPaths = new HashSet<>();
            for (WhitelistEntry entry : whitelist) {
                whitelistPaths.add(entry.getPath());
            }
            logger.debug("Белый список путей: {}", whitelistPaths);

            // Получаем список файлов и папок в корневом каталоге
            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(".");
            logger.debug("Список файлов и папок на генераторе '{}': {}", generator.getName(), files);

            for (ChannelSftp.LsEntry entry : files) {
                String filename = entry.getFilename();

                // Пропускаем скрытые файлы и папки
                if (filename.startsWith(".")) {
                    continue;
                }

                // Пропускаем файлы и папки из белого списка
                if (whitelistPaths.contains(filename)) {
                    logger.debug("Файл/папка '{}' находится в белом списке. Пропускаем.", filename);
                    continue;
                }

                // Удаляем файл или папку
                if (entry.getAttrs().isDir()) {
                    logger.info("Удаляем папку '{}' на генераторе '{}'", filename, generator.getName());
                    deleteDirectory(sftpChannel, filename);
                } else {
                    logger.info("Удаляем файл '{}' на генераторе '{}'", filename, generator.getName());
                    sftpChannel.rm(filename);
                }
            }

            sftpChannel.disconnect();
            session.disconnect();

            statusMap.put(generator.getName(), "Свободен");
            logger.info("Генератор '{}' успешно освобождён.", generator.getName());
        } catch (Exception e) {
            logger.error("Ошибка при освобождении генератора '{}': {}", generator.getName(), e.getMessage());
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }


    private void deleteDirectory(ChannelSftp sftpChannel, String path) throws SftpException {
        logger.debug("Удаление директории '{}'", path);
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
                logger.debug("Удаляем файл '{}'", fullPath);
                sftpChannel.rm(fullPath);
            }
        }

        logger.debug("Удаляем директорию '{}'", path);
        sftpChannel.rmdir(path);
    }


    private Session createSession(Generator generator) throws JSchException {
        logger.debug("Создание SSH-сессии для генератора '{}'", generator.getName());
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
