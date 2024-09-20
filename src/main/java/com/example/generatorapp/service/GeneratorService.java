// src/main/java/com/example/generatorapp/service/GeneratorService.java

package com.example.generatorapp.service;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.model.WhitelistEntry;
import com.example.generatorapp.repository.GeneratorRepository;
import com.example.generatorapp.repository.WhitelistEntryRepository;
import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Service
public class GeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(GeneratorService.class);

    @Autowired
    private GeneratorRepository generatorRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;


    public void updateGeneratorStatus(Generator generator) {
        logger.debug("Обновление статуса генератора '{}'", generator.getName());
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            InputStream inputStream = null;
            try {
                inputStream = sftpChannel.get("lockfile.txt");
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '0'
                    OutputStream outputStream = sftpChannel.put("lockfile.txt");
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
                    writer.write("0");
                    writer.close();
                    generator.setStatus("Свободен");
                    generatorRepository.save(generator);
                    sftpChannel.disconnect();
                    session.disconnect();
                    return;
                } else {
                    throw e;
                }
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            String content = reader.readLine().trim();
            if ("1".equals(content)) {
                generator.setStatus("Занят");
            } else {
                generator.setStatus("Свободен");
            }
            generatorRepository.save(generator);
            reader.close();

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            logger.error("Ошибка при обновлении статуса генератора '{}': {}", generator.getName(), e.getMessage());
            generator.setStatus("Ошибка: " + e.getMessage());
            generatorRepository.save(generator);
        }
    }

    public void occupyGenerator(Generator generator) {
        logger.info("Попытка занять генератор '{}'", generator.getName());
        generator.setStatus("Занят");
        generatorRepository.save(generator);
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            OutputStream outputStream;
            try {
                sftpChannel.lstat("lockfile.txt");
                outputStream = sftpChannel.put("lockfile.txt");
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '1'
                    outputStream = sftpChannel.put("lockfile.txt");
                } else {
                    throw e;
                }
            }

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write("1");
            writer.close();

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            logger.error("Ошибка при занятии генератора '{}': {}", generator.getName(), e.getMessage());
            generator.setStatus("Ошибка: " + e.getMessage());
            generatorRepository.save(generator);
        }
    }

    public void releaseGenerator(Generator generator) {
        logger.info("Попытка освободить генератор '{}'", generator.getName());
        generator.setStatus("Свободен");
        generatorRepository.save(generator);
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            OutputStream outputStream;
            try {
                sftpChannel.lstat("lockfile.txt");
                outputStream = sftpChannel.put("lockfile.txt");
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    // Файл не найден, создаём его и записываем '0'
                    outputStream = sftpChannel.put("lockfile.txt");
                } else {
                    throw e;
                }
            }

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream));
            writer.write("0");
            writer.close();

            // Удаление файлов и папок, не входящих в белый список
            List<WhitelistEntry> whitelist = whitelistEntryRepository.findAll();
            Set<String> whitelistPaths = new HashSet<>();
            for (WhitelistEntry entry : whitelist) {
                whitelistPaths.add(entry.getPath());
            }

            Vector<ChannelSftp.LsEntry> files = sftpChannel.ls(".");
            for (ChannelSftp.LsEntry entry : files) {
                String filename = entry.getFilename();

                if (filename.startsWith(".")) {
                    continue;
                }

                if (whitelistPaths.contains(filename)) {
                    continue;
                }

                if (entry.getAttrs().isDir()) {
                    deleteDirectory(sftpChannel, filename);
                } else {
                    sftpChannel.rm(filename);
                }
            }

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            logger.error("Ошибка при освобождении генератора '{}': {}", generator.getName(), e.getMessage());
            generator.setStatus("Ошибка: " + e.getMessage());
            generatorRepository.save(generator);
        }
    }

    public void refreshStatuses() {
        List<Generator> generators = generatorRepository.findAll();
        for (Generator generator : generators) {
            updateGeneratorStatus(generator);
        }
    }

    public void initializeStatuses() {
        refreshStatuses();
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

        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        return session;
    }


    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationEvent(ContextRefreshedEvent event) {
        initializeStatuses();
    }
}
