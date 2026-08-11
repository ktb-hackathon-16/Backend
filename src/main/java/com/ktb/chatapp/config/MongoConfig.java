package com.ktb.chatapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

@Slf4j
@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    public ApplicationRunner ensureMongoIndexes(MongoTemplate mongoTemplate) {
        return args -> {
            ensureIndex(mongoTemplate, "users",
                    new Index().on("email", Direction.ASC).unique().named("email_unique_idx"));
            ensureIndex(mongoTemplate, "rooms",
                    new Index().on("createdAt", Direction.DESC).named("created_at_desc_idx"));
            ensureIndex(mongoTemplate, "messages",
                    new Index().on("room", Direction.ASC).on("timestamp", Direction.ASC).named("room_timestamp_idx"));
            ensureIndex(mongoTemplate, "messages",
                    new Index()
                            .on("room", Direction.ASC)
                            .on("timestamp", Direction.DESC)
                            .on("_id", Direction.DESC)
                            .named("room_timestamp_id_desc_idx"));
            ensureIndex(mongoTemplate, "read_receipts",
                    new Index().on("room", Direction.ASC).named("room_idx"));
            ensureIndex(mongoTemplate, "read_receipts",
                    new Index().on("room", Direction.ASC).on("user", Direction.ASC).unique().named("room_user_idx"));
            ensureIndex(mongoTemplate, "read_receipts",
                    new Index().on("room", Direction.ASC).on("lastReadAt", Direction.ASC).named("room_last_read_at_idx"));
        };
    }

    private void ensureIndex(MongoTemplate mongoTemplate, String collection, Index index) {
        try {
            String indexName = mongoTemplate.indexOps(collection).ensureIndex(index);
            log.info("Mongo index ensured: collection={}, index={}", collection, indexName);
        } catch (RuntimeException e) {
            log.warn("Mongo index ensure failed: collection={}, index={}", collection, index.getIndexKeys(), e);
        }
    }
}
