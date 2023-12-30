/*
 * Copyright 2023 Swift Software Group, Inc.
 * (Code and content before December 13, 2023, Copyright Netflix, Inc.)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.swiftconductor.conductor.es6.dao.index;

import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.swiftconductor.conductor.common.config.TestObjectMapperConfiguration;
import com.swiftconductor.conductor.es6.config.ElasticSearchProperties;

@ContextConfiguration(
        classes = {TestObjectMapperConfiguration.class, ElasticSearchTest.TestConfiguration.class})
@RunWith(SpringRunner.class)
@TestPropertySource(
        properties = {"conductor.indexing.enabled=true", "conductor.elasticsearch.version=6"})
abstract class ElasticSearchTest {

    @Configuration
    static class TestConfiguration {

        @Bean
        public ElasticSearchProperties elasticSearchProperties() {
            return new ElasticSearchProperties();
        }
    }

    protected static final ElasticsearchContainer container =
            new ElasticsearchContainer(
                            DockerImageName.parse(
                                            "docker.elastic.co/elasticsearch/elasticsearch-oss")
                                    .withTag("6.8.17")) // this should match the client version
                    // Resolve issue with es container not starting on m1/m2 macs
                    .withEnv(Map.of("bootstrap.system_call_filter", "false"));

    @Autowired protected ObjectMapper objectMapper;

    @Autowired protected ElasticSearchProperties properties;

    @BeforeClass
    public static void startServer() {
        container.start();
    }

    @AfterClass
    public static void stopServer() {
        container.stop();
    }
}
