package cn.tongdun.yuntu.haina.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.security.SecureRandom;
import org.janusgraph.diskstorage.configuration.ConfigOption;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.graphdb.configuration.PreInitializeConfigOptions;
import org.janusgraph.graphdb.database.idassigner.IDPoolExhaustedException;
import org.janusgraph.graphdb.database.idassigner.placement.IDPlacementStrategy;
import org.janusgraph.graphdb.database.idassigner.placement.PartitionAssignment;
import org.janusgraph.graphdb.database.idassigner.placement.PartitionIDRange;
import org.janusgraph.graphdb.database.idassigner.placement.SimpleBulkPlacementStrategy;
import org.janusgraph.graphdb.database.idassigner.placement.SimplePartitionAssignment;
import org.janusgraph.graphdb.idmanagement.IDManager;
import org.janusgraph.graphdb.internal.InternalElement;
import org.janusgraph.graphdb.internal.InternalVertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A id placement strategy that assigns all vertices created in a transaction
 * to the same partition id. The partition id is selected randomly from a set
 * of partition ids that are retrieved upon initialization.
 * <p>
 * The number of partition ids to choose from is configurable.
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */
@PreInitializeConfigOptions
public class YuntuIDPlacementStrategy implements IDPlacementStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(org.janusgraph.graphdb.database.idassigner.placement.SimpleBulkPlacementStrategy.class);

    public static final ConfigOption<Integer> CONCURRENT_PARTITIONS =
            SimpleBulkPlacementStrategy.CONCURRENT_PARTITIONS;

    public static final int PARTITION_FINDING_ATTEMPTS = 1000;

    private final Random random = new SecureRandom();

    private final int[] currentPartitions;
    private List<PartitionIDRange> localPartitionIdRanges;
    private final Set<Integer> exhaustedPartitions;

    private volatile int currentPartition = 0;

    public YuntuIDPlacementStrategy(int concurrentPartitions) {
        Preconditions.checkArgument(concurrentPartitions > 0);
        currentPartitions = new int[concurrentPartitions];
        exhaustedPartitions = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    }

    public YuntuIDPlacementStrategy(Configuration config) {
        this(config.get(CONCURRENT_PARTITIONS));
    }

    private int nextPartitionID() {
        return (currentPartition++ % currentPartitions.length);
    }

    private void updateElement(int index) {
        Preconditions.checkArgument(localPartitionIdRanges != null && !localPartitionIdRanges.isEmpty(), "Local partition id ranges have not been initialized");
        int newPartition;
        int attempts = 0;
        do {
            attempts++;
            newPartition = localPartitionIdRanges.get(random.nextInt(localPartitionIdRanges.size())).getRandomID();
            if (attempts > PARTITION_FINDING_ATTEMPTS) {
                throw new IDPoolExhaustedException("Could not find non-exhausted partition");
            }
        } while (exhaustedPartitions.contains(newPartition));
        currentPartitions[index] = newPartition;
        log.debug("Setting partition at index [{}] to: {}", index, newPartition);
    }

    @Override
    public void injectIDManager(IDManager idManager) {
    } //We don't need the IDManager here

    @Override
    public int getPartition(InternalElement element) {
        return nextPartitionID();
    }

    @Override
    public void getPartitions(Map<InternalVertex, PartitionAssignment> vertices) {
        int partitionID = nextPartitionID();
        for (Map.Entry<InternalVertex, PartitionAssignment> entry : vertices.entrySet()) {
            entry.setValue(new SimplePartitionAssignment(partitionID));
        }
    }

    @Override
    public boolean supportsBulkPlacement() {
        return true;
    }

    @Override
    public void setLocalPartitionBounds(List<PartitionIDRange> localPartitionIdRanges) {
        Preconditions.checkArgument(localPartitionIdRanges != null && !localPartitionIdRanges.isEmpty());
        this.localPartitionIdRanges = Lists.newArrayList(localPartitionIdRanges); //copy
        for (int i = 0; i < currentPartitions.length; i++) {
            updateElement(i);
        }
    }

    public boolean isExhaustedPartition(int partitionID) {
        return exhaustedPartitions.contains(partitionID);
    }

    @Override
    public void exhaustedPartition(int partitionID) {
        exhaustedPartitions.add(partitionID);
        for (int i = 0; i < currentPartitions.length; i++) {
            if (currentPartitions[i] == partitionID) {
                updateElement(i);
            }
        }
    }
}
