/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.source.reader;

import org.apache.flink.annotation.Internal;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.SourceReaderBase;
import org.apache.flink.connector.base.source.reader.fetcher.SplitFetcher;
import org.apache.flink.connector.base.source.reader.fetcher.SplitFetcherManager;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.connector.pulsar.source.enumerator.topic.TopicPartition;
import org.apache.flink.connector.pulsar.source.split.PulsarPartitionSplit;

import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static java.util.Collections.singletonList;

/**
 * Pulsar's FetcherManager implementation for ordered consuming. This class is needed to help
 * acknowledge the message to Pulsar using the {@link Consumer} inside the {@link
 * PulsarPartitionSplitReader}.
 */
@Internal
public class PulsarSourceFetcherManager
        extends SplitFetcherManager<Message<byte[]>, PulsarPartitionSplit> {
    private static final Logger LOG = LoggerFactory.getLogger(PulsarSourceFetcherManager.class);

    private final Map<String, Integer> splitFetcherMapping = new HashMap<>();
    private final Map<Integer, Boolean> fetcherStatus = new HashMap<>();

    /**
     * Creates a new SplitFetcherManager with multiple I/O threads.
     *
     * @param elementsQueue The queue that is used to hand over data from the I/O thread (the
     *     fetchers) to the reader (which emits the records and book-keeps the state. This must be
     *     the same queue instance that is also passed to the {@link SourceReaderBase}.
     * @param splitReaderSupplier The factory for the split reader that connects to the source
     */
    public PulsarSourceFetcherManager(
            FutureCompletingBlockingQueue<RecordsWithSplitIds<Message<byte[]>>> elementsQueue,
            Supplier<SplitReader<Message<byte[]>, PulsarPartitionSplit>> splitReaderSupplier,
            Configuration configuration) {
        super(elementsQueue, splitReaderSupplier, configuration);
    }

    /**
     * Override this method for supporting multiple thread fetching, one fetcher thread for one
     * split.
     */
    @Override
    public void addSplits(List<PulsarPartitionSplit> splitsToAdd) {
        for (PulsarPartitionSplit split : splitsToAdd) {
            SplitFetcher<Message<byte[]>, PulsarPartitionSplit> fetcher =
                    getOrCreateFetcher(split.splitId());
            fetcher.addSplits(singletonList(split));
            // This method could be executed multiple times.
            startFetcher(fetcher);
        }
    }

    @Override
    protected void startFetcher(SplitFetcher<Message<byte[]>, PulsarPartitionSplit> fetcher) {
        if (fetcherStatus.get(fetcher.fetcherId()) != Boolean.TRUE) {
            fetcherStatus.put(fetcher.fetcherId(), true);
            super.startFetcher(fetcher);
        }
    }

    /** Close the finished split related fetcher. */
    public void closeFetcher(String splitId) {
        Integer fetchId = splitFetcherMapping.remove(splitId);
        if (fetchId != null) {
            fetcherStatus.remove(fetchId);
            SplitFetcher<Message<byte[]>, PulsarPartitionSplit> fetcher = fetchers.remove(fetchId);
            if (fetcher != null) {
                fetcher.shutdown();
            }
        }
    }

    public void acknowledgeMessages(Map<TopicPartition, MessageId> cursorsToCommit)
            throws PulsarClientException {
        LOG.debug("Acknowledge messages {}", cursorsToCommit);

        for (Map.Entry<TopicPartition, MessageId> entry : cursorsToCommit.entrySet()) {
            TopicPartition partition = entry.getKey();
            MessageId messageId = entry.getValue();

            SplitFetcher<Message<byte[]>, PulsarPartitionSplit> fetcher =
                    getOrCreateFetcher(partition.toString());
            triggerAcknowledge(fetcher, partition, messageId);
        }
    }

    private void triggerAcknowledge(
            SplitFetcher<Message<byte[]>, PulsarPartitionSplit> splitFetcher,
            TopicPartition partition,
            MessageId messageId)
            throws PulsarClientException {
        PulsarPartitionSplitReader splitReader =
                (PulsarPartitionSplitReader) splitFetcher.getSplitReader();
        splitReader.notifyCheckpointComplete(partition, messageId);
        startFetcher(splitFetcher);
    }

    private SplitFetcher<Message<byte[]>, PulsarPartitionSplit> getOrCreateFetcher(String splitId) {
        SplitFetcher<Message<byte[]>, PulsarPartitionSplit> fetcher;
        Integer fetcherId = splitFetcherMapping.get(splitId);

        if (fetcherId == null) {
            fetcher = createSplitFetcher();
        } else {
            fetcher = fetchers.get(fetcherId);
            // This fetcher has been stopped.
            if (fetcher == null) {
                fetcherStatus.remove(fetcherId);
                fetcher = createSplitFetcher();
            }
        }
        splitFetcherMapping.put(splitId, fetcher.fetcherId());

        return fetcher;
    }
}
