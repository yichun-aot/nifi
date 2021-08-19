# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License. You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied. See the License for the
# specific language governing permissions and limitations
# under the License.
#
ARG IMAGE_NAME=openjdk
ARG IMAGE_TAG=8-jre
FROM ${IMAGE_NAME}:${IMAGE_TAG}
ARG MAINTAINER="Apache NiFi <dev@nifi.apache.org>"
LABEL maintainer="${MAINTAINER}"
LABEL site="https://nifi.apache.org"

ARG UID=1000
ARG GID=1000
ARG NIFI_VERSION=1.14.0
ARG BASE_URL=https://archive.apache.org/dist
ARG MIRROR_BASE_URL=${MIRROR_BASE_URL:-${BASE_URL}}
ARG NIFI_BINARY_PATH=${NIFI_BINARY_PATH:-/nifi/${NIFI_VERSION}/nifi-${NIFI_VERSION}-bin.zip}
ARG NIFI_TOOLKIT_BINARY_PATH=${NIFI_TOOLKIT_BINARY_PATH:-/nifi/${NIFI_VERSION}/nifi-toolkit-${NIFI_VERSION}-bin.zip}

ARG PG_DRIVER_URL=https://jdbc.postgresql.org/download/postgresql-42.2.19.jar
ARG MYSQL_DRIVER_URL=https://repo1.maven.org/maven2/mysql/mysql-connector-java/8.0.23/mysql-connector-java-8.0.23.jar
ARG ORACLE_DRIVER_URL=https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc8/21.1.0.0/ojdbc8-21.1.0.0.jar
ARG MSSQL_DRIVER_URL=https://github.com/microsoft/mssql-jdbc/releases/download/v9.4.0/mssql-jdbc-9.4.0.jre8.jar
ARG NIFI_COMPOSE_BUNDLE_URL=https://github.com/yichun-aot/nifi-compose-bundle/blob/master/nifi-compose-nar-1.0.0-BETA.nar

ENV NIFI_BASE_DIR=/opt/nifi
ENV NIFI_HOME ${NIFI_BASE_DIR}/nifi-current
ENV NIFI_TOOLKIT_HOME ${NIFI_BASE_DIR}/nifi-toolkit-current

ENV NIFI_LIB_DIR=${NIFI_HOME}/lib
ENV NIFI_PID_DIR=${NIFI_HOME}/run
ENV NIFI_LOG_DIR=${NIFI_HOME}/logs

ADD sh/ ${NIFI_BASE_DIR}/scripts/
RUN chmod -R +x ${NIFI_BASE_DIR}/scripts/*.sh

# Setup NiFi user and create necessary directories
RUN groupadd -g ${GID} nifi || groupmod -n nifi `getent group ${GID} | cut -d: -f1` \
    && useradd --shell /bin/bash -u ${UID} -g ${GID} -m nifi \
    && mkdir -p ${NIFI_BASE_DIR} \
    && chown -R nifi:nifi ${NIFI_BASE_DIR} \
    && apt-get update \
    && apt-get install -y jq xmlstarlet procps

USER nifi

# Download, validate, and expand Apache NiFi Toolkit binary.
RUN curl -fSL ${MIRROR_BASE_URL}/${NIFI_TOOLKIT_BINARY_PATH} -o ${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION}-bin.zip \
    && echo "$(curl ${BASE_URL}/${NIFI_TOOLKIT_BINARY_PATH}.sha256) *${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION}-bin.zip" | sha256sum -c - \
    && unzip ${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION}-bin.zip -d ${NIFI_BASE_DIR} \
    && rm ${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION}-bin.zip \
    && mv ${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION} ${NIFI_TOOLKIT_HOME} \
    && ln -s ${NIFI_TOOLKIT_HOME} ${NIFI_BASE_DIR}/nifi-toolkit-${NIFI_VERSION}

# Download, validate, and expand Apache NiFi binary.
RUN curl -fSL ${MIRROR_BASE_URL}/${NIFI_BINARY_PATH} -o ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.zip \
    && echo "$(curl ${BASE_URL}/${NIFI_BINARY_PATH}.sha256) *${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.zip" | sha256sum -c - \
    && unzip ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.zip -d ${NIFI_BASE_DIR} \
    && rm ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}-bin.zip \
    && mv ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION} ${NIFI_HOME} \
    && mkdir -p ${NIFI_HOME}/conf \
    && mkdir -p ${NIFI_HOME}/database_repository \
    && mkdir -p ${NIFI_HOME}/flowfile_repository \
    && mkdir -p ${NIFI_HOME}/content_repository \
    && mkdir -p ${NIFI_HOME}/provenance_repository \
    && mkdir -p ${NIFI_HOME}/state \
    && mkdir -p ${NIFI_LOG_DIR} \
    && ln -s ${NIFI_HOME} ${NIFI_BASE_DIR}/nifi-${NIFI_VERSION}

# Download database drivers
RUN curl -fSL ${PG_DRIVER_URL} -o ${NIFI_LIB_DIR}/pg-jdbc.jar \
    && curl -fSL ${MYSQL_DRIVER_URL} -o ${NIFI_LIB_DIR}/mysql-connector.jar \
    && curl -fSL ${ORACLE_DRIVER_URL} -o ${NIFI_LIB_DIR}/oracle-jdbc.jar \
    && curl -fSL ${MSSQL_DRIVER_URL} -o ${NIFI_LIB_DIR}/mssql-jdbc.jar \
    && curl -fSL ${NIFI_COMPOSE_BUNDLE_URL} -o ${NIFI_LIB_DIR}/nifi-compose-bundle.nar
    
VOLUME ${NIFI_LOG_DIR} \
       ${NIFI_HOME}/conf \
       ${NIFI_HOME}/database_repository \
       ${NIFI_HOME}/flowfile_repository \
       ${NIFI_HOME}/content_repository \
       ${NIFI_HOME}/provenance_repository \
       ${NIFI_HOME}/state

# Clear nifi-env.sh in favour of configuring all environment variables in the Dockerfile
RUN echo "#!/bin/sh\n" > $NIFI_HOME/bin/nifi-env.sh

# Web HTTP(s) & Socket Site-to-Site Ports
EXPOSE 8080 8443 10000 8000

WORKDIR ${NIFI_HOME}

# Apply configuration and start NiFi
#
# We need to use the exec form to avoid running our command in a subshell and omitting signals,
# thus being unable to shut down gracefully:
# https://docs.docker.com/engine/reference/builder/#entrypoint
#
# Also we need to use relative path, because the exec form does not invoke a command shell,
# thus normal shell processing does not happen:
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example
ENTRYPOINT ["../scripts/start.sh"]
