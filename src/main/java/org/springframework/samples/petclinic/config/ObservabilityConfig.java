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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração da Observation API (Micrometer) para instrumentação granular.
 * <p>
 * Registra o {@link ObservedAspect} como bean Spring, habilitando o
 * processamento
 * das anotações {@code @Observed} via AOP (AspectJ proxy). Cada método anotado
 * gera automaticamente métricas de Timer (latência) e LongTaskTimer
 * (concorrência)
 * no Prometheus com tags {@code class}, {@code method} e {@code error}.
 * <p>
 * Restrições:
 * <ul>
 * <li>Funciona apenas em Spring Beans gerenciados pelo contexto</li>
 * <li>Não intercepta chamadas internas à mesma classe (self-invocation)</li>
 * <li>Não funciona em métodos {@code static} ou objetos instanciados com
 * {@code new}</li>
 * </ul>
 *
 * @see io.micrometer.observation.annotation.Observed
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    @Bean
    ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}
