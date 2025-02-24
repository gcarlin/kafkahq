package org.kafkahq.models;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.TopicConfig;
import org.kafkahq.repositories.ConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@ToString
@EqualsAndHashCode
@Getter
public class Topic {
    private String name;
    private boolean internal;
    private boolean configInternal;
    private boolean configStream;
    private final List<AccessControlList> acls = new ArrayList<>();
    private final List<Partition> partitions = new ArrayList<>();
    private List<ConsumerGroup> consumerGroups;

    public Topic(
        TopicDescription description,
        List<ConsumerGroup> consumerGroup,
        List<LogDir> logDirs,
        List<Partition.Offsets> offsets,
        List<AccessControlList> acls,
        boolean configInternal,
        boolean configStream
    ) {
        this.name = description.name();
        this.internal = description.isInternal();
        this.consumerGroups = consumerGroup;

        this.configInternal = configInternal;
        this.configStream = configStream;
        this.acls.addAll(acls);

        for (TopicPartitionInfo partition : description.partitions()) {
            this.partitions.add(new Partition(
                description.name(),
                partition,
                logDirs.stream()
                    .filter(logDir -> logDir.getPartition() == partition.partition())
                    .collect(Collectors.toList()),
                offsets.stream()
                    .filter(offset -> offset.getPartition() == partition.partition())
                    .findFirst()
                    .orElseThrow(() -> new NoSuchElementException(
                        "Partition Offsets '" + partition.partition() + "' doesn't exist for topic " + this.name
                    ))
            ));
        }
    }

    public boolean isInternal() {
        return this.internal || this.configInternal;
    }

    public boolean isStream() {
        return this.configStream;
    }

    public long getReplicaCount() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getNodes().stream())
            .map(Node::getId)
            .distinct()
            .count();
    }

    public long getInSyncReplicaCount() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getNodes().stream())
            .filter(Node.Partition::isInSyncReplicas)
            .map(Node::getId)
            .distinct()
            .count();
    }

    public List<LogDir> getLogDir() {
        return this.getPartitions().stream()
            .flatMap(partition -> partition.getLogDir().stream())
            .collect(Collectors.toList());
    }

    public Optional<Long> getLogDirSize() {
        Integer logDirCount = this.getPartitions().stream()
            .map(r -> r.getLogDir().size())
            .reduce(0, Integer::sum);

        if (logDirCount == 0) {
            return Optional.empty();
        }

        return Optional.of(this.getPartitions().stream()
            .map(Partition::getLogDirSize)
            .reduce(0L, Long::sum)
        );
    }

    public long getSize() {
        return this.getPartitions().stream()
            .map(partition -> partition.getLastOffset() - partition.getFirstOffset())
            .reduce(0L, Long::sum);
    }

    public long getSize(int partition) {
        for (Partition current : this.getPartitions()) {
            if (partition == current.getId()) {
                return current.getLastOffset() - current.getFirstOffset();
            }
        }

        throw new NoSuchElementException("Partition '" + partition + "' doesn't exist for topic " + this.name);
    }

    public Boolean canDeleteRecords(String clusterId, ConfigRepository configRepository) throws ExecutionException, InterruptedException {
        if (this.isInternal()) {
            return false;
        }

        return configRepository
            .findByTopic(clusterId, this.getName())
            .stream()
            .filter(config -> config.getName().equals(TopicConfig.CLEANUP_POLICY_CONFIG))
            .anyMatch(config -> config.getValue().contains(TopicConfig.CLEANUP_POLICY_COMPACT));
    }
}
