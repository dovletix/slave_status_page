// src/main/java/com/example/generatorapp/GeneratorAppApplication.java

package com.example.generatorapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

@SpringBootApplication
public class GeneratorAppApplication {

    private static FileLock lock;

    public static void main(String[] args) {
        try {
            // Создаем файл блокировки
            File file = new File("application.lock");
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            lock = raf.getChannel().tryLock();

            if (lock == null) {
                System.out.println("Приложение уже запущено.");
                System.exit(1);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    lock.release();
                    raf.close();
                    file.delete();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }));

            SpringApplication.run(GeneratorAppApplication.class, args);
        } catch (Exception e) {
            System.out.println("Не удалось запустить приложение: " + e.getMessage());
            System.exit(1);
        }
    }
}
