package org.kafkahq.repositories;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.utils.SecurityService;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.common.resource.ResourceType;
import org.kafkahq.models.Partition;
import org.kafkahq.models.Topic;
import org.kafkahq.modules.KafkaModule;
import org.kafkahq.modules.KafkaWrapper;
import org.kafkahq.utils.UserGroupUtils;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Singleton
public class TopicRepository extends AbstractRepository {
    @Inject
    KafkaWrapper kafkaWrapper;

    @Inject
    private KafkaModule kafkaModule;

    @Inject
    private ConsumerGroupRepository consumerGroupRepository;

    @Inject
    private LogDirRepository logDirRepository;

    @Inject
    private ConfigRepository configRepository;

    @Inject
    private AccessControlListRepository aclRepository;

    @Inject
    ApplicationContext applicationContext;

    @Inject
    private UserGroupUtils userGroupUtils;

    @Value("${kafkahq.topic.internal-regexps}")
    protected List<String> internalRegexps;

    @Value("${kafkahq.topic.stream-regexps}")
    protected List<String> streamRegexps;

    @Value("${kafkahq.security.default-groups}")
    private List<String> defaultGroups;

    @Value("${kafkahq.topic.skip-consumer-groups}")
    protected Boolean skipConsumerGroups;

    public enum TopicListView {
        ALL,
        HIDE_INTERNAL,
        HIDE_INTERNAL_STREAM,
        HIDE_STREAM,
    }

    public List<CompletableFuture<Topic>> list(String clusterId, TopicListView view, Optional<String> search) throws ExecutionException, InterruptedException {
        return all(clusterId, view, search)
            .stream()
            .map(s -> CompletableFuture.supplyAsync(() -> {
                try {
                    return this.findByName(clusterId, s);
                }
                catch(ExecutionException | InterruptedException ex) {
                    throw new CompletionException(ex);
                }
            }))
            .collect(Collectors.toList());
    }

    public List<String> all(String clusterId, TopicListView view, Optional<String> search) throws ExecutionException, InterruptedException {
        ArrayList<String> list = new ArrayList<>();

        Collection<TopicListing> listTopics = kafkaWrapper.listTopics(clusterId);

        for (TopicListing item : listTopics) {
            if (isSearchMatch(search, item.name()) && isListViewMatch(view, item.name()) && isTopicMatchRegex(
                getTopicFilterRegex(), item.name())) {
                list.add(item.name());
            }
        }

        list.sort(Comparator.comparing(String::toLowerCase));

        return list;
    }

    public boolean isListViewMatch(TopicListView view, String value) {
        switch (view) {
            case HIDE_STREAM:
                return !isStream(value);
            case HIDE_INTERNAL:
                return !isInternal(value);
            case HIDE_INTERNAL_STREAM:
                return !isInternal(value) && !isStream(value);
        }

        return true;
    }

    public Topic findByName(String clusterId, String name) throws ExecutionException, InterruptedException {

        Optional<Topic> topic = Optional.empty();
        if(isTopicMatchRegex(getTopicFilterRegex(),name)) {
            topic = this.findByName(clusterId, Collections.singletonList(name)).stream().findFirst();
        }

        return topic.orElseThrow(() -> new NoSuchElementException("Topic '" + name + "' doesn't exist"));
    }

    public List<Topic> findByName(String clusterId, List<String> topics) throws ExecutionException, InterruptedException {
        ArrayList<Topic> list = new ArrayList<>();

        Set<Map.Entry<String, TopicDescription>> topicDescriptions = kafkaWrapper.describeTopics(clusterId, topics).entrySet();
        Map<String, List<Partition.Offsets>> topicOffsets = kafkaWrapper.describeTopicsOffsets(clusterId, topics);

        Optional<List<String>> topicRegex = getTopicFilterRegex();

        for (Map.Entry<String, TopicDescription> description : topicDescriptions) {
            if(isTopicMatchRegex(topicRegex, description.getValue().name())){
                list.add(
                    new Topic(
                        description.getValue(),
                        this.skipConsumerGroups ? Collections.emptyList() : consumerGroupRepository.findByTopic(clusterId, description.getValue().name()),
                        logDirRepository.findByTopic(clusterId, description.getValue().name()),
                        topicOffsets.get(description.getValue().name()),
                        aclRepository.findByResourceType(clusterId, ResourceType.TOPIC, description.getValue().name()),
                        isInternal(description.getValue().name()),
                        isStream(description.getValue().name())
                    )
                );
            }
        }

        return list;
    }

    private boolean isInternal(String name) {
        return this.internalRegexps
            .stream()
            .anyMatch(name::matches);
    }

    private boolean isStream(String name) {
        return this.streamRegexps
            .stream()
            .anyMatch(name::matches);
    }

    public void create(String clusterId, String name, int partitions, short replicationFactor, List<org.kafkahq.models.Config> configs) throws ExecutionException, InterruptedException {
        kafkaModule
            .getAdminClient(clusterId)
            .createTopics(Collections.singleton(new NewTopic(name, partitions, replicationFactor)))
            .all()
            .get();

        configRepository.updateTopic(clusterId, name, configs);
    }

    public void delete(String clusterId, String name) throws ExecutionException, InterruptedException {
        kafkaModule.getAdminClient(clusterId)
            .deleteTopics(Collections.singleton(name))
            .all()
            .get();
    }

    private Optional<List<String>> getTopicFilterRegex() {

        List<String> topicFilterRegex = new ArrayList<>();

        if (applicationContext.containsBean(SecurityService.class)) {
            SecurityService securityService = applicationContext.getBean(SecurityService.class);
            Optional<Authentication> authentication = securityService.getAuthentication();
            if (authentication.isPresent()) {
                Authentication auth = authentication.get();
                topicFilterRegex.addAll(getTopicFilterRegexFromAttributes(auth.getAttributes()));
            }
        }
        // get topic filter regex for default groups
        topicFilterRegex.addAll(getTopicFilterRegexFromAttributes(userGroupUtils.getUserAttributes(this.defaultGroups)));

        return Optional.of(topicFilterRegex);
    }

    private List<String> getTopicFilterRegexFromAttributes(Map<String, Object> attributes) {
        if (attributes.get("topics-filter-regexp") != null) {
            if (attributes.get("topics-filter-regexp") instanceof List) {
                return (List<String>)attributes.get("topics-filter-regexp");
            }
        }
        return new ArrayList<>();
    }

}
