/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.internal.partition.operation;

import com.hazelcast.core.HazelcastInstanceNotActiveException;
import com.hazelcast.core.Member;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.internal.partition.InternalPartitionService;
import com.hazelcast.internal.partition.MigrationCycleOperation;
import com.hazelcast.internal.partition.MigrationInfo;
import com.hazelcast.internal.partition.PartitionRuntimeState;
import com.hazelcast.internal.partition.impl.InternalPartitionServiceImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.AbstractOperation;
import com.hazelcast.spi.ExceptionAction;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.exception.TargetNotMemberException;

import java.io.IOException;

/**
 *
 * Used for committing a migration on migration destination.
 * It updates the partition table on migration destination and finalizes the migration.
 *
 */
public class MigrationCommitOperation extends AbstractOperation implements MigrationCycleOperation {

    private PartitionRuntimeState partitionState;

    private MigrationInfo migrationInfo;

    private boolean success;

    public MigrationCommitOperation() {
    }

    public MigrationCommitOperation(PartitionRuntimeState partitionState, MigrationInfo migrationInfo) {
        this.partitionState = partitionState;
        this.migrationInfo = migrationInfo;
    }

    @Override
    public void run() {
        NodeEngine nodeEngine = getNodeEngine();
        if (!nodeEngine.isRunning()) {
            throw new HazelcastInstanceNotActiveException("This node is shutting down!");
        } else {
            final Member localMember = nodeEngine.getLocalMember();
            if (!localMember.getUuid().equals(migrationInfo.getDestinationUuid())) {
                throw new IllegalStateException("This member " + localMember
                        + " is migration destination but it is restarted. Migration: " + migrationInfo);
            }
        }

        partitionState.setEndpoint(getCallerAddress());
        InternalPartitionServiceImpl partitionService = getService();
        success = partitionService.processPartitionRuntimeState(partitionState);
    }

    @Override
    public boolean returnsResponse() {
        return true;
    }

    @Override
    public Object getResponse() {
        return success;
    }

    @Override
    public String getServiceName() {
        return InternalPartitionService.SERVICE_NAME;
    }

    @Override
    public ExceptionAction onInvocationException(Throwable throwable) {
        if (throwable instanceof MemberLeftException
                || throwable instanceof TargetNotMemberException) {
            return ExceptionAction.THROW_EXCEPTION;
        }
        return super.onInvocationException(throwable);
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        partitionState = new PartitionRuntimeState();
        partitionState.readData(in);
        migrationInfo = new MigrationInfo();
        migrationInfo.readData(in);
    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        partitionState.writeData(out);
        migrationInfo.writeData(out);
    }
}
