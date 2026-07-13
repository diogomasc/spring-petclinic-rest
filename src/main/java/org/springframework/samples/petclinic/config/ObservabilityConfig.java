/*
 * Copyright 2024 the original author or authors.
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
package org.springframework.samples.petclinic.config;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração central de Observabilidade e Cache da aplicação.
 *
 * <h3>Observabilidade (@Observed)</h3>
 * <p>Registra o {@link ObservedAspect} para processar anotações {@code @Observed}
 * via AOP. Gera métricas de Timer (latência) no Prometheus com tags
 * {@code class}, {@code method} e {@code error}.
 *
 * <h3>Cache — padrão Cache-Aside</h3>
 * <p>Ativa o Spring Cache Abstraction ({@code @EnableCaching}) com back-end
 * {@code ConcurrentMapCacheManager} (provisionado automaticamente pelo
 * Spring Boot Auto-configuration quando {@code spring-boot-starter-cache} está
 * no classpath sem outra implementação de cache presente).
 *
 * <p>Adequado para deployments de instância única (H2 in-memory, TCC).
 * Para produção multi-instância, substituir por Caffeine ou Redis com TTL.
 *
 * <p>Caches registrados em {@link org.springframework.samples.petclinic.service.ClinicServiceImpl}:
 * <ul>
 *   <li>{@code "petTypes"} — {@code findAllPetTypes()} e {@code findPetTypeById()}.
 *       Dados estáticos de referência (cat, dog, bird…). Torna {@code POST /pets} O(1)
 *       no lookup de PetType após o warm-up.</li>
 *   <li>{@code "vets"} — {@code findAllVets()}.
 *       Dados que não mudam durante sessão k6. Torna {@code GET /vets} O(1)
 *       após o warm-up, independente da carga N:M EAGER de especialidades.</li>
 * </ul>
 *
 * <p>Referência: Spring Boot 3.5 Reference — <em>Caching</em>
 * (docs.spring.io/spring-boot/3.5/reference/io/caching.html).
 *
 * @see io.micrometer.observation.annotation.Observed
 * @see org.springframework.cache.annotation.Cacheable
 * @see org.springframework.cache.annotation.CacheEvict
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class ObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfig.class);

    @Bean
    @ConditionalOnMissingBean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        log.info("ObservedAspect registrado — métricas @Observed ativas (metodo_execucao_seconds_*)");
        log.info("Spring Cache ativo — caches: petTypes, vets (ConcurrentMapCacheManager)");
        return new ObservedAspect(registry);
    }
}
