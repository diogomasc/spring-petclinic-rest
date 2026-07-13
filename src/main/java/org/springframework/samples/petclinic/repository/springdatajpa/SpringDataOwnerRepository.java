/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.repository.springdatajpa;

import java.util.Collection;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.repository.OwnerRepository;

/**
 * Spring Data JPA specialization of the {@link OwnerRepository} interface.
 *
 * <p>Todas as queries que retornam {@link Owner} usam {@code LEFT JOIN FETCH owner.pets}
 * para evitar o problema N+1: sem o fetch explícito, o Hibernate emite uma query
 * extra por owner ao acessar {@code owner.getPets()} durante a serialização JSON,
 * dado que {@code Owner.pets} é mapeado como {@code FetchType.EAGER}.
 *
 * <p>Referência: Spring Data JPA — <em>Ad-hoc Entity Graphs</em> e JPQL JOIN FETCH
 * (Spring Data JPA Reference, seção "JPA Query Methods").
 *
 * @author Michael Isvy
 * @since 15.1.2013
 */
@Profile("spring-data-jpa")
public interface SpringDataOwnerRepository extends OwnerRepository, Repository<Owner, Integer> {

    /**
     * Busca owners por sobrenome com pets pré-carregados via JOIN FETCH.
     * Usa DISTINCT para evitar duplicatas oriundas do produto cartesiano Owner × Pet.
     */
    @Override
    @Query("SELECT DISTINCT owner FROM Owner owner LEFT JOIN FETCH owner.pets WHERE owner.lastName LIKE :lastName%")
    Collection<Owner> findByLastName(@Param("lastName") String lastName);

    /**
     * Busca paginada de owners por sobrenome.
     * {@code @EntityGraph} aplica o fetch de pets via JPA EntityGraph
     * sem interferir na countQuery separada — padrão recomendado pela documentação
     * Spring Data JPA para queries paginadas com JOIN FETCH.
     */
    @Override
    @EntityGraph(attributePaths = {"pets"})
    @Query(
        value = "SELECT owner FROM Owner owner WHERE owner.lastName LIKE CONCAT(:lastName, '%')",
        countQuery = "SELECT COUNT(owner) FROM Owner owner WHERE owner.lastName LIKE CONCAT(:lastName, '%')")
    Page<Owner> findByLastName(@Param("lastName") String lastName, Pageable pageable);

    /**
     * Busca paginada de todos os owners.
     * {@code @EntityGraph} garante que os pets sejam carregados em uma única query,
     * evitando N+1 implícito para {@code FetchType.EAGER} sob paginação.
     */
    @Override
    @EntityGraph(attributePaths = {"pets"})
    @Query(
        value = "SELECT owner FROM Owner owner",
        countQuery = "SELECT COUNT(owner) FROM Owner owner")
    Page<Owner> findAll(Pageable pageable);

    /**
     * Busca owner por ID com pets pré-carregados via JOIN FETCH.
     */
    @Override
    @Query("SELECT owner FROM Owner owner LEFT JOIN FETCH owner.pets WHERE owner.id =:id")
    Owner findById(@Param("id") int id);

    /**
     * Retorna todos os owners com pets pré-carregados em uma única query SQL.
     *
     * <p>Usa {@code SELECT DISTINCT} + {@code LEFT JOIN FETCH} para consolidar o
     * carregamento de Owner e seus Pets em um único round-trip ao banco — equivalente
     * a um {@code JOIN} otimizado pelo planner do H2/PostgreSQL. Sem esse fetch,
     * o Hibernate emitiria N queries adicionais (uma por owner) ao acessar
     * {@code owner.getPets()} com {@code FetchType.EAGER} durante a serialização.
     */
    @Override
    @Query("SELECT DISTINCT owner FROM Owner owner LEFT JOIN FETCH owner.pets")
    Collection<Owner> findAll();
}
