/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.map;

import com.hazelcast.client.ClientCommandHandler;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.HazelcastException;
import com.hazelcast.core.Member;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.map.client.*;
import com.hazelcast.map.proxy.DataMapProxy;
import com.hazelcast.map.proxy.ObjectMapProxy;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.protocol.Command;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.SerializationServiceImpl;
import com.hazelcast.partition.MigrationEndpoint;
import com.hazelcast.partition.MigrationType;
import com.hazelcast.partition.PartitionInfo;
import com.hazelcast.query.Predicate;
import com.hazelcast.query.impl.QueryEntry;
import com.hazelcast.query.impl.QueryableEntry;
import com.hazelcast.spi.*;
import com.hazelcast.spi.exception.TransactionException;
import com.hazelcast.spi.impl.ResponseHandlerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public class MapService implements ManagedService, MigrationAwareService, MembershipAwareService,
        TransactionalService, RemoteService, EventPublishingService<EventData, EntryListener>,
        ClientProtocolService {

    public final static String SERVICE_NAME = "hz:impl:mapService";

    private final ILogger logger;
    private final NodeEngine nodeEngine;
    private final PartitionContainer[] partitionContainers;
    private final AtomicLong counter = new AtomicLong(new Random().nextLong());
<<<<<<< HEAD
    private final ConcurrentMap<String, MapInfo> mapInfos = new ConcurrentHashMap<String, MapInfo>();
=======
    private final ConcurrentMap<String, MapContainer> mapContainers = new ConcurrentHashMap<String, MapContainer>();
    private final ConcurrentMap<String, NearCache> nearCacheMap = new ConcurrentHashMap<String, NearCache>();
    private final Map<Command, ClientCommandHandler> commandHandlers = new HashMap<Command, ClientCommandHandler>();
>>>>>>> 116201ced8233fb25f3579319c364005c77190fb
    private final ConcurrentMap<ListenerKey, String> eventRegistrations = new ConcurrentHashMap<ListenerKey, String>();
    private final AtomicReference<List<Integer>> ownedPartitions;

    public MapService(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        logger = nodeEngine.getLogger(MapService.class.getName());
        partitionContainers = new PartitionContainer[nodeEngine.getPartitionService().getPartitionCount()];
        ownedPartitions = new AtomicReference<List<Integer>>(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
    }

    public void init(final NodeEngine nodeEngine, Properties properties) {
        int partitionCount = nodeEngine.getPartitionService().getPartitionCount();
        for (int i = 0; i < partitionCount; i++) {
            PartitionInfo partition = nodeEngine.getPartitionService().getPartitionInfo(i);
            partitionContainers[i] = new PartitionContainer(this, partition);
        }
        nodeEngine.getExecutionService().scheduleAtFixedRate(new MapEvictTask(), 10, 1, TimeUnit.SECONDS);
    }

    public MapContainer getMapContainer(String mapName) {
        MapContainer mapContainer = new MapContainer(mapName, nodeEngine.getConfig().getMapConfig(mapName));
        MapContainer temp = mapContainers.putIfAbsent(mapName, mapContainer);
        return temp == null ? mapContainer : temp;
    }

    public PartitionContainer getPartitionContainer(int partitionId) {
        return partitionContainers[partitionId];
    }

    public RecordStore getRecordStore(int partitionId, String mapName) {
        return getPartitionContainer(partitionId).getRecordStore(mapName);
    }

    public long nextId() {
        return counter.incrementAndGet();
    }

    public AtomicReference<List<Integer>> getOwnedPartitions() {
        return ownedPartitions;
    }

    public void beforeMigration(MigrationServiceEvent event) {
        // TODO: what if partition has transactions?
        ownedPartitions.set(null);
    }

    public Operation prepareMigrationOperation(MigrationServiceEvent event) {
        final PartitionContainer container = partitionContainers[event.getPartitionId()];
        return new MapMigrationOperation(container, event.getPartitionId(), event.getReplicaIndex(), false);
    }

    public void commitMigration(MigrationServiceEvent event) {
        logger.log(Level.FINEST, "Committing " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.SOURCE) {
            if (event.getMigrationType() == MigrationType.MOVE) {
                clearPartitionData(event.getPartitionId());
            } else if (event.getMigrationType() == MigrationType.MOVE_COPY_BACK) {
                final PartitionContainer container = partitionContainers[event.getPartitionId()];
                for (DefaultRecordStore mapPartition : container.maps.values()) {
                    final MapConfig mapConfig = getMapContainer(mapPartition.name).getMapConfig();
                    if (mapConfig.getTotalBackupCount() < event.getCopyBackReplicaIndex()) {
                        mapPartition.clear();
                    }
                }
            }
        }
        ownedPartitions.set(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
    }

    public void rollbackMigration(MigrationServiceEvent event) {
        logger.log(Level.FINEST, "Rolling back " + event);
        if (event.getMigrationEndpoint() == MigrationEndpoint.DESTINATION) {
            clearPartitionData(event.getPartitionId());
        }
        ownedPartitions.set(nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress()));
    }

    public int getMaxBackupCount() {
        int max = 1;
        for (PartitionContainer container : partitionContainers) {
            max = Math.max(max, container.getMaxBackupCount());
        }
        return max;
    }

    private void clearPartitionData(final int partitionId) {
        logger.log(Level.FINEST, "Clearing partition data -> " + partitionId);
        final PartitionContainer container = partitionContainers[partitionId];
        for (DefaultRecordStore mapPartition : container.maps.values()) {
            mapPartition.clear();
        }
        container.maps.clear();
        container.transactions.clear(); // TODO: not sure?
    }

    public Record createRecord(String name, Data dataKey, Object value, long ttl) {
        Record record = null;
        MapContainer mapContainer = getMapContainer(name);
        final MapConfig.RecordType recordType = mapContainer.getMapConfig().getRecordType();
        if (recordType == MapConfig.RecordType.DATA) {
            record = new DataRecord(dataKey, toData(value));
        } else if (recordType == MapConfig.RecordType.OBJECT) {
            record = new ObjectRecord(dataKey, toObject(value));
        } else {
            throw new IllegalArgumentException("Should not happen!");
        }

        if (ttl <= 0 && mapContainer.getMapConfig().getTimeToLiveSeconds() > 0) {
            record.getState().updateTtlExpireTime(mapContainer.getMapConfig().getTimeToLiveSeconds());
            scheduleOperation(name, dataKey, mapContainer.getMapConfig().getTimeToLiveSeconds());
        }
        if (ttl > 0) {
            record.getState().updateTtlExpireTime(ttl);
            scheduleOperation(name, record.getKey(), ttl);
        }
        if (mapContainer.getMapConfig().getMaxIdleSeconds() > 0) {
            record.getState().updateIdleExpireTime(mapContainer.getMapConfig().getMaxIdleSeconds());
            scheduleOperation(name, dataKey, mapContainer.getMapConfig().getMaxIdleSeconds());
        }
        return record;
    }

    public void prepare(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService prepare " + txnId);
        PartitionContainer pc = partitionContainers[partitionId];
        TransactionLog txnLog = pc.getTransactionLog(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(SERVICE_NAME, new MapTxnBackupPrepareOperation(txnLog), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    public void commit(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService commit " + txnId);
        getPartitionContainer(partitionId).commit(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(SERVICE_NAME, new MapTxnBackupCommitOperation(txnId), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception ignored) {
            //commit can never fail
        }
    }

    public void rollback(String txnId, int partitionId) throws TransactionException {
        System.out.println(nodeEngine.getThisAddress() + " MapService commit " + txnId);
        getPartitionContainer(partitionId).rollback(txnId);
        int maxBackupCount = 1; //txnLog.getMaxBackupCount();
        try {
            nodeEngine.getOperationService().takeBackups(SERVICE_NAME, new MapTxnBackupRollbackOperation(txnId), 0, partitionId,
                    maxBackupCount, 60);
        } catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    private NearCache getNearCache(String mapName) {
        NearCache nearCache = new NearCache(mapName, nodeEngine);
        NearCache tmp = nearCacheMap.putIfAbsent(mapName, nearCache);
        return tmp == null ? nearCache : tmp;
    }

    public void putNearCache(String mapName, Data key, Data value) {
        NearCache nearCache = getNearCache(mapName);
        nearCache.put(key, value);
    }

    public void invalidateNearCache(String mapName, Data key) {
        NearCache nearCache = getNearCache(mapName);
        nearCache.invalidate(key);
    }

    public void invalidateAllNearCaches(String mapName, Data key) {
        InvalidateNearCacheOperation operation = new InvalidateNearCacheOperation(mapName, key);
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        for (MemberImpl member : members) {
            try {
                if (member.localMember())
                    continue;
                Invocation invocation = nodeEngine.getOperationService().createInvocationBuilder(SERVICE_NAME, operation, member.getAddress()).build();
                invocation.invoke();
            } catch (Throwable throwable) {
                throw new HazelcastException(throwable);
            }
        }
        // below local invalidation is for the case the data is cached before partition is owned/migrated
        invalidateNearCache(mapName, key);
    }

    public Data getFromNearCache(String mapName, Data key) {
        NearCache nearCache = getNearCache(mapName);
        return nearCache.get(key);
    }

    public NodeEngine getNodeEngine() {
        return nodeEngine;
    }

    public String getServiceName() {
        return SERVICE_NAME;
    }

    public ObjectMapProxy createDistributedObject(Object objectId) {
        return new ObjectMapProxy(String.valueOf(objectId), this, nodeEngine);
    }

    public DataMapProxy createDistributedObjectForClient(Object objectId) {
        return new DataMapProxy(String.valueOf(objectId), this, nodeEngine);
    }

    public void destroyDistributedObject(Object objectId) {
        logger.log(Level.WARNING, "Destroying object: " + objectId);
        final String name = String.valueOf(objectId);
        mapContainers.remove(name);
        final PartitionContainer[] containers = partitionContainers;
        for (PartitionContainer container : containers) {
            if (container != null) {
                container.destroyMap(name);
            }
        }
        nodeEngine.getEventService().deregisterListeners(SERVICE_NAME, name);
    }

    public void memberAdded(final MembershipServiceEvent membershipEvent) {
    }

    public void memberRemoved(final MembershipServiceEvent membershipEvent) {
        // submit operations to partition threads to;
        // * release locks
        // * rollback transaction
        // * do not know ?
    }

    public void destroy() {
        final PartitionContainer[] containers = partitionContainers;
        for (int i = 0; i < containers.length; i++) {
            PartitionContainer container = containers[i];
            if (container != null) {
                container.destroy();
            }
            containers[i] = null;
        }
<<<<<<< HEAD
        mapInfos.clear();
=======
        mapContainers.clear();
        commandHandlers.clear();
>>>>>>> 116201ced8233fb25f3579319c364005c77190fb
        eventRegistrations.clear();
    }

    public Map<Command, ClientCommandHandler> getCommandsAsaMap() {
        Map<Command, ClientCommandHandler> commandHandlers = new HashMap<Command, ClientCommandHandler>();
        commandHandlers.put(Command.MGET, new MapGetHandler(this));
        commandHandlers.put(Command.MSIZE, new MapSizeHandler(this));
        commandHandlers.put(Command.MGETALL, new MapGetAllHandler(this));
        commandHandlers.put(Command.MPUT, new MapPutHandler(this));
        commandHandlers.put(Command.MTRYPUT, new MapTryPutHandler(this));
        commandHandlers.put(Command.MSET, new MapSetHandler(this));
        commandHandlers.put(Command.MPUTTRANSIENT, new MapPutTransientHandler(this));
        commandHandlers.put(Command.MLOCK, new MapLockHandler(this));
        commandHandlers.put(Command.MTRYLOCK, new MapLockHandler(this));
        commandHandlers.put(Command.MTRYREMOVE, new MapTryRemoveHandler(this));
        commandHandlers.put(Command.MISLOCKED, new MapIsLockedHandler(this));
        commandHandlers.put(Command.MUNLOCK, new MapUnlockHandler(this));
        commandHandlers.put(Command.MPUTALL, new MapPutAllHandler(this));
        commandHandlers.put(Command.MREMOVE, new MapRemoveHandler(this));
        commandHandlers.put(Command.MCONTAINSKEY, new MapContainsKeyHandler(this));
        commandHandlers.put(Command.MCONTAINSVALUE, new MapContainsValueHandler(this));
        commandHandlers.put(Command.MPUTIFABSENT, new MapPutIfAbsentHandler(this));
        commandHandlers.put(Command.MREMOVEIFSAME, new MapRemoveIfSameHandler(this));
        commandHandlers.put(Command.MREPLACEIFNOTNULL, new MapReplaceIfNotNullHandler(this));
        commandHandlers.put(Command.MREPLACEIFSAME, new MapReplaceIfSameHandler(this));
        commandHandlers.put(Command.MFLUSH, new MapFlushHandler(this));
        commandHandlers.put(Command.MEVICT, new MapEvictHandler(this));
        commandHandlers.put(Command.MENTRYSET, new MapEntrySetHandler(this));
        commandHandlers.put(Command.KEYSET, new KeySetHandler(this));
        commandHandlers.put(Command.MGETENTRY, new MapGetEntryHandler(this));
        commandHandlers.put(Command.MFORCEUNLOCK, new MapForceUnlockHandler(this));

        return commandHandlers;
    }

    public String addInterceptor(String mapName, MapInterceptor interceptor) {
        return getMapContainer(mapName).addInterceptor(interceptor);
    }

    public String removeInterceptor(String mapName, MapInterceptor interceptor) {
        return getMapContainer(mapName).removeInterceptor(interceptor);
    }

    // todo replace oldValue with existingEntry
    public Object intercept(String mapName, MapOperationType operationType, Data key, Object value, Object oldValue) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        Object result = value;
        // todo needs optimization about serialization (MapEntry type should be used as input)
        if (!interceptors.isEmpty()) {
            value = toObject(value);
            Map.Entry existingEntry = new AbstractMap.SimpleEntry(key, toObject(oldValue));
            MapInterceptorContext context = new MapInterceptorContext(mapName, operationType, key, value, existingEntry);
            for (MapInterceptor interceptor : interceptors) {
                result = interceptor.process(context);
                context.setNewValue(toObject(result));
            }
        }
        return result;
    }

    // todo replace oldValue with existingEntry
    public void interceptAfterProcess(String mapName, MapOperationType operationType, Data key, Object value, Object oldValue) {
        List<MapInterceptor> interceptors = getMapContainer(mapName).getInterceptors();
        // todo needs optimization about serialization (MapEntry type should be used as input)
        if (!interceptors.isEmpty()) {
            value = toObject(value);
            Map.Entry existingEntry = new AbstractMap.SimpleEntry(key, toObject(oldValue));
            MapInterceptorContext context = new MapInterceptorContext(mapName, operationType, key, value, existingEntry);
            for (MapInterceptor interceptor : interceptors) {
                interceptor.afterProcess(context);
            }
        }
    }

    public void publishEvent(Address caller, String mapName, int eventType, Data dataKey, Data dataOldValue, Data dataValue) {
        Collection<EventRegistration> candidates = nodeEngine.getEventService().getRegistrations(SERVICE_NAME, mapName);
        Set<EventRegistration> registrationsWithValue = new HashSet<EventRegistration>();
        Set<EventRegistration> registrationsWithoutValue = new HashSet<EventRegistration>();

        if (candidates.isEmpty())
            return;

        Object key = null;
        Object value = null;
        Object oldValue = null;

        for (EventRegistration candidate : candidates) {
            EntryEventFilter filter = (EntryEventFilter) candidate.getFilter();
            if (filter instanceof QueryEventFilter) {
                Object testValue;
                if (eventType == EntryEvent.TYPE_REMOVED) {
                    oldValue = oldValue != null ? oldValue : toObject(dataOldValue);
                    testValue = oldValue;
                } else {
                    value = value != null ? value : toObject(value);
                    testValue = value;
                }
                key = key != null ? key : toObject(key);

                QueryEventFilter qfilter = (QueryEventFilter) filter;
                if (qfilter.eval(new SimpleMapEntry(key, testValue))) {
                    if (filter.isIncludeValue()) {
                        registrationsWithValue.add(candidate);
                    } else {
                        registrationsWithoutValue.add(candidate);
                    }
                }

            } else if (filter.eval(dataKey)) {
                if (filter.isIncludeValue()) {
                    registrationsWithValue.add(candidate);
                } else {
                    registrationsWithoutValue.add(candidate);
                }
            }
        }
        if (registrationsWithValue.isEmpty() && registrationsWithoutValue.isEmpty())
            return;
        String source = nodeEngine.getThisAddress().toString();
        EventData event = new EventData(source, caller, dataKey, dataValue, dataOldValue, eventType);

        nodeEngine.getEventService().publishEvent(SERVICE_NAME, registrationsWithValue, event);
        nodeEngine.getEventService().publishEvent(SERVICE_NAME, registrationsWithoutValue, event.cloneWithoutValues());
    }


    public void addEventListener(EntryListener entryListener, EventFilter eventFilter, String mapName) {
        EventRegistration registration = nodeEngine.getEventService().registerListener(SERVICE_NAME, mapName, eventFilter, entryListener);
        eventRegistrations.put(new ListenerKey(entryListener, ((EntryEventFilter) eventFilter).getKey()), registration.getId());
    }

    public void removeEventListener(EntryListener entryListener, String mapName, Object key) {
        String registrationId = eventRegistrations.get(new ListenerKey(entryListener, key));
        nodeEngine.getEventService().deregisterListener(SERVICE_NAME, mapName, registrationId);
    }

    public Object toObject(Object data) {
        if (data == null)
            return null;
        if (data instanceof Data)
            return nodeEngine.toObject(data);
        else
            return data;
    }

    public Data toData(Object object) {
        if (object == null)
            return null;
        if (object instanceof Data)
            return (Data) object;
        else
            return nodeEngine.toData(object);
    }

    public void dispatchEvent(EventData eventData, EntryListener listener) {
        Member member = nodeEngine.getClusterService().getMember(eventData.getCaller());
        EntryEvent event = null;

        if (eventData.getDataOldValue() == null) {
            event = new EntryEvent(eventData.getSource(), member, eventData.getEventType(), toObject(eventData.getDataKey()), toObject(eventData.getDataNewValue()));
        } else {
            event = new EntryEvent(eventData.getSource(), member, eventData.getEventType(), toObject(eventData.getDataKey()), toObject(eventData.getDataOldValue()), toObject(eventData.getDataNewValue()));
        }

        switch (event.getEventType()) {
            case ADDED:
                listener.entryAdded(event);
                break;
            case EVICTED:
                listener.entryEvicted(event);
                break;
            case UPDATED:
                listener.entryUpdated(event);
                break;
            case REMOVED:
                listener.entryRemoved(event);
                break;
        }
    }


    public void scheduleOperation(String mapName, Data key, long executeTime) {
        MapRecordStateOperation stateOperation = new MapRecordStateOperation(mapName, key);
        final MapRecordTask recordTask = new MapRecordTask(nodeEngine, stateOperation,
                nodeEngine.getPartitionService().getPartitionId(key));
        nodeEngine.getExecutionService().schedule(recordTask, executeTime, TimeUnit.MILLISECONDS);
    }

    private class MapEvictTask implements Runnable {
        public void run() {
            for (MapContainer mapContainer : mapContainers.values()) {
                String evictionPolicy = mapContainer.getMapConfig().getEvictionPolicy();
                MaxSizeConfig maxSizeConfig = mapContainer.getMapConfig().getMaxSizeConfig();
                if (!evictionPolicy.equals("NONE") && maxSizeConfig.getSize() > 0) {
                    boolean check = checkLimits(mapContainer);
                    if (check) {
                        evictMap(mapContainer);
                    }
                }
            }
        }

        private void evictMap(MapContainer mapContainer) {
            List recordList = new ArrayList();
            for (int i = 0; i < nodeEngine.getPartitionService().getPartitionCount(); i++) {
                Address owner = nodeEngine.getPartitionService().getPartitionOwner(i);
                if (nodeEngine.getThisAddress().equals(owner)) {
                    String mapName = mapContainer.getName();
                    PartitionContainer pc = partitionContainers[i];
                    RecordStore recordStore = pc.getRecordStore(mapName);
                    Set<Map.Entry<Data, Record>> recordEntries = recordStore.getRecords().entrySet();
                    for (Map.Entry<Data, Record> entry : recordEntries) {
                        recordList.add(entry.getValue());
                    }
                }
            }

            String evictionPolicy = mapContainer.getMapConfig().getEvictionPolicy();
            int evictSize = recordList.size() * mapContainer.getMapConfig().getEvictionPercentage() / 100;
            if (evictSize == 0)
                return;

            if (evictionPolicy.equals("LRU")) {
                Collections.sort(recordList, new Comparator<AbstractRecord>() {
                    public int compare(AbstractRecord o1, AbstractRecord o2) {
                        return o1.getLastAccessTime().compareTo(o2.getLastAccessTime());
                    }
                });
                for (Object record : recordList) {
                    AbstractRecord arec = (AbstractRecord) record;

                }
            } else if (evictionPolicy.equals("LFU")) {
                Collections.sort(recordList, new Comparator<AbstractRecord>() {
                    public int compare(AbstractRecord o1, AbstractRecord o2) {
                        return o1.getHits().compareTo(o2.getHits());
                    }
                });
            }
            Set<Data> keySet = new HashSet();
            for (int i = 0; i < evictSize; i++) {
                Record rec = (Record) recordList.get(i);
                keySet.add(rec.getKey());
            }
            for (Data key : keySet) {
                EvictOperation evictOperation = new EvictOperation(mapContainer.getName(), key, null);
                evictOperation.setPartitionId(nodeEngine.getPartitionService().getPartitionId(key));
                evictOperation.setResponseHandler(ResponseHandlerFactory.createEmptyResponseHandler());
                evictOperation.setService(MapService.this);
                nodeEngine.getOperationService().runOperation(evictOperation);
            }
        }

        private boolean checkLimits(MapContainer mapContainer) {
            MaxSizeConfig maxSizeConfig = mapContainer.getMapConfig().getMaxSizeConfig();
            String mapName = mapContainer.getName();
            String maxSizePolicy = maxSizeConfig.getMaxSizePolicy();
            if (maxSizePolicy.equals("CLUSTER_WIDE") || maxSizePolicy.equals("PER_JVM") || maxSizePolicy.equals("PER_PARTITION")) {
                int totalSize = 0;
                for (int i = 0; i < nodeEngine.getPartitionService().getPartitionCount(); i++) {
                    Address owner = nodeEngine.getPartitionService().getPartitionOwner(i);
                    if (nodeEngine.getThisAddress().equals(owner)) {
                        int size = partitionContainers[i].getRecordStore(mapName).getRecords().size();
                        if (maxSizePolicy.equals("PER_PARTITION")) {
                            if (size > maxSizeConfig.getSize())
                                return true;
                        } else {
                            totalSize += size;
                        }
                    }
                }
                if (maxSizePolicy.equals("CLUSTER_WIDE")) {
                    return totalSize * nodeEngine.getClusterService().getSize() >= maxSizeConfig.getSize();
                } else if (maxSizePolicy.equals("PER_JVM"))
                    return totalSize > maxSizeConfig.getSize();
                else
                    return false;

            }

            if (maxSizePolicy.equals("USED_HEAP_SIZE") || maxSizePolicy.equals("USED_HEAP_PERCENTAGE")) {
                long total = Runtime.getRuntime().totalMemory();
                long used = (total - Runtime.getRuntime().freeMemory());
                if (maxSizePolicy.equals("USED_HEAP_SIZE")) {
                    return maxSizeConfig.getSize() > (used / 1024 / 1024);
                } else {
                    return maxSizeConfig.getSize() > (used / total);
                }
            }
            return false;
        }

    }

    public QueryableEntrySet getQueryableEntrySet(String mapName) {
        List<Integer> memberPartitions = nodeEngine.getPartitionService().getMemberPartitions(nodeEngine.getThisAddress());
        List<ConcurrentMap<Data, Record>> mlist = new ArrayList<ConcurrentMap<Data, Record>>();
        for (Integer partition : memberPartitions) {
            PartitionContainer container = getPartitionContainer(partition);
            RecordStore recordStore = container.getRecordStore(mapName);
            mlist.add(recordStore.getRecords());
        }

        return new QueryableEntrySet((SerializationServiceImpl) nodeEngine.getSerializationService(), mlist);
    }

    public Set<QueryableEntry> queryOnPartition(String mapName, Predicate predicate, int partitionId) {
        Set<QueryableEntry> result = new HashSet<QueryableEntry>();
        PartitionContainer container = getPartitionContainer(partitionId);
        RecordStore recordStore = container.getRecordStore(mapName);
        ConcurrentMap<Data, Record> records = recordStore.getRecords();
        for (Record record : records.values()) {
            QueryEntry queryEntry = new QueryEntry((SerializationServiceImpl) nodeEngine.getSerializationService(), record.getKey(), record.getKey(), record.getValue());
            if (predicate.apply(queryEntry)) {
                result.add(queryEntry);
            }
        }
        return result;
    }


}
