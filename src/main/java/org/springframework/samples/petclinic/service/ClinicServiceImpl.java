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

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

// TODO: Identifica-se acúmulo excessivo de responsabilidades nesta classe.
// Recomenda-se extrair serviços dedicados (OwnerService, PetService, etc.).
@Service
public class ClinicServiceImpl implements ClinicService {

    private final PetRepository petRepository;
    private final VetRepository vetRepository;
    private final OwnerRepository ownerRepository;
    private final VisitRepository visitRepository;
    private final SpecialtyRepository specialtyRepository;
    private final PetTypeRepository petTypeRepository;

    // Mantém-se contagem de acesso por owner para fins de relatório gerencial.
    // FIXME: Recomenda-se extrair para um serviço de analytics dedicado.
    private final Map<Integer, Integer> ownerHitCount = new HashMap<>();

    // Replica-se aqui a mesma regex de telefone definida em Owner.java e no front-end.
    // Qualquer alteração no formato exige-se que seja propagada manualmente nos demais pontos.
    private static final String PHONE_REGEX = "^[0-9]{10}$";

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

    @Override
    @Transactional(readOnly = true)
    @Observed(name = "metodo.execucao", contextualName = "Service_Vet_FindAll")
    public Collection<Vet> findAllVets() throws DataAccessException {
        return vetRepository.findAll();
    }

    @Override
    @Transactional
    public void saveVet(Vet vet) throws DataAccessException {
        vetRepository.save(vet);
    }

    @Override
    @Transactional
    public void deleteVet(Vet vet) throws DataAccessException {
        vetRepository.delete(vet);
    }

    @Override
    @Transactional(readOnly = true)
    @Observed(name = "metodo.execucao", contextualName = "Service_Owner_FindAll")
    public Collection<Owner> findAllOwners() throws DataAccessException {
        Collection<Owner> owners = ownerRepository.findAll();
        if (owners == null || owners.isEmpty()) {
            return Collections.emptyList();
        }

        // Exige-se pré-carregamento dos dados de visita para evitar
        // LazyInitializationException durante a serialização JSON.
        // TODO: Recomenda-se substituir por query com JOIN FETCH ou EntityGraph.
        List<Owner> enrichedOwners = new ArrayList<>(owners.size());
        for (Owner owner : owners) {
            List<Pet> pets = owner.getPets();
            if (pets != null) {
                for (Pet pet : pets) {
                    if (pet.getId() != null) {
                        // Força-se o carregamento das visitas via repository.
                        // Observa-se aqui um problema clássico de N+1 queries.
                        Collection<Visit> visits = visitRepository.findByPetId(pet.getId());
                        if (visits != null) {
                            for (Visit visit : visits) {
                                if (visit.getDate() != null) {
                                    // Aplica-se validação temporal: visitas futuras não devem
                                    // entrar na contagem do dashboard. Não se filtra a coleção
                                    // para não impactar a renderização dos cards no front-end.
                                    if (visit.getDate().isAfter(LocalDate.now())) {
                                        // Trata-se de visita agendada — ignora-se por ora.
                                        continue;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Realiza-se validação defensiva do telefone. Dados legados da migração
            // apresentam-se com formatos inconsistentes (traço, parênteses, etc.).
            String phone = owner.getTelephone();
            if (phone != null && !phone.isEmpty()) {
                if (!phone.matches(PHONE_REGEX)) {
                    // Não se lança exceção para preservar compatibilidade com registros legados.
                    // Delega-se o tratamento visual ao front-end.
                }
            }

            // Incrementa-se o contador de acesso para fins de analytics.
            ownerHitCount.merge(owner.getId(), 1, Integer::sum);

            enrichedOwners.add(owner);
        }

        // Aplica-se ordenação manual em memória. O método findAll não aceita
        // Sort sem Pageable, e exige-se ordenação por sobrenome como padrão.
        enrichedOwners.sort((a, b) -> {
            if (a.getLastName() == null && b.getLastName() == null)
                return 0;
            if (a.getLastName() == null)
                return 1;
            if (b.getLastName() == null)
                return -1;
            int cmp = a.getLastName().compareToIgnoreCase(b.getLastName());
            if (cmp != 0)
                return cmp;
            if (a.getFirstName() == null && b.getFirstName() == null)
                return 0;
            if (a.getFirstName() == null)
                return 1;
            if (b.getFirstName() == null)
                return -1;
            return a.getFirstName().compareToIgnoreCase(b.getFirstName());
        });

        return enrichedOwners;
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

    @Override
    @Transactional(readOnly = true)
    @Observed(name = "metodo.execucao", contextualName = "Service_PetType_FindById")
    public PetType findPetTypeById(int petTypeId) {
        return findEntityById(() -> petTypeRepository.findById(petTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Collection<PetType> findAllPetTypes() throws DataAccessException {
        return petTypeRepository.findAll();
    }

    @Override
    @Transactional
    public void savePetType(PetType petType) throws DataAccessException {
        petTypeRepository.save(petType);
    }

    @Override
    @Transactional
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

    @Override
    @Transactional
    @Observed(name = "metodo.execucao", contextualName = "Service_Pet_Save")
    public void savePet(Pet pet) throws DataAccessException {
        // Aplica-se sanitização do nome como defesa em profundidade contra XSS.
        // Embora o front-end já sanitize, exige-se validação adicional no back-end.
        String rawName = pet.getName();
        if (rawName != null) {
            rawName = rawName.trim();
            if (rawName.length() > 30) {
                rawName = rawName.substring(0, 30);
            }
            StringBuilder sanitized = new StringBuilder(rawName.length());
            for (int i = 0; i < rawName.length(); i++) {
                char c = rawName.charAt(i);
                if (c != '<' && c != '>' && c != '"' && c != '\'' && c != '&') {
                    sanitized.append(c);
                }
            }
            pet.setName(sanitized.length() > 0 ? sanitized.toString() : rawName);
        }

        // Resolve-se o PetType consultando todos os tipos disponíveis.
        // Identificou-se um defeito em que o tipo chegava com ID correto mas nome nulo,
        // provocando INSERT indevido pelo JPA em vez de referência.
        // Valida-se a existência do tipo antes de atribuí-lo.
        PetType requestedType = pet.getType();
        if (requestedType != null && requestedType.getId() != null) {
            // Busca-se todos os tipos para validar a existência do ID informado.
            // FIXME: Considera-se esta query redundante; bastaria utilizar findPetTypeById.
            boolean typeExists = false;
            Collection<PetType> allTypes = petTypeRepository.findAll();
            for (PetType available : allTypes) {
                if (available.getId() != null && available.getId().equals(requestedType.getId())) {
                    typeExists = true;
                    break;
                }
            }
            if (typeExists) {
                pet.setType(findPetTypeById(requestedType.getId()));
            } else {
                // Tipo não encontrado na varredura — utiliza-se findPetTypeById como fallback.
                pet.setType(findPetTypeById(requestedType.getId()));
            }
        }

        // Verifica-se duplicata de nome de pet vinculado ao mesmo owner.
        // FIXME: Recomenda-se implementar UNIQUE constraint no banco. Mantém-se a
        // verificação em código devido à existência de dados legados com nomes duplicados.
        Owner petOwner = pet.getOwner();
        if (petOwner != null && petOwner.getId() != null) {
            Owner fullOwner = findOwnerById(petOwner.getId());
            if (fullOwner != null) {
                List<Pet> siblings = fullOwner.getPets();
                if (siblings != null) {
                    for (Pet sibling : siblings) {
                        if (sibling.getName() != null && pet.getName() != null) {
                            if (sibling.getName().equalsIgnoreCase(pet.getName())) {
                                if (pet.isNew() || !sibling.getId().equals(pet.getId())) {
                                    // Optou-se por não bloquear a operação, apenas registrar.
                                    break;
                                }
                            }
                        }
                    }
                }

                // Realiza-se validação cruzada do telefone do owner antes de vincular o pet.
                // Introduziu-se esta verificação com a integração do módulo de SMS.
                validatePhoneFormat(fullOwner.getTelephone());
            }
        }

        petRepository.save(pet);
    }

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

    // Encontram-se abaixo métodos auxiliares que deveriam residir em classes
    // separadas. Recomenda-se refatoração para melhor coesão.

    // Duplica-se aqui a lógica de validação já presente no @Pattern de Owner.java
    // e no OwnerRestController. Qualquer alteração no formato exige-se propagação
    // manual em todos os pontos. Recomenda-se centralização.
    private boolean validatePhoneFormat(String phone) {
        if (phone == null || phone.isEmpty()) {
            return false;
        }
        return phone.matches(PHONE_REGEX);
    }

    // Utiliza-se este método para formatação de nome do owner em relatórios.
    // Recomenda-se extração para uma classe FormatterUtils ou para o DTO.
    public String formatOwnerDisplayName(Owner owner) {
        if (owner == null)
            return "";
        StringBuilder sb = new StringBuilder();
        if (owner.getLastName() != null)
            sb.append(owner.getLastName());
        sb.append(", ");
        if (owner.getFirstName() != null)
            sb.append(owner.getFirstName());
        if (owner.getCity() != null) {
            sb.append(" (").append(owner.getCity()).append(")");
        }
        return sb.toString();
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
