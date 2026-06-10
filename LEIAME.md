# LEIAME — Spring PetClinic REST (TCC)

## Visão Geral

O **Spring PetClinic REST** é uma aplicação de referência que expõe uma API REST completa para gerenciamento de uma clínica veterinária. Neste TCC, o projeto serve como **objeto de estudo** para análise de métricas de refatoração: é analisado estaticamente (SonarQube, SpotBugs, JaCoCo) e dinamicamente (K6, Prometheus, Grafana) antes e depois de refatorações.

| Atributo | Valor |
|---|---|
| **Framework** | Spring Boot 4.0.6 |
| **Java** | 21 |
| **Banco** | H2 (in-memory, perfil padrão) |
| **API** | OpenAPI 3.0 (contrato-first) |
| **Porta** | 9966 |
| **Context-path** | `/petclinic/` |
| **Segurança** | Desabilitada para dev (`petclinic.security.enable=false`) |

---

## Árvore de Arquivos

```
spring-petclinic-rest/
├── pom.xml                          # Dependências e plugins Maven
├── src/
│   ├── main/
│   │   ├── java/.../petclinic/
│   │   │   ├── model/               # Entidades JPA (domínio)
│   │   │   │   ├── BaseEntity.java       # @Id + @GeneratedValue
│   │   │   │   ├── NamedEntity.java      # + name
│   │   │   │   ├── Person.java           # + firstName, lastName
│   │   │   │   ├── Owner.java            # + address, city, telephone, pets
│   │   │   │   ├── Pet.java              # + birthDate, type, owner, visits
│   │   │   │   ├── PetType.java          # tipo do pet (cat, dog, etc.)
│   │   │   │   ├── Visit.java            # + date, description, pet
│   │   │   │   ├── Vet.java              # + specialties (N:M)
│   │   │   │   ├── Specialty.java        # especialidade veterinária
│   │   │   │   ├── User.java             # autenticação
│   │   │   │   └── Role.java             # papéis de acesso
│   │   │   ├── repository/          # Interfaces de repositório
│   │   │   │   ├── jpa/                  # Implementação JPA pura
│   │   │   │   ├── jdbc/                 # Implementação JDBC
│   │   │   │   └── springdatajpa/        # Implementação Spring Data JPA
│   │   │   ├── service/             # Camada de serviço (facade)
│   │   │   │   ├── ClinicService.java        # Interface
│   │   │   │   └── ClinicServiceImpl.java    # Implementação (@Transactional)
│   │   │   ├── rest/
│   │   │   │   ├── controller/       # REST Controllers
│   │   │   │   │   ├── OwnerRestController.java
│   │   │   │   │   ├── PetRestController.java
│   │   │   │   │   ├── VetRestController.java
│   │   │   │   │   ├── VisitRestController.java
│   │   │   │   │   ├── PetTypeRestController.java
│   │   │   │   │   ├── SpecialtyRestController.java
│   │   │   │   │   └── UserRestController.java
│   │   │   │   ├── advice/           # @ControllerAdvice global
│   │   │   │   └── validation/       # Validadores customizados
│   │   │   ├── mapper/              # MapStruct (Entity ↔ DTO)
│   │   │   ├── security/            # Configuração Spring Security
│   │   │   └── util/
│   │   │       └── CallMonitoringAspect.java  # AOP: monitoramento JMX
│   │   └── resources/
│   │       ├── application.properties        # Configuração principal
│   │       ├── openapi.yml                   # Contrato OpenAPI 3.0
│   │       └── db/h2/schema.sql + data.sql   # DDL + seed data
│   └── test/
│       ├── java/.../petclinic/
│       │   ├── rest/controller/      # Testes dos controllers (MockMvc)
│       │   ├── service/clinicService/ # Testes do service (vários backends)
│       │   ├── service/userService/   # Testes do UserService
│       │   └── model/                # Testes de validação
│       ├── jmeter/                   # Benchmark JMeter (referência)
│       └── postman/                  # Coleção Postman para regressão
```

---

## Modelo de Domínio (Diagrama ER)

```mermaid
erDiagram
    OWNERS {
        int id PK
        varchar first_name
        varchar last_name
        varchar address
        varchar city
        varchar telephone
    }

    PETS {
        int id PK
        varchar name
        date birth_date
        int type_id FK
        int owner_id FK
    }

    TYPES {
        int id PK
        varchar name
    }

    VISITS {
        int id PK
        int pet_id FK
        date visit_date
        varchar description
    }

    VETS {
        int id PK
        varchar first_name
        varchar last_name
    }

    SPECIALTIES {
        int id PK
        varchar name
    }

    VET_SPECIALTIES {
        int vet_id FK
        int specialty_id FK
    }

    USERS {
        varchar username PK
        varchar password
        boolean enabled
    }

    ROLES {
        int id PK
        varchar username FK
        varchar role
    }

    OWNERS ||--o{ PETS : "possui (1:N)"
    PETS }o--|| TYPES : "tem tipo (N:1)"
    PETS ||--o{ VISITS : "recebe (1:N)"
    VETS }o--o{ SPECIALTIES : "tem (N:M via VET_SPECIALTIES)"
    USERS ||--o{ ROLES : "tem (1:N)"
```

---

## Hierarquia de Classes (Herança JPA)

```mermaid
classDiagram
    class BaseEntity {
        #Integer id
        +getId()
        +setId()
        +isNew()
    }

    class NamedEntity {
        #String name
        +getName()
        +setName()
    }

    class Person {
        #String firstName
        #String lastName
        +getFirstName()
        +getLastName()
    }

    class Owner {
        -String address
        -String city
        -String telephone
        -Set~Pet~ pets
        +getPets()
        +addPet()
    }

    class Pet {
        -LocalDate birthDate
        -PetType type
        -Owner owner
        -Set~Visit~ visits
        +getVisits()
        +addVisit()
    }

    class PetType {
    }

    class Visit {
        -LocalDate date
        -String description
        -Pet pet
    }

    class Vet {
        -Set~Specialty~ specialties
        +getSpecialties()
        +addSpecialty()
    }

    class Specialty {
    }

    BaseEntity <|-- NamedEntity
    NamedEntity <|-- Person
    NamedEntity <|-- PetType
    NamedEntity <|-- Specialty
    Person <|-- Owner
    Person <|-- Vet

    BaseEntity <|-- Pet
    BaseEntity <|-- Visit

    Owner "1" --> "*" Pet : pets
    Pet "*" --> "1" PetType : type
    Pet "*" --> "1" Owner : owner
    Pet "1" --> "*" Visit : visits
    Visit "*" --> "1" Pet : pet
    Vet "*" --> "*" Specialty : specialties
```

---

## Arquitetura em Camadas

```mermaid
flowchart TD
    subgraph "REST Layer"
        OC[OwnerRestController]
        PC[PetRestController]
        VC[VetRestController]
        VIC[VisitRestController]
        PTC[PetTypeRestController]
        SC[SpecialtyRestController]
    end

    subgraph "Mapper Layer (MapStruct)"
        OM[OwnerMapper]
        PM[PetMapper]
        VM[VetMapper]
        VIM[VisitMapper]
    end

    subgraph "Service Layer"
        CS[ClinicService]
        CSI[ClinicServiceImpl]
    end

    subgraph "Repository Layer"
        OR[OwnerRepository]
        PR[PetRepository]
        VR[VetRepository]
        VIR[VisitRepository]
        PTR[PetTypeRepository]
        SR[SpecialtyRepository]
    end

    subgraph "Persistence"
        DB[(H2 Database)]
    end

    OC --> OM --> CS
    PC --> PM --> CS
    VC --> VM --> CS
    VIC --> VIM --> CS
    PTC --> CS
    SC --> CS

    CS --> CSI
    CSI --> OR & PR & VR & VIR & PTR & SR
    OR & PR & VR & VIR & PTR & SR --> DB
```

---

## Endpoints da API REST

Base URL: `http://localhost:9966/petclinic/api`

### Owners (Donos)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/owners` | Listar todos os donos |
| `GET` | `/owners?lastName={name}` | Buscar por sobrenome |
| `GET` | `/owners/{ownerId}` | Obter dono por ID |
| `POST` | `/owners` | Criar novo dono |
| `PUT` | `/owners/{ownerId}` | Atualizar dono |
| `DELETE` | `/owners/{ownerId}` | Remover dono |
| `GET` | `/v2/owners` | Listar donos (paginado) |

### Pets

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/pets` | Listar todos os pets |
| `GET` | `/pets/{petId}` | Obter pet por ID |
| `POST` | `/owners/{ownerId}/pets` | Adicionar pet a um dono |
| `PUT` | `/owners/{ownerId}/pets/{petId}` | Atualizar pet |
| `DELETE` | `/pets/{petId}` | Remover pet |

### Visits (Visitas)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/visits` | Listar todas as visitas |
| `GET` | `/visits/{visitId}` | Obter visita por ID |
| `POST` | `/owners/{ownerId}/pets/{petId}/visits` | Adicionar visita |
| `PUT` | `/visits/{visitId}` | Atualizar visita |
| `DELETE` | `/visits/{visitId}` | Remover visita |

### Vets (Veterinários)

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/vets` | Listar todos os veterinários |
| `GET` | `/vets/{vetId}` | Obter vet por ID |
| `POST` | `/vets` | Criar veterinário |
| `PUT` | `/vets/{vetId}` | Atualizar veterinário |
| `DELETE` | `/vets/{vetId}` | Remover veterinário |

### PetTypes e Specialties

| Método | Endpoint | Descrição |
|---|---|---|
| `GET` | `/pettypes` | Listar tipos de pet |
| `GET` | `/specialties` | Listar especialidades |

### Observabilidade

| Endpoint | Descrição |
|---|---|
| `/actuator/health` | Status da aplicação |
| `/actuator/prometheus` | Métricas Micrometer (formato Prometheus) |
| `/actuator/metrics` | Métricas Spring Boot |
| `/actuator/info` | Informações do build |
| `/swagger-ui.html` | Swagger UI interativo |

---

## Endpoints Críticos para Análise Dinâmica

Os endpoints abaixo foram selecionados para o teste de carga K6 por representarem as operações mais relevantes para a análise de correlação estática ↔ dinâmica:

| Endpoint | Por que é crítico? | Risco sob estresse |
|---|---|---|
| `GET /owners` | **Carga N+1**: `Owner` carrega `Set<Pet>` com `FetchType.EAGER`, que por sua vez carrega `Set<Visit>` EAGER. Listar todos os owners executa cascata de queries | Latência cresce com volume de dados |
| `POST /owners` | **Write-path completo**: validação → mapeamento → JPA persist → flush. Transação completa | Contenção de locks no banco |
| `GET /owners/{id}` | **Consulta com grafo**: retorna owner + pets + visits aninhados. Potencial N+1 se não otimizado | Latência proporcional ao nº de pets/visits |
| `POST /owners/{id}/pets` | **Cascata JPA**: `CascadeType.ALL` no `Pet.type` pode causar side-effects inesperados. `savePet()` faz lookup de `PetType` antes de salvar | Falha silenciosa se type_id inválido |
| `POST /visits` | **Inserção em tabela filha**: criação de visit requer lookup do pet, potencial lock no owner pai via foreign key | Deadlock sob alta concorrência |
| `GET /vets` | **Relação N:M**: `Vet` → `Specialty` via `@ManyToMany EAGER` + tabela de junção `vet_specialties`. Grafo de objetos denso | Memory pressure com muitos vets |

### Trade-off: Por que esses e não outros?

| Decisão | Rationale |
|---|---|
| **Foco em Owner/Pet/Visit** | São o "core path" da aplicação — onde a complexidade de negócio e JPA se concentra |
| **Inclusão de Vets** | Exemplifica relação N:M (mais custosa que 1:N) — bom candidato para anomalias de acoplamento |
| **Exclusão de PetTypes/Specialties isolados** | São tabelas de lookup simples (CRUD trivial) — baixo potencial de anomalia estática ou dinâmica |
| **GET prioritário sobre DELETE** | Operações de leitura representam >80% do tráfego real e são onde `FetchType.EAGER` gera mais impacto |
| **Health check incluído** | Estabelece baseline de latência do framework puro (sem lógica de negócio) para normalização |

### Relação com Análise Estática

```mermaid
flowchart LR
    subgraph "Análise Estática"
        CC[Complexidade Ciclomática]
        CBO[Coupling Between Objects]
        LCOM[Lack of Cohesion]
        CS[Code Smells]
    end

    subgraph "Análise Dinâmica"
        LAT[Latência p50/p95/p99]
        ERR[Taxa de Erro]
        THR[Throughput req/s]
    end

    subgraph "Domínio Alvo"
        OC2["ClinicServiceImpl"]
        OR2["OwnerRepository"]
        VR2["VisitRepository"]
    end

    CC -->|"alta CC → lógica complexa"| LAT
    CBO -->|"alto CBO → cascata de dependências"| LAT
    LCOM -->|"baixa coesão → God Class"| ERR
    CS -->|"code smells detectados"| OC2

    OC2 --> LAT
    OR2 --> THR
    VR2 --> ERR
```

A hipótese do TCC é que classes com **alta complexidade ciclomática** e **alto acoplamento (CBO)** detectados pelo SonarQube tendem a apresentar **maior latência** e **maior taxa de erro** sob estresse, e que a refatoração dessas classes pode melhorar tanto as métricas estáticas quanto as dinâmicas.

---

## Como Executar

### Compilar

```bash
mvn clean compile
```

### Executar Testes

```bash
mvn test                    # Roda todos os testes
mvn verify                  # Testes + JaCoCo coverage check
```

> **Importante para o TCC:** Os testes são o **contrato de comportamento** da aplicação. Refatorações devem **preservar todos os testes passando** — se um teste quebrar, a refatoração alterou comportamento observável e é inválida.

#### Testes disponíveis

| Categoria | Classes | O que testam |
|---|---|---|
| Controller (MockMvc) | `OwnerRestControllerTests`, `PetRestControllerTests`, `VetRestControllerTests`, `VisitRestControllerTests`, `PetTypeRestControllerTests`, `SpecialtyRestControllerTests`, `UserRestControllerTests` | Endpoints REST, serialização JSON, HTTP status codes |
| Service | `ClinicServiceJpaTests`, `ClinicServiceSpringDataJpaTests`, `ClinicServiceH2JdbcTests`, `ClinicServiceHsqlJdbcTests` | Lógica de negócio nos diferentes backends de persistência |
| Validação | `ValidatorTests`, `PetAgeValidatorTest` | Constraints de Bean Validation |
| Config | `SpringConfigTests` | Context carrega sem erros |

### Rodar a aplicação

```bash
mvn spring-boot:run
```

Acesse:
- **API**: http://localhost:9966/petclinic/api/owners
- **Swagger UI**: http://localhost:9966/petclinic/swagger-ui.html
- **Prometheus**: http://localhost:9966/petclinic/actuator/prometheus

---

## Dependências do pom.xml (Resumo)

### Aplicação

| Dependência | Propósito |
|---|---|
| `spring-boot-starter-webmvc` | REST API |
| `spring-boot-starter-data-jpa` | Persistência JPA |
| `spring-boot-starter-actuator` | Endpoints de gerenciamento |
| `spring-boot-starter-aspectj` | AOP (`@Observed`, `CallMonitoringAspect`) |
| `spring-boot-starter-security` | Autenticação/autorização |
| `spring-boot-starter-validation` | Bean Validation (Jakarta) |
| `spring-boot-starter-cache` | Cache de queries |
| `micrometer-registry-prometheus` | Exportação de métricas para Prometheus |
| `springdoc-openapi-starter-webmvc-ui` | Swagger UI + OpenAPI |
| `mapstruct` | Mapeamento Entity ↔ DTO |
| `com.vmware.sdk:pbm` | Análise de código complementar |

### Bancos de dados (perfis)

| Perfil | Banco |
|---|---|
| `h2` (padrão) | H2 in-memory |
| `hsqldb` | HSQLDB in-memory |
| `mysql` | MySQL |
| `postgres` | PostgreSQL |

### Plugins de qualidade

| Plugin | Propósito |
|---|---|
| `jacoco-maven-plugin` | Cobertura de código (85% line, 66% branch) |
| `sonar-maven-plugin` | Análise estática SonarQube |
| `spotbugs-maven-plugin` | Detecção de bugs |
| `refactor-first-maven-plugin` | Priorização de refatoração |
| `openapi-generator-maven-plugin` | Geração de DTOs e interfaces a partir do contrato |
