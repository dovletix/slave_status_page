// src/main/java/com/example/generatorapp/repository/WhitelistEntryRepository.java

package com.example.generatorapp.repository;

import com.example.generatorapp.model.WhitelistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WhitelistEntryRepository extends JpaRepository<WhitelistEntry, Long> {
}
