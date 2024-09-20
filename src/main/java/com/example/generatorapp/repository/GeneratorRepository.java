// src/main/java/com/example/generatorapp/repository/GeneratorRepository.java

package com.example.generatorapp.repository;

import com.example.generatorapp.model.Generator;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GeneratorRepository extends JpaRepository<Generator, Long> {
}
