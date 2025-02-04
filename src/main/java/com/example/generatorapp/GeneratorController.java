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

    // Карта описания причины занятия генератора
    private Map<String, String> descriptionMap = new ConcurrentHashMap<>();

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
        model.addAttribute("descriptionMap", descriptionMap);
        return "index";
    }

    @PostMapping("/occupy/{generatorId}")
    public String occupyGenerator(@PathVariable Long generatorId, @RequestParam("userName") String userName, @RequestParam("description") String description) {
        Generator generator = generatorRepository.findById(generatorId).orElse(null);
        if (generator != null) {
            occupyGeneratorAction(generator, userName, description); // Выполняем синхронно
        }
        return "redirect:/";
    }

    @PostMapping("/release/{generatorId}")
    public String releaseGenerator(@PathVariable Long generatorId) {
        Generator generator = generatorRepository.findById(generatorId).orElse(null);
        if (generator != null) {
            releaseGeneratorAction(generator); // Выполняем синхронно
        }
        return "redirect:/";
    }

    @PostMapping("/updateStatuses")
    public String updateStatuses() {
        updateAllStatuses(); // Выполняем синхронно
        return "redirect:/";
    }

    @GetMapping("/addGenerator")
    public String addGeneratorForm(Model model) {
        model.addAttribute("generator", new Generator());
        return "addGenerator";
    }

    @PostMapping("/addGenerator")
    public String addGenerator(@ModelAttribute Generator generator) {
        generatorRepository.save(generator);
        updateAllStatuses(); // Обновляем статусы после добавления генератора
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
            updateGeneratorStatus(generator); // Выполняем синхронно
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


            String descritionField;
            try (InputStream inputStream = sftpChannel.get("lockfile.txt");
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String content = reader.readLine().trim();
                if ("1".equals(content)) {
                    // Читаем имя пользователя
                    String descriptionName = "Неизвестно";
                    try (InputStream descriptionStream = sftpChannel.get("description.txt");
                         BufferedReader descriptionReader = new BufferedReader(new InputStreamReader(descriptionStream))) {
                        descriptionName = descriptionReader.readLine().trim();
                    } catch (SftpException e) {
                        // Файл description.txt может отсутствовать
                        System.out.println("Файл description.txt может отсутствует. Неизвестна причина занятия генератора.");
                    }
                    descritionField = descriptionName;
                } else {
                    descritionField = "-";
                }
            } catch (SftpException e) {
                descritionField = "lockfile.txt не найден";
            }

            statusMap.put(generator.getName(), status);
            descriptionMap.put(generator.getDescription(), descritionField);

        } catch (Exception e) {
            statusMap.put(generator.getName(), "Ошибка: " + e.getMessage());
            descriptionMap.put(generator.getDescription(), "Ошибка: " + e.getMessage());
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


    private void occupyGeneratorAction(Generator generator, String userName, String description) {
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
                        descriptionMap.put(generator.getDescription(), "Занят другим пользователем");
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

                        // Сохраняем причину занятие генератора
                        try (OutputStream descriptionStream = sftpChannel.put("description.txt");
                             BufferedWriter descriptionWriter = new BufferedWriter(new OutputStreamWriter(descriptionStream))) {
                            descriptionWriter.write(description);
                        }

                        statusMap.put(generator.getName(), "Занят (" + userName + ")");
                        descriptionMap.put(generator.getDescription(), description);
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
            descriptionMap.put(generator.getDescription(), "Ошибка: " + e.getMessage());
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
                    }

                    // Удаляем файл с описанием причины занятия генератора
                    try {
                        sftpChannel.rm("description.txt");
                    } catch (SftpException e) {
                        // Файл может отсутствовать
                    }


                    // Выполняем дополнительные команды
                    executeRemoteCommand(session, "sh $HOME/apa/bin/stoptest.sh");
                    executeRemoteCommand(session, "pkill jmeter");

                    // Определяем белый список
                    List<String> whitelist = Arrays.asList("lockfile.txt", "$HOME/apa/bin");

                    // Получаем домашнюю директорию пользователя
                    String homeDir = getHomeDirectory(session);

                    // Строим команду find для удаления файлов с определенными расширениями
                    StringBuilder findCommand = new StringBuilder("find " + homeDir);

                    // Ищем файлы с нужными расширениями и файл nohup.out
                    findCommand.append(" -type f \\( -name '*.jmx' -o -name '*.jtl' -o -name '*.csv' -o -name '*.log' -o -name '*.sh' -o -name 'nohup.out' \\)");

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
                    executeRemoteCommand(session, findCommand.toString());

                    statusMap.put(generator.getName(), "Свободен");
                    descriptionMap.put(generator.getName(), "-");

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
            descriptionMap.put(generator.getDescription(), "Ошибка: " + e.getMessage());
        }
    }

    private void executeRemoteCommand(Session session, String command) throws JSchException, IOException {
        ChannelExec channelExec = null;
        try {
            channelExec = (ChannelExec) session.openChannel("exec");
            channelExec.setCommand(command);
            channelExec.setErrStream(System.err);
            InputStream in = channelExec.getInputStream();
            channelExec.connect();

            // Читаем вывод команды (при необходимости)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Обработка вывода (если требуется)
                }
            }
        } finally {
            if (channelExec != null && channelExec.isConnected()) {
                channelExec.disconnect();
            }
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
