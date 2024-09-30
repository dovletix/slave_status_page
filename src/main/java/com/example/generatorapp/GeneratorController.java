package com.example.generatorapp;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.repository.GeneratorRepository;
import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import javax.annotation.PostConstruct;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Controller
public class GeneratorController {

    @Autowired
    private GeneratorRepository generatorRepository;

    // Карта статусов
    private Map<String, String> statusMap = new ConcurrentHashMap<>();

    // Метод, который выполняется один раз при запуске приложения
    @PostConstruct
    public void init() {
        updateAllStatuses();
    }

    @GetMapping("/")
    public String index(Model model) {
        List<Generator> generators = generatorRepository.findAll();
        model.addAttribute("generators", generators);
        model.addAttribute("statusMap", statusMap);

        // Обновляем статусы, если они отсутствуют
        for (Generator gen : generators) {
            if (!statusMap.containsKey(gen.getName())) {
                statusMap.put(gen.getName(), "Неизвестно");
            }
        }

        return "index";
    }


    @PostMapping("/occupy/{generatorId}")
    public String occupyGenerator(@PathVariable Long generatorId, @RequestParam("userName") String userName) {
        Generator generator = generatorRepository.findById(generatorId).orElse(null);
        if (generator != null) {
            new Thread(() -> occupyGeneratorAction(generator, userName)).start();
        }
        return "redirect:/";
    }

    @PostMapping("/release/{generatorId}")
    public String releaseGenerator(@PathVariable Long generatorId) {
        Generator generator = generatorRepository.findById(generatorId).orElse(null);
        if (generator != null) {
            new Thread(() -> releaseGeneratorAction(generator)).start();
        }
        return "redirect:/";
    }

    @PostMapping("/updateStatuses")
    public String updateStatuses() {
        updateAllStatuses();
        return "redirect:/";
    }

    // Новый метод для добавления генератора
    @GetMapping("/addGenerator")
    public String addGeneratorForm(Model model) {
        model.addAttribute("generator", new Generator());
        return "addGenerator";
    }

    @PostMapping("/addGenerator")
    public String addGenerator(@ModelAttribute Generator generator) {
        generatorRepository.save(generator);
        return "redirect:/";
    }

    // Метод для удаления генератора
    @PostMapping("/delete/{generatorId}")
    public String deleteGenerator(@PathVariable Long generatorId) {
        generatorRepository.deleteById(generatorId);
        return "redirect:/";
    }

    // Метод для изменения пароля генератора
    @GetMapping("/changePassword/{generatorId}")
    public String changePasswordForm(@PathVariable Long generatorId, Model model) {
        Generator generator = generatorRepository.findById(generatorId).orElse(null);
        if (generator != null) {
            model.addAttribute("generator", generator);
            return "changePassword";
        }
        return "redirect:/";
    }

    @PostMapping("/changePassword")
    public String changePassword(@RequestParam("id") Long id, @RequestParam("password") String password) {
        Generator existingGenerator = generatorRepository.findById(id).orElse(null);
        if (existingGenerator != null) {
            existingGenerator.setPassword(password);
            generatorRepository.save(existingGenerator);
        }
        return "redirect:/";
    }

    private void updateAllStatuses() {
        List<Generator> generators = generatorRepository.findAll();
        for (Generator generator : generators) {
            new Thread(() -> updateGeneratorStatus(generator)).start();
        }
    }

    private void updateGeneratorStatus(Generator generator) {
        Session session = null;
        ChannelSftp sftpChannel = null;
        try {
            session = createSession(generator);
            session.connect();

            sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            String status;
            try (InputStream inputStream = sftpChannel.get("lockfile.txt");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String content = reader.readLine().trim();
                if ("1".equals(content)) {
                    // Читаем имя пользователя
                    String occupierName = "Неизвестно";
                    try (InputStream occupierStream = sftpChannel.get("occupier.txt");
                         BufferedReader occupierReader = new BufferedReader(new InputStreamReader(occupierStream))) {
                        occupierName = occupierReader.readLine().trim();
                    } catch (SftpException e) {
                        // Файл occupier.txt может отсутствовать
                        System.out.println("Файл occupier.txt может отсутствует. Неизвестно кто занял генератор.");
                    }
                    status = "Занят (" + occupierName + ")";
                } else {
                    status = "Свободен";
                }
            } catch (SftpException e) {
                status = "lockfile.txt не найден";
            }

            statusMap.put(generator.getName(), status);

        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        } finally {
            // Гарантируем закрытие ресурсов
            if (sftpChannel != null && sftpChannel.isConnected()) {
                sftpChannel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }


    private void occupyGeneratorAction(Generator generator, String userName) {
        try {
            boolean generatorOccupied = false;
            Session session = null;
            ChannelSftp sftpChannel = null;

            // Критическая секция
            synchronized (generator) {
                try {
                    session = createSession(generator);
                    session.connect();

                    sftpChannel = (ChannelSftp) session.openChannel("sftp");
                    sftpChannel.connect();

                    // Проверяем, не занят ли генератор
                    String lockStatus = "0";
                    try (InputStream inputStream = sftpChannel.get("lockfile.txt");
                         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                        lockStatus = reader.readLine().trim();
                    } catch (SftpException e) {
                        // lockfile.txt может отсутствовать, считаем, что генератор свободен
                    }

                    if ("1".equals(lockStatus)) {
                        // Генератор уже занят
                        statusMap.put(generator.getName(), "Занят другим пользователем");
                        generatorOccupied = true;
                    } else {
                        // Занимаем генератор
                        try (OutputStream outputStream = sftpChannel.put("lockfile.txt");
                             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                            writer.write("1");
                        }

                        // Сохраняем имя пользователя
                        try (OutputStream occupierStream = sftpChannel.put("occupier.txt");
                             BufferedWriter occupierWriter = new BufferedWriter(new OutputStreamWriter(occupierStream))) {
                            occupierWriter.write(userName);
                        }

                        statusMap.put(generator.getName(), "Занят (" + userName + ")");
                    }
                } finally {
                    // Гарантируем закрытие ресурсов
                    if (sftpChannel != null && sftpChannel.isConnected()) {
                        sftpChannel.disconnect();
                    }
                    if (session != null && session.isConnected()) {
                        session.disconnect();
                    }
                }
            } // Конец синхронизированного блока

            // Задержка и обновление статусов вне синхронизированного блока
            Thread.sleep(1000);
            updateAllStatuses();

        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }

    private void releaseGeneratorAction(Generator generator) {
        try {
            Session session = null;
            ChannelSftp sftpChannel = null;

            // Критическая секция
            synchronized (generator) {
                try {
                    session = createSession(generator);
                    session.connect();

                    sftpChannel = (ChannelSftp) session.openChannel("sftp");
                    sftpChannel.connect();

                    // Устанавливаем lockfile.txt в '0'
                    try (OutputStream outputStream = sftpChannel.put("lockfile.txt");
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream))) {
                        writer.write("0");
                    }

                    // Удаляем файл с именем пользователя
                    try {
                        sftpChannel.rm("occupier.txt");
                    } catch (SftpException e) {
                        // Файл может отсутствовать
                        System.out.println("Файл occupier.txt отсутствует. Удалять нечего.");
                    }

                    // Определяем белый список
                    List<String> whitelist = Arrays.asList("whitelisted_file", "whitelisted_folder");

                    // Получаем домашнюю директорию пользователя
                    String homeDir = getHomeDirectory(session);

                    // Строим команду find для удаления файлов с определенными расширениями
                    StringBuilder findCommand = new StringBuilder("find " + homeDir);

                    // Ищем файлы с нужными расширениями
                    findCommand.append(" -type f \\( -name '*.jtl' -o -name '*.csv' -o -name '*.log' \\)");

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
                    ChannelExec channelExec = null;
                    try {
                        channelExec = (ChannelExec) session.openChannel("exec");
                        channelExec.setCommand(findCommand.toString());
                        InputStream in = channelExec.getInputStream();
                        channelExec.connect();

                        // Читаем вывод команды (при необходимости)
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                // Обработка вывода
                            }
                        }
                    } finally {
                        if (channelExec != null && channelExec.isConnected()) {
                            channelExec.disconnect();
                        }
                    }

                    statusMap.put(generator.getName(), "Свободен");

                } finally {
                    // Гарантируем закрытие ресурсов
                    if (sftpChannel != null && sftpChannel.isConnected()) {
                        sftpChannel.disconnect();
                    }
                    if (session != null && session.isConnected()) {
                        session.disconnect();
                    }
                }
            } // Конец синхронизированного блока

            // Задержка и обновление статусов вне синхронизированного блока
            Thread.sleep(1000);
            updateAllStatuses();

        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
        }
    }


    private String getHomeDirectory(Session session) throws JSchException, IOException {
        ChannelExec channelExec = null;
        try {
            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand("echo $HOME");
            InputStream in = channelExec.getInputStream();
            channelExec.connect();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                return reader.readLine().trim();
            }
        } finally {
            // Гарантируем закрытие ресурса
            if (channelExec != null && channelExec.isConnected()) {
                channelExec.disconnect();
            }
        }
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
