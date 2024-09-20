package com.example.generatorapp.model;

import javax.persistence.*;

@Entity
public class WhitelistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String path;

    // Конструкторы
    public WhitelistEntry() {
    }

    public WhitelistEntry(String path) {
        this.path = path;
    }

    // Геттеры и сеттеры

    public Long getId() {
        return id;
    }

    // Сеттер для 'id' обычно не нужен

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
