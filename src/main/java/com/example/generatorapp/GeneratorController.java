// Помните, что необходимо добавить зависимости в ваш проект:
// - Spring Boot для создания веб-приложения
// - JSch для SSH-подключений

// Файл build.gradle (если используете Gradle):
/*
plugins {
    id 'org.springframework.boot' version '2.7.5'
    id 'io.spring.dependency-management' version '1.0.14.RELEASE'
    id 'java'
}

group = 'com.example'
version = '1.0.0'
sourceCompatibility = '11'

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-thymeleaf'
    implementation 'com.jcraft:jsch:0.1.55'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
*/

// Основной класс приложения:

package com.example.generatorapp;

import com.jcraft.jsch.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.jcraft.jsch.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Контроллер:

@Controller
public class GeneratorController {

    // Класс Generator для хранения информации о генераторе
    public static class Generator {
        private String name;
        private String address;
        private String username;
        private String password;

        public Generator(String name, String address, String username, String password) {
            this.name = name;
            this.address = address;
            this.username = username;
            this.password = password;
        }

        public String getName() {
            return name;
        }

        public String getAddress() {
            return address;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }
    }

    // Список генераторов
    private List<Generator> generators = new ArrayList<>();

    // Карта статусов
    private Map<String, String> statusMap = new ConcurrentHashMap<>();

    public GeneratorController() {
        // Инициализируем список генераторов
        generators.add(new Generator("Generator1", "192.168.1.10", "username", "password"));
        generators.add(new Generator("Generator2", "192.168.1.11", "username", "password"));

        // Инициализируем статус каждого генератора
        for (Generator generator : generators) {
            statusMap.put(generator.getName(), "Неизвестно");
        }
    }

    @GetMapping("/")
    public String index(Model model) {
        // Обновляем статусы
        updateAllStatuses();

        model.addAttribute("generators", generators);
        model.addAttribute("statusMap", statusMap);
        return "index";
    }

    @PostMapping("/occupy/{generatorName}")
    public String occupyGenerator(@PathVariable String generatorName) {
        Generator generator = getGeneratorByName(generatorName);
        if (generator != null) {
            new Thread(() -> occupyGeneratorAction(generator)).start();
        }
        return "redirect:/";
    }

    @PostMapping("/release/{generatorName}")
    public String releaseGenerator(@PathVariable String generatorName) {
        Generator generator = getGeneratorByName(generatorName);
        if (generator != null) {
            new Thread(() -> releaseGeneratorAction(generator)).start();
        }
        return "redirect:/";
    }

    private Generator getGeneratorByName(String name) {
        for (Generator generator : generators) {
            if (generator.getName().equals(name)) {
                return generator;
            }
        }
        return null;
    }

    private void updateAllStatuses() {
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

            try {
                InputStream inputStream = sftpChannel.get("lockfile.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String content = reader.readLine().trim();
                if ("1".equals(content)) {
                    statusMap.put(generator.getName(), "Занят");
                } else {
                    statusMap.put(generator.getName(), "Свободен");
                }
                reader.close();
            } catch (SftpException e) {
                statusMap.put(generator.getName(), "lockfile.txt не найден");
            }

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

            // Определяем белый список
            List<String> whitelist = Arrays.asList("apa", "lockfile.txt");

            // Получаем домашнюю директорию пользователя
            String homeDir = getHomeDirectory(session);

            // Строим команду find для удаления
            StringBuilder findCommand = new StringBuilder("find " + homeDir + " -maxdepth 1 -mindepth 1");

            // Исключаем скрытые файлы и директории
            findCommand.append(" ! -name '.*'");

            // Исключаем элементы из белого списка
            for (String item : whitelist) {
                findCommand.append(" ! -name '" + item + "'");
            }

            // Добавляем команду для удаления найденных файлов и директорий
            findCommand.append(" -exec rm -r {} +");

            // Выполняем команду удаления
            ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(findCommand.toString());
            channelExec.connect();

            // Читаем вывод команды (если необходимо)
            InputStream in = channelExec.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = reader.readLine()) != null) {
                // Можно обработать вывод, если нужно
                System.out.println(line);
            }
            channelExec.disconnect();

            sftpChannel.disconnect();
            session.disconnect();

            statusMap.put(generator.getName(), "Свободен");
        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }

    private String getHomeDirectory(Session session) throws JSchException, IOException {
        // Выполняем 'echo $HOME' для получения домашней директории
        ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
        channelExec.setCommand("echo $HOME");
        InputStream in = channelExec.getInputStream();
        channelExec.connect();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String homeDir = reader.readLine().trim();

        channelExec.disconnect();
        return homeDir;
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
