package com.example.generatorapp;

import com.jcraft.jsch.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    // Метод, который выполняется один раз при запуске приложения
    @PostConstruct
    public void init() {
        updateAllStatuses();
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("generators", generators);
        model.addAttribute("statusMap", statusMap);
        return "index";
    }

    @PostMapping("/occupy/{generatorName}")
    public String occupyGenerator(@PathVariable String generatorName, @RequestParam("userName") String userName) {
        Generator generator = getGeneratorByName(generatorName);
        if (generator != null) {
            new Thread(() -> occupyGeneratorAction(generator, userName)).start();
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

    // Новый метод для обновления всех статусов при нажатии кнопки
    @PostMapping("/updateStatuses")
    public String updateStatuses() {
        updateAllStatuses();
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

            String status;
            try {
                InputStream inputStream = sftpChannel.get("lockfile.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                String content = reader.readLine().trim();
                reader.close();

                if ("1".equals(content)) {
                    // Читаем имя пользователя
                    String occupierName = "Неизвестно";
                    try {
                        InputStream occupierStream = sftpChannel.get("occupier.txt");
                        BufferedReader occupierReader = new BufferedReader(new InputStreamReader(occupierStream));
                        occupierName = occupierReader.readLine().trim();
                        occupierReader.close();
                    } catch (SftpException e) {
                        // Файл occupier.txt может отсутствовать
                    }
                    status = "Занят (" + occupierName + ")";
                } else {
                    status = "Свободен";
                }
            } catch (SftpException e) {
                status = "lockfile.txt не найден";
            }

            statusMap.put(generator.getName(), status);

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }


    private void occupyGeneratorAction(Generator generator, String userName) {
        synchronized (generator) {
            try {
                Session session = createSession(generator);
                session.connect();

                ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
                sftpChannel.connect();

                // Проверяем, не занят ли генератор
                String lockStatus = "0";
                try {
                    InputStream inputStream = sftpChannel.get("lockfile.txt");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    lockStatus = reader.readLine().trim();
                    reader.close();
                } catch (SftpException e) {
                    // lockfile.txt может отсутствовать, считаем, что генератор свободен
                }

                if ("1".equals(lockStatus)) {
                    // Генератор уже занят
                    statusMap.put(generator.getName(), "Занят другим пользователем");
                } else {
                    // Занимаем генератор
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("1");
                    writer.close();

                    // Сохраняем имя пользователя
                    OutputStream occupierStream = sftpChannel.put("occupier.txt");
                    BufferedWriter occupierWriter = new BufferedWriter(new OutputStreamWriter(occupierStream));
                    occupierWriter.write(userName);
                    occupierWriter.close();

                    statusMap.put(generator.getName(), "Занят (" + userName + ")");
                }

                sftpChannel.disconnect();
                session.disconnect();

                // Задержка в 1 секунду перед обновлением статусов
                Thread.sleep(1000);
                updateAllStatuses();

            } catch (Exception e) {
                statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
            }
        }
    }

    private void releaseGeneratorAction(Generator generator) {
        synchronized (generator) {
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

                // Удаляем файл с именем пользователя
                try {
                    sftpChannel.rm("occupier.txt");
                } catch (SftpException e) {
                    // Файл может отсутствовать
                }

                // Определяем белый список
                List<String> whitelist = Arrays.asList("lockfile.txt", "whitelisted_folder");

                // Получаем домашнюю директорию пользователя
                String homeDir = getHomeDirectory(session);

                // Строим команду find для удаления файлов с определенными расширениями
                StringBuilder findCommand = new StringBuilder("find " + homeDir);

                // Ищем файлы с нужными расширениями
                findCommand.append(" -type f \\( -name '*.jmx' -o -name '*.csv' -o -name '*.sh' \\)");

                // Исключаем скрытые файлы и файлы внутри скрытых директорий
                findCommand.append(" -not -path '*/.*/*' -not -name '.*'");

                // Исключаем файлы и директории из белого списка и их содержимое
                for (String item : whitelist) {
                    String itemPath = homeDir + "/" + item;
                    findCommand.append(" -not -path '" + itemPath + "'");
                    findCommand.append(" -not -path '" + itemPath + "/*'");
                    findCommand.append(" -not -path '" + itemPath + "/**'");
                }

                // Удаляем найденные файлы
                findCommand.append(" -exec rm -f {} +");

                // Выполняем команду удаления
                ChannelExec channelExec = (ChannelExec) session.openChannel("exec");
                channelExec.setCommand(findCommand.toString());
                InputStream in = channelExec.getInputStream();
                channelExec.connect();

                BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                String line;
                while ((line = reader.readLine()) != null) {
                    // Обработка вывода (при необходимости)
                }
                reader.close();
                channelExec.disconnect();

                sftpChannel.disconnect();
                session.disconnect();

                statusMap.put(generator.getName(), "Свободен");

                // Задержка в 1 секунду перед обновлением статусов
                Thread.sleep(1000);
                updateAllStatuses();

            } catch (Exception e) {
                statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
            }
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
