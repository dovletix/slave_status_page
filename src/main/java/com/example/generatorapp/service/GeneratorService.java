// src/main/java/com/example/generatorapp/service/GeneratorService.java

package com.example.generatorapp.service;

import com.example.generatorapp.model.Generator;
import com.example.generatorapp.model.WhitelistEntry;
import com.example.generatorapp.repository.WhitelistEntryRepository;
import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class GeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(GeneratorService.class);

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    private Map<Long, String> statusMap = new ConcurrentHashMap<>();

    public Map<Long, String> getStatusMap() {
        return statusMap;
    }

    public void updateAllStatuses(List<Generator> generators) {
        for (Generator generator : generators) {
            new Thread(() -> updateGeneratorStatus(generator)).start();
        }
    }

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
                    statusMap.put(generator.getId(), "Свободен");
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
                statusMap.put(generator.getId(), "Занят");
            } else {
                statusMap.put(generator.getId(), "Свободен");
            }
            reader.close();

            sftpChannel.disconnect();
            session.disconnect();
        } catch (Exception e) {
            logger.error("Ошибка при обновлении статуса генератора '{}': {}", generator.getName(), e.getMessage());
            statusMap.put(generator.getId(), "Ошибка: " + e.getMessage());
        }
    }

    public void occupyGenerator(Generator generator) {
        logger.info("Попытка занять генератор '{}'", generator.getName());
        statusMap.put(generator.getId(), "Занят");
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
        } catch (Exception e) {
            logger.error("Ошибка при занятии генератора '{}': {}", generator.getName(), e.getMessage());
            statusMap.put(generator.getId(), "Ошибка: " + e.getMessage());
        }
    }

    public void releaseGenerator(Generator generator) {
        logger.info("Попытка освободить генератор '{}'", generator.getName());
        statusMap.put(generator.getId(), "Свободен");
        try {
            Session session = createSession(generator);
            session.connect();

            ChannelSftp sftpChannel = (ChannelSftp) session.openChannel("sftp");
            sftpChannel.connect();

            OutputStream outputStream = sftpChannel.put("lockfile.txt");
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
            statusMap.put(generator.getId(), "Ошибка: " + e.getMessage());
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

        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        return session;
    }
}
