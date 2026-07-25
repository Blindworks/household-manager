package com.household.manager.repository;

import com.household.manager.tractive.TractiveAuth;
import org.springframework.data.jpa.repository.JpaRepository;

/** Zugriff auf das einzige Tractive-Token (id = {@link TractiveAuth#SINGLETON_ID}). */
public interface TractiveAuthRepository extends JpaRepository<TractiveAuth, Long> {
}
