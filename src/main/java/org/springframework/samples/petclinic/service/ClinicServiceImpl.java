/*
 * Copyright 2002-2017 the original author or authors.
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
package org.springframework.samples.petclinic.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectRetrievalFailureException;
import org.springframework.samples.petclinic.model.*;
import org.springframework.samples.petclinic.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.observation.annotation.Observed;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Implementação do contrato {@link ClinicService} via JPA.
 *
 * <p>Atua como <b>Facade</b> (Gamma et al., 1994) sobre os seis repositórios do
 * domínio, garantindo que Controllers e demais clientes interajam apenas com
 * esta interface coesa. Toda lógica de negócio e orquestração de transações
 * reside aqui — conforme o princípio SRP (Martin, 2003).
 *
 * <p><b>Refatorações aplicadas (branch refactoring-metrics-pos-refactoring):</b>
 * <ul>
 *   <li>N+1 Queries removidas de {@link #findAllOwners()} — delegado ao ORM;</li>
 *   <li>Consulta redundante {@code petTypeRepository.findAll()} removida de {@link #savePet(Pet)};</li>
 *   <li>{@code validatePhoneFormat()} e {@code formatOwnerDisplayName()} removidos
 *       — responsabilidade delegada a {@code @Pattern} em {@code Owner.java} e ao DTO;</li>
 *   <li>{@code ownerHitCount} removido — analytics de acesso pertencem a serviço dedicado (SRP).</li>
 * </ul>
 */
@Service
public class ClinicServiceImpl implements ClinicService {

    private final PetRepository petRepository;
    private final VetRepository vetRepository;
    private final OwnerRepository ownerRepository;
    private final VisitRepository visitRepository;
    private final SpecialtyRepository specialtyRepository;
    private final PetTypeRepository petTypeRepository;

    public ClinicServiceImpl(
            PetRepository petRepository,
            VetRepository vetRepository,
            OwnerRepository ownerRepository,
            VisitRepository visitRepository,
            SpecialtyRepository specialtyRepository,
            PetTypeRepository petTypeRepository) {
        this.petRepository = petRepository;
        this.vetRepository = vetRepository;
        this.ownerRepository = ownerRepository;
        this.visitRepository = visitRepository;
        this.specialtyRepository = specialtyRepository;
        this.petTypeRepository = petTypeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Pet> findAllPets() throws DataAccessException {
        return petRepository.findAll();
    }

    @Override
    @Transactional
    public void deletePet(Pet pet) throws DataAccessException {
        petRepository.delete(pet);
    }

    @Override
    @Transactional(readOnly = true)
    public Visit findVisitById(int visitId) throws DataAccessException {
        return findEntityById(() -> visitRepository.findById(visitId));
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Visit> findAllVisits() throws DataAccessException {
        return visitRepository.findAll();
    }

    @Override
    @Transactional
    public void deleteVisit(Visit visit) throws DataAccessException {
        visitRepository.delete(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public Vet findVetById(int id) throws DataAccessException {
        return findEntityById(() -> vetRepository.findById(id));
    }

    /**
     * Lista todos os veterinários com especialidades via relação N:M EAGER (Vet ↔ Specialty).
     *
     * <p><b>Exercitado por:</b> {@code GET /api/vets} (grupo k6 "GET /vets").
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Vet_FindAll"}}.
     *
     * <p><b>Cache:</b> resultado armazenado em {@code "vets"} após a primeira chamada —
     * padrão Cache-Aside. Vets são dados de referência que não mudam durante uma
     * sessão de testes k6, tornando {@code GET /vets} O(1) após o warm-up.
     * Invalidado em {@link #saveVet} e {@link #deleteVet}.
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable("vets")
    @Observed(name = "metodo.execucao", contextualName = "Service_Vet_FindAll")
    public Collection<Vet> findAllVets() throws DataAccessException {
        return vetRepository.findAll();
    }

    @Override
    @Transactional
    @CacheEvict(value = "vets", allEntries = true)
    public void saveVet(Vet vet) throws DataAccessException {
        vetRepository.save(vet);
    }

    @Override
    @Transactional
    @CacheEvict(value = "vets", allEntries = true)
    public void deleteVet(Vet vet) throws DataAccessException {
        vetRepository.delete(vet);
    }

    /**
     * Lista todos os donos delegando integralmente ao repositório.
     *
     * <p><b>Exercitado por:</b> {@code GET /api/owners} (grupo k6 "GET /owners").
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Owner_FindAll"}}.
     *
     * <p><b>Refatoração (N+1 removido):</b> a versão degradada executava
     * {@code visitRepository.findByPetId()} para cada pet de cada owner, resultando
     * em O(N×P) queries extras. O ORM (JPA/Hibernate) gerencia o carregamento
     * via {@code FetchType} configurado no mapeamento da entidade — 1 query total.
     */
    @Override
    @Transactional(readOnly = true)
    @Observed(name = "metodo.execucao", contextualName = "Service_Owner_FindAll")
    public Collection<Owner> findAllOwners() throws DataAccessException {
        return ownerRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Owner> findOwners(String lastName, Pageable pageable) throws DataAccessException {
        if (lastName != null) {
            return ownerRepository.findByLastName(lastName, pageable);
        }
        return ownerRepository.findAll(pageable);
    }

    @Override
    @Transactional
    public void deleteOwner(Owner owner) throws DataAccessException {
        ownerRepository.delete(owner);
    }

    /**
     * Busca o tipo de pet por ID — chamado internamente por {@link #savePet(Pet)}.
     *
     * <p><b>Exercitado por:</b> {@code POST /api/owners/{id}/pets} (cadeia interna via savePet).
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_PetType_FindById"}}.
     *
     * <p><b>Cache:</b> resultado armazenado em {@code "petTypes"} com chave {@code petTypeId}.
     * PetTypes são dados de referência estáticos — cat, dog, bird, hamster, etc.
     * Elimina o lookup ao banco no hot path de cada {@code POST /pets} sob carga k6.
     * Invalidado em {@link #savePetType} e {@link #deletePetType}.
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "petTypes", key = "#petTypeId")
    @Observed(name = "metodo.execucao", contextualName = "Service_PetType_FindById")
    public PetType findPetTypeById(int petTypeId) {
        return findEntityById(() -> petTypeRepository.findById(petTypeId));
    }

    /**
     * Lista todos os tipos de pet.
     *
     * <p><b>Cache:</b> resultado armazenado em {@code "petTypes"} com chave especial
     * {@code 'all'} — evita colisão com entradas individuais por ID.
     * Invalidado em {@link #savePetType} e {@link #deletePetType}.
     */
    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "petTypes", key = "'all'")
    public Collection<PetType> findAllPetTypes() throws DataAccessException {
        return petTypeRepository.findAll();
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "petTypes", allEntries = true)
    })
    public void savePetType(PetType petType) throws DataAccessException {
        petTypeRepository.save(petType);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "petTypes", allEntries = true)
    })
    public void deletePetType(PetType petType) throws DataAccessException {
        petTypeRepository.delete(petType);
    }

    @Override
    @Transactional(readOnly = true)
    public Specialty findSpecialtyById(int specialtyId) {
        return findEntityById(() -> specialtyRepository.findById(specialtyId));
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Specialty> findAllSpecialties() throws DataAccessException {
        return specialtyRepository.findAll();
    }

    @Override
    @Transactional
    public void saveSpecialty(Specialty specialty) throws DataAccessException {
        specialtyRepository.save(specialty);
    }

    @Override
    @Transactional
    public void deleteSpecialty(Specialty specialty) throws DataAccessException {
        specialtyRepository.delete(specialty);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<PetType> findPetTypes() throws DataAccessException {
        return petRepository.findPetTypes();
    }

    /**
     * Busca dono por ID retornando o grafo completo Owner + pets aninhados + visits.
     *
     * <p><b>Exercitado por:</b>
     * <ul>
     *   <li>{@code GET /api/owners/{id}} (grupo k6 "GET /owners/{ownerId}");</li>
     *   <li>{@code POST /api/owners/{id}/pets} (lookup interno antes de savePet).</li>
     * </ul>
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Owner_FindById"}}.
     */
    @Override
    @Transactional(readOnly = true)
    @Observed(name = "metodo.execucao", contextualName = "Service_Owner_FindById")
    public Owner findOwnerById(int id) throws DataAccessException {
        return findEntityById(() -> ownerRepository.findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Pet findPetById(int id) throws DataAccessException {
        return findEntityById(() -> petRepository.findById(id));
    }

    /**
     * Persiste um pet resolvendo o PetType por ID antes do flush JPA.
     *
     * <p><b>Exercitado por:</b> {@code POST /api/owners/{id}/pets} (grupo k6 "POST /owners/{ownerId}/pets").
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Pet_Save"}}.
     *
     * <p><b>Refatoração (consulta redundante removida):</b> a versão degradada executava
     * {@code petTypeRepository.findAll()} para validar a existência do tipo — query
     * completamente redundante, pois ambos os ramos do {@code if/else} invocavam
     * {@code findPetTypeById()} de qualquer forma. O método agora executa exatamente
     * 2 operações: 1 lookup pontual de PetType + 1 INSERT.
     */
    @Override
    @Transactional
    @Observed(name = "metodo.execucao", contextualName = "Service_Pet_Save")
    public void savePet(Pet pet) throws DataAccessException {
        pet.setType(findPetTypeById(pet.getType().getId()));
        petRepository.save(pet);
    }

    /**
     * Persiste uma visita na tabela filha associada ao pet via FK.
     *
     * <p><b>Exercitado por:</b> {@code POST /api/owners/{id}/pets/{petId}/visits}
     * (grupo k6 "POST /owners/{ownerId}/pets/{petId}/visits").
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Visit_Save"}}.
     */
    @Override
    @Transactional
    @Observed(name = "metodo.execucao", contextualName = "Service_Visit_Save")
    public void saveVisit(Visit visit) throws DataAccessException {
        visitRepository.save(visit);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Vet> findVets() throws DataAccessException {
        return vetRepository.findAll();
    }

    /**
     * Persiste um dono via JPA (INSERT ou UPDATE).
     *
     * <p><b>Exercitado por:</b> {@code POST /api/owners} (grupo k6 "POST /owners").
     * <p><b>Span Prometheus:</b> {@code metodo_execucao_seconds{observation_contextualName="Service_Owner_Save"}}.
     */
    @Override
    @Transactional
    @Observed(name = "metodo.execucao", contextualName = "Service_Owner_Save")
    public void saveOwner(Owner owner) throws DataAccessException {
        ownerRepository.save(owner);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Owner> findOwnerByLastName(String lastName) throws DataAccessException {
        return ownerRepository.findByLastName(lastName);
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<Visit> findVisitsByPetId(int petId) {
        return visitRepository.findByPetId(petId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Specialty> findSpecialtiesByNameIn(Set<String> names) {
        return findEntityById(() -> specialtyRepository.findSpecialtiesByNameIn(names));
    }

    private <T> T findEntityById(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (ObjectRetrievalFailureException | EmptyResultDataAccessException e) {
            // Just ignore not found exceptions for Jdbc/Jpa realization
            return null;
        }
    }

}
