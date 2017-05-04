/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.spi;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.impl.SpiDataSerializerHook;

import java.io.IOException;

/**
 * Default implementation of {@link ReplicaFragmentNamespace} that can be used by services to identify
 * their {@link com.hazelcast.core.DistributedObject}s.
 *
 * @see FragmentedMigrationAwareService
 * @since 3.9
 */
public final class DefaultReplicaFragmentNamespace implements ReplicaFragmentNamespace, IdentifiedDataSerializable {

    private String service;

    private String objectName;

    public DefaultReplicaFragmentNamespace() {
    }

    public DefaultReplicaFragmentNamespace(String service, String objectName) {
        this.service = service;
        this.objectName = objectName;
    }

    @Override
    public String getServiceName() {
        return service;
    }

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeUTF(service);
        out.writeUTF(objectName);
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        service = in.readUTF();
        objectName = in.readUTF();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultReplicaFragmentNamespace that = (DefaultReplicaFragmentNamespace) o;

        return service.equals(that.service) && objectName.equals(that.objectName);
    }

    @Override
    public int hashCode() {
        int result = service.hashCode();
        result = 31 * result + objectName.hashCode();
        return result;
    }

    @Override
    public int getFactoryId() {
        return SpiDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return SpiDataSerializerHook.DEFAULT_REPLICA_FRAGMENT_NS;
    }

    @Override
    public String toString() {
        return "DefaultReplicaFragmentNamespace{" + "service='" + service + '\'' + ", objectName='" + objectName + '\''
                + '}';
    }
}