// Copyright (c) 2018, Baidu.com, Inc. All Rights Reserved

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

#include "service/brpc_service.h"

#include <string.h>

#include "common/logging.h"
#include "service/brpc.h"
#include "service/internal_service.h"

namespace palo {

BRpcService::BRpcService(ExecEnv* exec_env)
        : _exec_env(exec_env),
        _server(new brpc::Server()) {
}

BRpcService::~BRpcService() {
}

Status BRpcService::start(int port) {
    // Add service
    _server->AddService(new PInternalServiceImpl(_exec_env), brpc::SERVER_OWNS_SERVICE);
    // start service
    brpc::ServerOptions options;
    if (_server->Start(port, &options) != 0) {
        char buf[64];
        LOG(WARNING) << "start brpc failed, errno=" << errno
            << ", errmsg=" << strerror_r(errno, buf, 64) << ", port=" << port;
        return Status("start brpc service failed");
    }
    return Status::OK;
}

}
