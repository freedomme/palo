// Copyright (c) 2017, Baidu.com, Inc. All Rights Reserved

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.baidu.palo.qe;

import java.nio.ByteBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.thrift.transport.TTransportException;

import com.baidu.palo.common.ClientPool;
import com.baidu.palo.analysis.RedirectStatus;
import com.baidu.palo.thrift.FrontendService;
import com.baidu.palo.thrift.TMasterOpRequest;
import com.baidu.palo.thrift.TMasterOpResult;
import com.baidu.palo.thrift.TNetworkAddress;

public class MasterOpExecutor {
    private static final Logger LOG = LogManager.getLogger(MasterOpExecutor.class);

    private final String originStmt;
    private final ConnectContext ctx;
    private TMasterOpResult result;

    private int waitTimeoutMs;
    // the total time of thrift connectTime add readTime and writeTime
    private int thriftTimeoutMs;

    public MasterOpExecutor(String originStmt, ConnectContext ctx, RedirectStatus status) {
        this.originStmt = originStmt;
        this.ctx = ctx;
        if (status.isNeedToWaitJournalSync()) {
            this.waitTimeoutMs = ctx.getSessionVariable().getQueryTimeoutS() * 1000;
        } else {
            this.waitTimeoutMs = 0;
        }
        this.thriftTimeoutMs = ctx.getSessionVariable().getQueryTimeoutS() * 1000;
    }

    public void execute() throws Exception {
        forward();
        LOG.info("forwarding to master get result max journal id: {}", result.maxJournalId);
        ctx.getCatalog().getJournalObservable().waitOn(result.maxJournalId, waitTimeoutMs);
    }
    
    // Send request to Master
    private void forward() throws Exception {
        String masterHost = ctx.getCatalog().getMasterIp();
        int masterRpcPort = ctx.getCatalog().getMasterRpcPort();
        TNetworkAddress thriftAddress = new TNetworkAddress(masterHost, masterRpcPort);

        FrontendService.Client client = null;
        try {
            client = ClientPool.frontendPool.borrowObject(thriftAddress, thriftTimeoutMs);
        } catch (Exception e) {
            // may throw NullPointerException. add err msg
            throw new Exception("Failed to get master client.", e);
        }
        TMasterOpRequest params = new TMasterOpRequest();
        params.setCluster(ctx.getClusterName());
        params.setSql(originStmt);
        params.setUser(ctx.getUser());
        params.setDb(ctx.getDatabase());
        params.setResourceInfo(ctx.toResourceCtx());
        params.setExecMemLimit(ctx.getSessionVariable().getMaxExecMemByte());
        params.setQueryTimeout(ctx.getSessionVariable().getQueryTimeoutS());

        LOG.info("Forward statement {} to Master {}", originStmt, thriftAddress);

        boolean isReturnToPool = false;
        try {
            result = client.forward(params);
            isReturnToPool = true;
        } catch (TTransportException e) { 
            boolean ok = ClientPool.frontendPool.reopen(client, thriftTimeoutMs);
            if (!ok) {
                throw e;
            }
            if (e.getType() == TTransportException.TIMED_OUT) {
                throw e;
            } else {
                result = client.forward(params);
                isReturnToPool = true;
            }
        } finally {
            if (isReturnToPool) {
                ClientPool.frontendPool.returnObject(thriftAddress, client);
            } else {
                ClientPool.frontendPool.invalidateObject(thriftAddress, client);
            }
        }
    }

    public ByteBuffer getOutputPacket() {
        if (result == null) {
            return null;
        }
        return result.packet;
    }
    
    public ShowResultSet getProxyResultSet() {
        if (result == null) {
            return null;
        }
        if (result.isSetResultSet()) {
            return new ShowResultSet(result.resultSet);
        } else {
            return null;
        }
    }
    
    public void setResult(TMasterOpResult result) {
        this.result = result;
    }
}

