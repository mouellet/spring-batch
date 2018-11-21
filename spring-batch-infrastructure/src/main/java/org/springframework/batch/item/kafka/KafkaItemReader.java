/*
 * Copyright 2018 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.batch.item.kafka;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * <p>
 * An {@link ItemReader} implementation for Apache Kafka.
 * </p>
 *
 * Single-thread consumer with manually assigned list of partitions and offsets store outside of Kafka. Supports:
 * <ul>
 * <li>Retry/restart, topic-partition offsets saved in ExecutionContext.
 * <li>Uses 'max.poll.records' for chunking/paging.
 * <li>Remote chunking, manual topic-partition assignment.
 * <li>...
 * </ul>
 *
 * @author Mathieu Ouellet
 * @since 4.2
 *
 */
public class KafkaItemReader<K, V> extends AbstractItemCountingItemStreamItemReader<V> implements InitializingBean {

	private static final String TOPIC_PARTITION_OFFSET = "topic.partition.offset";

	private static final long DEFAULT_POLL_TIMEOUT = 50L;

	private static final long MIN_ASSIGN_TIMEOUT = 2000L;

	private final Supplier<Duration> minTimeoutProvider = () -> Duration
			.ofMillis(Math.max(this.pollTimeout.toMillis() * 20, MIN_ASSIGN_TIMEOUT));

	private Duration pollTimeout = Duration.ofMillis(DEFAULT_POLL_TIMEOUT);

	private AtomicBoolean assigned = new AtomicBoolean(false);

	private Duration assignTimeout = this.minTimeoutProvider.get();

	private List<TopicPartition> topicPartitions;

	private List<String> topics;

	private ConsumerFactory<K, V> consumerFactory;

	private boolean saveState = true;

	private int maxRecordsPerPoll;

	private Consumer<K, V> consumer;

	private Map<TopicPartition, Long> offsets = new HashMap<>();

	private Iterator<ConsumerRecord<K, V>> records;

	public KafkaItemReader() {
		super();
		setName(ClassUtils.getShortName(KafkaItemReader.class));
	}

	public void setPollTimeout(long pollTimeout) {
		Assert.isTrue(pollTimeout >= 0, "'pollTimeout' must no be negative.");
		this.pollTimeout = Duration.ofMillis(pollTimeout);
	}

	public void setTopicPartitions(List<TopicPartition> topicPartitions) {
		this.topicPartitions = topicPartitions;
	}

	public void setTopics(List<String> topics) {
		this.topics = topics;
	}

	public void setConsumerFactory(ConsumerFactory<K, V> consumerFactory) {
		this.consumerFactory = consumerFactory;
	}

	/**
	 * The flag that determines whether to save internal state for restarts.
	 * @return true if the flag was set
	 */
	@Override
	public boolean isSaveState() {
		return saveState;
	}

	/**
	 * Set the flag that determines whether to save internal data for {@link ExecutionContext}. Only switch this to
	 * false if you don't want to save any state from this stream, and you don't need it to be restartable. Always set
	 * it to false if the reader is being used in a concurrent environment.
	 *
	 * @param saveState flag value (default true).
	 */
	@Override
	public void setSaveState(boolean saveState) {
		this.saveState = saveState;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.state(topicPartitions != null || topics != null,
				"Either 'topicPartitions' or 'topics' must be provided.");
		Assert.state(topicPartitions == null || topics == null,
				"Both 'topicPartitions' and 'topics' cannot be specified together.");
		Assert.notNull(consumerFactory, "'consumerFactory' must not be null.");
		Object maxPoll = consumerFactory.getConfigurationProperties().get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG);
		Assert.notNull(maxPoll, "Consumer configuration for 'max.poll.records' must not be null.");
		if (maxPoll instanceof Number) {
			this.maxRecordsPerPoll = ((Number) maxPoll).intValue();
		}
		else if (maxPoll instanceof String) {
			this.maxRecordsPerPoll = Integer.parseInt((String) maxPoll);
		}
		Assert.isTrue(maxRecordsPerPoll > 0,
				"Consumer configuration for 'max.poll.records' must be greater than zero.");

		Object enableAutoCommit = consumerFactory.getConfigurationProperties()
				.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
		Assert.notNull(enableAutoCommit, "Consumer configuration for 'enable.auto.commit' must not be null.");
		Assert.state(
				(enableAutoCommit instanceof Boolean && !((Boolean) enableAutoCommit))
						|| (enableAutoCommit instanceof String && !Boolean.valueOf((String) enableAutoCommit)),
				"Consumer configuration for 'enable.auto.commit' must be false.");
	}

	@Override
	@SuppressWarnings("unchecked")
	public void open(ExecutionContext executionContext) throws ItemStreamException {
		super.open(executionContext);
		try {
			if (isSaveState() && executionContext.containsKey(TOPIC_PARTITION_OFFSET)) {
				offsets = (Map<TopicPartition, Long>) executionContext.get(TOPIC_PARTITION_OFFSET);
			}
			else {
				offsets = consumer.beginningOffsets(topicPartitions);
			}

			if (offsets != null && !offsets.isEmpty()) {
				offsets.forEach(consumer::seek);
			}
		}
		catch (Exception e) {
			throw new ItemStreamException("Failed to initialize the reader", e);
		}
	}

	@Override
	protected void doOpen() throws Exception {
		consumer = consumerFactory.createConsumer();
		if (topics != null) {
			topicPartitions = topics.stream().flatMap(topic -> consumer.partitionsFor(topic).stream())
					.map(partitionInfo -> new TopicPartition(partitionInfo.topic(), partitionInfo.partition()))
					.collect(Collectors.toList());
		}
		consumer.assign(topicPartitions);
	}

	@Override
	protected void jumpToItem(int itemIndex) throws Exception {
	}

	@Override
	protected V doRead() throws Exception {
		if (records == null || !records.hasNext()) {
			records = doPoll();
		}
		if (records.hasNext()) {
			ConsumerRecord<K, V> record = records.next();
			offsets.put(new TopicPartition(record.topic(), record.partition()), record.offset());
			return record.value();
		}
		return null;
	}

	protected Iterator<ConsumerRecord<K, V>> doPoll() {
		return this.consumer.poll(this.assigned.getAndSet(true) ? this.pollTimeout : this.assignTimeout).iterator();
	}

	@Override
	public void update(ExecutionContext executionContext) throws ItemStreamException {
		super.update(executionContext);
		if (saveState) {
			Assert.notNull(executionContext, "ExecutionContext must not be null");
			executionContext.put(TOPIC_PARTITION_OFFSET, offsets);
		}
	}

	@Override
	protected void doClose() throws Exception {
		records = null;
		if (this.consumer != null) {
			this.consumer.close();
			this.consumer = null;
			this.assigned.set(false);
		}
	}
}
