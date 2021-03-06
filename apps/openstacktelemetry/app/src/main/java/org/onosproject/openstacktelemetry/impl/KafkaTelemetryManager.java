/*
 * Copyright 2018-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.openstacktelemetry.impl;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.onosproject.openstacktelemetry.api.FlowInfo;
import org.onosproject.openstacktelemetry.api.KafkaTelemetryAdminService;
import org.onosproject.openstacktelemetry.api.OpenstackTelemetryService;
import org.onosproject.openstacktelemetry.api.TelemetryCodec;
import org.onosproject.openstacktelemetry.api.TelemetryConfigService;
import org.onosproject.openstacktelemetry.api.config.KafkaTelemetryConfig;
import org.onosproject.openstacktelemetry.api.config.TelemetryConfig;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;

import static org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BATCH_SIZE_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.BUFFER_MEMORY_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.LINGER_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.METADATA_FETCH_TIMEOUT_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.RETRIES_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.RETRY_BACKOFF_MS_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.TIMEOUT_CONFIG;
import static org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG;
import static org.onosproject.openstacktelemetry.api.Constants.KAFKA_SCHEME;
import static org.onosproject.openstacktelemetry.api.config.TelemetryConfig.ConfigType.KAFKA;
import static org.onosproject.openstacktelemetry.api.config.TelemetryConfig.Status.ENABLED;
import static org.onosproject.openstacktelemetry.config.DefaultKafkaTelemetryConfig.fromTelemetryConfig;
import static org.onosproject.openstacktelemetry.util.OpenstackTelemetryUtil.testConnectivity;

/**
 * Kafka telemetry manager.
 */
@Component(immediate = true, service = KafkaTelemetryAdminService.class)
public class KafkaTelemetryManager implements KafkaTelemetryAdminService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final int METADATA_FETCH_TIMEOUT_VAL = 300;
    private static final int TIMEOUT_VAL = 300;
    private static final int RETRY_BACKOFF_MS_VAL = 10000;
    private static final int RECONNECT_BACKOFF_MS_VAL = 10000;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OpenstackTelemetryService openstackTelemetryService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TelemetryConfigService telemetryConfigService;

    private Map<String, Producer<String, byte[]>> producers = Maps.newConcurrentMap();

    @Activate
    protected void activate() {

        openstackTelemetryService.addTelemetryService(this);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        stopAll();

        openstackTelemetryService.removeTelemetryService(this);

        log.info("Stopped");
    }

    @Override
    public Set<Future<RecordMetadata>> publish(Set<FlowInfo> flowInfos) {

        if (producers == null || producers.isEmpty()) {
            log.debug("Kafka telemetry service has not been enabled!");
            return null;
        }

        log.debug("Send telemetry record to kafka server...");
        Set<Future<RecordMetadata>> futureSet = Sets.newHashSet();
        producers.forEach((k, v) -> {
            TelemetryConfig config = telemetryConfigService.getConfig(k);
            KafkaTelemetryConfig kafkaConfig = fromTelemetryConfig(config);

            try {
                Class codecClazz = Class.forName(kafkaConfig.codec());
                TelemetryCodec codec = (TelemetryCodec) codecClazz.newInstance();

                ByteBuffer buffer = codec.encode(flowInfos);
                ProducerRecord record = new ProducerRecord<>(
                        kafkaConfig.topic(), kafkaConfig.key(), buffer.array());
                futureSet.add(v.send(record));
            } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
                log.warn("Failed to send telemetry record due to {}", e);
            }
        });
        return futureSet;
    }

    @Override
    public boolean isRunning() {
        return !producers.isEmpty();
    }

    @Override
    public boolean start(String name) {
        boolean success = false;
        TelemetryConfig config = telemetryConfigService.getConfig(name);
        KafkaTelemetryConfig kafkaConfig = fromTelemetryConfig(config);

        if (kafkaConfig != null && !config.name().equals(KAFKA_SCHEME) &&
                config.status() == ENABLED) {
            StringBuilder kafkaServerBuilder = new StringBuilder();
            kafkaServerBuilder.append(kafkaConfig.address());
            kafkaServerBuilder.append(":");
            kafkaServerBuilder.append(kafkaConfig.port());

            // Configure Kafka server properties
            Properties prop = new Properties();
            prop.put(BOOTSTRAP_SERVERS_CONFIG, kafkaServerBuilder.toString());
            prop.put(RETRIES_CONFIG, kafkaConfig.retries());
            prop.put(ACKS_CONFIG, kafkaConfig.requiredAcks());
            prop.put(BATCH_SIZE_CONFIG, kafkaConfig.batchSize());
            prop.put(LINGER_MS_CONFIG, kafkaConfig.lingerMs());
            prop.put(BUFFER_MEMORY_CONFIG, kafkaConfig.memoryBuffer());
            prop.put(KEY_SERIALIZER_CLASS_CONFIG, kafkaConfig.keySerializer());
            prop.put(VALUE_SERIALIZER_CLASS_CONFIG, kafkaConfig.valueSerializer());
            prop.put(METADATA_FETCH_TIMEOUT_CONFIG, METADATA_FETCH_TIMEOUT_VAL);
            prop.put(TIMEOUT_CONFIG, TIMEOUT_VAL);
            prop.put(RETRY_BACKOFF_MS_CONFIG, RETRY_BACKOFF_MS_VAL);
            prop.put(RECONNECT_BACKOFF_MS_CONFIG, RECONNECT_BACKOFF_MS_VAL);

            if (testConnectivity(kafkaConfig.address(), kafkaConfig.port())) {
                producers.put(name, new KafkaProducer<>(prop));
                success = true;
            } else {
                log.warn("Unable to connect to {}:{}, " +
                            "please check the connectivity manually",
                            kafkaConfig.address(), kafkaConfig.port());
            }
        }

        return success;
    }

    @Override
    public void stop(String name) {
        Producer<String, byte[]> producer = producers.get(name);

        if (producer != null) {
            producer.close();
            producers.remove(name);
        }
    }

    @Override
    public boolean restart(String name) {
        stop(name);
        return start(name);
    }

    @Override
    public void startAll() {
        telemetryConfigService.getConfigsByType(KAFKA).forEach(c -> start(c.name()));
        log.info("Kafka producer has Started");
    }

    @Override
    public void stopAll() {
        if (!producers.isEmpty()) {
            producers.values().forEach(Producer::close);
        }

        producers.clear();

        log.info("Kafka producer has Stopped");
    }

    @Override
    public void restartAll() {
        stopAll();
        startAll();
    }
}
