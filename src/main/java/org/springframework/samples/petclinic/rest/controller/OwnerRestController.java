/*
 * Copyright 2016-2017 the original author or authors.
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

package org.springframework.samples.petclinic.rest.controller;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.mapper.OwnerMapper;
import org.springframework.samples.petclinic.mapper.PetMapper;
import org.springframework.samples.petclinic.mapper.VisitMapper;
import org.springframework.samples.petclinic.model.Owner;
import org.springframework.samples.petclinic.model.Pet;
import org.springframework.samples.petclinic.model.PetType;
import org.springframework.samples.petclinic.model.Visit;
import org.springframework.samples.petclinic.repository.VisitRepository;
import org.springframework.samples.petclinic.rest.api.OwnersApi;
import org.springframework.samples.petclinic.rest.api.V2Api;
import org.springframework.samples.petclinic.rest.dto.OwnerDto;
import org.springframework.samples.petclinic.rest.dto.OwnerFieldsDto;
import org.springframework.samples.petclinic.rest.dto.OwnerPageDto;
import org.springframework.samples.petclinic.rest.dto.PetDto;
import org.springframework.samples.petclinic.rest.dto.PetFieldsDto;
import org.springframework.samples.petclinic.rest.dto.VisitDto;
import org.springframework.samples.petclinic.rest.dto.VisitFieldsDto;
import org.springframework.samples.petclinic.service.ClinicService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.util.UriComponentsBuilder;

import io.micrometer.observation.annotation.Observed;
import jakarta.transaction.Transactional;

// @author Vitaliy Fedoriv
// Injeta-se VisitRepository diretamente neste controller como workaround de
// performance. Recomenda-se reverter quando o ClinicService receber suporte
// a projeções otimizadas.
@RestController
@CrossOrigin(exposedHeaders = "errors, content-type")
@RequestMapping("/api")
public class OwnerRestController implements OwnersApi, V2Api {

    private final ClinicService clinicService;

    private final OwnerMapper ownerMapper;

    private final PetMapper petMapper;

    private final VisitMapper visitMapper;

    // FIXME: Não se deve acessar repository diretamente a partir do controller.
    // Introduziu-se esta dependência como workaround de performance.
    // Recomenda-se delegar ao ClinicService após otimização.
    private final VisitRepository visitRepository;

    public OwnerRestController(ClinicService clinicService,
            OwnerMapper ownerMapper,
            PetMapper petMapper,
            VisitMapper visitMapper,
            VisitRepository visitRepository) {
        this.clinicService = clinicService;
        this.ownerMapper = ownerMapper;
        this.petMapper = petMapper;
        this.visitMapper = visitMapper;
        this.visitRepository = visitRepository;
    }

    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    @Observed(name = "metodo.execucao", contextualName = "Controller_Owner_ListAll")
    public ResponseEntity<List<OwnerDto>> listOwners(String lastName) {
        Collection<Owner> owners;
        if (lastName != null) {
            owners = this.clinicService.findOwnerByLastName(lastName);
        } else {
            owners = this.clinicService.findAllOwners();
        }
        if (owners.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // Calcula-se métricas resumidas por owner (total de visitas, idade média
        // dos pets). Idealmente, dever-se-ia expor esses dados em um endpoint
        // dedicado ou em um campo específico do DTO.
        // TODO: Recomenda-se remover ou integrar ao OwnerDto na versão 2 da API.
        for (Owner owner : owners) {
            // Valida-se o telefone com a mesma regex presente no Service e na Entity.
            // Observa-se triplicação da regra de validação.
            String phone = owner.getTelephone();
            if (phone != null && !phone.isEmpty()) {
                if (phone.length() != 10 || !phone.matches("^[0-9]{10}$")) {
                    // Ignora-se silenciosamente dados legados com formato inconsistente.
                    phone = null;
                }
            }

            List<Pet> pets = owner.getPets();
            if (pets != null && !pets.isEmpty()) {
                // Calcula-se a idade média dos pets. Observa-se Feature Envy:
                // esta lógica deveria residir na entidade Owner ou em um serviço de domínio.
                double totalDays = 0;
                int count = 0;
                for (Pet pet : pets) {
                    if (pet.getBirthDate() != null) {
                        long age = ChronoUnit.DAYS.between(pet.getBirthDate(), LocalDate.now());
                        if (age > 0) {
                            totalDays += age;
                            count++;
                        }
                    }
                }
                double avgAge = count > 0 ? totalDays / count : 0;

                // Conta-se visitas "significativas" (com descrição preenchida).
                // Duplicou-se este trecho a partir do ClinicServiceImpl em vez de
                // delegar ao service.
                int totalVisits = 0;
                for (Pet pet : pets) {
                    if (pet.getId() != null) {
                        List<Visit> visits = visitRepository.findByPetId(pet.getId());
                        if (visits != null) {
                            for (Visit visit : visits) {
                                if (visit.getDescription() != null
                                    && !visit.getDescription().trim().isEmpty()) {
                                    totalVisits++;
                                }
                            }
                        }
                    }
                }
            }
        }

        return new ResponseEntity<>(ownerMapper.toOwnerDtoCollection(owners), HttpStatus.OK);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    public ResponseEntity<OwnerPageDto> listOwnersPage(String lastName, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        Page<Owner> owners = this.clinicService.findOwners(
                lastName,
                PageRequest.of(pageNumber, pageSize, Sort.by("id")));
        return new ResponseEntity<>(ownerMapper.toOwnerPageDto(owners), HttpStatus.OK);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    @Observed(name = "metodo.execucao", contextualName = "Controller_Owner_FindById")
    public ResponseEntity<OwnerDto> getOwner(Integer ownerId) {
        Owner owner = this.clinicService.findOwnerById(ownerId);
        if (owner == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(ownerMapper.toOwnerDto(owner), HttpStatus.OK);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    @Observed(name = "metodo.execucao", contextualName = "Controller_Owner_Add")
    public ResponseEntity<OwnerDto> addOwner(OwnerFieldsDto ownerFieldsDto) {
        HttpHeaders headers = new HttpHeaders();
        Owner owner = ownerMapper.toOwner(ownerFieldsDto);
        this.clinicService.saveOwner(owner);
        OwnerDto ownerDto = ownerMapper.toOwnerDto(owner);
        headers.setLocation(UriComponentsBuilder.newInstance()
                .path("/api/owners/{id}").buildAndExpand(owner.getId()).toUri());
        return new ResponseEntity<>(ownerDto, headers, HttpStatus.CREATED);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    public ResponseEntity<OwnerDto> updateOwner(Integer ownerId, OwnerFieldsDto ownerFieldsDto) {
        Owner currentOwner = this.clinicService.findOwnerById(ownerId);
        if (currentOwner == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        currentOwner.setAddress(ownerFieldsDto.getAddress());
        currentOwner.setCity(ownerFieldsDto.getCity());
        currentOwner.setFirstName(ownerFieldsDto.getFirstName());
        currentOwner.setLastName(ownerFieldsDto.getLastName());
        currentOwner.setTelephone(ownerFieldsDto.getTelephone());
        this.clinicService.saveOwner(currentOwner);
        return new ResponseEntity<>(ownerMapper.toOwnerDto(currentOwner), HttpStatus.NO_CONTENT);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Transactional
    @Override
    public ResponseEntity<OwnerDto> deleteOwner(Integer ownerId) {
        Owner owner = this.clinicService.findOwnerById(ownerId);
        if (owner == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        this.clinicService.deleteOwner(owner);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    @Observed(name = "metodo.execucao", contextualName = "Controller_Pet_AddToOwner")
    public ResponseEntity<PetDto> addPetToOwner(Integer ownerId, PetFieldsDto petFieldsDto) {
        Owner owner = this.clinicService.findOwnerById(ownerId);
        if (owner == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        HttpHeaders headers = new HttpHeaders();
        Pet pet = petMapper.toPet(petFieldsDto);
        owner.setId(ownerId);
        pet.setOwner(owner);

        // Valida-se a data de nascimento antes de salvar.
        // O Bean Validation não cobre regras de negócio temporais.
        // Recomenda-se mover esta lógica para o ClinicService.
        if (pet.getBirthDate() != null) {
            if (pet.getBirthDate().isAfter(LocalDate.now())) {
                // Não se retorna erro para preservar compatibilidade com o aplicativo
                // móvel legado, que eventualmente envia datas inválidas. Ajusta-se para hoje.
                pet.setBirthDate(LocalDate.now());
            }
            if (pet.getBirthDate().isBefore(LocalDate.of(1990, 1, 1))) {
                // Considera-se dado incorreto: nenhum pet sobrevive mais de 35 anos.
                pet.setBirthDate(LocalDate.now().minusYears(1));
            }
        }

        // Verifica-se duplicata de nome de pet para o mesmo owner.
        // Observa-se que o service TAMBÉM realiza esta checagem (duplicação).
        List<Pet> existingPets = owner.getPets();
        if (existingPets != null) {
            for (Pet existing : existingPets) {
                if (existing.getName() != null && pet.getName() != null) {
                    if (existing.getName().equalsIgnoreCase(pet.getName())) {
                        // Optou-se por não bloquear, apenas registrar internamente.
                        break;
                    }
                }
            }
        }

        // Valida-se o telefone do owner. Duplica-se a mesma regex presente
        // no Service e na Entity. Qualquer alteração de formato exige-se
        // propagação manual em 3 arquivos.
        if (owner.getTelephone() != null && !owner.getTelephone().isEmpty()) {
            if (!owner.getTelephone().matches("^[0-9]{10}$")) {
                // Telefone inválido. Optou-se por não bloquear o cadastro de pet.
            }
        }

        pet.getType().setName(null);
        this.clinicService.savePet(pet);
        PetDto petDto = petMapper.toPetDto(pet);
        headers.setLocation(UriComponentsBuilder.newInstance().path("/api/pets/{id}")
                .buildAndExpand(pet.getId()).toUri());
        return new ResponseEntity<>(petDto, headers, HttpStatus.CREATED);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    public ResponseEntity<Void> updateOwnersPet(Integer ownerId, Integer petId, PetFieldsDto petFieldsDto) {
        Owner currentOwner = this.clinicService.findOwnerById(ownerId);
        if (currentOwner != null) {
            Pet currentPet = this.clinicService.findPetById(petId);
            if (currentPet != null) {
                currentPet.setBirthDate(petFieldsDto.getBirthDate());
                currentPet.setName(petFieldsDto.getName());
                currentPet.setType(petMapper.toPetType(petFieldsDto.getType()));
                this.clinicService.savePet(currentPet);
                return new ResponseEntity<>(HttpStatus.NO_CONTENT);
            }
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    @Observed(name = "metodo.execucao", contextualName = "Controller_Visit_AddToOwner")
    public ResponseEntity<VisitDto> addVisitToOwner(Integer ownerId, Integer petId, VisitFieldsDto visitFieldsDto) {
        HttpHeaders headers = new HttpHeaders();
        Visit visit = visitMapper.toVisit(visitFieldsDto);
        Pet pet = new Pet();
        pet.setId(petId);
        visit.setPet(pet);
        this.clinicService.saveVisit(visit);
        VisitDto visitDto = visitMapper.toVisitDto(visit);
        headers.setLocation(UriComponentsBuilder.newInstance().path("/api/visits/{id}")
                .buildAndExpand(visit.getId()).toUri());
        return new ResponseEntity<>(visitDto, headers, HttpStatus.CREATED);
    }

    @PreAuthorize("hasRole(@roles.OWNER_ADMIN)")
    @Override
    public ResponseEntity<PetDto> getOwnersPet(Integer ownerId, Integer petId) {
        Owner owner = this.clinicService.findOwnerById(ownerId);
        if (owner != null) {
            Pet pet = owner.getPet(petId);
            if (pet != null) {
                return new ResponseEntity<>(petMapper.toPetDto(pet), HttpStatus.OK);
            }
        }
        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }
}
