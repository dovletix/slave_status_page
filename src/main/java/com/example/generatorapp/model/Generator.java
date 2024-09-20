package com.example.generatorapp.model;

import javax.persistence.*;

@Entity
public class Generator {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String address;
    private String username;
    private String password;

    // Конструкторы
    public Generator() {
    }

    public Generator(String name, String address, String username, String password) {
        this.name = name;
        this.address = address;
        this.username = username;
        this.password = password;
    }

    // Геттеры и сеттеры

    public Long getId() {
        return id;
    }

    // Если вы не хотите предоставлять сеттер для 'id' (обычно для идентификаторов он не нужен), можно его не добавлять.

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    // Если вы беспокоитесь о безопасности, можете не предоставлять геттер для 'password'.

    public void setPassword(String password) {
        this.password = password;
    }
}
